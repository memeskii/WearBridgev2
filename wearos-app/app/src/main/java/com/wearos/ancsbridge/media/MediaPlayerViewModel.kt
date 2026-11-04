package com.wearos.ancsbridge.media

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Wear OS media player UI.
 *
 * Implements:
 * - Playback control proxies (Play, Pause, Toggle, Next, Prev)
 * - Rotary bezel / crown scroll volume control with debounce and haptic tick feedback
 * - Time formatting helpers (mm:ss)
 */
class MediaPlayerViewModel(
    private val repository: MediaPlayerRepository,
    private val context: Context
) : ViewModel() {

    val uiState: StateFlow<MediaPlayerUiState> = repository.uiState

    // Rotary bezel volume control accumulator & debounce
    private var rotaryAccumulator = 0f
    private var lastRotaryCommandTimestamp = 0L
    private val rotaryDebounceMs = 120L
    private val rotaryThreshold = 1.0f

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    /**
     * Handles rotary crown / bezel rotation.
     * Accumulates delta and fires debounced AMS volume up/down commands with haptic tick.
     *
     * @param delta Scroll delta from Compose rotary input modifier (positive = clockwise / up, negative = down)
     */
    fun onRotaryScroll(delta: Float) {
        rotaryAccumulator += delta
        val now = System.currentTimeMillis()

        if (rotaryAccumulator >= rotaryThreshold) {
            if (now - lastRotaryCommandTimestamp >= rotaryDebounceMs) {
                lastRotaryCommandTimestamp = now
                rotaryAccumulator = 0f
                repository.volumeUp()
                performHapticTick()
            }
        } else if (rotaryAccumulator <= -rotaryThreshold) {
            if (now - lastRotaryCommandTimestamp >= rotaryDebounceMs) {
                lastRotaryCommandTimestamp = now
                rotaryAccumulator = 0f
                repository.volumeDown()
                performHapticTick()
            }
        }
    }

    private fun performHapticTick() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(15L)
            }
        } catch (_: Exception) {
            // Ignore vibration exceptions on test runners or devices without haptics
        }
    }

    fun togglePlayPause() {
        repository.togglePlayPause()
        performHapticTick()
    }

    fun nextTrack() {
        repository.nextTrack()
        performHapticTick()
    }

    fun previousTrack() {
        repository.previousTrack()
        performHapticTick()
    }

    fun volumeUp() {
        repository.volumeUp()
        performHapticTick()
    }

    fun volumeDown() {
        repository.volumeDown()
        performHapticTick()
    }

    fun advanceRepeatMode() {
        repository.advanceRepeatMode()
    }

    fun advanceShuffleMode() {
        repository.advanceShuffleMode()
    }

    companion object {
        /**
         * Formats seconds into "m:ss" or "mm:ss".
         */
        fun formatTime(seconds: Double): String {
            val totalSec = maxOf(0, seconds.toInt())
            val mins = totalSec / 60
            val secs = totalSec % 60
            return "%d:%02d".format(mins, secs)
        }

        fun provideFactory(context: Context): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val repo = MediaPlayerRepository.getInstance(context)
                    return MediaPlayerViewModel(repo, context) as T
                }
            }
        }
    }
}
