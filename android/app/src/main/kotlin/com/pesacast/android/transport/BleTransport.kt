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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

private const val TAG = "BleTransport"

/** UUID for the M-PESA transaction notify characteristic (distinct from the service UUID). */
val CHAR_UUID: UUID = UUID.fromString("E7A12346-B09E-4B3C-83A6-00112233AABB")

/** Standard Client Characteristic Configuration Descriptor — required for notifications. */
val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

/**
 * BLE GATT Server (peripheral) transport.
 *
 * The Android device advertises a custom GATT service. The desktop PesaCast app
 * (BLE central) scans, connects, and subscribes to [CHAR_UUID]. Whenever a
 * transaction arrives, we send a GATT notification with the JSON payload.
 *
 * Large payloads (>512 bytes) are chunked; the receiver reassembles them using
 * a simple length-prefix framing ("NNNN:" prefix where NNNN is the total length).
 */
class BleTransport(private val context: Context) {

    private val gson = Gson()
    private val bleManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    private val _state = MutableStateFlow<TransportState>(TransportState.Disconnected)
    val state: StateFlow<TransportState> = _state.asStateFlow()

    private var gattServer: BluetoothGattServer? = null
    private var connectedDevice: BluetoothDevice? = null
    private var txnCharacteristic: BluetoothGattCharacteristic? = null
    private val pendingQueue = ArrayDeque<String>()

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
        gattServer?.close()
        gattServer = null
        connectedDevice = null
        bleManager.adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        _state.value = TransportState.Disconnected
        Log.i(TAG, "BLE peripheral stopped")
    }

    fun sendTransaction(txn: MpesaTransaction) {
        val json = gson.toJson(txn)
        val device = connectedDevice
        val characteristic = txnCharacteristic
        val server = gattServer

        if (device == null || characteristic == null || server == null ||
            _state.value !is TransportState.Connected
        ) {
            Log.d(TAG, "BLE not connected — queuing transaction")
            pendingQueue.addLast(json)
            return
        }
        sendChunked(server, device, characteristic, json)
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
                    Log.i(TAG, "Desktop connected via BLE: ${device.address}")
                    connectedDevice = device
                    _state.value = TransportState.Connected
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "Desktop disconnected from BLE")
                    connectedDevice = null
                    _state.value = TransportState.Connecting // still advertising
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

    // MARK: - Chunked send

    /**
     * Splits [json] into MTU-safe chunks (max 512 bytes each) and sends them sequentially
     * as GATT notifications. A length-prefix header ("NNNN:") allows the receiver to
     * reassemble multi-chunk payloads.
     */
    @Suppress("MissingPermission")
    private fun sendChunked(
        server: BluetoothGattServer,
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        json: String
    ) {
        val fullPayload = "${json.length}:$json"
        val bytes = fullPayload.toByteArray(Charsets.UTF_8)
        val chunkSize = 512

        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            val chunk = bytes.copyOfRange(offset, end)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                server.notifyCharacteristicChanged(device, characteristic, false, chunk)
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = chunk
                @Suppress("DEPRECATION")
                server.notifyCharacteristicChanged(device, characteristic, false)
            }
            offset = end
        }
        Log.d(TAG, "Sent BLE transaction (${bytes.size} bytes, ${(bytes.size + chunkSize - 1) / chunkSize} chunks)")
    }

    private fun drainQueue() {
        val server = gattServer ?: return
        val device = connectedDevice ?: return
        val characteristic = txnCharacteristic ?: return
        while (pendingQueue.isNotEmpty()) {
            val json = pendingQueue.removeFirst()
            sendChunked(server, device, characteristic, json)
        }
    }

    private fun isSupported(): Boolean =
        bleManager.adapter?.isEnabled == true &&
            bleManager.adapter.bluetoothLeAdvertiser != null
}
