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
import kotlinx.coroutines.cancel
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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val companionPackets = kotlinx.coroutines.channels.Channel<Pair<BluetoothGatt, ByteArray>>(256)

    init {
        scope.launch(Dispatchers.Default) {
            for ((source, data) in companionPackets) {
                if (source === gatt) handleCompanionData(data)
            }
        }
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _notificationSourceEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val notificationSourceEvents: SharedFlow<ByteArray> = _notificationSourceEvents.asSharedFlow()

    private val _dataSourceEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val dataSourceEvents: SharedFlow<ByteArray> = _dataSourceEvents.asSharedFlow()

    private val _callStateEvents = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
    val callStateEvents: SharedFlow<ByteArray> = _callStateEvents.asSharedFlow()

    var negotiatedMtu: Int = 23
        private set

    // BLE Packet Multiplexer instance
    val multiplexer = BLEPacketMultiplexer()

    @Volatile
    private var gatt: BluetoothGatt? = null
    private var targetDevice: BluetoothDevice? = null
    private var reconnectJob: Job? = null
    private var reconnectDelay = INITIAL_RECONNECT_DELAY_MS
    private var autoReconnect = true

    // MARK: - Strict Sequential Android GATT Operation Queue
    sealed class GattOperation {
        data object RequestMtu : GattOperation()
        data object DiscoverServices : GattOperation()
        data class WriteDescriptor(
            val descriptor: BluetoothGattDescriptor,
            val value: ByteArray
        ) : GattOperation()

        data class WriteCharacteristic(
            val characteristic: BluetoothGattCharacteristic,
            val value: ByteArray,
            val writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        ) : GattOperation()

        data class ReadCharacteristic(
            val characteristic: BluetoothGattCharacteristic
        ) : GattOperation()
    }

    private val gattOperationQueue = java.util.concurrent.ConcurrentLinkedQueue<GattOperation>()
    private val pendingCompanionPayloads = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
    @Volatile
    private var isGattOperationInFlight = false
    private var gattWatchdogJob: Job? = null
    private val gattLock = Any()

    private val _companionConnected = MutableStateFlow(false)
    val companionConnected = _companionConnected.asStateFlow()
    private var heartbeatJob: Job? = null
    @Volatile
    private var heartbeatConfirmed = false
    @Volatile
    private var lastHeartbeatReply = 0L
    @Volatile
    private var pendingHeartbeat = 0L
    private var companionRetryJob: Job? = null
    private var lastServiceDiscoveryTime = 0L

    val isQueueBusy: Boolean
        get() = isGattOperationInFlight || !gattOperationQueue.isEmpty() || !pendingCompanionPayloads.isEmpty()

    private var notificationSourceChar: BluetoothGattCharacteristic? = null
    private var controlPointChar: BluetoothGattCharacteristic? = null
    private var dataSourceChar: BluetoothGattCharacteristic? = null

    private val amsManager = com.wearos.ancsbridge.media.AmsMediaManager.getInstance(context)
    private val mediaRepo = com.wearos.ancsbridge.media.MediaPlayerRepository.getInstance(context)

    init {
        multiplexer.outboundChunkWriter = { chunk ->
            enqueueCompanionWrite(chunk)
        }
        multiplexer.onPayloadReceived = { channel, payload ->
            handleMultiplexerPayload(channel, payload)
        }
        com.wearos.ancsbridge.health.HealthTelemetryManager.getInstance(context).outboundDataDispatcher = { channel, data ->
            val mtu = (negotiatedMtu - 3).coerceIn(20, 512)
            val chunks = multiplexer.createChunks(channel, data, mtu)
            for (chunk in chunks) {
                enqueueCompanionWrite(chunk)
            }
        }
        mediaRepo.outboundChannelSender = { channel, data ->
            findCompanionCharacteristic() != null && sendChannelPayload(channel, data)
        }
        com.wearos.ancsbridge.messages.MessageReplyManager.getInstance(context).outboundReplyDispatcher = { data ->
            val mtu = (negotiatedMtu - 3).coerceIn(20, 512)
            val chunks = multiplexer.createChunks(BLEPacketMultiplexer.CHANNEL_MESSAGE_REPLY, data, mtu)
            for (chunk in chunks) {
                enqueueCompanionWrite(chunk)
            }
            true
        }
        com.wearos.ancsbridge.filetransfer.FileTransferManager.getInstance(context).outboundPacketSender = { data ->
            val mtu = (negotiatedMtu - 3).coerceIn(20, 512)
            val chunks = multiplexer.createChunks(BLEPacketMultiplexer.CHANNEL_BINARY_FILE, data, mtu)
            for (chunk in chunks) {
                enqueueCompanionWrite(chunk)
            }
        }
        amsManager.outboundWriteHandler = { char, data ->
            enqueueGattOperation(GattOperation.WriteCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
            true
        }
    }

    val bondStateReceiver = BondStateReceiver(
        onBonded = { device ->
            Log.i(TAG, "Bond completed, re-discovering services")
            scope.launch {
                delay(1000)
                enqueueGattOperation(GattOperation.DiscoverServices)
            }
        },
        onBondFailed = { device ->
            Log.e(TAG, "Bond failed")
            _connectionState.value = ConnectionState.Error("Pairing failed")
            disconnect()
        }
    )

    private val gattCallback = GattCallback(
        acceptsGatt = { it === gatt },
        onConnected = { gatt ->
            negotiatedMtu = 23
            val displayName = if (isTruncatedBleName(gatt.device.name ?: "")) "iPhone" else (gatt.device.name ?: "iPhone")
            _connectionState.value = ConnectionState.Connecting(displayName)

            // Request higher MTU for less fragmentation
            enqueueGattOperation(GattOperation.RequestMtu)
        },
        onDisconnected = onDisconnected@{ gatt, status ->
            if (gatt !== this.gatt) {
                gatt.close()
                return@onDisconnected
            }
            Log.i(TAG, "Disconnected, status=$status")
            synchronized(gattLock) {
                if (gatt === this.gatt) retireGatt(gatt)
            }
        },
        onServicesDiscoveredCallback = { gatt, status ->
            finishGattOperation()
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service discovery failed: $status")
                synchronized(gattLock) { if (gatt === this.gatt) retireGatt(gatt) }
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
                    Log.w(TAG, "Bonded phone has no ANCS service. Reconnecting with backoff.")
                    synchronized(gattLock) { if (gatt === this.gatt) retireGatt(gatt) }
                }
                processNextGattOperation()
                return@GattCallback
            }

            // ANCS service found — get characteristics
            Log.i(TAG, "ANCS service found!")

            // Also discover Apple Media Service (AMS)
            val amsService = gatt.getService(AncsConstants.AMS_SERVICE_UUID)
            if (amsService != null) {
                Log.i(TAG, "Apple Media Service (AMS) found!")
                val remoteCmd = amsService.getCharacteristic(AncsConstants.AMS_REMOTE_COMMAND_UUID)
                val entityUpd = amsService.getCharacteristic(AncsConstants.AMS_ENTITY_UPDATE_UUID)
                val entityAttr = amsService.getCharacteristic(AncsConstants.AMS_ENTITY_ATTRIBUTE_UUID)
                amsManager.onAmsServiceDiscovered(gatt, remoteCmd, entityUpd, entityAttr)
            } else {
                Log.d(TAG, "Apple Media Service (AMS) not found on device")
            }

            // If already subscribed (rediscovery for companion service), check companion and flush queue
            if (notificationSourceChar != null && dataSourceChar != null) {
                Log.i(TAG, "ANCS already subscribed, checking companion service")
                readCompanionStatus()
                if (heartbeatJob?.isActive != true) subscribeToCompanionCharacteristic()
                val companionChar = findCompanionCharacteristic()
                if (companionChar != null && pendingCompanionPayloads.isNotEmpty()) {
                    Log.i(TAG, "Companion characteristic confirmed on rediscovery, flushing ${pendingCompanionPayloads.size} pending payloads")
                    while (pendingCompanionPayloads.isNotEmpty()) {
                        val buffered = pendingCompanionPayloads.poll() ?: break
                        enqueueGattOperation(GattOperation.WriteCharacteristic(companionChar, buffered, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
                    }
                }
                processNextGattOperation()
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
            dataSourceChar?.let(::subscribeToCharacteristic)
        },
        onNotificationSourceChanged = { data ->
            _notificationSourceEvents.tryEmit(data)
        },
        onDataSourceChanged = { data ->
            _dataSourceEvents.tryEmit(data)
        },
        onDescriptorWritten = { descriptor, status ->
            gattWatchdogJob?.cancel()
            synchronized(gattLock) {
                isGattOperationInFlight = false
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Descriptor write failed: $status for ${descriptor.characteristic.uuid}")
                if (descriptor.characteristic.uuid == AncsConstants.DATA_SOURCE_UUID ||
                    descriptor.characteristic.uuid == AncsConstants.NOTIFICATION_SOURCE_UUID) {
                    synchronized(gattLock) { gatt?.let(::retireGatt) }
                }
            } else {
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
                        reconnectDelay = INITIAL_RECONNECT_DELAY_MS
                        _connectionState.value = ConnectionState.Connected(deviceName)
                        val session = gatt
                        // Read the real iPhone name from GAP service
                        readGapDeviceName()
                        // Read companion status to notify iPhone app
                        readCompanionStatus()
                        // Subscribe to call state & companion characteristic from companion app
                        scope.launch {
                            delay(500)
                            if (gatt !== session) return@launch
                            subscribeToCallState()
                            delay(300)
                            if (gatt !== session) return@launch
                            subscribeToCompanionCharacteristic()
                            delay(300)
                            if (gatt !== session) return@launch
                            subscribeToAmsEntityUpdate()
                            delay(300)
                            if (gatt !== session) return@launch
                            subscribeToAmsRemoteCommand()
                        }
                    }
                    AncsConstants.QUICK_REPLY_CHARACTERISTIC_UUID -> startHeartbeat()
                    AncsConstants.AMS_ENTITY_UPDATE_UUID -> {
                        Log.i(TAG, "Subscribed to AMS Entity Update notifications (2F7CABCE) — enqueuing 3 sequential entity subscriptions!")
                        amsManager.subscribeToEntityUpdates()
                    }
                    AncsConstants.AMS_REMOTE_COMMAND_UUID -> {
                        Log.i(TAG, "Subscribed to AMS Remote Command notifications!")
                    }
                }
            }
            processNextGattOperation()
        },
        onCharacteristicWritten = { characteristic, status ->
            gattWatchdogJob?.cancel()
            synchronized(gattLock) {
                isGattOperationInFlight = false
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed: $status for ${characteristic.uuid}")
            } else {
                Log.i(TAG, "Characteristic write confirmed for ${characteristic.uuid}")
                if (characteristic.uuid == AncsConstants.AMS_ENTITY_ATTRIBUTE_UUID) {
                    Log.d(TAG, "AMS Entity Attribute write confirmed, enqueuing read")
                    enqueueGattOperation(GattOperation.ReadCharacteristic(characteristic))
                }
            }
            processNextGattOperation()
        },
        onMtuChanged = { mtu, status ->
            finishGattOperation()
            Log.i(TAG, "MTU negotiated: $mtu (status=$status)")
            negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            // After MTU negotiation, discover services
            enqueueGattOperation(GattOperation.DiscoverServices)
        },
        onCharacteristicReadFailure = {
            gattWatchdogJob?.cancel()
            synchronized(gattLock) {
                isGattOperationInFlight = false
            }
            processNextGattOperation()
        },
        onCharacteristicReadCallback = { characteristic, value ->
            gattWatchdogJob?.cancel()
            synchronized(gattLock) {
                isGattOperationInFlight = false
            }
            if (characteristic.uuid == AncsConstants.AMS_ENTITY_ATTRIBUTE_UUID) {
                amsManager.handleEntityAttributeRead(value)
            } else {
                onCharacteristicRead(characteristic.uuid, value)
            }
            processNextGattOperation()
        },
        onCallStateChanged = { data ->
            _callStateEvents.tryEmit(data)
        },
        onCompanionDataReceived = { data ->
            gatt?.let { source ->
                if (companionPackets.trySend(source to data).isFailure) Log.w(TAG, "Companion packet queue is full")
            }
        },
        onAmsEntityUpdate = { data ->
            amsManager.handleEntityUpdate(data)
        },
        onAmsRemoteCommandsUpdate = { data ->
            amsManager.handleSupportedCommandsUpdate(data)
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
                val status = runCatching { JSONObject(str) }.getOrNull()
                if (status?.optString("type") == "bridge_pong") {
                    if (status.optLong("nonce") != pendingHeartbeat || pendingHeartbeat == 0L) return
                    lastHeartbeatReply = android.os.SystemClock.elapsedRealtime()
                    heartbeatConfirmed = true
                    _companionConnected.value = true
                    val battery = status.optInt("battery", -1)
                    if (battery in 0..100) {
                        context.getSharedPreferences("phone_status", Context.MODE_PRIVATE).edit()
                            .putInt("battery", battery)
                            .putLong("updatedAt", System.currentTimeMillis())
                            .apply()
                    }
                } else if (str.contains("\"type\":\"call_state\"") || str.contains("RINGING")) {
                    handleMockCallState(str)
                } else if (str.contains("\"type\":\"alert\"")) {
                    handleAlertPacket(str)
                } else if (str.contains("OTA_UPDATE_URL")) {
                    com.wearos.ancsbridge.installer.WatchOtaUpdater.getInstance(context).handleOtaCommand(str)
                }
            }
            BLEPacketMultiplexer.CHANNEL_MESSAGE_REPLY -> {
                Log.i(TAG, "Multiplexer Reply received: ${payload.size} bytes")
                com.wearos.ancsbridge.messages.MessageReplyManager.getInstance(context).handleInboundChannelPayload(payload)
            }
            BLEPacketMultiplexer.CHANNEL_CONTACTS_JSON -> {
                Log.i(TAG, "Multiplexer Contacts payload received: ${payload.size} bytes")
                val session = gatt ?: return
                com.wearos.ancsbridge.contacts.ContactRepository.getInstance(context).upsertContacts(payload) { count ->
                    val hash = com.wearos.ancsbridge.filetransfer.FileTransferManager.bytesToHex(
                            java.security.MessageDigest.getInstance("SHA-256").digest(payload)
                        )
                    scope.launch {
                        if (gatt !== session) return@launch
                        val ack = JSONObject().put("type", "contacts_result").put("hash", hash).put("count", count)
                        sendChannelPayload(BLEPacketMultiplexer.CHANNEL_CONTACTS_JSON, ack.toString().toByteArray(Charsets.UTF_8))
                    }
                }
            }
            BLEPacketMultiplexer.CHANNEL_WEATHER_JSON -> {
                Log.i(TAG, "Multiplexer Weather payload received: ${payload.size} bytes")
                com.wearos.ancsbridge.weather.WeatherRepository.getInstance(context).saveWeather(payload)
            }
            BLEPacketMultiplexer.CHANNEL_HEALTH_TELEMETRY_JSON -> {
                Log.i(TAG, "Multiplexer Health Telemetry payload received: ${payload.size} bytes")
                com.wearos.ancsbridge.health.HealthTelemetryManager.getInstance(context).handleInboundCommand(payload)
            }
            BLEPacketMultiplexer.CHANNEL_MEDIA_QOL -> {
                Log.i(TAG, "Multiplexer Media QoL payload received: ${payload.size} bytes")
                scope.launch { mediaRepo.handleMediaQoLPayload(payload) }
            }
            BLEPacketMultiplexer.CHANNEL_BINARY_FILE -> {
                Log.i(TAG, "Multiplexer File payload received: ${payload.size} bytes")
                com.wearos.ancsbridge.filetransfer.FileTransferManager.getInstance(context).handleIncomingChannel07Payload(payload)
            }
        }
    }

    private fun handleMockCallState(jsonStr: String) {
        try {
            val json = JSONObject(jsonStr)
            var callerName = json.optString("callerName", "Jane Doe")
            val phoneNumber = json.optString("phoneNumber", "+1 (555) 019-2834")
            val resolved = com.wearos.ancsbridge.contacts.ContactRepository.getInstance(context).resolveContactName(phoneNumber)
            if (!resolved.isNullOrEmpty()) {
                callerName = resolved
            }
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

        _connectionState.value = ConnectionState.Connecting(if (isTruncatedBleName(device.name ?: "")) "iPhone" else (device.name ?: "iPhone"))

        gatt?.close()
        gatt = device.connectGatt(
            context,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            android.os.Handler(android.os.Looper.getMainLooper())
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

        _connectionState.value = ConnectionState.Connecting(if (isTruncatedBleName(device.name ?: "")) "iPhone" else (device.name ?: "iPhone"))

        gatt?.close()
        gatt = device.connectGatt(
            context,
            true,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE,
            BluetoothDevice.PHY_LE_1M_MASK,
            android.os.Handler(android.os.Looper.getMainLooper())
        )
    }

    /**
     * Write to the ANCS Control Point characteristic.
     */
    fun writeControlPoint(data: ByteArray): Boolean {
        val char = controlPointChar ?: return false
        enqueueGattOperation(GattOperation.WriteCharacteristic(char, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
        return true
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
        Log.i(TAG, "Enqueuing GAP Device Name characteristic read")
        enqueueGattOperation(GattOperation.ReadCharacteristic(char))
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
        enqueueGattOperation(GattOperation.DiscoverServices)
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
                Log.i(TAG, "Enqueuing companion status characteristic read to notify iPhone app")
                enqueueGattOperation(GattOperation.ReadCharacteristic(char))
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

    fun subscribeToAmsEntityUpdate() {
        val gatt = this.gatt ?: return
        val service = gatt.getService(AncsConstants.AMS_SERVICE_UUID) ?: run {
            Log.d(TAG, "subscribeToAmsEntityUpdate: AMS service not found")
            return
        }
        val char = service.getCharacteristic(AncsConstants.AMS_ENTITY_UPDATE_UUID) ?: run {
            Log.d(TAG, "subscribeToAmsEntityUpdate: Entity Update characteristic not found")
            return
        }
        subscribeToCharacteristic(char)
        Log.i(TAG, "Subscribing to AMS Entity Update characteristic 2F7CABCE")
    }

    fun subscribeToAmsRemoteCommand() {
        val gatt = this.gatt ?: return
        val service = gatt.getService(AncsConstants.AMS_SERVICE_UUID) ?: run {
            Log.d(TAG, "subscribeToAmsRemoteCommand: AMS service not found")
            return
        }
        val char = service.getCharacteristic(AncsConstants.AMS_REMOTE_COMMAND_UUID) ?: run {
            Log.d(TAG, "subscribeToAmsRemoteCommand: Remote Command characteristic not found")
            return
        }
        subscribeToCharacteristic(char)
        Log.i(TAG, "Subscribing to AMS Remote Command notifications 9B3C81D8")
    }

    // MARK: - Outbound Characteristic Writes & Unified GATT Execution

    fun enqueueGattOperation(operation: GattOperation) {
        gattOperationQueue.add(operation)
        processNextGattOperation()
    }

    private fun finishGattOperation() {
        synchronized(gattLock) {
            gattWatchdogJob?.cancel()
            isGattOperationInFlight = false
        }
    }

    private fun processNextGattOperation() {
        synchronized(gattLock) {
            val currentGatt = this.gatt ?: run {
                gattOperationQueue.clear()
                pendingCompanionPayloads.clear()
                gattWatchdogJob?.cancel()
                isGattOperationInFlight = false
                return
            }

            if (isGattOperationInFlight) {
                return
            }

            while (true) {
                val nextOp = gattOperationQueue.poll() ?: return

                isGattOperationInFlight = true

                gattWatchdogJob?.cancel()
                gattWatchdogJob = scope.launch {
                    delay(3000)
                    synchronized(gattLock) {
                        if (this@BleConnectionManager.gatt !== currentGatt || !isGattOperationInFlight) return@launch
                        Log.w(TAG, "GATT operation timed out. Retiring the connection.")
                        retireGatt(currentGatt)
                    }
                }

                val success = try {
                    when (nextOp) {
                        GattOperation.RequestMtu -> currentGatt.requestMtu(TARGET_MTU)
                        GattOperation.DiscoverServices -> currentGatt.discoverServices()
                        is GattOperation.WriteDescriptor -> {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                val status = currentGatt.writeDescriptor(nextOp.descriptor, nextOp.value)
                                status == android.bluetooth.BluetoothStatusCodes.SUCCESS
                            } else {
                                @Suppress("DEPRECATION")
                                nextOp.descriptor.value = nextOp.value
                                @Suppress("DEPRECATION")
                                currentGatt.writeDescriptor(nextOp.descriptor)
                            }
                        }
                        is GattOperation.WriteCharacteristic -> {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                                val status = currentGatt.writeCharacteristic(nextOp.characteristic, nextOp.value, nextOp.writeType)
                                status == android.bluetooth.BluetoothStatusCodes.SUCCESS
                            } else {
                                @Suppress("DEPRECATION")
                                nextOp.characteristic.value = nextOp.value
                                @Suppress("DEPRECATION")
                                nextOp.characteristic.writeType = nextOp.writeType
                                @Suppress("DEPRECATION")
                                currentGatt.writeCharacteristic(nextOp.characteristic)
                            }
                        }
                        is GattOperation.ReadCharacteristic -> {
                            currentGatt.readCharacteristic(nextOp.characteristic)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Exception executing GattOperation $nextOp: ${e.message}", e)
                    false
                }

                if (success) return
                Log.w(TAG, "GATT rejected ${nextOp.javaClass.simpleName}")
                finishGattOperation()
                if (nextOp == GattOperation.RequestMtu) {
                    gattOperationQueue.add(GattOperation.DiscoverServices)
                } else if (nextOp == GattOperation.DiscoverServices || nextOp is GattOperation.WriteDescriptor) {
                    retireGatt(currentGatt)
                    return
                }
            }
        }
    }

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
            pendingCompanionPayloads.removeIf { it.startsWith(prefixBytes) }
        }

        val char = findCompanionCharacteristic()
        if (char == null) {
            Log.d(TAG, "Companion characteristic not yet discovered. Buffering payload (${payload.size} bytes). Triggering discovery check.")
            pendingCompanionPayloads.add(payload)
            checkAndDiscoverCompanion()
            return true
        }

        // Flush any buffered companion payloads first
        while (pendingCompanionPayloads.isNotEmpty()) {
            val buffered = pendingCompanionPayloads.poll() ?: break
            enqueueGattOperation(GattOperation.WriteCharacteristic(char, buffered, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
        }

        enqueueGattOperation(GattOperation.WriteCharacteristic(char, payload, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
        return true
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
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
     * Send payload over any multiplexer channel to the companion app.
     */
    fun sendChannelPayload(channel: Byte, data: ByteArray): Boolean {
        val mtu = (negotiatedMtu - 3).coerceIn(20, 512)
        val chunks = multiplexer.createChunks(channel, data, mtu)
        var success = true
        for (chunk in chunks) {
            if (!enqueueCompanionWrite(chunk)) success = false
        }
        return success
    }

    /**
     * Send multiplexed quick reply.
     */
    fun sendMultiplexedQuickReply(notificationUid: Int, replyText: String): Boolean {
        val payload = org.json.JSONObject()
            .put("type", "reply")
            .put("uid", notificationUid)
            .put("message", replyText)
            .toString().toByteArray(Charsets.UTF_8)
        return sendChannelPayload(BLEPacketMultiplexer.CHANNEL_MESSAGE_REPLY, payload)
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
        val previousGatt = gatt
        gatt = null
        clearCharacteristics()
        if (previousGatt != null) closeGatt(previousGatt)
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
        val currentGatt = this.gatt ?: return

        currentGatt.setCharacteristicNotification(characteristic, true)

        val descriptor = characteristic.getDescriptor(AncsConstants.CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "CCCD not found for ${characteristic.uuid}")
            return
        }

        enqueueGattOperation(GattOperation.WriteDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE))
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        val session = gatt ?: return
        heartbeatJob = scope.launch {
            while (gatt === session) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!isQueueBusy && heartbeatConfirmed && now - lastHeartbeatReply > 100_000L) {
                    _companionConnected.value = false
                    synchronized(gattLock) {
                        if (gatt === session) retireGatt(session)
                    }
                    return@launch
                }
                if (!isQueueBusy) {
                    val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "WearBridge:heartbeat")
                    wakeLock.acquire(5_000L)
                    try {
                        pendingHeartbeat = now
                        val ping = JSONObject().put("type", "bridge_ping").put("nonce", now)
                        sendChannelPayload(BLEPacketMultiplexer.CHANNEL_PING_CONTROL, ping.toString().toByteArray())
                        delay(1_500L)
                    } finally {
                        if (wakeLock.isHeld) wakeLock.release()
                    }
                }
                delay(45_000L)
            }
        }
    }

    private fun retireGatt(previousGatt: BluetoothGatt) {
        gatt = null
        clearCharacteristics()
        closeGatt(previousGatt)
        _connectionState.value = ConnectionState.Disconnected()
        if (autoReconnect && targetDevice != null) scheduleReconnect()
    }

    private fun closeGatt(session: BluetoothGatt) {
        try {
            session.disconnect()
        } catch (error: SecurityException) {
            Log.w(TAG, "Bluetooth permission was revoked before disconnect", error)
        } finally {
            try {
                session.close()
            } catch (error: SecurityException) {
                Log.w(TAG, "Bluetooth permission was revoked before closing GATT", error)
            }
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            Log.i(TAG, "Scheduling reconnect in ${reconnectDelay}ms")
            val jitter = kotlin.random.Random.nextLong(reconnectDelay / 4 + 1)
            delay((reconnectDelay + jitter).coerceAtMost(MAX_RECONNECT_DELAY_MS))
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            targetDevice?.let { reconnect(it) }
        }
    }

    private fun clearCharacteristics() {
        heartbeatJob?.cancel()
        heartbeatConfirmed = false
        _companionConnected.value = false
        lastHeartbeatReply = 0L
        pendingHeartbeat = 0L
        notificationSourceChar = null
        controlPointChar = null
        dataSourceChar = null
        gattWatchdogJob?.cancel()
        companionRetryJob?.cancel()
        gattOperationQueue.clear()
        pendingCompanionPayloads.clear()
        isGattOperationInFlight = false
        amsManager.reset()
    }

    fun destroy() {
        scope.cancel()
        mediaRepo.outboundChannelSender = null
        com.wearos.ancsbridge.messages.MessageReplyManager.getInstance(context).outboundReplyDispatcher = null
        com.wearos.ancsbridge.filetransfer.FileTransferManager.getInstance(context).outboundPacketSender = null
        disconnect()
    }
}
