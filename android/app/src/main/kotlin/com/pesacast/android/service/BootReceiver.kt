package com.pesacast.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.pesacast.android.util.PreferencesManager

/**
 * Restarts [MirrorService] after the device boots, so mirroring resumes without
 * the user having to open the app manually.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val prefs = PreferencesManager(context)
        if (prefs.mirroringEnabled) {
            ContextCompat.startForegroundService(context, MirrorService.startIntent(context))
        }
    }
}
