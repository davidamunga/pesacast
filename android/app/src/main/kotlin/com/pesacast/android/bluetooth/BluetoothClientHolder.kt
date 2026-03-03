package com.pesacast.android.bluetooth

/**
 * Application-scoped singleton holder so [SmsReceiver] (which has no access
 * to the Activity or ViewModel) can still enqueue messages.
 */
object BluetoothClientHolder {
    var client: BluetoothClient? = null
}
