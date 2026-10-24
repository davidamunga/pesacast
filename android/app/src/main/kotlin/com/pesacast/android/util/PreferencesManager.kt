package com.pesacast.android.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.pesacast.android.webhook.KeystoreSecretCipher
import com.pesacast.android.webhook.SecretCipher

class PreferencesManager(
    context: Context,
    cipher: SecretCipher = KeystoreSecretCipher(),
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pesacast_prefs", Context.MODE_PRIVATE)

    // The WiFi pairing token is the key to the encrypted channel, so it is stored encrypted. Older
    // versions kept it in plain text under "wifi_token"; MigratingSecret upgrades it on first read.
    private val wifiTokenSecret = MigratingSecret(
        cipher = cipher,
        get = { key -> prefs.getString(key, null) },
        put = { key, value -> prefs.edit { if (value == null) remove(key) else putString(key, value) } },
        encryptedKey = KEY_WIFI_TOKEN_ENC,
        legacyPlaintextKey = KEY_WIFI_TOKEN,
    )

    var mirroringEnabled: Boolean
        get() = prefs.getBoolean(KEY_MIRRORING, true)
        set(value) = prefs.edit { putBoolean(KEY_MIRRORING, value) }

    // WiFi transport
    var wifiEnabled: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_WIFI_ENABLED, value) }

    var wifiToken: String?
        get() = wifiTokenSecret.read()
        set(value) = wifiTokenSecret.write(value)

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
        private const val KEY_WIFI_TOKEN   = "wifi_token"          // legacy plaintext, migrated away
        private const val KEY_WIFI_TOKEN_ENC = "wifi_token_enc"
        private const val KEY_WIFI_PORT    = "wifi_port"

        // Legacy keys — not removed to avoid data loss on upgrade
        private const val KEY_MAC_ADDRESS  = "mac_device_address"
        private const val KEY_MAC_NAME     = "mac_device_name"
    }
}
