package com.wearos.ancsbridge

import com.wearos.ancsbridge.filetransfer.FileCompletePayload
import com.wearos.ancsbridge.filetransfer.FileStartPayload
import com.wearos.ancsbridge.filetransfer.FileTransferConstants
import com.wearos.ancsbridge.filetransfer.FileTransferManager
import com.wearos.ancsbridge.filetransfer.FileWindowAckPayload
import com.wearos.ancsbridge.installer.OtaCommandPayload
import com.wearos.ancsbridge.installer.ReceivedFileItem
import com.wearos.ancsbridge.installer.WatchOtaUpdater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class Phase6FileTransferTest {

    @Test
    fun testFileStartPayloadSerialization() {
        val payload = FileStartPayload(
            transferId = "123e4567-e89b-12d3-a456-426614174000",
            fileName = "cyber_dial.apk",
            fileSize = 1845200L,
            fileType = FileTransferConstants.FILE_TYPE_WATCH_FACE,
            sha256 = "5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8",
            totalWindows = 57
        )

        val json = payload.toJson()
        assertTrue("JSON should contain FILE_START", json.contains("\"cmd\":\"FILE_START\""))
        assertTrue("JSON should contain fileName", json.contains("\"fileName\":\"cyber_dial.apk\""))
        assertTrue("JSON should contain sha256", json.contains("\"sha256\":\"5e884898da28047151d0e56f8dc6292773603d0d6aabbdd62a11ef721d1542d8\""))

        val parsed = FileStartPayload.fromJson(json)
        assertNotNull("Parsed payload should not be null", parsed)
        assertEquals(payload.transferId, parsed?.transferId)
        assertEquals(payload.fileName, parsed?.fileName)
        assertEquals(payload.fileSize, parsed?.fileSize)
        assertEquals(payload.fileType, parsed?.fileType)
        assertEquals(payload.sha256, parsed?.sha256)
        assertEquals(payload.totalWindows, parsed?.totalWindows)
    }

    @Test
    fun testFileWindowAckPayloadSerialization() {
        val ack = FileWindowAckPayload(
            transferId = "transfer-abc-123",
            windowIndex = 4,
            bytesReceived = 131072L,
            progress = 0.52
        )

        val json = ack.toJson()
        assertTrue("JSON should contain FILE_WINDOW_ACK", json.contains("\"cmd\":\"FILE_WINDOW_ACK\""))

        val parsed = FileWindowAckPayload.fromJson(json)
        assertNotNull("Parsed ACK should not be null", parsed)
        assertEquals(ack.transferId, parsed?.transferId)
        assertEquals(4, parsed?.windowIndex)
        assertEquals(131072L, parsed?.bytesReceived)
        assertEquals(0.52, parsed?.progress ?: 0.0, 0.001)
    }

    @Test
    fun testFileCompletePayloadSerialization() {
        val complete = FileCompletePayload(
            transferId = "transfer-xyz-789",
            status = FileTransferConstants.STATUS_VERIFIED,
            savedPath = "/storage/emulated/0/Android/data/com.wearos.ancsbridge/files/transfers/cyber_dial.apk"
        )

        val json = complete.toJson()
        assertTrue("JSON should contain FILE_COMPLETE", json.contains("\"cmd\":\"FILE_COMPLETE\""))
        assertTrue("JSON should contain VERIFIED", json.contains("\"status\":\"VERIFIED\""))

        val parsed = FileCompletePayload.fromJson(json)
        assertNotNull("Parsed complete payload should not be null", parsed)
        assertEquals(FileTransferConstants.STATUS_VERIFIED, parsed?.status)
        assertEquals(complete.savedPath, parsed?.savedPath)
    }

    @Test
    fun testStreamingSha256ChecksumCalculation() {
        // Standard test vector: "The quick brown fox jumps over the lazy dog"
        // SHA-256 = d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592
        val testText = "The quick brown fox jumps over the lazy dog"
        val expectedHash = "d7a8fbb307d7809469ca9abcb0082e4f8d5651e46d3cdb762d02d0bf37c9e592"

        val tempFile = File.createTempFile("sha_test", ".txt")
        tempFile.deleteOnExit()
        tempFile.writeText(testText, Charsets.UTF_8)

        val computedHash = FileTransferManager.computeFileSha256(tempFile)
        assertEquals("Computed SHA-256 must match known test vector", expectedHash, computedHash.lowercase())
    }

    @Test
    fun testMultiWindowAssemblyAndHashVerification() {
        // Create 100KB synthetic binary data
        val totalBytes = 100 * 1024
        val testData = ByteArray(totalBytes)
        for (i in 0 until totalBytes) {
            testData[i] = ((i * 31 + 17) and 0xFF).toByte()
        }

        // Compute direct whole-array SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        val directHash = FileTransferManager.bytesToHex(digest.digest(testData))

        // Stream via 32KB windows into a temporary file
        val tempFile = File.createTempFile("stream_test", ".bin")
        tempFile.deleteOnExit()

        val streamingDigest = MessageDigest.getInstance("SHA-256")
        val windowSize = 32 * 1024

        FileOutputStream(tempFile).use { fos ->
            var offset = 0
            while (offset < totalBytes) {
                val len = minOf(windowSize, totalBytes - offset)
                val window = testData.copyOfRange(offset, offset + len)

                fos.write(window)
                streamingDigest.update(window)

                offset += len
            }
        }

        val streamingHash = FileTransferManager.bytesToHex(streamingDigest.digest())
        val diskHash = FileTransferManager.computeFileSha256(tempFile)

        assertEquals("Streaming digest must match whole data digest", directHash, streamingHash)
        assertEquals("Disk digest must match streaming digest", streamingHash, diskHash)
    }

    @Test
    fun testReceivedFileItemProperties() {
        val apkItem = ReceivedFileItem(
            fileName = "minimal_retro.apk",
            filePath = "/transfers/minimal_retro.apk",
            sizeBytes = 250 * 1024L,
            timestamp = 1700000000000L,
            isVerified = true,
            installStatus = ReceivedFileItem.STATUS_READY,
            sha256 = "abcd1234"
        )
        assertTrue("Should detect APK", apkItem.isApk)
        assertTrue("Should detect Watch Face format", apkItem.isWatchFaceFormat)
        assertEquals("250.0 KB", apkItem.formattedSize)

        val wffItem = ReceivedFileItem(
            fileName = "dial.wff",
            filePath = "/transfers/dial.wff",
            sizeBytes = 1500 * 1024L,
            timestamp = 1700000000000L,
            isVerified = true,
            installStatus = ReceivedFileItem.STATUS_INSTALLED,
            sha256 = "ef012345"
        )
        assertFalse("WFF is not APK", wffItem.isApk)
        assertTrue("WFF is watch face format", wffItem.isWatchFaceFormat)
        assertEquals("1.46 MB", wffItem.formattedSize)
    }

    // MARK: - OTA Self-Updater Tests
    @Test
    fun testOtaCommandPayloadSerializationAndDeserialization() {
        val payload = OtaCommandPayload(
            cmd = "OTA_UPDATE_URL",
            url = "http://192.168.1.150:8080/wearbridge-ota.apk",
            versionName = "2.1.0",
            versionCode = 210,
            sha256 = "abcd1234ef567890aabbccddeeff00112233445566778899aabbccddeeff0011"
        )

        val jsonStr = payload.toJson()
        assertTrue("JSON should contain OTA_UPDATE_URL", jsonStr.contains("\"cmd\":\"OTA_UPDATE_URL\""))
        assertTrue("JSON should contain url", jsonStr.contains("http://192.168.1.150:8080/wearbridge-ota.apk"))
        assertTrue("JSON should contain versionName", jsonStr.contains("\"versionName\":\"2.1.0\""))
        assertTrue("JSON should contain versionCode", jsonStr.contains("\"versionCode\":210"))

        val parsed = OtaCommandPayload.fromJson(jsonStr)
        assertNotNull("Parsed payload should not be null", parsed)
        assertEquals(payload.cmd, parsed?.cmd)
        assertEquals(payload.url, parsed?.url)
        assertEquals(payload.versionName, parsed?.versionName)
        assertEquals(payload.versionCode, parsed?.versionCode)
        assertEquals(payload.sha256, parsed?.sha256)

        // Invalid command type returns null
        val invalidJson = """{"cmd":"UNKNOWN","url":"http://test.com"}"""
        val invalidParsed = OtaCommandPayload.fromJson(invalidJson)
        assertEquals(null, invalidParsed)
    }

    @Test
    fun testWatchOtaUpdaterVersionComparison() {
        // Newer versions
        assertTrue("2.1.0 is newer than 2.0.0", WatchOtaUpdater.isNewerVersion("2.1.0", "2.0.0"))
        assertTrue("2.0.1 is newer than 2.0.0", WatchOtaUpdater.isNewerVersion("2.0.1", "2.0.0"))
        assertTrue("v2.1.0 prefix handling", WatchOtaUpdater.isNewerVersion("v2.1.0", "2.0.0"))
        assertTrue("2.0.0 is newer than 1.9.9", WatchOtaUpdater.isNewerVersion("2.0.0", "1.9.9"))
        assertTrue("3.0 is newer than 2.9.9", WatchOtaUpdater.isNewerVersion("3.0", "2.9.9"))

        // Equal or older versions
        assertFalse("2.0.0 is not newer than 2.0.0", WatchOtaUpdater.isNewerVersion("2.0.0", "2.0.0"))
        assertFalse("2.0.0 is not newer than 2.1.0", WatchOtaUpdater.isNewerVersion("2.0.0", "2.1.0"))
        assertFalse("2.0.0 is not newer than 2.0.1", WatchOtaUpdater.isNewerVersion("2.0.0", "2.0.1"))
        assertFalse("1.0.0 is not newer than 2.0.0", WatchOtaUpdater.isNewerVersion("1.0.0", "2.0.0"))
        assertFalse("v2.0.0 is not newer than v2.0.0", WatchOtaUpdater.isNewerVersion("v2.0.0", "v2.0.0"))
    }

    @Test
    fun testOtaApkSha256Verification() {
        val testApkContent = "PK\u0003\u0004SimulatedSyntheticApkPayloadForOtaIntegrityTesting123456789"
        val tempApk = File.createTempFile("wearbridge_ota_test", ".apk")
        tempApk.deleteOnExit()
        tempApk.writeText(testApkContent, Charsets.UTF_8)

        // Compute expected SHA-256
        val digest = MessageDigest.getInstance("SHA-256")
        val expectedHash = FileTransferManager.bytesToHex(digest.digest(testApkContent.toByteArray(Charsets.UTF_8)))

        val computedHash = FileTransferManager.computeFileSha256(tempApk)
        assertEquals("Computed SHA-256 must match expected hash", expectedHash.lowercase(), computedHash.lowercase())
    }
}
