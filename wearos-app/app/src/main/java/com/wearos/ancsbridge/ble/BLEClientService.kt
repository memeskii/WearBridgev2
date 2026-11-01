package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import com.wearos.ancsbridge.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.LinkedList
import java.util.UUID

/**
 * Full ANCS & Companion BLE GATT Client Service for Wear OS.
 *
 * Manages the complete ANCS lifecycle:
 * - BLE connection & auto-reconnect with exponential backoff
 * - Bluetooth bonding with iOS device
 * - MTU negotiation (512 bytes)
 * - ANCS service discovery
 * - Serialized CCCD descriptor subscription (Data Source first, then Notification Source)
 * - Notification Source & Data Source event streaming
 * - Control Point command writes
 * - GAP device name resolution
 * - Quick Reply BLE Transport over custom characteristic 7F8A0003-9B3C-4D2A-B9E1-8C5F6D7E8A01
 */
@SuppressLint("MissingPermission")
class BLEClientService(private val context: Context) {

    companion object {
        private const val TAG = "BLEClientService"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 3_000L
        private const val TARGET_MTU = 512
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 2

        // Apple Notification Center Service (ANCS) UUIDs
        val ANCS_SERVICE_UUID: UUID = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        val NOTIFICATION_SOURCE_UUID: UUID = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        val CONTROL_POINT_UUID: UUID = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        val DATA_SOURCE_UUID: UUID = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        // iOS Companion App GATT Service & Quick Reply Characteristic UUIDs
        val COMPANION_SERVICE_UUID: UUID = UUID.fromString("A0E3D5B1-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        val QUICK_REPLY_CHARACTERISTIC_UUID: UUID = UUID.fromString("7F8A0003-9B3C-4D2A-B9E1-8C5F6D7E8A01")
        val CALL_STATE_CHARACTERISTIC_UUID: UUID = UUID.fromString("A0E3D5B4-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _notificationSourceEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val notificationSourceEvents: SharedFlow<ByteArray> = _notificationSourceEvents.asSharedFlow()

    private val _dataSourceEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val dataSourceEvents: SharedFlow<ByteArray> = _dataSourceEvents.asSharedFlow()

    private val _callStateEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val callStateEvents: SharedFlow<ByteArray> = _callStateEvents.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var reconnectJob: Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var autoReconnect = true

    // CCCD descriptor write queue — Android requires serial descriptor writes
    private val descriptorWriteQueue = LinkedList<Pair<BluetoothGattCharacteristic, BluetoothGattDescriptor>>()
    private var isWritingDescriptor = false

    // Outbound characteristic write queue — Android permits only ONE in-flight GATT write
    private val characteristicWriteQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var isWritingCharacteristic = false
    private var writeWatchdogJob: Job? = null

    var onDescriptorQueueDrained: (() -> Unit)? = null
    val isQueueBusy: Boolean
        get() = isWritingDescriptor || descriptorWriteQueue.isNotEmpty() || isWritingCharacteristic || !characteristicWriteQueue.isEmpty()

    private var serviceDiscoveryRetries = 0
    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null

    val isConnected: Boolean
        get() = _connectionState.value is ConnectionState.Connected

    /**
     * Receiver to handle Bluetooth bonding events.
     */
    val bondStateReceiver = BondStateReceiver(
        onBonded = { device ->
            Log.i(TAG, "Bond completed with ${device.address}, re-discovering services...")
            scope.launch {
                delay(1000)
                gatt?.discoverServices()
            }
        },
        onBondFailed = { device ->
            Log.e(TAG, "Bond failed with ${device.address}")
            _connectionState.value = ConnectionState.Error("Pairing failed")
            disconnect()
        }
    )

    private val gattCallback = GattCallback(
        onConnected = { gatt ->
            reconnectDelay = INITIAL_RECONNECT_DELAY_MS
            val displayName = if (isTruncatedBleName(gatt.device.name ?: "")) "iPhone" else (gatt.device.name ?: "iPhone")
            _connectionState.value = ConnectionState.Connecting(displayName)

            // Negotiate 512-byte MTU for fragmented ANCS data source packets
            gatt.requestMtu(TARGET_MTU)
        },
        onDisconnected = { gatt, status ->
            Log.i(TAG, "Disconnected, status=$status")
            _connectionState.value = ConnectionState.Disconnected()
            clearCharacteristics()
            gatt.close()
            this.gatt = null

            if (autoReconnect && targetDevice != null) {
                scheduleReconnect()
            }
        },
        onServicesDiscoveredCallback = { gatt, status ->
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                _connectionState.value = ConnectionState.Error("Service discovery failed")
                return@GattCallback
            }

            val ancsService = gatt.getService(ANCS_SERVICE_UUID)
            if (ancsService == null) {
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "ANCS not found; initiating bonding with iPhone...")
                    val bondDisplayName = if (isTruncatedBleName(gatt.device.name ?: "")) "iPhone" else (gatt.device.name ?: "iPhone")
                    _connectionState.value = ConnectionState.Bonding(bondDisplayName)
                    gatt.device.createBond()
                } else {
                    serviceDiscoveryRetries++
                    if (serviceDiscoveryRetries <= MAX_SERVICE_DISCOVERY_RETRIES) {
                        Log.w(TAG, "Bonded but ANCS not visible, retrying ($serviceDiscoveryRetries/$MAX_SERVICE_DISCOVERY_RETRIES)")
                        scope.launch {
                            gatt.disconnect()
                            delay(2000)
                            connect(gatt.device, autoReconnect = false)
                        }
                    } else {
                        Log.e(TAG, "ANCS service not found after retries")
                        _connectionState.value = ConnectionState.Error("Not an iPhone (no ANCS)")
                        scope.launch {
                            gatt.disconnect()
                            gatt.close()
                            this@BLEClientService.gatt = null
                        }
                    }
                }
                return@GattCallback
            }

            // ANCS service found
            Log.i(TAG, "ANCS service discovered successfully!")
            serviceDiscoveryRetries = 0

            // If already subscribed (e.g. secondary discovery for companion), skip re-subscribing
            if (notificationSourceChar != null && dataSourceChar != null) {
                Log.i(TAG, "ANCS already subscribed; checking companion service status")
                readCompanionStatus()
                return@GattCallback
            }

            notificationSourceChar = ancsService.getCharacteristic(NOTIFICATION_SOURCE_UUID)
            controlPointChar = ancsService.getCharacteristic(CONTROL_POINT_UUID)
            dataSourceChar = ancsService.getCharacteristic(DATA_SOURCE_UUID)

            if (notificationSourceChar == null || dataSourceChar == null) {
                Log.e(TAG, "Required ANCS characteristics are missing")
                _connectionState.value = ConnectionState.Error("ANCS characteristics missing")
                return@GattCallback
            }

            // Apple ANCS Spec: Subscribe to Data Source FIRST, then Notification Source
            subscribeToCharacteristic(dataSourceChar!!)
        },
        onNotificationSourceChanged = { data ->
            _notificationSourceEvents.tryEmit(data)
        },
        onDataSourceChanged = { data ->
            _dataSourceEvents.tryEmit(data)
        },
        onDescriptorWritten = { descriptor, status ->
            isWritingDescriptor = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor write failed ($status) for ${descriptor.characteristic.uuid}")
                return@GattCallback
            }

            when (descriptor.characteristic.uuid) {
                DATA_SOURCE_UUID -> {
                    Log.i(TAG, "Data Source subscribed. Now subscribing to Notification Source...")
                    notificationSourceChar?.let { subscribeToCharacteristic(it) }
                }
                NOTIFICATION_SOURCE_UUID -> {
                    Log.i(TAG, "Notification Source subscribed — ANCS session is active!")
                    val deviceName = resolveDeviceName()
                    _connectionState.value = ConnectionState.Connected(deviceName)
                    readGapDeviceName()
                    readCompanionStatus()
                    scope.launch {
                        delay(500)
                        subscribeToCallState()
                    }
                }
            }

            processDescriptorWriteQueue()
        },
        onCharacteristicWritten = { characteristic, status ->
            writeWatchdogJob?.cancel()
            isWritingCharacteristic = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed ($status) for ${characteristic.uuid}")
            } else if (characteristic.uuid == QUICK_REPLY_CHARACTERISTIC_UUID) {
                Log.i(TAG, "Companion write confirmed for ${characteristic.uuid}")
            }
            processCharacteristicWriteQueue()
        },
        onMtuChanged = { mtu, status ->
            Log.i(TAG, "MTU negotiated: $mtu (status=$status). Discovering services...")
            gatt?.discoverServices()
        },
        onCharacteristicReadCallback = { characteristic, value ->
            onCharacteristicRead(characteristic.uuid, value)
        },
        onCallStateChanged = { data ->
            _callStateEvents.tryEmit(data)
        }
    )

    // MARK: - Connection Methods

    fun connect(device: BluetoothDevice, autoReconnect: Boolean = true) {
        val currentState = _connectionState.value
        if (gatt != null && gatt?.device?.address == device.address &&
            (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting)) {
            Log.d(TAG, "Already connected/connecting to ${device.address}, skipping")
            return
        }

        reconnectJob?.cancel()
        this.autoReconnect = autoReconnect
        this.targetDevice = device
        this.serviceDiscoveryRetries = 0

        _connectionState.value = ConnectionState.Connecting(
            if (isTruncatedBleName(device.name ?: "")) "iPhone" else (device.name ?: "iPhone")
        )

        gatt?.close()
        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun reconnect(device: BluetoothDevice) {
        val currentState = _connectionState.value
        if (gatt != null && gatt?.device?.address == device.address &&
            (currentState is ConnectionState.Connected || currentState is ConnectionState.Connecting)) {
            Log.d(TAG, "Already connected/connecting to ${device.address}, skipping reconnect")
            return
        }

        reconnectJob?.cancel()
        this.targetDevice = device
        this.autoReconnect = true
        this.serviceDiscoveryRetries = 0

        _connectionState.value = ConnectionState.Connecting(
            if (isTruncatedBleName(device.name ?: "")) "iPhone" else (device.name ?: "iPhone")
        )

        gatt?.close()
        gatt = device.connectGatt(
            context,
            true,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    fun attachGatt(gatt: BluetoothGatt) {
        this.gatt = gatt
        this._connectionState.value = ConnectionState.Connected(resolveDeviceName())
    }

    fun disconnect() {
        autoReconnect = false
        reconnectJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        clearCharacteristics()
        targetDevice = null
        _connectionState.value = ConnectionState.Idle
    }

    // MARK: - ANCS Control Point

    /**
     * Write to the ANCS Control Point characteristic.
     */
    fun writeControlPoint(data: ByteArray): Boolean {
        val gatt = this.gatt ?: return false
        val char = controlPointChar ?: return false

        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        char.value = data
        return gatt.writeCharacteristic(char)
    }

    // MARK: - Outbound Characteristic Writes (Quick Reply, Battery, Health)

    /**
     * Enqueue and serialize an outbound characteristic write to the iOS Companion App.
     */
    @Synchronized
    private fun enqueueCompanionWrite(payload: ByteArray): Boolean {
        if (gatt == null) {
            Log.d(TAG, "enqueueCompanionWrite: deferring payload (${payload.size} bytes), no active GATT connection")
            return false
        }

        // Deduplicate BATT and HEALTH telemetry payloads to prevent stale queue accumulation
        val prefix = when {
            payload.startsWith("BATT:".toByteArray(Charsets.UTF_8)) -> "BATT:"
            payload.startsWith("HEALTH:".toByteArray(Charsets.UTF_8)) -> "HEALTH:"
            else -> null
        }
        if (prefix != null) {
            val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
            characteristicWriteQueue.removeIf { it.startsWith(prefixBytes) }
        }

        characteristicWriteQueue.add(payload)
        processCharacteristicWriteQueue()
        return true
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    @Synchronized
    private fun processCharacteristicWriteQueue() {
        val gatt = this.gatt ?: run {
            characteristicWriteQueue.clear()
            writeWatchdogJob?.cancel()
            isWritingCharacteristic = false
            return
        }

        if (isWritingDescriptor || descriptorWriteQueue.isNotEmpty() || isWritingCharacteristic) {
            Log.d(TAG, "processCharacteristicWriteQueue: Deferring write (busy). Queue size: ${characteristicWriteQueue.size}")
            return
        }

        val companionService = gatt.getService(COMPANION_SERVICE_UUID)
        val characteristic = companionService?.getCharacteristic(QUICK_REPLY_CHARACTERISTIC_UUID)
            ?: gatt.services.flatMap { it.characteristics }
                .firstOrNull { it.uuid == QUICK_REPLY_CHARACTERISTIC_UUID }

        if (characteristic == null) {
            if (characteristicWriteQueue.isNotEmpty()) {
                Log.d(TAG, "processCharacteristicWriteQueue: Companion characteristic not yet discovered. Queue size: ${characteristicWriteQueue.size}")
                gatt.discoverServices()
            }
            return
        }

        val nextPayload = characteristicWriteQueue.poll() ?: return

        isWritingCharacteristic = true
        val payloadStr = String(nextPayload, Charsets.UTF_8)
        Log.i(TAG, "processCharacteristicWriteQueue: Dispatching write '${payloadStr.take(30)}' (${nextPayload.size} bytes)")

        // 2500ms watchdog to ensure write queue never locks up if ACK is missed
        writeWatchdogJob?.cancel()
        writeWatchdogJob = scope.launch {
            delay(2500)
            if (isWritingCharacteristic) {
                Log.w(TAG, "writeCharacteristic timed out after 2500ms, resetting lock")
                isWritingCharacteristic = false
                processCharacteristicWriteQueue()
            }
        }

        val success = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(
                characteristic,
                nextPayload,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            status == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = nextPayload
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        if (!success) {
            Log.e(TAG, "processCharacteristicWriteQueue: writeCharacteristic returned false, unblocking queue")
            writeWatchdogJob?.cancel()
            isWritingCharacteristic = false
            processCharacteristicWriteQueue()
        }
    }

    /**
     * Transmit a canned quick reply back to the iOS Companion App.
     * Payload format: "REPLY:<UID>:<TEXT>"
     */
    fun sendQuickReply(notificationUid: Int, replyText: String): Boolean {
        val payload = "REPLY:$notificationUid:$replyText".toByteArray(Charsets.UTF_8)
        return enqueueCompanionWrite(payload)
    }

    /**
     * Transmit real-time battery and charging status to the iOS Companion App.
     * Payload format: "BATT:<level>:<1|0>"
     */
    fun sendBatteryTelemetry(batteryPct: Int, isCharging: Boolean): Boolean {
        val chargingFlag = if (isCharging) 1 else 0
        val payload = "BATT:$batteryPct:$chargingFlag".toByteArray(Charsets.UTF_8)
        return enqueueCompanionWrite(payload)
    }

    /**
     * Transmit real-time health data (steps, heart rate BPM, active calories) to iOS Companion App.
     * Payload format: "HEALTH:<steps>:<heartRateBpm>:<activeKcal>"
     */
    fun sendHealthTelemetry(steps: Int, heartRateBpm: Int, activeKcal: Int): Boolean {
        val payload = "HEALTH:$steps:$heartRateBpm:$activeKcal".toByteArray(Charsets.UTF_8)
        return enqueueCompanionWrite(payload)
    }

    // MARK: - Helper Methods & Subscriptions

    private fun subscribeToCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = this.gatt ?: return
        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(CCCD_UUID) ?: run {
            Log.e(TAG, "CCCD descriptor not found for ${characteristic.uuid}")
            return
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        descriptorWriteQueue.add(characteristic to descriptor)
        if (!isWritingDescriptor) {
            processDescriptorWriteQueue()
        }
    }

    private fun processDescriptorWriteQueue() {
        if (descriptorWriteQueue.isEmpty()) {
            isWritingDescriptor = false
            Log.i(TAG, "ANCS descriptor write queue fully drained")
            onDescriptorQueueDrained?.invoke()
            processCharacteristicWriteQueue()
            return
        }
        val (_, descriptor) = descriptorWriteQueue.poll() ?: run {
            isWritingDescriptor = false
            onDescriptorQueueDrained?.invoke()
            return
        }
        isWritingDescriptor = true
        val success = gatt?.writeDescriptor(descriptor) ?: false
        if (!success) {
            isWritingDescriptor = false
            processDescriptorWriteQueue()
        }
    }

    private fun readCompanionStatus() {
        val gatt = this.gatt ?: return
        val statusCharUuid = UUID.fromString("A0E3D5B2-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        val service = gatt.getService(COMPANION_SERVICE_UUID)
        val char = service?.getCharacteristic(statusCharUuid)
        if (char != null) {
            gatt.readCharacteristic(char)
        }
    }

    fun subscribeToCallState() {
        val gatt = this.gatt ?: return
        val service = gatt.getService(COMPANION_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(CALL_STATE_CHARACTERISTIC_UUID) ?: return
        subscribeToCharacteristic(char)
    }

    fun readGapDeviceName() {
        val gatt = this.gatt ?: return
        val gapServiceUuid = UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val deviceNameUuid = UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        val service = gatt.getService(gapServiceUuid) ?: return
        val char = service.getCharacteristic(deviceNameUuid) ?: return
        gatt.readCharacteristic(char)
    }

    private var cachedDeviceName: String? = null

    private fun onCharacteristicRead(uuid: UUID, value: ByteArray) {
        val deviceNameUuid = UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        if (uuid == deviceNameUuid) {
            val name = String(value, Charsets.UTF_8)
            if (name.isNotBlank() && !isTruncatedBleName(name)) {
                cachedDeviceName = name
                _connectionState.value = ConnectionState.Connected(name)
            }
        }
    }

    private fun resolveDeviceName(): String {
        cachedDeviceName?.let { return it }
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bondedName = bluetoothManager.adapter?.bondedDevices?.firstOrNull { it.address == gatt?.device?.address }?.name
        val rawName = bondedName ?: gatt?.device?.name ?: "iPhone"
        return if (isTruncatedBleName(rawName)) "iPhone" else rawName
    }

    private fun isTruncatedBleName(name: String): Boolean {
        return name.startsWith("AncsBrid", ignoreCase = true) ||
            name.startsWith("AppleToWe", ignoreCase = true) ||
            name.startsWith("WearBrid", ignoreCase = true) ||
            name.startsWith("WearBridge", ignoreCase = true)
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            targetDevice?.let { reconnect(it) }
        }
    }

    private fun clearCharacteristics() {
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
        descriptorWriteQueue.clear()
        characteristicWriteQueue.clear()
        isWritingDescriptor = false
        isWritingCharacteristic = false
    }

    fun destroy() {
        autoReconnect = false
        reconnectJob?.cancel()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }
}
