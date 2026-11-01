package com.wearos.ancsbridge.health

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Manages Wear OS hardware health sensors (Step Counter & Heart Rate),
 * performs daily midnight baseline calculations and active calorie estimation,
 * and throttles BLE dispatches to the iOS Companion App.
 */
class HealthDataManager(
    private val context: Context,
    private val onHealthUpdate: (steps: Int, heartRateBpm: Int, activeKcal: Int) -> Unit
) : SensorEventListener {

    companion object {
        private const val TAG = "HealthDataManager"
        private const val PREFS_NAME = "wearbridge_health_prefs"
        private const val KEY_STEP_BASELINE = "step_baseline"
        private const val KEY_BASELINE_DATE = "baseline_date"
        private const val KEY_LAST_RAW_STEPS = "last_raw_steps"

        private const val STEP_CHANGE_THRESHOLD = 25
        private const val HR_CHANGE_THRESHOLD = 3
        private const val TIME_DISPATCH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
        private const val CALORIES_PER_STEP = 0.045f
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val stepSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val heartRateSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    private var stepBaseline: Long = 0L
    private var baselineDate: String = ""

    // Current live sensor values
    var currentDailySteps: Int = 0
        private set
    var currentHeartRate: Int = 0
        private set
    var currentActiveKcal: Int = 0
        private set

    // Throttling state
    private var lastSentSteps: Int = 0
    private var lastSentHeartRate: Int = 0
    private var lastSentTimestamp: Long = 0L

    init {
        loadPersistedBaseline()
    }

    /**
     * Start listening to Wear OS step counter and heart rate sensors.
     * Safely unregisters any prior listeners first to prevent duplicates upon permission grant.
     */
    fun start() {
        Log.i(TAG, "Starting HealthDataManager. Step sensor available: ${stepSensor != null}, HR sensor available: ${heartRateSensor != null}")
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {}

        try {
            stepSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            } ?: Log.w(TAG, "Sensor.TYPE_STEP_COUNTER not supported on this device")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException registering stepSensor: ${e.message}")
        }

        try {
            heartRateSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            } ?: Log.w(TAG, "Sensor.TYPE_HEART_RATE not supported on this device")
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException registering heartRateSensor: ${e.message}")
        }
    }

    /**
     * Stop sensor listeners to preserve watch battery.
     */
    fun stop() {
        Log.i(TAG, "Stopping HealthDataManager sensor listeners")
        try {
            sensorManager.unregisterListener(this)
        } catch (_: Exception) {}
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val rawSteps = event.values[0].toLong()
                handleRawStepsUpdate(rawSteps)
            }
            Sensor.TYPE_HEART_RATE -> {
                val bpm = event.values[0].toInt()
                if (bpm > 0) {
                    currentHeartRate = bpm
                    evaluateAndDispatch(force = false)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    /**
     * Process cumulative step counter updates with midnight baseline reset.
     */
    private fun handleRawStepsUpdate(rawSteps: Long) {
        val today = getCurrentDateString()

        // 1. Check if a new calendar day began (midnight reset)
        if (today != baselineDate) {
            Log.i(TAG, "Midnight transition detected! Old date: $baselineDate, New date: $today. Baseline set to $rawSteps")
            stepBaseline = rawSteps
            baselineDate = today
            savePersistedBaseline(stepBaseline, baselineDate)
        }
        // 2. Check if device rebooted (cumulative step counter reset below baseline)
        else if (rawSteps < stepBaseline) {
            Log.w(TAG, "Device reboot detected: rawSteps ($rawSteps) < stepBaseline ($stepBaseline). Resetting baseline to 0")
            stepBaseline = 0L
            savePersistedBaseline(stepBaseline, baselineDate)
        }

        prefs.edit().putLong(KEY_LAST_RAW_STEPS, rawSteps).apply()

        // Calculate steps taken today
        val stepsToday = (rawSteps - stepBaseline).coerceAtLeast(0L).toInt()
        currentDailySteps = stepsToday
        currentActiveKcal = (stepsToday * CALORIES_PER_STEP).toInt()

        evaluateAndDispatch(force = false)
    }

    /**
     * Evaluates throttling conditions and dispatches health telemetry if criteria are met.
     */
    @Synchronized
    private fun evaluateAndDispatch(force: Boolean) {
        val now = System.currentTimeMillis()
        val stepDelta = currentDailySteps - lastSentSteps
        val hrDelta = abs(currentHeartRate - lastSentHeartRate)
        val timeDelta = now - lastSentTimestamp

        val shouldDispatch = force ||
            stepDelta >= STEP_CHANGE_THRESHOLD ||
            hrDelta >= HR_CHANGE_THRESHOLD ||
            (timeDelta >= TIME_DISPATCH_INTERVAL_MS && (currentDailySteps > 0 || currentHeartRate > 0))

        if (shouldDispatch) {
            Log.i(TAG, "Dispatching Health Update: steps=$currentDailySteps (+$stepDelta), HR=${currentHeartRate}bpm, kcal=$currentActiveKcal (force=$force)")
            lastSentSteps = currentDailySteps
            lastSentHeartRate = currentHeartRate
            lastSentTimestamp = now

            onHealthUpdate(currentDailySteps, currentHeartRate, currentActiveKcal)
        }
    }

    /**
     * Immediately push current health state (e.g. after ANCS descriptor write queue drains).
     */
    fun forcePushHealthUpdate() {
        evaluateAndDispatch(force = true)
    }

    private fun loadPersistedBaseline() {
        val today = getCurrentDateString()
        baselineDate = prefs.getString(KEY_BASELINE_DATE, "") ?: ""
        stepBaseline = prefs.getLong(KEY_STEP_BASELINE, 0L)

        if (baselineDate != today) {
            // First run today or midnight rolled over while app was stopped
            baselineDate = today
            val lastRaw = prefs.getLong(KEY_LAST_RAW_STEPS, 0L)
            stepBaseline = lastRaw
            savePersistedBaseline(stepBaseline, baselineDate)
        }
        Log.i(TAG, "Loaded step baseline: $stepBaseline for date $baselineDate")
    }

    private fun savePersistedBaseline(baseline: Long, date: String) {
        prefs.edit()
            .putLong(KEY_STEP_BASELINE, baseline)
            .putString(KEY_BASELINE_DATE, date)
            .apply()
    }

    private fun getCurrentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
