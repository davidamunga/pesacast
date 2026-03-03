package com.pesacast.android.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pesacast_prefs", Context.MODE_PRIVATE)

    var macDeviceAddress: String?
        get() = prefs.getString(KEY_MAC_ADDRESS, null)
        set(value) = prefs.edit { putString(KEY_MAC_ADDRESS, value) }

    var macDeviceName: String?
        get() = prefs.getString(KEY_MAC_NAME, null)
        set(value) = prefs.edit { putString(KEY_MAC_NAME, value) }

    var mirroringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIRRORING, true)
        set(value) = prefs.edit { putBoolean(KEY_MIRRORING, value) }

    companion object {
        private const val KEY_MAC_ADDRESS = "mac_device_address"
        private const val KEY_MAC_NAME    = "mac_device_name"
        private const val KEY_MIRRORING   = "mirroring_enabled"
    }
}
