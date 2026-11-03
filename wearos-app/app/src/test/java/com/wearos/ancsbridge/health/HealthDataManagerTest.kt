package com.wearos.ancsbridge.health

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class HealthDataManagerTest {

    private lateinit var context: Context
    private lateinit var sensorManager: SensorManager
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var sharedPreferencesEditor: SharedPreferences.Editor

    private val inMemoryPrefs = mutableMapOf<String, Any>()

    @Before
    fun setup() {
        context = mock(Context::class.java)
        sensorManager = mock(SensorManager::class.java)
        sharedPreferences = mock(SharedPreferences::class.java)
        sharedPreferencesEditor = mock(SharedPreferences.Editor::class.java)

        `when`(context.getSystemService(Context.SENSOR_SERVICE)).thenReturn(sensorManager)
        `when`(context.getSharedPreferences("wearbridge_health_prefs", Context.MODE_PRIVATE)).thenReturn(sharedPreferences)
        `when`(sharedPreferences.edit()).thenReturn(sharedPreferencesEditor)

        `when`(sharedPreferencesEditor.putLong(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val value = invocation.arguments[1] as Long
            inMemoryPrefs[key] = value
            sharedPreferencesEditor
        }
        `when`(sharedPreferencesEditor.putString(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val value = invocation.arguments[1] as String
            inMemoryPrefs[key] = value
            sharedPreferencesEditor
        }
        `when`(sharedPreferencesEditor.putInt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val value = invocation.arguments[1] as Int
            inMemoryPrefs[key] = value
            sharedPreferencesEditor
        }

        `when`(sharedPreferences.getLong(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val defaultVal = invocation.arguments[1] as Long
            inMemoryPrefs[key] as? Long ?: defaultVal
        }
        `when`(sharedPreferences.getString(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val defaultVal = invocation.arguments[1] as String
            inMemoryPrefs[key] as? String ?: defaultVal
        }
        `when`(sharedPreferences.getInt(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer { invocation ->
            val key = invocation.arguments[0] as String
            val defaultVal = invocation.arguments[1] as Int
            inMemoryPrefs[key] as? Int ?: defaultVal
        }
    }

    @Test
    fun `test HealthTelemetry JSON schema matches target structure`() {
        val payload = HealthTelemetryPayload(
            timestamp = 1730550000L,
            metrics = HealthMetrics(
                heart_rate = 72,
                blood_oxygen = 98,
                stress_level = 25,
                steps = 4520,
                calories_burnt = 310,
                sleep_data = SleepData(
                    status = "light",
                    duration_minutes = 360,
                    stages = SleepStages(
                        deep_minutes = 90,
                        light_minutes = 180,
                        rem_minutes = 60,
                        awake_minutes = 30
                    ),
                    efficiency = 88
                )
            )
        )

        val jsonString = payload.toJson()
        val json = JSONObject(jsonString)

        assertEquals(1730550000L, json.getLong("timestamp"))
        val metrics = json.getJSONObject("metrics")
        assertEquals(72, metrics.getInt("heart_rate"))
        assertEquals(98, metrics.getInt("blood_oxygen"))
        assertEquals(25, metrics.getInt("stress_level"))
        assertEquals(4520, metrics.getInt("steps"))
        assertEquals(310, metrics.getInt("calories_burnt"))

        val sleep = metrics.getJSONObject("sleep_data")
        assertEquals("light", sleep.getString("status"))
        assertEquals(360, sleep.getInt("duration_minutes"))
        assertEquals(88, sleep.getInt("efficiency"))

        val stages = sleep.getJSONObject("stages")
        assertEquals(90, stages.getInt("deep_minutes"))
        assertEquals(180, stages.getInt("light_minutes"))
        assertEquals(60, stages.getInt("rem_minutes"))
        assertEquals(30, stages.getInt("awake_minutes"))

        // Round-trip parse
        val deserialized = HealthTelemetryPayload.fromJson(jsonString)
        assertEquals(payload.timestamp, deserialized.timestamp)
        assertEquals(payload.metrics.heart_rate, deserialized.metrics.heart_rate)
        assertEquals(payload.metrics.blood_oxygen, deserialized.metrics.blood_oxygen)
        assertEquals(payload.metrics.stress_level, deserialized.metrics.stress_level)
        assertEquals(payload.metrics.steps, deserialized.metrics.steps)
        assertEquals(payload.metrics.calories_burnt, deserialized.metrics.calories_burnt)
        assertEquals(payload.metrics.sleep_data.status, deserialized.metrics.sleep_data.status)
        assertEquals(payload.metrics.sleep_data.duration_minutes, deserialized.metrics.sleep_data.duration_minutes)
        assertEquals(payload.metrics.sleep_data.stages.deep_minutes, deserialized.metrics.sleep_data.stages.deep_minutes)
    }

    @Test
    fun `test HealthDataManager state management and update dispatch`() {
        var dispatchedPayload: HealthTelemetryPayload? = null

        val manager = HealthDataManager(context) { payload ->
            dispatchedPayload = payload
        }

        // Update SpO2
        manager.handleSpO2Update(99)
        // Update Stress
        manager.handleStressUpdate(22)
        // Update Sleep record
        manager.updateSleepRecord(
            status = "deep",
            durationMinutes = 450,
            efficiency = 92,
            stages = SleepStages(deep_minutes = 120, light_minutes = 220, rem_minutes = 80, awake_minutes = 30)
        )

        manager.forcePushHealthUpdate()

        assertNotNull(dispatchedPayload)
        val p = dispatchedPayload!!
        assertEquals(99, p.metrics.blood_oxygen)
        assertEquals(22, p.metrics.stress_level)
        assertEquals("deep", p.metrics.sleep_data.status)
        assertEquals(450, p.metrics.sleep_data.duration_minutes)
        assertEquals(92, p.metrics.sleep_data.efficiency)
        assertEquals(120, p.metrics.sleep_data.stages.deep_minutes)
        assertTrue(p.metrics.calories_burnt >= 0)
    }
}
