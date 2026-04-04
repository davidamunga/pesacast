package com.pesacast.android.transport

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.google.gson.Gson
import com.pesacast.android.model.MpesaTransaction
import com.pesacast.android.model.SERVICE_UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

private const val TAG = "BleTransport"

private const val DEFAULT_ATT_MTU = 23
private const val ATT_HEADER_BYTES = 3

private const val NOTIFICATION_ACK_TIMEOUT_MS = 10_000L

/** UUID for the M-PESA transaction notify characteristic (distinct from the service UUID). */
val CHAR_UUID: UUID = UUID.fromString("E7A12346-B09E-4B3C-83A6-00112233AABB")

/** Standard Client Characteristic Configuration Descriptor — required for notifications. */
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

/**
 * BLE GATT Server (peripheral) transport.
 *
 * The Android device advertises a custom GATT service. The desktop PesaCast app
 * (BLE central) scans, connects, and subscribes to [CHAR_UUID]. Whenever a
 * transaction arrives, we send GATT notifications with the JSON payload.
 *
 * **Chunking:** payloads are split into chunks whose size is derived from the
 * per-device negotiated MTU ([onMtuChanged]) rather than the GATT_MAX_ATTR_LEN
 * constant (512). Safe payload per notification = negotiated_MTU - 3.
 */
class BleTransport(private val context: Context) {

    private val gson = Gson()
    private val bleManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    val state: StateFlow<TransportState> = _state.asStateFlow()

    private var gattServer: BluetoothGattServer? = null
    private val connectedDevices = CopyOnWriteArraySet<BluetoothDevice>()
    private var txnCharacteristic: BluetoothGattCharacteristic? = null
    private val pendingQueue = ArrayDeque<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val deviceMtu = ConcurrentHashMap<BluetoothDevice, Int>()

    private val deviceSendChannels = ConcurrentHashMap<BluetoothDevice, Channel<ByteArray>>()

    private val deviceNotificationAck = ConcurrentHashMap<BluetoothDevice, CompletableDeferred<Boolean>>()

    private val deviceSendJobs = ConcurrentHashMap<BluetoothDevice, Job>()

    // MARK: - Public API

    @Suppress("MissingPermission")
    fun start() {
        if (!isSupported()) {
            Log.w(TAG, "BLE peripheral mode not supported on this device")
            _state.value = TransportState.Disconnected
            return
        }

        openGattServer()
        startAdvertising()
        _state.value = TransportState.Connecting // "waiting for connection"
        Log.i(TAG, "BLE peripheral started — advertising PesaCast service")
    }

    @Suppress("MissingPermission")
    fun stop() {
        scope.cancel()
        gattServer?.close()
        gattServer = null
        connectedDevices.clear()
        deviceMtu.clear()
        deviceSendChannels.values.forEach { it.close() }
        deviceSendChannels.clear()
        deviceNotificationAck.values.forEach { it.cancel() }
        deviceNotificationAck.clear()
        deviceSendJobs.clear()
        bleManager.adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        _state.value = TransportState.Disconnected
        Log.i(TAG, "BLE peripheral stopped")
    }

    fun sendTransaction(txn: MpesaTransaction) {
        val json = gson.toJson(txn)
        val devices = connectedDevices.toList()

        if (devices.isEmpty() || txnCharacteristic == null || gattServer == null ||
            _state.value !is TransportState.Connected
        ) {
            Log.d(TAG, "BLE not connected — queuing transaction")
            pendingQueue.addLast(json)
            return
        }
        Log.d(TAG, "Broadcasting transaction to ${devices.size} device(s)")
        for (device in devices) {
            enqueueChunks(device, json)
        }
    }

    // MARK: - GATT server

    @Suppress("MissingPermission")
    private fun openGattServer() {
        val service = BluetoothGattService(
            UUID.fromString(SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val characteristic = BluetoothGattCharacteristic(
            CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        // CCCD descriptor lets the central enable/disable notifications
        val cccd = BluetoothGattDescriptor(
            CCCD_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        characteristic.addDescriptor(cccd)
        service.addCharacteristic(characteristic)
        txnCharacteristic = characteristic

        gattServer = bleManager.openGattServer(context, gattServerCallback)
        gattServer?.addService(service)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        @Suppress("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices.add(device)
                    startSendLoop(device)
                    Log.i(TAG, "Desktop connected: ${device.address} (${connectedDevices.size} total)")
                    _state.value = TransportState.Connected(connectedDevices.size)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device)
                    stopSendLoop(device)
                    Log.i(TAG, "Desktop disconnected: ${device.address} (${connectedDevices.size} remaining)")
                    _state.value = if (connectedDevices.isEmpty()) {
                        TransportState.Connecting // still advertising
                    } else {
                        TransportState.Connected(connectedDevices.size)
                    }
                }
            }
        }

        @Suppress("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray
        ) {
            if (descriptor.uuid == CCCD_UUID) {
                val notificationsEnabled =
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                Log.d(TAG, "CCCD write: notifications ${if (notificationsEnabled) "enabled" else "disabled"}")
                descriptor.value = value
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
                }
                if (notificationsEnabled) drainQueue()
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            @Suppress("MissingPermission")
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        /**
         * Fires after the BLE stack has transmitted (or failed to transmit) a notification.
         * We complete the pending [CompletableDeferred] so the send coroutine can proceed
         * to the next chunk.
         */
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val success = status == BluetoothGatt.GATT_SUCCESS
            if (!success) Log.w(TAG, "onNotificationSent failure for ${device.address}: status=$status")
            deviceNotificationAck[device]?.complete(success)
        }

        /**
         * The central has requested an MTU change. Store the negotiated value so
         * [enqueueChunks] can use the correct safe payload size (mtu - 3) for this device.
         */
        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            deviceMtu[device] = mtu
            Log.d(TAG, "MTU changed for ${device.address}: $mtu (safe payload: ${mtu - ATT_HEADER_BYTES} bytes)")
        }
    }

