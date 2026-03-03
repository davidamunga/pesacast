package com.pesacast.android.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.pesacast.android.model.MpesaTransaction
import com.pesacast.android.model.SERVICE_UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

private const val TAG = "BluetoothClient"
private val RFCOMM_UUID: UUID = UUID.fromString(SERVICE_UUID)

/** Connection states exposed to the UI. */
sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val deviceName: String?) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

/**
 * Manages a persistent Classic Bluetooth RFCOMM connection to the macOS PesaCast server.
 *
 * - Call [connect] to initiate; it reconnects automatically with exponential back-off.
 * - Call [sendTransaction] to enqueue a JSON payload.
 * - Call [disconnect] to close cleanly.
 */
class BluetoothClient(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private var socket: BluetoothSocket? = null
    private var connectJob: Job? = null

    // Queue of JSON lines waiting to be sent; drained once connected.
    private val pendingQueue = ArrayDeque<String>()

    // MARK: - Public API

    fun connect(macAddress: String) {
        disconnect()
        connectJob = scope.launch {
            connectWithRetry(macAddress)
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        closeSocket()
        _state.value = ConnectionState.Disconnected
    }

    fun sendTransaction(txn: MpesaTransaction) {
        val line = gson.toJson(txn) + "\n"
        scope.launch {
            val out = socket?.outputStream
            if (out != null && socket?.isConnected == true) {
                try {
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.flush()
                    Log.d(TAG, "Sent: $line")
                } catch (e: IOException) {
                    Log.w(TAG, "Send failed, queuing: ${e.message}")
                    pendingQueue.addLast(line)
                }
            } else {
                Log.d(TAG, "Not connected — queuing transaction")
                pendingQueue.addLast(line)
            }
        }
    }

    // MARK: - Private

    private suspend fun connectWithRetry(macAddress: String) {
        var backoffMs = 2_000L
        while (true) {
            _state.value = ConnectionState.Connecting
            try {
                val device = getAdapter()?.getRemoteDevice(macAddress)
                    ?: run {
                        _state.value = ConnectionState.Error("Bluetooth not available")
                        return
                    }

                val s = device.createRfcommSocketToServiceRecord(RFCOMM_UUID)
                getAdapter()?.cancelDiscovery()
                s.connect()             // blocks until connected or throws

                socket = s
                val name = try { device.name } catch (_: SecurityException) { null }
                _state.value = ConnectionState.Connected(name)
                Log.i(TAG, "Connected to $name ($macAddress)")

                drainQueue()
                backoffMs = 2_000L     // reset back-off on success

                // Wait until the socket closes
                awaitDisconnect(s)

                Log.i(TAG, "Socket closed — will reconnect")
                _state.value = ConnectionState.Disconnected

            } catch (e: SecurityException) {
                Log.e(TAG, "Missing BLUETOOTH_CONNECT permission: ${e.message}")
                _state.value = ConnectionState.Error("Missing Bluetooth permission")
                return
            } catch (e: IOException) {
                Log.w(TAG, "Connect failed: ${e.message} — retrying in ${backoffMs}ms")
                _state.value = ConnectionState.Error("Could not reach Mac (retrying…)")
                closeSocket()
            }

            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
        }
    }

    /** Blocks on an empty read until the socket is closed remotely. */
    private suspend fun awaitDisconnect(s: BluetoothSocket) = withContext(Dispatchers.IO) {
        try {
            val buf = ByteArray(1)
            while (s.isConnected) {
                val n = s.inputStream.read(buf)
                if (n < 0) break
            }
        } catch (_: IOException) { /* socket closed */ }
    }

    private suspend fun drainQueue() {
        val out = socket?.outputStream ?: return
        while (pendingQueue.isNotEmpty()) {
            val line = pendingQueue.removeFirst()
            try {
                out.write(line.toByteArray(Charsets.UTF_8))
                out.flush()
                Log.d(TAG, "Drained queued message")
            } catch (e: IOException) {
                pendingQueue.addFirst(line)   // put back and stop
                break
            }
        }
    }

    private fun closeSocket() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    @Suppress("DEPRECATION")
    private fun getAdapter(): BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
}
