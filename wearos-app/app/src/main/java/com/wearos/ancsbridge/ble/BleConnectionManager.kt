package com.wearos.ancsbridge.ble

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wearos.ancsbridge.AncsApplication
import com.wearos.ancsbridge.R
import com.wearos.ancsbridge.model.ConnectionState
import com.wearos.ancsbridge.ui.IncomingCallActivity
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
import org.json.JSONObject
import java.util.LinkedList

/**
 * Manages the BLE GATT connection to an iPhone and the ANCS subscription lifecycle.
 *
 * Flow: connect → bond → discover services → subscribe DS → subscribe NS → active
 * Integrated with BLEPacketMultiplexer for MTU-aware multiplexed framing and diagnostics.
 */
@SuppressLint("MissingPermission")
class BleConnectionManager(private val context: Context) {

    companion object {
        private const val TAG = "BleConnectionManager"
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 3_000L
        private const val TARGET_MTU = 512
        private const val MAX_SERVICE_DISCOVERY_RETRIES = 2
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

    var negotiatedMtu: Int = TARGET_MTU
        private set

    // BLE Packet Multiplexer instance
    val multiplexer = BLEPacketMultiplexer()

    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var reconnectJob: Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var autoReconnect = true

    // CCCD write queue — must serialize descriptor writes
    private val descriptorWriteQueue = LinkedList<Pair<BluetoothGattCharacteristic, BluetoothGattDescriptor>>()
    private var isWritingDescriptor = false

    // Outbound characteristic write queue — Android permits only ONE in-flight GATT write
    private val characteristicWriteQueue = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var isWritingCharacteristic = false
    private var writeWatchdogJob: Job? = null
    private var companionRetryJob: Job? = null
    private var lastServiceDiscoveryTime = 0L

    var onDescriptorQueueDrained: (() -> Unit)? = null
    val isQueueBusy: Boolean
        get() = isWritingDescriptor || descriptorWriteQueue.isNotEmpty() || isWritingCharacteristic || !characteristicWriteQueue.isEmpty()

    private var serviceDiscoveryRetries = 0
    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null

    init {
        multiplexer.outboundChunkWriter = { chunk ->
            enqueueCompanionWrite(chunk)
        }
        multiplexer.onPayloadReceived = { channel, payload ->
            handleMultiplexerPayload(channel, payload)
        }
    }

    val bondStateReceiver = BondStateReceiver(
        onBonded = { device ->
            Log.i(TAG, "Bond completed, re-discovering services")
            scope.launch {
                delay(1000)
                gatt?.discoverServices()
            }
        },
        onBondFailed = { device ->
            Log.e(TAG, "Bond failed")
            _connectionState.value = ConnectionState.Error("Pairing failed")
            disconnect()
        }
    )

    private val gattCallback = GattCallback(
        onConnected = { gatt ->
            reconnectDelay = INITIAL_RECONNECT_DELAY_MS
            val displayName = if (isTruncatedBleName(gatt.device.name ?: "")) "iPhone" else (gatt.device.name ?: "iPhone")
            _connectionState.value = ConnectionState.Connecting(displayName)

            // Request higher MTU for less fragmentation
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

            val ancsService = gatt.getService(AncsConstants.ANCS_SERVICE_UUID)
            if (ancsService == null) {
                // ANCS not visible — likely not bonded yet
                if (gatt.device.bondState != BluetoothDevice.BOND_BONDED) {
                    Log.i(TAG, "ANCS not found, initiating bonding")
                    val bondDisplayName = if (isTruncatedBleName(gatt.device.name ?: "")) "iPhone" else (gatt.device.name ?: "iPhone")
                    _connectionState.value = ConnectionState.Bonding(bondDisplayName)
                    gatt.device.createBond()
                } else {
                    // Bonded but ANCS still not visible — retry once, then give up
                    serviceDiscoveryRetries++
                    if (serviceDiscoveryRetries <= MAX_SERVICE_DISCOVERY_RETRIES) {
                        Log.w(TAG, "Bonded but ANCS not found, retry $serviceDiscoveryRetries/$MAX_SERVICE_DISCOVERY_RETRIES")
                        scope.launch {
                            gatt.disconnect()
                            delay(2000)
                            connect(gatt.device, autoReconnect = false)
                        }
                    } else {
                        Log.e(TAG, "ANCS not found after $MAX_SERVICE_DISCOVERY_RETRIES retries — not an iPhone?")
                        _connectionState.value = ConnectionState.Error("Not an iPhone (no ANCS)")
                        scope.launch {
                            gatt.disconnect()
                            gatt.close()
                            this@BleConnectionManager.gatt = null
                        }
                    }
                }
                return@GattCallback
            }

            // ANCS service found — get characteristics
            Log.i(TAG, "ANCS service found!")
            serviceDiscoveryRetries = 0

            // If already subscribed (rediscovery for companion service), check companion and flush queue
            if (notificationSourceChar != null && dataSourceChar != null) {
                Log.i(TAG, "ANCS already subscribed, checking companion service")
                readCompanionStatus()
                val companionChar = findCompanionCharacteristic()
                if (companionChar != null) {
                    Log.i(TAG, "Companion characteristic confirmed on rediscovery, processing write queue (${characteristicWriteQueue.size} pending)")
                    processCharacteristicWriteQueue()
                }
                return@GattCallback
            }

            notificationSourceChar = ancsService.getCharacteristic(AncsConstants.NOTIFICATION_SOURCE_UUID)
            controlPointChar = ancsService.getCharacteristic(AncsConstants.CONTROL_POINT_UUID)
            dataSourceChar = ancsService.getCharacteristic(AncsConstants.DATA_SOURCE_UUID)

            if (notificationSourceChar == null || dataSourceChar == null) {
                Log.e(TAG, "Missing ANCS characteristics")
                _connectionState.value = ConnectionState.Error("ANCS characteristics missing")
                return@GattCallback
            }

            // Subscribe to Data Source FIRST (Apple recommendation), then Notification Source
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
                Log.e(TAG, "Descriptor write failed: $status for ${descriptor.characteristic.uuid}")
                return@GattCallback
            }

            val charUuid = descriptor.characteristic.uuid
            when (charUuid) {
                AncsConstants.DATA_SOURCE_UUID -> {
                    Log.i(TAG, "Subscribed to Data Source, now subscribing to Notification Source")
                    notificationSourceChar?.let { subscribeToCharacteristic(it) }
                }
                AncsConstants.NOTIFICATION_SOURCE_UUID -> {
                    Log.i(TAG, "Subscribed to Notification Source — ANCS session active!")
                    // Show initial name (may be truncated BLE name)
                    val deviceName = resolveDeviceName()
                    _connectionState.value = ConnectionState.Connected(deviceName)
                    // Read the real iPhone name from GAP service
                    readGapDeviceName()
                    // Read companion status to notify iPhone app
                    readCompanionStatus()
                    // Subscribe to call state & companion characteristic from companion app
                    scope.launch {
                        delay(500)
                        subscribeToCallState()
                        delay(300)
                        subscribeToCompanionCharacteristic()
                    }
                }
            }

            // Process next queued descriptor write
            processDescriptorWriteQueue()
        },
        onCharacteristicWritten = { characteristic, status ->
            writeWatchdogJob?.cancel()
            isWritingCharacteristic = false
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: $status for ${characteristic.uuid}")
            } else {
                Log.i(TAG, "Companion write confirmed for ${characteristic.uuid}")
            }
            processCharacteristicWriteQueue()
        },
        onMtuChanged = { mtu, status ->
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
            negotiatedMtu = mtu
            // After MTU negotiation, discover services
            gatt?.discoverServices()
        },
        onCharacteristicReadCallback = { characteristic, value ->
            onCharacteristicRead(characteristic.uuid, value)
        },
        onCallStateChanged = { data ->
            _callStateEvents.tryEmit(data)
        },
        onCompanionDataReceived = { data ->
            handleCompanionData(data)
        }
    )

    // MARK: - Inbound Multiplexer Handlers
    private fun handleCompanionData(data: ByteArray) {
        if (BLEPacketMultiplexer.isMultiplexedPacket(data)) {
            multiplexer.processIncomingChunk(data)
        } else {
            Log.d(TAG, "Non-multiplexed data received on companion characteristic: ${data.size} bytes")
        }
    }

    private fun handleMultiplexerPayload(channel: Byte, payload: ByteArray) {
        when (channel) {
            BLEPacketMultiplexer.CHANNEL_PING_CONTROL -> {
                val str = String(payload, Charsets.UTF_8)
                Log.i(TAG, "Multiplexer Ping/Control payload: $str")
                if (str.contains("\"type\":\"call_state\"") || str.contains("RINGING")) {
                    handleMockCallState(str)
                } else if (str.contains("\"type\":\"alert\"")) {
                    handleAlertPacket(str)
                }
            }
            BLEPacketMultiplexer.CHANNEL_MESSAGE_REPLY -> {
                Log.i(TAG, "Multiplexer Reply received: ${payload.size} bytes")
            }
            BLEPacketMultiplexer.CHANNEL_BINARY_FILE -> {
                Log.i(TAG, "Multiplexer File received and verified: ${payload.size} bytes")
            }
        }
    }

    private fun handleMockCallState(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val callerName = json.optString("callerName", "Jane Doe")
            val phoneNumber = json.optString("phoneNumber", "+1 (555) 019-2834")
            val state = json.optString("state", "RINGING")

            if (state.equals("RINGING", ignoreCase = true)) {
                val callIntent = Intent(context, IncomingCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_NO_USER_ACTION or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                    putExtra(IncomingCallActivity.EXTRA_CALLER_NAME, callerName)
                    putExtra(IncomingCallActivity.EXTRA_NOTIFICATION_UID, 999999L)
                    putExtra(IncomingCallActivity.EXTRA_APP_NAME, "Phone")
                }
                context.startActivity(callIntent)
                Log.i(TAG, "Triggered IncomingCallActivity for mock call: $callerName ($phoneNumber)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling mock call state: ${e.message}")
        }
    }

    private fun handleAlertPacket(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            val title = json.optString("title", "Diagnostics Alert")
            val message = json.optString("message", "Custom GATT Alert")
            Log.i(TAG, "Received Alert Packet: $title - $message")

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val notification = NotificationCompat.Builder(context, AncsApplication.CHANNEL_OTHER)
                .setSmallIcon(R.drawable.ic_notification_default)
                .setContentTitle(title)
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            notificationManager?.notify(8888, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling alert packet: ${e.message}")
        }
    }

    /**
     * Connect to a BLE device (iPhone).
     */
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

        _connectionState.value = ConnectionState.Connecting(if (isTruncatedBleName(device.name ?: "")) "iPhone" else (device.name ?: "iPhone"))

        gatt?.close()
        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    /**
     * Reconnect to a previously bonded device using autoConnect.
     */
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

        _connectionState.value = ConnectionState.Connecting(if (isTruncatedBleName(device.name ?: "")) "iPhone" else (device.name ?: "iPhone"))

        gatt?.close()
        gatt = device.connectGatt(
            context,
            true,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

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

    /** Cached real device name read from GAP characteristic */
    private var cachedDeviceName: String? = null

    private fun resolveDeviceName(): String {
        cachedDeviceName?.let { return it }
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        val bondedName = adapter?.bondedDevices?.firstOrNull { device ->
            device.address == gatt?.device?.address
        }?.name

        val rawName = bondedName ?: gatt?.device?.name ?: "iPhone"
        return if (isTruncatedBleName(rawName)) "iPhone" else rawName
    }

    private fun isTruncatedBleName(name: String): Boolean {
        return name.startsWith("AncsBrid", ignoreCase = true) ||
            name.startsWith("AppleToWe", ignoreCase = true) ||
            name.startsWith("WearBrid", ignoreCase = true) ||
            name.startsWith("WearBridge", ignoreCase = true)
    }

    fun readGapDeviceName() {
        val gatt = this.gatt ?: return
        val gapServiceUuid = java.util.UUID.fromString("00001800-0000-1000-8000-00805f9b34fb")
        val deviceNameUuid = java.util.UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        val service = gatt.getService(gapServiceUuid) ?: return
        val char = service.getCharacteristic(deviceNameUuid) ?: return
        gatt.readCharacteristic(char)
        Log.i(TAG, "Reading GAP Device Name characteristic")
    }

    fun onCharacteristicRead(characteristicUuid: java.util.UUID, value: ByteArray) {
        val deviceNameUuid = java.util.UUID.fromString("00002A00-0000-1000-8000-00805f9b34fb")
        if (characteristicUuid == deviceNameUuid) {
            val name = String(value, Charsets.UTF_8)
            if (name.isNotBlank() && !isTruncatedBleName(name)) {
                cachedDeviceName = name
                Log.i(TAG, "GAP Device Name: $name")
                _connectionState.value = ConnectionState.Connected(name)
                onDeviceNameResolved?.invoke(name)
            }
        }
    }

    var onDeviceNameResolved: ((String) -> Unit)? = null

    private fun findCompanionCharacteristic(): BluetoothGattCharacteristic? {
        val gatt = this.gatt ?: return null
        val service = gatt.getService(AncsConstants.COMPANION_SERVICE_UUID)
        val char = service?.getCharacteristic(AncsConstants.QUICK_REPLY_CHARACTERISTIC_UUID)
        if (char != null) return char
        return gatt.services.flatMap { it.characteristics }
            .firstOrNull { it.uuid == AncsConstants.QUICK_REPLY_CHARACTERISTIC_UUID }
    }

    fun checkAndDiscoverCompanion() {
        val now = System.currentTimeMillis()
        if (now - lastServiceDiscoveryTime < 4000L) return
        lastServiceDiscoveryTime = now
        Log.i(TAG, "Requesting service discovery to locate iOS companion service")
        gatt?.discoverServices()
    }

    private fun scheduleCompanionServiceCheck(delayMs: Long = 4000L) {
        companionRetryJob?.cancel()
        companionRetryJob = scope.launch {
            delay(delayMs)
            if (findCompanionCharacteristic() == null && gatt != null) {
                checkAndDiscoverCompanion()
            }
        }
    }

    private fun readCompanionStatus() {
        val gatt = this.gatt ?: return
        val companionServiceUuid = AncsConstants.COMPANION_SERVICE_UUID
        val statusCharUuid = java.util.UUID.fromString("A0E3D5B2-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
        val service = gatt.getService(companionServiceUuid)
        if (service != null) {
            val char = service.getCharacteristic(statusCharUuid)
            if (char != null) {
                gatt.readCharacteristic(char)
                Log.i(TAG, "Reading companion status characteristic to notify iPhone app")
            } else {
                Log.d(TAG, "Companion status characteristic not found")
            }
        } else {
            Log.d(TAG, "Companion service not found — iPhone companion app may not be running yet")
            scheduleCompanionServiceCheck()
        }
    }

    fun subscribeToCallState() {
        val gatt = this.gatt ?: return
        val service = gatt.getService(AncsConstants.COMPANION_SERVICE_UUID) ?: run {
            Log.d(TAG, "subscribeToCallState: companion service not found")
            return
        }
        val char = service.getCharacteristic(AncsConstants.CALL_STATE_CHARACTERISTIC_UUID) ?: run {
            Log.d(TAG, "subscribeToCallState: call state characteristic not found")
            return
        }
        subscribeToCharacteristic(char)
        Log.i(TAG, "Subscribing to call state characteristic")
    }

    fun subscribeToCompanionCharacteristic() {
        val gatt = this.gatt ?: return
        val service = gatt.getService(AncsConstants.COMPANION_SERVICE_UUID) ?: run {
            Log.d(TAG, "subscribeToCompanionCharacteristic: companion service not found")
            return
        }
        val char = service.getCharacteristic(AncsConstants.QUICK_REPLY_CHARACTERISTIC_UUID) ?: run {
            Log.d(TAG, "subscribeToCompanionCharacteristic: quick reply characteristic not found")
            return
        }
        subscribeToCharacteristic(char)
        Log.i(TAG, "Subscribing to companion multiplexer characteristic 7F8A0003")
    }

    // MARK: - Outbound Characteristic Writes (Quick Reply, Battery, Health)

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

        val char = findCompanionCharacteristic()
        if (char == null) {
            if (characteristicWriteQueue.isNotEmpty()) {
                Log.d(TAG, "processCharacteristicWriteQueue: Companion characteristic not yet discovered. Queue size: ${characteristicWriteQueue.size}. Triggering discovery check.")
                checkAndDiscoverCompanion()
            }
            return
        }

        val nextPayload = characteristicWriteQueue.poll() ?: return

        isWritingCharacteristic = true
        Log.i(TAG, "processCharacteristicWriteQueue: Dispatching write (${nextPayload.size} bytes)")

        writeWatchdogJob?.cancel()
        writeWatchdogJob = scope.launch {
            delay(2500)
            if (isWritingCharacteristic) {
                Log.w(TAG, "writeCharacteristic timed out after 2500ms, resetting lock")
                isWritingCharacteristic = false
                processCharacteristicWriteQueue()
            }
        }

        val success = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(char, nextPayload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            status == android.bluetooth.BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            char.value = nextPayload
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(char)
        }

        if (!success) {
            Log.e(TAG, "processCharacteristicWriteQueue: writeCharacteristic returned false, unblocking queue")
            writeWatchdogJob?.cancel()
            isWritingCharacteristic = false
            processCharacteristicWriteQueue()
        }
    }

    /**
     * Send quick reply over BLE to the iOS companion app using custom characteristic.
     * Format: "REPLY:<UID>:<TEXT>"
     */
    fun sendQuickReply(notificationUid: Int, replyText: String): Boolean {
        val payload = "REPLY:$notificationUid:$replyText".toByteArray(Charsets.UTF_8)
        return enqueueCompanionWrite(payload)
    }

    /**
     * Send multiplexed quick reply.
     */
    fun sendMultiplexedQuickReply(notificationUid: Int, replyText: String): Boolean {
        val payload = "{\"type\":\"reply\",\"uid\":$notificationUid,\"message\":\"$replyText\"}".toByteArray(Charsets.UTF_8)
        val chunks = multiplexer.createChunks(BLEPacketMultiplexer.CHANNEL_MESSAGE_REPLY, payload, (negotiatedMtu - 3).coerceAtLeast(20))
        var success = true
        for (chunk in chunks) {
            if (!enqueueCompanionWrite(chunk)) success = false
        }
        return success
    }

    /**
     * Send real-time battery telemetry to iOS Companion App.
     * Format: "BATT:<level>:<1|0>" over characteristic 7F8A0003-9B3C-4D2A-B9E1-8C5F6D7E8A01.
     */
    fun sendBatteryTelemetry(batteryPct: Int, isCharging: Boolean): Boolean {
        val chargingFlag = if (isCharging) 1 else 0
        val payload = "BATT:$batteryPct:$chargingFlag".toByteArray(Charsets.UTF_8)
        return enqueueCompanionWrite(payload)
    }

    /**
     * Send real-time health telemetry to iOS Companion App.
     * Format: "HEALTH:<steps>:<heartRateBpm>:<activeKcal>" over characteristic 7F8A0003-9B3C-4D2A-B9E1-8C5F6D7E8A01.
     */
    fun sendHealthTelemetry(steps: Int, heartRateBpm: Int, activeKcal: Int): Boolean {
        val payload = "HEALTH:$steps:$heartRateBpm:$activeKcal".toByteArray(Charsets.UTF_8)
        return enqueueCompanionWrite(payload)
    }

    /**
     * Send standardized Health Telemetry JSON schema to Companion App.
     * Format: "HEALTH_JSON:<json>" over characteristic 7F8A0003-9B3C-4D2A-B9E1-8C5F6D7E8A01.
     */
    fun sendHealthTelemetry(payload: com.wearos.ancsbridge.health.HealthTelemetryPayload): Boolean {
        val jsonStr = payload.toJson()
        val data = "HEALTH_JSON:$jsonStr".toByteArray(Charsets.UTF_8)
        return enqueueCompanionWrite(data)
    }

    /**
     * Write quick reply payload to the iPhone companion app via BLE.
     * Format: "callerName|replyMessage" (Legacy support)
     */
    fun writeQuickReply(payload: String) {
        enqueueCompanionWrite(payload.toByteArray(Charsets.UTF_8))
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

    fun getBondedIPhone(): BluetoothDevice? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return null
        return adapter.bondedDevices?.firstOrNull { device ->
            val name = device.name ?: ""
            name.contains("iPhone", ignoreCase = true) ||
                name.contains("AncsBrid", ignoreCase = true) ||
                name.contains("AncsBridge", ignoreCase = true) ||
                name.contains("AppleToWe", ignoreCase = true) ||
                name.contains("AppleToWearOS", ignoreCase = true) ||
                name.contains("WearBridge", ignoreCase = true) ||
                name.contains("WearBrid", ignoreCase = true)
        }
    }

    private fun subscribeToCharacteristic(characteristic: BluetoothGattCharacteristic) {
        val gatt = this.gatt ?: return

        gatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(AncsConstants.CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "CCCD not found for ${characteristic.uuid}")
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

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Scheduling reconnect in ${reconnectDelay}ms")
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            targetDevice?.let { reconnect(it) }
        }
    }

    private fun clearCharacteristics() {
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
        writeWatchdogJob?.cancel()
        companionRetryJob?.cancel()
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
