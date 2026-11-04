package com.wearos.ancsbridge.media

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.content.Context
import android.util.Log
import com.wearos.ancsbridge.ble.AncsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * State representing iOS media playback received via Apple Media Service (AMS).
 */
data class AmsMediaState(
    val isPlaying: Boolean = false,
    val playbackState: Int = 0, // 0=Paused, 1=Playing, 2=Rewinding, 3=FastForwarding
    val playbackRate: Float = 1.0f,
    val elapsedTime: Double = 0.0,
    val lastReportTimestamp: Long = System.currentTimeMillis(),
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationSeconds: Double = 0.0,
    val volume: Float = 0.5f,
    val appName: String = "",
    val queueIndex: Int = 0,
    val queueCount: Int = 0,
    val shuffleMode: Int = 0,
    val repeatMode: Int = 0
)

/**
 * Native Watch Client for Apple Media Service (AMS).
 *
 * AMS UUID: 89D3502B-0F36-433A-8EF4-C502AD55F8DC
 * - Remote Command:   9B3C81D8-57B1-4A8A-B8DF-0E56F7CA51C2 (write/notify)
 * - Entity Update:    2F7CABCE-808D-411F-9A0C-BB92BA96C102 (write/notify)
 * - Entity Attribute: C6B2F38C-23AB-46D8-A6AB-A3A870B5D770 (write/read)
 *
 * Implements entity subscription, real-time update parsing, truncated attribute
 * retrieval via Entity Attribute characteristic, and outbound remote commands.
 */
