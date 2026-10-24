package com.pesacast.android.webhook

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * A random identifier created once per install and sent as `device_id` so a receiver can tell
 * phones apart. It is not a secret and is not derived from anything about the device or the user;
 * reinstalling the app creates a new one. Not included in backups (ADR-0014).
 */
class DeviceId(context: Context) {
    private val prefs = context.getSharedPreferences("pesacast_device", Context.MODE_PRIVATE)

    fun get(): String = prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also { id ->
        prefs.edit { putString(KEY, id) }
    }

    private companion object {
        const val KEY = "device_id"
    }
}
