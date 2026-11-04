package com.wearos.ancsbridge

import com.wearos.ancsbridge.ble.AncsConstants
import com.wearos.ancsbridge.ble.BLEPacketMultiplexer
import com.wearos.ancsbridge.media.AmsMediaManager
import com.wearos.ancsbridge.media.MediaPlayerRepository
import com.wearos.ancsbridge.media.MediaPlayerViewModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4MediaTest {

    @Test
    fun testAmsPlaybackInfoParsing() {
        // Standard Playing state: State=1, Rate=1.0, Elapsed=42.35s
        val info1 = "1,1.0,42.35"
        val parsed1 = AmsMediaManager.parsePlaybackInfo(info1)
        assertNotNull("PlaybackInfo should parse successfully", parsed1)
        assertEquals(1, parsed1!!.first)
        assertEquals(1.0f, parsed1.second, 0.001f)
        assertEquals(42.35, parsed1.third, 0.001)

        // Paused state: State=0, Rate=0.0, Elapsed=120.0s
        val info2 = "0,0.0,120.0"
        val parsed2 = AmsMediaManager.parsePlaybackInfo(info2)
        assertNotNull(parsed2)
        assertEquals(0, parsed2!!.first)
        assertEquals(0.0f, parsed2.second, 0.001f)
        assertEquals(120.0, parsed2.third, 0.001)

        // Fast-Forwarding state with higher rate: State=3, Rate=2.0, Elapsed=88.5s
        val info3 = "3,2.0,88.5"
        val parsed3 = AmsMediaManager.parsePlaybackInfo(info3)
        assertNotNull(parsed3)
        assertEquals(3, parsed3!!.first)
        assertEquals(2.0f, parsed3.second, 0.001f)
        assertEquals(88.5, parsed3.third, 0.001)

        // Invalid format
        val invalidInfo = "invalid_string"
        assertNull(AmsMediaManager.parsePlaybackInfo(invalidInfo))

        val emptyInfo = ""
        assertNull(AmsMediaManager.parsePlaybackInfo(emptyInfo))
    }

    @Test
    fun testTruncatedAttributeFlagDetection() {
        // Notification payload: [EntityID, AttributeID, Flags, ...Value]
        // Bit 0 of Flags indicates truncation

        // Case 1: Truncated title (Bit 0 = 1)
        val truncatedPayload = byteArrayOf(
            AncsConstants.AMS_ENTITY_ID_TRACK,
            AncsConstants.AMS_TRACK_ATTR_TITLE,
            0x01.toByte() // Truncated flag set
        )
        val isTruncated1 = (truncatedPayload[2].toInt() and AncsConstants.AMS_ENTITY_UPDATE_FLAG_TRUNCATED.toInt()) != 0
        assertTrue("Bit 0 set should be detected as truncated", isTruncated1)

        // Case 2: Untruncated title (Bit 0 = 0) with UTF-8 payload
        val titleBytes = "Blinding Lights".toByteArray(Charsets.UTF_8)
        val fullPayload = byteArrayOf(
            AncsConstants.AMS_ENTITY_ID_TRACK,
            AncsConstants.AMS_TRACK_ATTR_TITLE,
            0x00.toByte() // Not truncated
        ) + titleBytes

        val isTruncated2 = (fullPayload[2].toInt() and AncsConstants.AMS_ENTITY_UPDATE_FLAG_TRUNCATED.toInt()) != 0
        assertFalse("Bit 0 cleared should be detected as untruncated", isTruncated2)

        val extractedValue = String(fullPayload, 3, fullPayload.size - 3, Charsets.UTF_8)
        assertEquals("Blinding Lights", extractedValue)

        // Case 3: Truncated Arabic track title (Bit 0 = 1)
        val arabicTruncated = byteArrayOf(
            AncsConstants.AMS_ENTITY_ID_TRACK,
            AncsConstants.AMS_TRACK_ATTR_TITLE,
            0x01.toByte()
        )
        assertTrue((arabicTruncated[2].toInt() and 0x01) != 0)
    }

    @Test
    fun testProgressInterpolationMath() {
        val lastReportTimestamp = 1000000L
        val elapsed = 30.0
        val duration = 200.0
        val rate = 1.0f

        // 5 seconds elapsed since last report
        val now5s = lastReportTimestamp + 5000L
        val pos5s = MediaPlayerRepository.calculateInterpolatedPosition(
            elapsedTime = elapsed,
            lastReportTimestamp = lastReportTimestamp,
            playbackRate = rate,
            durationSeconds = duration,
            now = now5s
        )
        assertEquals(35.0, pos5s, 0.001)

        // 2x playback rate: 10s elapsed -> +20s
        val now10s = lastReportTimestamp + 10000L
        val pos2x = MediaPlayerRepository.calculateInterpolatedPosition(
            elapsedTime = elapsed,
            lastReportTimestamp = lastReportTimestamp,
            playbackRate = 2.0f,
            durationSeconds = duration,
            now = now10s
        )
        assertEquals(50.0, pos2x, 0.001)

        // Clamping to duration: 300s elapsed should be clamped to 200.0
        val nowFar = lastReportTimestamp + 300000L
        val posClamped = MediaPlayerRepository.calculateInterpolatedPosition(
            elapsedTime = elapsed,
            lastReportTimestamp = lastReportTimestamp,
            playbackRate = rate,
            durationSeconds = duration,
            now = nowFar
        )
        assertEquals(duration, posClamped, 0.001)

        // Clamping to 0: negative delta should not fall below 0
        val posMinClamped = MediaPlayerRepository.calculateInterpolatedPosition(
            elapsedTime = -10.0,
            lastReportTimestamp = lastReportTimestamp,
            playbackRate = rate,
            durationSeconds = duration,
            now = lastReportTimestamp
        )
        assertEquals(0.0, posMinClamped, 0.001)
    }

    @Test
    fun testMediaQoLPayloadJsonRoundTrip() {
        val json = JSONObject().apply {
            put("trackKey", "The Weeknd_Blinding Lights")
            put("durationSeconds", 200.5)
            put("audioRouteName", "AirPods Pro")
            put("artworkBase64", "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
        }

        val jsonString = json.toString()
        val parsed = JSONObject(jsonString)

        assertEquals("The Weeknd_Blinding Lights", parsed.getString("trackKey"))
        assertEquals(200.5, parsed.getDouble("durationSeconds"), 0.001)
        assertEquals("AirPods Pro", parsed.getString("audioRouteName"))
        assertTrue(parsed.getString("artworkBase64").isNotBlank())
    }

    @Test
    fun testTimeFormatting() {
        assertEquals("0:00", MediaPlayerViewModel.formatTime(0.0))
        assertEquals("0:45", MediaPlayerViewModel.formatTime(45.0))
        assertEquals("1:05", MediaPlayerViewModel.formatTime(65.0))
        assertEquals("3:34", MediaPlayerViewModel.formatTime(214.5))
        assertEquals("60:00", MediaPlayerViewModel.formatTime(3600.0))
    }
}

