package com.pesacast.android.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.pesacast.android.service.MirrorService
import com.pesacast.android.transport.TransportManager
import com.pesacast.android.transport.TransportState
import com.pesacast.android.util.PermissionPlan
import com.pesacast.android.util.PreferencesManager

/** Reads the real phone for the setup checklist. Cheap enough to build on every screen refresh. */
class AndroidDeviceState(private val context: Context) : DeviceState {

    private val prefs = PreferencesManager(context)
    private val transport = TransportManager.getInstance(context)

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    override val sdkInt: Int get() = Build.VERSION.SDK_INT
    override val manufacturer: String get() = Build.MANUFACTURER.orEmpty()
    override val smsGranted: Boolean get() = granted(Manifest.permission.RECEIVE_SMS)

    override val notificationsGranted: Boolean
        get() = sdkInt < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    override val ignoringBatteryOptimizations: Boolean
        get() = context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    override val serviceRunning: Boolean get() = MirrorService.running
    override val webhookEnabled: Boolean get() = transport.webhookSettings.enabled
    override val wifiEnabled: Boolean get() = prefs.wifiEnabled

    override val bluetoothAvailable: Boolean
        get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) &&
            PermissionPlan.bluetoothPermissions(sdkInt).all(::granted) &&
            transport.ble.state.value !is TransportState.Disconnected

    override val oemStepAcknowledged: Boolean get() = prefs.setupOemAcknowledged
    override val lastTestPassed: Boolean? get() = prefs.setupLastTestPassed
}