    // MARK: - Advertising

    @Suppress("MissingPermission")
    private fun startAdvertising() {
        val advertiser = bleManager.adapter.bluetoothLeAdvertiser ?: run {
            Log.w(TAG, "BluetoothLeAdvertiser not available")
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0) // advertise indefinitely
            .build()

        // Primary advert: service UUID only.
        // Device name is pushed to scan response to avoid exceeding the 31-byte legacy limit.
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
            .build()

        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()

        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "BLE advertising failed: error $errorCode")
            _state.value = TransportState.Disconnected
        }
    }

    /**
     * Starts a coroutine that processes [deviceSendChannels] for [device] one chunk at a time.
     * Each chunk is sent via [BluetoothGattServer.notifyCharacteristicChanged] and the coroutine
     * suspends until [onNotificationSent] fires before moving to the next chunk.
     */
    private fun startSendLoop(device: BluetoothDevice) {
        val channel = Channel<ByteArray>(Channel.UNLIMITED)
        deviceSendChannels[device] = channel

        deviceSendJobs[device] = scope.launch {
            for (chunk in channel) {
                val server = gattServer ?: break
                val characteristic = txnCharacteristic ?: break

                val ack = CompletableDeferred<Boolean>()
                deviceNotificationAck[device] = ack

                @Suppress("MissingPermission")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, characteristic, false, chunk)
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = chunk
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(device, characteristic, false)
                }

                // Wait for the BLE stack to confirm delivery before sending the next chunk.
                val success = withTimeoutOrNull(NOTIFICATION_ACK_TIMEOUT_MS) { ack.await() }
                if (success != true) {
                    Log.w(TAG, "Chunk ack timeout/failure for ${device.address} — stopping send loop")
                    break
                }
            }
            deviceSendChannels.remove(device)
            deviceNotificationAck.remove(device)
            Log.d(TAG, "Send loop exited for ${device.address}")
        }
    }

    private fun stopSendLoop(device: BluetoothDevice) {
        deviceSendJobs.remove(device)?.cancel()
        deviceSendChannels.remove(device)?.close()
        // Cancel any in-progress ack deferred so the coroutine doesn't hang
        deviceNotificationAck.remove(device)?.cancel()
        deviceMtu.remove(device)
    }

    // MARK: - Chunked send

    /**
     * Splits [json] into MTU-safe chunks and enqueues them into the device's send channel.
     * Chunk size is derived from the negotiated MTU for [device] (MTU - 3 for the ATT header).
     * Falls back to 20 bytes (default MTU 23 - 3) if MTU has not yet been negotiated.
     *
     * The actual notify calls are made by the per-device send coroutine in [startSendLoop],
     * which waits for [onNotificationSent] between chunks.
     */
    private fun enqueueChunks(device: BluetoothDevice, json: String) {
        val channel = deviceSendChannels[device] ?: run {
            Log.w(TAG, "No send channel for ${device.address} — dropping payload")
            return
        }
        val fullPayload = "${json.length}:$json"
        val bytes = fullPayload.toByteArray(Charsets.UTF_8)
        val chunkSize = (deviceMtu[device] ?: DEFAULT_ATT_MTU) - ATT_HEADER_BYTES

        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            channel.trySend(bytes.copyOfRange(offset, end))
            offset = end
        }
        val chunkCount = (bytes.size + chunkSize - 1) / chunkSize
        Log.d(TAG, "Enqueued $chunkCount chunk(s) for ${device.address} (${bytes.size}B payload, ${chunkSize}B chunks)")
    }

    private fun drainQueue() {
        val devices = connectedDevices.toList().ifEmpty { return }
        while (pendingQueue.isNotEmpty()) {
            val json = pendingQueue.removeFirst()
            for (device in devices) {
                enqueueChunks(device, json)
            }
        }
    }

    private fun isSupported(): Boolean =
        bleManager.adapter?.isEnabled == true &&
            bleManager.adapter.bluetoothLeAdvertiser != null
}
