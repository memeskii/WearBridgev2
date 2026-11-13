package com.wearos.ancsbridge.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.wearos.ancsbridge.ble.BLEPacketMultiplexer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Unified UI State combining native AMS media playback info and Channel 0x05 QoL enhancements.
 */
data class MediaPlayerUiState(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val isPlaying: Boolean = false,
    val playbackFeedback: String? = null,
    val playbackRate: Float = 1.0f,
    val durationSeconds: Double = 0.0,
    val currentPositionSeconds: Double = 0.0,
    val volume: Float = 0.5f,
    val audioRouteName: String = "iPhone",
    val artwork: Bitmap? = null,
    val appName: String = "",
    val shuffleMode: Int = 0,
    val repeatMode: Int = 0
)

/**
 * Repository orchestrating Apple Media Service (AMS) and Custom GATT Channel 0x05 Media QoL.
 *
 * Responsibilities:
 * 1. Merges AMS playback state with Channel 0x05 album art and audio route metadata.
 * 2. Runs a 1-second coroutine ticker for smooth playback progress interpolation.
 * 3. Automatically requests artwork over Channel 0x05 when track title or artist changes.
 * 4. Decodes incoming Channel 0x05 JPEG base64 payloads to Bitmap.
 */
class MediaPlayerRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MediaPlayerRepository"

        @Volatile
        private var instance: MediaPlayerRepository? = null

        fun getInstance(context: Context): MediaPlayerRepository {
            return instance ?: synchronized(this) {
                instance ?: MediaPlayerRepository(context.applicationContext).also { instance = it }
            }
        }

        /**
         * Pure function to calculate interpolated playback position.
         */
        fun calculateInterpolatedPosition(
            elapsedTime: Double,
            lastReportTimestamp: Long,
            playbackRate: Float,
            durationSeconds: Double,
            now: Long = System.currentTimeMillis()
        ): Double {
            if (lastReportTimestamp <= 0L) return elapsedTime
            val deltaSeconds = (now - lastReportTimestamp) / 1000.0
            val raw = elapsedTime + (deltaSeconds * playbackRate)
            val clamped = maxOf(0.0, raw)
            return if (durationSeconds > 0) minOf(clamped, durationSeconds) else clamped
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val amsManager = AmsMediaManager.getInstance(context)

    private val _uiState = MutableStateFlow(MediaPlayerUiState())
    val uiState: StateFlow<MediaPlayerUiState> = _uiState.asStateFlow()

    // Outbound dispatcher for Channel 0x05 commands (wired to BleConnectionManager)
    var outboundChannelSender: ((Byte, ByteArray) -> Boolean)? = null

    private var pendingPlayback: Boolean? = null
    private var playbackTimeout: Job? = null
    private var progressTickerJob: Job? = null
    private var artFetchJob: Job? = null
    private var lastAmsState: AmsMediaState = AmsMediaState()
    private var lastRequestedTrackKey: String = ""

    init {
        // Observe AMS media state changes
        scope.launch {
            amsManager.mediaState.collect { amsState ->
                handleAmsStateUpdate(amsState)
            }
        }

        // Observe track changes from AMS to auto-fetch artwork over Channel 0x05
        scope.launch {
            amsManager.trackChangeEvents.collect { (title, artist) ->
                onTrackChanged(title, artist)
            }
        }
    }

    private fun handleAmsStateUpdate(amsState: AmsMediaState) {
        lastAmsState = amsState

        val interpolated = if (amsState.isPlaying) {
            calculateInterpolatedPosition(
                elapsedTime = amsState.elapsedTime,
                lastReportTimestamp = amsState.lastReportTimestamp,
                playbackRate = amsState.playbackRate,
                durationSeconds = amsState.durationSeconds
            )
        } else {
            amsState.elapsedTime
        }

        val playing = reconcilePlayback(amsState.isPlaying)
        _uiState.update { current ->
            val sameTrack = current.title == amsState.title && current.artist == amsState.artist
            current.copy(
                artwork = current.artwork.takeIf { sameTrack },
                title = amsState.title,
                artist = amsState.artist,
                album = amsState.album,
                isPlaying = playing,
                playbackRate = amsState.playbackRate,
                durationSeconds = amsState.durationSeconds,
                currentPositionSeconds = interpolated,
                volume = amsState.volume,
                appName = amsState.appName,
                shuffleMode = amsState.shuffleMode,
                repeatMode = amsState.repeatMode
            )
        }

        updateProgressTicker(playing)
    }

    private fun updateProgressTicker(isPlaying: Boolean) {
        if (isPlaying) {
            if (progressTickerJob?.isActive != true) {
                progressTickerJob = scope.launch {
                    while (isActive) {
                        delay(1000L)
                        if (lastAmsState.isPlaying) {
                            val newPos = calculateInterpolatedPosition(
                                elapsedTime = lastAmsState.elapsedTime,
                                lastReportTimestamp = lastAmsState.lastReportTimestamp,
                                playbackRate = lastAmsState.playbackRate,
                                durationSeconds = lastAmsState.durationSeconds
                            )
                            _uiState.update { it.copy(currentPositionSeconds = newPos) }
                        }
                    }
                }
            }
        } else {
            progressTickerJob?.cancel()
            progressTickerJob = null
        }
    }

    /**
     * Called when AMS reports a new track title or artist.
     * Applies a 350ms debounce before requesting album artwork over Channel 0x05,
     * ensuring both Title and Artist attributes settle and avoiding redundant transfers on rapid skips.
     */
    fun onTrackChanged(title: String, artist: String) {
        if (title.isBlank() && artist.isBlank()) return

        artFetchJob?.cancel()
        artFetchJob = scope.launch {
            delay(350L)
            val currentAms = amsManager.mediaState.value
            val effectiveTitle = if (currentAms.title.isNotBlank()) currentAms.title else title
            val effectiveArtist = if (currentAms.artist.isNotBlank()) currentAms.artist else artist
            val trackKey = "${effectiveArtist}_${effectiveTitle}"

            if (trackKey == lastRequestedTrackKey) {
                Log.d(TAG, "Artwork already requested for trackKey: $trackKey")
                return@launch
            }
            lastRequestedTrackKey = trackKey

            requestArtwork(effectiveTitle, effectiveArtist)
        }
    }

    /**
     * Sends {"cmd": "FETCH_ART", "title": "...", "artist": "..."} over Channel 0x05.
     */
    fun requestArtwork(title: String, artist: String) {
        try {
            val json = JSONObject().apply {
                put("cmd", "FETCH_ART")
                put("title", title)
                put("artist", artist)
            }
            val payload = json.toString().toByteArray(Charsets.UTF_8)
            val sent = outboundChannelSender?.invoke(BLEPacketMultiplexer.CHANNEL_MEDIA_QOL, payload) ?: false
            Log.i(TAG, "Sent FETCH_ART request over Channel 0x05 for '$title' by '$artist' (success=$sent)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send FETCH_ART command: ${e.message}")
        }
    }

    /**
     * Handles inbound Channel 0x05 MediaQoLPayload and MediaStateSyncPayload from iOS.
     * Merges full state (title, artist, album, isPlaying, progress, route, art)
     * so watch media player works seamlessly even if AMS is not ready.
     */
    fun handleMediaQoLPayload(payload: ByteArray) {
        try {
            val jsonStr = String(payload, Charsets.UTF_8)
            Log.d(TAG, "Received Channel 0x05 Media payload: ${jsonStr.length} chars")

            val json = JSONObject(jsonStr)
            val isFullState = json.optString("cmd") == "MEDIA_STATE"
            val isClearState = isFullState && json.optString("title").isBlank() &&
                !json.optBoolean("isPlaying") && lastAmsState.title.isBlank()

            val title = json.optString("title", "")
            val artist = json.optString("artist", "")
            val album = json.optString("album", "")
            val hasIsPlaying = json.has("isPlaying")
            val isPlaying = json.optBoolean("isPlaying", _uiState.value.isPlaying)
            val playbackRate = json.optDouble("playbackRate", 1.0).toFloat()
            val elapsed = json.optDouble("elapsedSeconds", -1.0)
            val duration = json.optDouble("durationSeconds", _uiState.value.durationSeconds)
            val volume = json.optDouble("volume", _uiState.value.volume.toDouble()).toFloat()
            val route = json.optString("audioRouteName", "")
            val base64Art = json.optString("artworkBase64", "")

            var decodedBitmap: Bitmap? = null
            if (base64Art.isNotBlank()) {
                try {
                    val imageBytes = Base64.decode(base64Art, Base64.DEFAULT)
                    decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                    Log.i(TAG, "Decoded album artwork bitmap: ${decodedBitmap?.width}x${decodedBitmap?.height} (${imageBytes.size} bytes)")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decode base64 artwork: ${e.message}")
                }
            }

            if (isClearState) {
                _uiState.update { it.copy(title = "", artist = "", album = "", artwork = null,
                    durationSeconds = 0.0, currentPositionSeconds = 0.0, isPlaying = false) }
                lastAmsState = AmsMediaState()
                updateProgressTicker(false)
                return
            }
            if (isFullState && title.isBlank()) return
            val displayedPlayback = if (hasIsPlaying) reconcilePlayback(isPlaying) else _uiState.value.isPlaying
            _uiState.update { current ->
                val changedTrack = title.isNotBlank() && (title != current.title || artist != current.artist)
                current.copy(
                    title = if (title.isNotBlank()) title else current.title,
                    artist = if (artist.isNotBlank()) artist else current.artist,
                    album = if (album.isNotBlank()) album else current.album,
                    isPlaying = displayedPlayback,
                    playbackRate = playbackRate,
                    currentPositionSeconds = if (elapsed >= 0) elapsed else current.currentPositionSeconds,
                    durationSeconds = if (duration > 0) duration else current.durationSeconds,
                    volume = volume.coerceIn(0.0f, 1.0f),
                    audioRouteName = if (route.isNotBlank()) route else current.audioRouteName,
                    artwork = decodedBitmap ?: current.artwork.takeUnless { changedTrack }
                )
            }

            if (hasIsPlaying) {
                lastAmsState = lastAmsState.copy(
                    isPlaying = isPlaying,
                    title = if (title.isNotBlank()) title else lastAmsState.title,
                    artist = if (artist.isNotBlank()) artist else lastAmsState.artist,
                    durationSeconds = if (duration > 0) duration else lastAmsState.durationSeconds,
                    elapsedTime = if (elapsed >= 0) elapsed else lastAmsState.elapsedTime,
                    lastReportTimestamp = System.currentTimeMillis()
                )
                updateProgressTicker(displayedPlayback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Channel 0x05 payload: ${e.message}")
        }
    }

    /**
     * Dispatches active MEDIA_CONTROL action over Channel 0x05 to iOS companion app.
     */
    fun dispatchMediaControl(action: String): Boolean {
        return try {
            val json = JSONObject().apply {
                put("cmd", "MEDIA_CONTROL")
                put("action", action)
                put("timestamp", System.currentTimeMillis())
            }
            val data = json.toString().toByteArray(Charsets.UTF_8)
            outboundChannelSender?.invoke(BLEPacketMultiplexer.CHANNEL_MEDIA_QOL, data) == true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch MEDIA_CONTROL action: ${e.message}")
            false
        }
    }

    fun sendPlay() = setPlayback(true)

    fun sendPause() = setPlayback(false)

    fun togglePlayPause() = setPlayback(!_uiState.value.isPlaying)

    private fun reconcilePlayback(confirmed: Boolean): Boolean {
        val requested = pendingPlayback ?: return confirmed
        if (requested == confirmed) {
            pendingPlayback = null
            playbackTimeout?.cancel()
            _uiState.update { it.copy(playbackFeedback = null) }
        }
        return requested
    }

    private fun setPlayback(playing: Boolean) {
        val queued = if (playing) amsManager.sendPlay() else amsManager.sendPause()
        if (!queued && !dispatchMediaControl(if (playing) "PLAY" else "PAUSE")) {
            _uiState.update { it.copy(playbackFeedback = "iPhone unavailable") }
            return
        }
        playbackTimeout?.cancel()
        pendingPlayback = playing
        _uiState.update { it.copy(isPlaying = playing, playbackFeedback = "Waiting for iPhone…") }
        updateProgressTicker(playing)
        playbackTimeout = scope.launch {
            delay(5_000)
            pendingPlayback = null
            val confirmed = lastAmsState.isPlaying
            _uiState.update { it.copy(isPlaying = confirmed, playbackFeedback = "Playback unconfirmed") }
            updateProgressTicker(confirmed)
            delay(3_000)
            _uiState.update { it.copy(playbackFeedback = null) }
        }
    }

    fun nextTrack() {
        if (!amsManager.nextTrack()) dispatchMediaControl("NEXT")
    }

    fun previousTrack() {
        if (!amsManager.previousTrack()) dispatchMediaControl("PREV")
    }

    fun volumeUp() = adjustVolume(true)

    fun volumeDown() = adjustVolume(false)

    private fun adjustVolume(up: Boolean) {
        val queued = if (up) amsManager.volumeUp() else amsManager.volumeDown()
        if (queued || dispatchMediaControl(if (up) "VOL_UP" else "VOL_DOWN")) {
            val delta = if (up) 0.06f else -0.06f
            _uiState.update { it.copy(volume = (it.volume + delta).coerceIn(0f, 1f)) }
        }
    }

    fun advanceRepeatMode() = amsManager.advanceRepeatMode()
    fun advanceShuffleMode() = amsManager.advanceShuffleMode()
}