@SuppressLint("MissingPermission")
class AmsMediaManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "AmsMediaManager"

        @Volatile
        private var instance: AmsMediaManager? = null

        fun getInstance(context: Context): AmsMediaManager {
            return instance ?: synchronized(this) {
                instance ?: AmsMediaManager(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Parses the AMS PlaybackInfo attribute string formatted as:
         * "playbackState,playbackRate,elapsedTime" (e.g. "1,1.0,42.35")
         *
         * @return Triple(playbackState, playbackRate, elapsedTime) or null if invalid
         */
        fun parsePlaybackInfo(infoStr: String): Triple<Int, Float, Double>? {
            val parts = infoStr.split(",")
            if (parts.size >= 3) {
                val state = parts[0].trim().toIntOrNull() ?: 0
                val rate = parts[1].trim().toFloatOrNull() ?: 1.0f
                val elapsed = parts[2].trim().toDoubleOrNull() ?: 0.0
                return Triple(state, rate, elapsed)
            }
            return null
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _mediaState = MutableStateFlow(AmsMediaState())
    val mediaState: StateFlow<AmsMediaState> = _mediaState.asStateFlow()

    // Supported remote commands reported by iOS via Remote Command characteristic notifications
    private val _supportedCommands = MutableStateFlow<Set<Byte>>(emptySet())
    val supportedCommands: StateFlow<Set<Byte>> = _supportedCommands.asStateFlow()

    fun isCommandSupported(commandId: Byte): Boolean {
        val current = _supportedCommands.value
        return current.isEmpty() || current.contains(commandId)
    }

    fun handleSupportedCommandsUpdate(data: ByteArray) {
        val commands = data.toSet()
        _supportedCommands.value = commands
        Log.i(TAG, "Supported AMS Remote Commands updated (${commands.size} commands): ${commands.map { "0x%02X".format(it) }}")
    }

    // Emits (title, artist) whenever track title or artist changes
    private val _trackChangeEvents = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)
    val trackChangeEvents: SharedFlow<Pair<String, String>> = _trackChangeEvents.asSharedFlow()

    // BLE GATT references
    private var gatt: BluetoothGatt? = null
    private var remoteCommandChar: BluetoothGattCharacteristic? = null
    private var entityUpdateChar: BluetoothGattCharacteristic? = null
    private var entityAttributeChar: BluetoothGattCharacteristic? = null

    // Callback allowing BleConnectionManager to route outbound characteristic writes
    var outboundWriteHandler: ((BluetoothGattCharacteristic, ByteArray) -> Boolean)? = null

    // Queue for resolving truncated attributes sequentially via Entity Attribute char
    private val truncatedFetchQueue = ArrayDeque<Pair<Byte, Byte>>()
    private var currentlyFetchingAttribute: Pair<Byte, Byte>? = null

    /**
     * Initializes AMS characteristics when discovered by BluetoothGatt.
     */
    fun onAmsServiceDiscovered(
        gatt: BluetoothGatt,
        remoteCmd: BluetoothGattCharacteristic?,
        entityUpd: BluetoothGattCharacteristic?,
        entityAttr: BluetoothGattCharacteristic?
    ) {
        this.gatt = gatt
        this.remoteCommandChar = remoteCmd
        this.entityUpdateChar = entityUpd
        this.entityAttributeChar = entityAttr

        Log.i(TAG, "AMS service discovered: remoteCmd=${remoteCmd != null}, entityUpd=${entityUpd != null}, entityAttr=${entityAttr != null}")
    }

    /**
     * Writes subscription commands to the Entity Update characteristic:
     * - EntityIDPlayer (0x00): Name (0x00), PlaybackInfo (0x01), Volume (0x02)
     * - EntityIDQueue  (0x01): Index (0x00), Count (0x01), ShuffleMode (0x02), RepeatMode (0x03)
     * - EntityIDTrack  (0x02): Artist (0x00), Album (0x01), Title (0x02), Duration (0x03)
     */
    fun subscribeToEntityUpdates() {
        val char = entityUpdateChar ?: run {
            Log.w(TAG, "Cannot subscribe: Entity Update characteristic not found")
            return
        }

        // EntityIDPlayer: 0x00, 0x00, 0x01, 0x02
        val playerSub = byteArrayOf(
            AncsConstants.AMS_ENTITY_ID_PLAYER,
            AncsConstants.AMS_PLAYER_ATTR_NAME,
            AncsConstants.AMS_PLAYER_ATTR_PLAYBACK_INFO,
            AncsConstants.AMS_PLAYER_ATTR_VOLUME
        )
        writeCharacteristic(char, playerSub)

        // EntityIDQueue: 0x01, 0x00, 0x01, 0x02, 0x03
        val queueSub = byteArrayOf(
            AncsConstants.AMS_ENTITY_ID_QUEUE,
            AncsConstants.AMS_QUEUE_ATTR_INDEX,
            AncsConstants.AMS_QUEUE_ATTR_COUNT,
            AncsConstants.AMS_QUEUE_ATTR_SHUFFLE_MODE,
            AncsConstants.AMS_QUEUE_ATTR_REPEAT_MODE
        )
        writeCharacteristic(char, queueSub)

        // EntityIDTrack: 0x02, 0x00, 0x01, 0x02, 0x03
        val trackSub = byteArrayOf(
            AncsConstants.AMS_ENTITY_ID_TRACK,
            AncsConstants.AMS_TRACK_ATTR_ARTIST,
            AncsConstants.AMS_TRACK_ATTR_ALBUM,
            AncsConstants.AMS_TRACK_ATTR_TITLE,
            AncsConstants.AMS_TRACK_ATTR_DURATION
        )
        writeCharacteristic(char, trackSub)

        Log.i(TAG, "Written AMS entity subscription commands for Player, Queue, and Track")
    }

    /**
     * Handles incoming notification from Entity Update characteristic (2F7CABCE...).
     * Notification payload format:
     * Byte 0: EntityID
     * Byte 1: AttributeID
     * Byte 2: EntityUpdateFlags (Bit 0 = Truncated)
     * Bytes 3+: UTF-8 string value (if not truncated)
     */
    fun handleEntityUpdate(data: ByteArray) {
        if (data.size < 3) {
            Log.w(TAG, "Entity update payload too short (${data.size} bytes)")
            return
        }

        val entityId = data[0]
        val attributeId = data[1]
        val flags = data[2]
        val isTruncated = (flags.toInt() and AncsConstants.AMS_ENTITY_UPDATE_FLAG_TRUNCATED.toInt()) != 0

        if (isTruncated) {
            Log.d(TAG, "Entity update truncated for entity=$entityId, attr=$attributeId. Enqueueing full attribute read.")
            enqueueTruncatedAttributeFetch(entityId, attributeId)
        } else {
            val value = if (data.size > 3) String(data, 3, data.size - 3, Charsets.UTF_8) else ""
            applyAttributeUpdate(entityId, attributeId, value)
        }
    }

    /**
     * Handles characteristic read response from Entity Attribute characteristic (C6B2F38C...).
     * Contains the complete untruncated UTF-8 value.
     */
    fun handleEntityAttributeRead(valueBytes: ByteArray) {
        val current = currentlyFetchingAttribute ?: run {
            Log.w(TAG, "Received Entity Attribute read response but no pending attribute request")
            return
        }

        val value = String(valueBytes, Charsets.UTF_8)
        Log.i(TAG, "Resolved untruncated attribute (${current.first}, ${current.second}): '$value'")
        applyAttributeUpdate(current.first, current.second, value)

        currentlyFetchingAttribute = null
        processNextTruncatedAttribute()
    }

    private fun enqueueTruncatedAttributeFetch(entityId: Byte, attributeId: Byte) {
        synchronized(truncatedFetchQueue) {
            val pair = Pair(entityId, attributeId)
            if (!truncatedFetchQueue.contains(pair) && currentlyFetchingAttribute != pair) {
                truncatedFetchQueue.add(pair)
            }
        }
        processNextTruncatedAttribute()
    }

    private fun processNextTruncatedAttribute() {
        if (currentlyFetchingAttribute != null) return

        val next: Pair<Byte, Byte>?
        synchronized(truncatedFetchQueue) {
            next = truncatedFetchQueue.pollFirst()
        }
        if (next == null) return

        currentlyFetchingAttribute = next
        val (entityId, attributeId) = next

        val char = entityAttributeChar
        val currentGatt = gatt
        if (char == null || currentGatt == null) {
            Log.w(TAG, "Cannot fetch truncated attribute: char or gatt is null")
            currentlyFetchingAttribute = null
            return
        }

        // 1. Write [EntityID, AttributeID] to Entity Attribute characteristic
        val cmd = byteArrayOf(entityId, attributeId)
        val success = writeCharacteristic(char, cmd)
        if (!success) {
            Log.w(TAG, "Failed to write attribute request to Entity Attribute characteristic")
            currentlyFetchingAttribute = null
            return
        }

        // 2. Read the Entity Attribute characteristic after write completes
        // Note: in BleConnectionManager, write completion will call triggerEntityAttributeRead()
    }

    /**
     * Triggered by BleConnectionManager when a write to Entity Attribute characteristic succeeds.
     */
    fun triggerEntityAttributeRead(): Boolean {
        val char = entityAttributeChar ?: return false
        val currentGatt = gatt ?: return false
        return currentGatt.readCharacteristic(char)
    }

    /**
     * Updates internal media state based on parsed attribute values.
     */
    fun applyAttributeUpdate(entityId: Byte, attributeId: Byte, value: String) {
        val prevTitle = _mediaState.value.title
        val prevArtist = _mediaState.value.artist

        _mediaState.update { current ->
            when (entityId) {
                AncsConstants.AMS_ENTITY_ID_PLAYER -> {
                    when (attributeId) {
                        AncsConstants.AMS_PLAYER_ATTR_NAME -> current.copy(appName = value)
                        AncsConstants.AMS_PLAYER_ATTR_PLAYBACK_INFO -> {
                            val parsed = parsePlaybackInfo(value)
                            if (parsed != null) {
                                val (state, rate, elapsed) = parsed
                                current.copy(
                                    isPlaying = (state == 1),
                                    playbackState = state,
                                    playbackRate = rate,
                                    elapsedTime = elapsed,
                                    lastReportTimestamp = System.currentTimeMillis()
                                )
                            } else {
                                current
                            }
                        }
                        AncsConstants.AMS_PLAYER_ATTR_VOLUME -> {
                            val vol = value.toFloatOrNull() ?: current.volume
                            current.copy(volume = vol.coerceIn(0.0f, 1.0f))
                        }
                        else -> current
                    }
                }
                AncsConstants.AMS_ENTITY_ID_QUEUE -> {
                    when (attributeId) {
                        AncsConstants.AMS_QUEUE_ATTR_INDEX -> current.copy(queueIndex = value.toIntOrNull() ?: current.queueIndex)
                        AncsConstants.AMS_QUEUE_ATTR_COUNT -> current.copy(queueCount = value.toIntOrNull() ?: current.queueCount)
                        AncsConstants.AMS_QUEUE_ATTR_SHUFFLE_MODE -> current.copy(shuffleMode = value.toIntOrNull() ?: current.shuffleMode)
                        AncsConstants.AMS_QUEUE_ATTR_REPEAT_MODE -> current.copy(repeatMode = value.toIntOrNull() ?: current.repeatMode)
                        else -> current
                    }
                }
                AncsConstants.AMS_ENTITY_ID_TRACK -> {
                    when (attributeId) {
                        AncsConstants.AMS_TRACK_ATTR_ARTIST -> current.copy(artist = value)
                        AncsConstants.AMS_TRACK_ATTR_ALBUM -> current.copy(album = value)
                        AncsConstants.AMS_TRACK_ATTR_TITLE -> current.copy(title = value)
                        AncsConstants.AMS_TRACK_ATTR_DURATION -> {
                            val dur = value.toDoubleOrNull() ?: current.durationSeconds
                            current.copy(durationSeconds = dur)
                        }
                        else -> current
                    }
                }
                else -> current
            }
        }

        val newTitle = _mediaState.value.title
        val newArtist = _mediaState.value.artist

        // Check if track title or artist changed
        if ((newTitle != prevTitle || newArtist != prevArtist) && (newTitle.isNotBlank() || newArtist.isNotBlank())) {
            Log.i(TAG, "Track changed: '$newTitle' by '$newArtist'. Emitting track change event.")
            scope.launch {
                _trackChangeEvents.emit(Pair(newTitle, newArtist))
            }
        }
    }

    // MARK: - Outbound AMS Remote Commands

    fun sendPlay(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_PLAY)
    fun sendPause(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_PAUSE)
    fun togglePlayPause(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_TOGGLE_PLAY_PAUSE)
    fun nextTrack(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_NEXT_TRACK)
    fun previousTrack(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_PREVIOUS_TRACK)
    fun volumeUp(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_VOLUME_UP)
    fun volumeDown(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_VOLUME_DOWN)
    fun advanceRepeatMode(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_ADVANCE_REPEAT_MODE)
    fun advanceShuffleMode(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_ADVANCE_SHUFFLE_MODE)
    fun likeTrack(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_LIKE_TRACK)
    fun dislikeTrack(): Boolean = sendRemoteCommand(AncsConstants.AMS_REMOTE_CMD_DISLIKE_TRACK)

    fun sendRemoteCommand(commandId: Byte): Boolean {
        val char = remoteCommandChar ?: run {
            Log.w(TAG, "Cannot send AMS remote command 0x%02X: characteristic is null".format(commandId))
            return false
        }
        if (!isCommandSupported(commandId)) {
            Log.w(TAG, "AMS Remote Command 0x%02X is not reported as supported by iOS player. Aborting write to avoid GATT 0xA0 error.".format(commandId))
            return false
        }
        val data = byteArrayOf(commandId)
        val success = writeCharacteristic(char, data)
        Log.i(TAG, "Sent AMS Remote Command 0x%02X. Success=$success".format(commandId))
        return success
    }

    private fun writeCharacteristic(char: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        outboundWriteHandler?.let { handler ->
            return handler(char, data)
        }

        val currentGatt = gatt ?: return false
        char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        char.value = data
        return currentGatt.writeCharacteristic(char)
    }

    /**
     * Resets AMS state on disconnection.
     */
    fun reset() {
        gatt = null
        remoteCommandChar = null
        entityUpdateChar = null
        entityAttributeChar = null
        synchronized(truncatedFetchQueue) {
            truncatedFetchQueue.clear()
        }
        currentlyFetchingAttribute = null
        _supportedCommands.value = emptySet()
        _mediaState.value = AmsMediaState()
    }
}
