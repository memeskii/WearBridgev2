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

        _uiState.update { current ->
            current.copy(
                title = amsState.title,
                artist = amsState.artist,
                album = amsState.album,
                isPlaying = amsState.isPlaying,
                playbackRate = amsState.playbackRate,
                durationSeconds = amsState.durationSeconds,
                currentPositionSeconds = interpolated,
                volume = amsState.volume,
                appName = amsState.appName,
                shuffleMode = amsState.shuffleMode,
                repeatMode = amsState.repeatMode
            )
        }

        updateProgressTicker(amsState.isPlaying)
    }

    private fun updateProgressTicker(isPlaying: Boolean) {
        if (isPlaying) {
            if (progressTickerJob == null || !progressTickerJob!!.isActive) {
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
     * Handles inbound Channel 0x05 MediaQoLPayload from iOS.
     * Schema: {"trackKey": "...", "durationSeconds": ..., "audioRouteName": "...", "artworkBase64": "..."}
     */
    fun handleMediaQoLPayload(payload: ByteArray) {
        try {
            val jsonStr = String(payload, Charsets.UTF_8)
            Log.i(TAG, "Received Channel 0x05 MediaQoL payload: ${jsonStr.length} chars")

            val json = JSONObject(jsonStr)
            val duration = json.optDouble("durationSeconds", _uiState.value.durationSeconds)
            val route = json.optString("audioRouteName", "iPhone")
            val base64Art = json.optString("artworkBase64", "")

            var decodedBitmap: Bitmap? = null
            if (base64Art.isNotBlank()) {
                val imageBytes = Base64.decode(base64Art, Base64.DEFAULT)
                decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                Log.i(TAG, "Decoded album artwork bitmap: ${decodedBitmap?.width}x${decodedBitmap?.height} (${imageBytes.size} bytes)")
            }

            _uiState.update { current ->
                current.copy(
                    durationSeconds = if (duration > 0) duration else current.durationSeconds,
                    audioRouteName = if (route.isNotBlank()) route else current.audioRouteName,
                    artwork = decodedBitmap ?: current.artwork
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Channel 0x05 payload: ${e.message}")
        }
    }

    // Proxy commands to AmsMediaManager
    fun sendPlay() = amsManager.sendPlay()
    fun sendPause() = amsManager.sendPause()
    fun togglePlayPause() = amsManager.togglePlayPause()
    fun nextTrack() = amsManager.nextTrack()
    fun previousTrack() = amsManager.previousTrack()
    fun volumeUp() = amsManager.volumeUp()
    fun volumeDown() = amsManager.volumeDown()
    fun advanceRepeatMode() = amsManager.advanceRepeatMode()
    fun advanceShuffleMode() = amsManager.advanceShuffleMode()
}
