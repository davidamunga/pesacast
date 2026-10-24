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
