package com.pesacast.android.transport

import android.content.Context
import android.util.Log
import com.pesacast.android.model.MpesaTransaction
import com.pesacast.android.util.PreferencesManager
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

    // MARK: - Lifecycle

    fun start() {
        Log.i(TAG, "Starting BLE transport")
        ble.start()
        applyWifiSettings()
    }

    /** Starts or stops only the WiFi transport to match preferences; BLE is left untouched. */
    fun applyWifiSettings() {
        if (prefs.wifiEnabled) {
            val token = prefs.wifiToken ?: WifiTransport.generateToken().also { prefs.wifiToken = it }
            Log.i(TAG, "Starting WiFi transport on port ${prefs.wifiPort}")
            wifi.start(token, prefs.wifiPort) { prefs.wifiAllowLegacy }
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
