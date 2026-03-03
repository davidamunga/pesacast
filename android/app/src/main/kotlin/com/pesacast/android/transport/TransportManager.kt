package com.pesacast.android.transport

import android.content.Context
import android.util.Log
import com.pesacast.android.model.MpesaTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

private const val TAG = "TransportManager"

/** Exposes a combined connection state across both transports. */
sealed class TransportState {
    data object Disconnected : TransportState()
    data object Connecting : TransportState()
    data object Connected : TransportState()
}

/**
 * Manages the [BleTransport].
 */
class TransportManager(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val ble = BleTransport(context)

    val combinedState: StateFlow<TransportState> = ble.state

    // MARK: - Lifecycle

    fun start() {
        Log.i(TAG, "Starting BLE transport")
        ble.start()
    }

    fun stop() {
        ble.stop()
    }

    fun restartWithNewSettings() {
        stop()
        start()
    }

    // MARK: - Sending

    fun sendTransaction(txn: MpesaTransaction) {
        Log.d(TAG, "sendTransaction via BLE")
        ble.sendTransaction(txn)
    }

    // MARK: - Singleton

    companion object {
        @Volatile
        private var instance: TransportManager? = null

        fun getInstance(context: Context): TransportManager =
            instance ?: synchronized(this) {
                instance ?: TransportManager(context.applicationContext).also { instance = it }
            }
    }
}
