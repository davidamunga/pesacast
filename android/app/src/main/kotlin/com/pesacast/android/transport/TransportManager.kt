package com.pesacast.android.transport

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import android.util.Log
import com.pesacast.android.model.MpesaTransaction
import com.pesacast.android.setup.EndToEnd
import com.pesacast.android.setup.EndToEndSummary
import com.pesacast.android.setup.WebhookOutcome
import com.pesacast.android.util.PermissionPlan
import com.pesacast.android.util.PreferencesManager
import com.pesacast.android.webhook.DeviceId
import com.pesacast.android.webhook.TestResult
import com.pesacast.android.webhook.UrlConnectionPoster
import com.pesacast.android.webhook.WebhookConfig
import com.pesacast.android.webhook.WebhookDelivery
import com.pesacast.android.webhook.WebhookQueue
import com.pesacast.android.webhook.WebhookSettings
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

private const val TAG = "TransportManager"

/** Combined connection state across BLE and WiFi transports. */
sealed class TransportState {
    data object Disconnected : TransportState()
    data object Connecting : TransportState()
    data class Connected(val deviceCount: Int = 1) : TransportState()
}

/**
 * Manages both the [BleTransport] and [WifiTransport].
 *
 * BLE always runs when mirroring is enabled. WiFi runs only when [PreferencesManager.wifiEnabled]
 * is true. Both transports independently report connection state; [combinedState] merges them
 * so the UI reflects the overall picture. [sendTransaction] fans out to all active transports.
 */
class TransportManager(private val context: Context) {

    private val prefs = PreferencesManager(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val ble = BleTransport(context)
    val wifi = WifiTransport(context)

    /** Optional HTTPS webhook sink, independent of the desktop transports (ADR-0013). */
    val webhookSettings = WebhookSettings(context)
    val deviceId = DeviceId(context)
    val webhook = WebhookDelivery(
        queue = WebhookQueue(File(context.filesDir, "webhook-queue.json")),
        config = ::currentWebhookConfig,
        poster = UrlConnectionPoster(),
        scope = scope,
    )

    /**
     * Merged state: Connected if any transport has clients, Connecting if any transport is
     * advertising/listening but no clients yet, Disconnected otherwise.
     */
    val combinedState: StateFlow<TransportState> = combine(ble.state, wifi.state) { b, w ->
        val bleCount  = (b as? TransportState.Connected)?.deviceCount ?: 0
        val wifiCount = (w as? TransportState.Connected)?.deviceCount ?: 0
        val total = bleCount + wifiCount
        when {
            total > 0 -> TransportState.Connected(total)
            b is TransportState.Connecting || w is TransportState.Connecting -> TransportState.Connecting
            else -> TransportState.Disconnected
        }
    }.stateIn(scope, SharingStarted.Eagerly, TransportState.Disconnected)

    private fun grantedPermissions(): Set<String> =
        PermissionPlan.bluetoothPermissions(Build.VERSION.SDK_INT)
            .filter { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
            .toSet()

    // MARK: - Lifecycle

    fun start() {
        if (PermissionPlan.evaluate(grantedPermissions(), Build.VERSION.SDK_INT).bluetoothGranted) {
            Log.i(TAG, "Starting BLE transport")
            ble.start()
        } else {
            // Without Bluetooth permissions the BLE APIs throw SecurityException; keep the rest running.
            Log.w(TAG, "Bluetooth permissions not granted: BLE transport not started")
        }
        applyWifiSettings()
        // Deliver anything left in the webhook queue from before a restart or reboot.
        if (webhookSettings.enabled) webhook.kick()
    }

    /** Starts or stops only the WiFi transport to match preferences; BLE is left untouched. */
    fun applyWifiSettings() {
        if (prefs.wifiEnabled) {
            val token = prefs.wifiTokenOrRegenerate(WifiTransport::generateToken)
            Log.i(TAG, "Starting WiFi transport on port ${prefs.wifiPort}")
            wifi.start(token, prefs.wifiPort)
        } else {
            wifi.stop()
        }
    }

    fun stop() {
        ble.stop()
        wifi.stop()
    }

    fun restartWithNewSettings() {
        stop()
        start()
    }

    /** One place that builds the webhook configuration, used by delivery and by the setup test. */
    private fun currentWebhookConfig() = webhookSettings.config()?.copy(deviceId = deviceId.get())

    // MARK: - Setup test

    /**
     * Sends a clearly labelled test through every enabled destination and reports each result
     * (setup checklist, ADR-0015). Blocking: the webhook check is a network call, so call it off
     * the main thread. The webhook gets a `"test": true` ping with no transaction; a connected
     * desktop gets the usual test transaction. Nothing is queued if no desktop is connected.
     */
    fun runEndToEndTest(): EndToEndSummary {
        val cfg = currentWebhookConfig()
        val webhookOutcome = if (webhookSettings.enabled && cfg != null) {
            when (val r = webhook.sendTest(cfg)) {
                is TestResult.Success -> WebhookOutcome.Ok(r.httpCode)
                is TestResult.Failure -> WebhookOutcome.Failed(r.message)
            }
        } else null

        val desktops = ((ble.state.value as? TransportState.Connected)?.deviceCount ?: 0) +
            ((wifi.state.value as? TransportState.Connected)?.deviceCount ?: 0)
        if (desktops > 0) sendTransaction(MpesaTransaction.makeTest())

        val desktopLinkOn = prefs.wifiEnabled || ble.state.value !is TransportState.Disconnected
        return EndToEnd.summarize(webhookOutcome, desktops, desktopLinkOn).also {
            prefs.setupLastTestPassed = it.passed
        }
    }

    /** Sends the webhook connectivity check for a configuration that may not be saved yet. Blocking. */
    fun sendWebhookTest(config: WebhookConfig): TestResult = webhook.sendTest(config.withDeviceIdIfMissing(deviceId.get()))

    // MARK: - Sending

    fun sendTransaction(txn: MpesaTransaction) {
        Log.d(TAG, "sendTransaction — fanning out to active transports")
        ble.sendTransaction(txn)
        wifi.sendTransaction(txn)
    }

    // MARK: - Singleton

    companion object {
        @Volatile
        private var instance: TransportManager? = null

        fun getInstance(context: Context): TransportManager =
            instance ?: synchronized(this) {
                instance ?: TransportManager(context.applicationContext).also { instance = it }
            }
    }
}
