package com.pesacast.android.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pesacast.android.model.MpesaTransaction
import com.pesacast.android.transport.TransportManager
import com.pesacast.android.transport.TransportState
import com.pesacast.android.transport.WifiTransport
import com.pesacast.android.util.PreferencesManager
import com.pesacast.android.util.TransactionStore
import com.pesacast.android.webhook.TestResult
import com.pesacast.android.webhook.WebhookConfig
import com.pesacast.android.webhook.WebhookStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import java.net.Inet4Address

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val prefs = PreferencesManager(app)
    val transport = TransportManager.getInstance(app)

    val bleState: StateFlow<TransportState> = transport.ble.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, TransportState.Disconnected)

    val wifiState: StateFlow<TransportState> = transport.wifi.state
        .stateIn(viewModelScope, SharingStarted.Eagerly, TransportState.Disconnected)

    val wifiError: StateFlow<String?> = transport.wifi.error
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Merged BLE + WiFi state — drives the app-bar summary badge. */
    val combinedState: StateFlow<TransportState> = transport.combinedState
        .stateIn(viewModelScope, SharingStarted.Eagerly, TransportState.Disconnected)

    val transactions: StateFlow<List<MpesaTransaction>> = TransactionStore.transactions
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // MARK: - Webhook (ADR-0013)

    val webhookSettings get() = transport.webhookSettings

    val webhookStatus: StateFlow<WebhookStatus> = transport.webhook.status
        .stateIn(viewModelScope, SharingStarted.Eagerly, WebhookStatus())

    private val _webhookEnabled = MutableStateFlow(transport.webhookSettings.enabled)
    val webhookEnabled: StateFlow<Boolean> = _webhookEnabled.asStateFlow()

    /** Outcome of the last "save & send test": null while idle, otherwise a result to show. */
    private val _webhookTest = MutableStateFlow<WebhookTestUi>(WebhookTestUi.Idle)
    val webhookTest: StateFlow<WebhookTestUi> = _webhookTest.asStateFlow()

    sealed class WebhookTestUi {
        data object Idle : WebhookTestUi()
        data object Running : WebhookTestUi()
        data class Done(val result: TestResult) : WebhookTestUi()
    }

    /** Stores the fields. Changing any of them turns the webhook off until a new test succeeds. */
    fun saveWebhook(url: String, token: String, secret: String) {
        val s = transport.webhookSettings
        val changed = s.url != url.trim() || s.token != token || s.secret != secret
        s.url = url
        s.token = token
        s.secret = secret
        if (changed && s.enabled) setWebhookEnabled(false)
    }

    fun testWebhook(url: String, token: String, secret: String) {
        saveWebhook(url, token, secret)
        _webhookTest.value = WebhookTestUi.Running
        viewModelScope.launch(Dispatchers.IO) {
            val cfg = WebhookConfig(true, url.trim(), token.ifBlank { null }, secret.ifBlank { null })
            val result = transport.webhook.sendTest(cfg)
            if (result is TestResult.Success) transport.webhookSettings.markVerified()
            _webhookTest.value = WebhookTestUi.Done(result)
        }
    }

    /** Returns false (and stays off) if enabling is not allowed because the current values were not tested. */
    fun setWebhookEnabled(enabled: Boolean): Boolean {
        val s = transport.webhookSettings
        if (enabled && !s.canEnable()) {
            _webhookEnabled.value = false
            return false
        }
        s.enabled = enabled
        _webhookEnabled.value = enabled
        if (enabled) transport.webhook.kick()
        return true
    }

    // WiFi token as observable state so UI can update after regeneration
    private val _wifiToken = MutableStateFlow(prefs.wifiToken ?: "")
    val wifiToken: StateFlow<String> = _wifiToken.asStateFlow()

    // Local IP — refreshed on demand (e.g. when WiFi section becomes visible)
    private val _localIp = MutableStateFlow(resolveLocalIp())
    val localIp: StateFlow<String> = _localIp.asStateFlow()

    fun refreshLocalIp() {
        _localIp.value = resolveLocalIp()
    }

    /**
     * Returns the IPv4 address of the active WiFi interface specifically.
     * Using ConnectivityManager + TRANSPORT_WIFI avoids picking up VPN tunnels,
     * mobile hotspot interfaces, or other non-WiFi adapters that NetworkInterface
     * would return first.
     */
    private fun resolveLocalIp(): String {
        return try {
            val cm = getApplication<Application>()
                .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return ""
            val caps = cm.getNetworkCapabilities(network) ?: return ""
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return ""
            cm.getLinkProperties(network)
                ?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress ?: ""
        } catch (e: Exception) {
            Log.w("MainViewModel", "Could not resolve WiFi IP: ${e.message}")
            ""
        }
    }

    // MARK: - Lifecycle

    fun restartWithNewSettings() {
        transport.restartWithNewSettings()
    }

    // MARK: - WiFi settings

    fun setWifiEnabled(enabled: Boolean) {
        prefs.wifiEnabled = enabled
        transport.applyWifiSettings()
    }

    fun regenerateWifiToken() {
        val newToken = WifiTransport.generateToken()
        prefs.wifiToken = newToken
        _wifiToken.value = newToken
        if (prefs.wifiEnabled) transport.applyWifiSettings()
    }

    // MARK: - Testing

    fun sendTest() {
        transport.sendTransaction(MpesaTransaction.makeTest())
    }
}
