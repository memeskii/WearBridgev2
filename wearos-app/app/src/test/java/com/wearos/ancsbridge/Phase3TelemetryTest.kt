package com.wearos.ancsbridge

import com.wearos.ancsbridge.ble.BLEPacketMultiplexer
import com.wearos.ancsbridge.health.ActivityData
import com.wearos.ancsbridge.health.CaloriesData
import com.wearos.ancsbridge.health.DailyHealthTelemetryPayload
import com.wearos.ancsbridge.health.HealthTelemetryRepository
import com.wearos.ancsbridge.health.SleepSessionData
import com.wearos.ancsbridge.health.SpO2Data
import com.wearos.ancsbridge.health.SpO2Sample
import com.wearos.ancsbridge.health.StressData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3TelemetryTest {

    @Test
    fun testStressScoreCalculation() {
        // High HRV RMSSD indicates relaxed parasympathetic state (low stress)
        val lowStress = HealthTelemetryRepository.calculateStressScore(hrvRmssd = 75.0, heartRate = 62, restingHr = 60)
        assertTrue("High HRV should result in low stress (got $lowStress)", lowStress < 30)

        // Low HRV RMSSD indicates elevated sympathetic tone (high stress)
        val highStress = HealthTelemetryRepository.calculateStressScore(hrvRmssd = 20.0, heartRate = 88, restingHr = 60)
        assertTrue("Low HRV should result in high stress (got $highStress)", highStress > 70)

        // Fallback without HRV: based on HR elevation above resting
        val fallbackStress = HealthTelemetryRepository.calculateStressScore(hrvRmssd = 0.0, heartRate = 95, restingHr = 60)
        assertTrue("Elevated HR delta should yield elevated stress score (got $fallbackStress)", fallbackStress > 60)
    }

    @Test
    fun testSleepScoreCalculation() {
        // Full night sleep: 450 minutes (7.5 hours) with 100m deep, 90m REM, 25m awake
        val goodScore = HealthTelemetryRepository.calculateSleepScore(
            durationMinutes = 450,
            deepMinutes = 100,
            remMinutes = 90,
            awakeMinutes = 25
        )
        assertTrue("Good 7.5hr sleep should score >= 75 (got $goodScore)", goodScore >= 75)

        // Zero duration should return 0
        val zeroScore = HealthTelemetryRepository.calculateSleepScore(0, 0, 0, 0)
        assertEquals(0, zeroScore)
    }

    @Test
    fun testTelemetryPayloadSerializationRoundTrip() {
        val payload = DailyHealthTelemetryPayload(
            date = "2026-11-03",
            timestamp = 1730650000L,
            calories = CaloriesData(total = 2150.4, active = 450.2),
            spo2 = SpO2Data(
                latest = 98,
                avg = 97.2,
                min = 94,
                samples = listOf(
                    SpO2Sample(1730640000L, 95),
                    SpO2Sample(1730643600L, 98)
                )
            ),
            sleep = SleepSessionData(
                totalDurationMinutes = 445,
                sleepScore = 86,
                awakeMinutes = 30,
                lightMinutes = 210,
                deepMinutes = 115,
                remMinutes = 90
            ),
            stress = StressData(stressScore = 32, hrvRmssd = 48.5),
            activity = ActivityData(steps = 8450, heartRate = 72, restingHeartRate = 58)
        )

        val jsonStr = payload.toJsonString()
        assertTrue(jsonStr.contains("2026-11-03"))
        assertTrue(jsonStr.contains("2150.4"))
        assertTrue(jsonStr.contains("48.5"))

        val parsed = DailyHealthTelemetryPayload.fromJsonString(jsonStr)
        assertNotNull(parsed)
        assertEquals("2026-11-03", parsed!!.date)
        assertEquals(2150.4, parsed.calories.total, 0.01)
        assertEquals(450.2, parsed.calories.active, 0.01)
        assertEquals(98, parsed.spo2.latest)
        assertEquals(97.2, parsed.spo2.avg, 0.01)
        assertEquals(2, parsed.spo2.samples.size)
        assertEquals(445, parsed.sleep.totalDurationMinutes)
        assertEquals(86, parsed.sleep.sleepScore)
        assertEquals(115, parsed.sleep.deepMinutes)
        assertEquals(32, parsed.stress.stressScore)
        assertEquals(48.5, parsed.stress.hrvRmssd, 0.01)
        assertEquals(8450, parsed.activity.steps)
    }

    @Test
    fun testChannel0x04PullTelemetryChunkingAndReassembly() {
        val multiplexer = BLEPacketMultiplexer()
        val command = "PULL_TELEMETRY".toByteArray(Charsets.UTF_8)

        var receivedCommand: String? = null
        multiplexer.onPayloadReceived = { channel, data ->
            if (channel == BLEPacketMultiplexer.CHANNEL_HEALTH_TELEMETRY_JSON) {
                receivedCommand = String(data, Charsets.UTF_8)
            }
        }

        val chunks = multiplexer.createChunks(BLEPacketMultiplexer.CHANNEL_HEALTH_TELEMETRY_JSON, command, 30)
        for (chunk in chunks) {
            multiplexer.processIncomingChunk(chunk)
        }

        assertEquals("PULL_TELEMETRY", receivedCommand)
    }
}
