package com.pesacast.android.util

import android.Manifest

/**
 * Which runtime permissions each feature needs, so a denial of one does not take the others down.
 *
 *  - SMS is the core: without it the app cannot see M-PESA messages.
 *  - Bluetooth (Android 12+) is only for the BLE link to a desktop. Webhook and WiFi do not need it.
 *  - Notifications (Android 13+) only affect whether the service notification is visible.
 */
object PermissionPlan {

    data class State(
        val smsGranted: Boolean,
        val bluetoothGranted: Boolean,
        val notificationsGranted: Boolean,
    ) {
        /** The service can run (and deliver to a webhook or WiFi client) as soon as SMS is allowed. */
        val canRunService: Boolean get() = smsGranted
    }

    fun smsPermissions(): List<String> = listOf(Manifest.permission.RECEIVE_SMS)

    fun bluetoothPermissions(sdk: Int): List<String> =
        if (sdk >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyList()

    fun notificationPermissions(sdk: Int): List<String> =
        if (sdk >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /**
     * Everything still worth asking for, in one prompt. Phones without Bluetooth hardware
     * ([hasBluetooth] false) are never asked for it.
     */
    fun toRequest(granted: Set<String>, sdk: Int, hasBluetooth: Boolean = true): List<String> =
        (smsPermissions() + (if (hasBluetooth) bluetoothPermissions(sdk) else emptyList()) +
            notificationPermissions(sdk)).filter { it !in granted }

    fun evaluate(granted: Set<String>, sdk: Int, hasBluetooth: Boolean = true) = State(
        smsGranted = smsPermissions().all { it in granted },
        // Nothing to grant on a phone without Bluetooth, so it never counts as "denied".
        bluetoothGranted = !hasBluetooth || bluetoothPermissions(sdk).all { it in granted },
        notificationsGranted = notificationPermissions(sdk).all { it in granted },
    )
}
