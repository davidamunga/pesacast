package com.pesacast.android.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pesacast_prefs", Context.MODE_PRIVATE)

    var mirroringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIRRORING, true)
        set(value) = prefs.edit { putBoolean(KEY_MIRRORING, value) }

    // WiFi transport
    var wifiEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_ENABLED, value) }

    var wifiToken: String?
        get() = prefs.getString(KEY_WIFI_TOKEN, null)
        set(value) = prefs.edit { putString(KEY_WIFI_TOKEN, value) }

    /** Accept legacy unencrypted (v1) WiFi clients. See ADR-0011 for the removal timeline. */
    var wifiAllowLegacy: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ALLOW_LEGACY, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_ALLOW_LEGACY, value) }

    var wifiPort: Int
        get() = prefs.getInt(KEY_WIFI_PORT, 7877)
        set(value) = prefs.edit { putInt(KEY_WIFI_PORT, value) }

    // Legacy (unused — kept to avoid clearing existing installs' prefs)
    @Deprecated("Unused — Classic Bluetooth was removed")
    var macDeviceAddress: String?
        get() = prefs.getString(KEY_MAC_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_MAC_ADDRESS, value) }

    @Deprecated("Unused — Classic Bluetooth was removed")
    var macDeviceName: String?
        get() = prefs.getString(KEY_MAC_NAME, null)
        set(value) = prefs.edit { putString(KEY_MAC_NAME, value) }

    companion object {
        private const val KEY_MIRRORING    = "mirroring_enabled"
        private const val KEY_WIFI_ENABLED = "wifi_enabled"
        private const val KEY_WIFI_TOKEN   = "wifi_token"
        private const val KEY_WIFI_PORT    = "wifi_port"
        private const val KEY_WIFI_ALLOW_LEGACY = "wifi_allow_legacy"

        // Legacy keys — not removed to avoid data loss on upgrade
        private const val KEY_MAC_ADDRESS  = "mac_device_address"
        private const val KEY_MAC_NAME     = "mac_device_name"
    }
}
