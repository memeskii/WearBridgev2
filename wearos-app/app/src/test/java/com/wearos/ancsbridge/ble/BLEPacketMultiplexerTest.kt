package com.wearos.ancsbridge.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BLEPacketMultiplexerTest {

    @Test
    fun testCrc32StandardVector() {
        val testString = "123456789"
        val data = testString.toByteArray(Charsets.UTF_8)
        val crc = BLEPacketMultiplexer.calculateCrc32(data)
        // Standard IEEE 802.3 CRC32 for "123456789" is 0xCBF43926 (3422009062 in decimal)
        assertEquals(0xCBF43926L, crc)
    }

    @Test
    fun testSingleChunkFraming() {
        val multiplexer = BLEPacketMultiplexer()
        val payload = "Hello from Wear OS Multiplexer".toByteArray(Charsets.UTF_8)

        val chunks = multiplexer.createChunks(
            channel = BLEPacketMultiplexer.CHANNEL_MESSAGE_REPLY,
            payload = payload,
            maxChunkSize = 100
        )

        assertEquals(1, chunks.size)
        val chunk = chunks[0]

        // Header tests
        assertEquals(BLEPacketMultiplexer.CHANNEL_MESSAGE_REPLY, chunk[0])
        val flags = chunk[1].toInt() and 0xFF
        assertTrue((flags and BLEPacketMultiplexer.FLAG_START.toInt()) != 0)
        assertTrue((flags and BLEPacketMultiplexer.FLAG_END.toInt()) != 0)
        assertFalse((flags and BLEPacketMultiplexer.FLAG_ACK.toInt()) != 0)
        assertFalse((flags and BLEPacketMultiplexer.FLAG_CRC_ERROR.toInt()) != 0)

        // Sequence number = 0
        val seq = (((chunk[2].toInt() and 0xFF) shl 8) or (chunk[3].toInt() and 0xFF)).toShort()
        assertEquals(0.toShort(), seq)

        // Length: 4 header + payload + 4 CRC32
        assertEquals(4 + payload.size + 4, chunk.size)
    }

    @Test
    fun testMultiChunkFraming() {
        val multiplexer = BLEPacketMultiplexer()
        val payload = ByteArray(150) { 0x42.toByte() }

        // Chunk with maxChunkSize = 40 (payload slice per chunk = 36)
        val chunks = multiplexer.createChunks(
            channel = BLEPacketMultiplexer.CHANNEL_BINARY_FILE,
            payload = payload,
            maxChunkSize = 40
        )

        assertTrue("Expected multiple chunks for 150 byte payload", chunks.size > 1)

        // First chunk must have START and not END
        val firstFlags = chunks.first()[1].toInt() and 0xFF
        assertTrue((firstFlags and BLEPacketMultiplexer.FLAG_START.toInt()) != 0)
        assertFalse((firstFlags and BLEPacketMultiplexer.FLAG_END.toInt()) != 0)

        // Last chunk must have END and not START
        val lastFlags = chunks.last()[1].toInt() and 0xFF
        assertFalse((lastFlags and BLEPacketMultiplexer.FLAG_START.toInt()) != 0)
        assertTrue((lastFlags and BLEPacketMultiplexer.FLAG_END.toInt()) != 0)

        // Sequence numbers sequential
        for ((index, chunk) in chunks.withIndex()) {
            val seq = (((chunk[2].toInt() and 0xFF) shl 8) or (chunk[3].toInt() and 0xFF)).toShort()
            assertEquals(index.toShort(), seq)
        }
    }

    @Test
    fun testReassemblySuccess() {
        val multiplexer = BLEPacketMultiplexer()
        val originalPayload = "Comprehensive WearBridge BLE Multiplexer Reassembly Test".toByteArray(Charsets.UTF_8)

        var receivedChannel: Byte? = null
        var receivedPayload: ByteArray? = null

        multiplexer.onPayloadReceived = { channel, data ->
            receivedChannel = channel
            receivedPayload = data
        }

        val chunks = multiplexer.createChunks(
            channel = BLEPacketMultiplexer.CHANNEL_PING_CONTROL,
            payload = originalPayload,
            maxChunkSize = 25
        )
        assertTrue(chunks.size > 1)

        for (chunk in chunks) {
            multiplexer.processIncomingChunk(chunk)
        }

        assertEquals(BLEPacketMultiplexer.CHANNEL_PING_CONTROL, receivedChannel)
        assertNotNull(receivedPayload)
        assertEquals(String(originalPayload), String(receivedPayload!!))
    }

    @Test
    fun testReassemblyCrcMismatchTriggersCrcError() {
        val multiplexer = BLEPacketMultiplexer()
        val originalPayload = "Corrupted Payload Test".toByteArray(Charsets.UTF_8)

        var crcErrorReported = false
        multiplexer.outboundChunkWriter = { chunk ->
            val flags = chunk[1].toInt() and 0xFF
            if ((flags and BLEPacketMultiplexer.FLAG_CRC_ERROR.toInt()) != 0) {
                crcErrorReported = true
            }
            true
        }

        val chunks = multiplexer.createChunks(
            channel = BLEPacketMultiplexer.CHANNEL_WEATHER_JSON,
            payload = originalPayload,
            maxChunkSize = 30
        )

        // Corrupt one byte of data in the first chunk
        chunks[0][BLEPacketMultiplexer.HEADER_SIZE] = (chunks[0][BLEPacketMultiplexer.HEADER_SIZE].toInt() xor 0xFF).toByte()

        for (chunk in chunks) {
            multiplexer.processIncomingChunk(chunk)
        }

        assertTrue("Expected CRC error packet to be transmitted when data is corrupted", crcErrorReported)
    }

    @Test
    fun testAutoPingAckReply() {
        val multiplexer = BLEPacketMultiplexer()
        val pingJson = "{\"type\":\"ping\",\"timestamp\":1730635200000}".toByteArray(Charsets.UTF_8)

        var ackTransmitted = false
        var ackPayload: ByteArray? = null

        multiplexer.outboundChunkWriter = { chunk ->
            val flags = chunk[1].toInt() and 0xFF
            if ((flags and BLEPacketMultiplexer.FLAG_ACK.toInt()) != 0) {
                ackTransmitted = true
                ackPayload = chunk.copyOfRange(BLEPacketMultiplexer.HEADER_SIZE, chunk.size)
            }
            true
        }

        val chunks = multiplexer.createChunks(
            channel = BLEPacketMultiplexer.CHANNEL_PING_CONTROL,
            payload = pingJson,
            maxChunkSize = 100
        )

        for (chunk in chunks) {
            multiplexer.processIncomingChunk(chunk)
        }

        assertTrue("Expected auto-reply ACK on ping packet", ackTransmitted)
        assertNotNull(ackPayload)
        assertEquals(String(pingJson), String(ackPayload!!))
    }

    @Test
    fun testFileTransferAckWithCrc() {
        val multiplexer = BLEPacketMultiplexer()
        val filePayload = ByteArray(1024) { (it % 256).toByte() }
        val expectedCrc = BLEPacketMultiplexer.calculateCrc32(filePayload)

        var ackReceivedCrc: Long? = null

        multiplexer.outboundChunkWriter = { chunk ->
            val flags = chunk[1].toInt() and 0xFF
            if ((flags and BLEPacketMultiplexer.FLAG_ACK.toInt()) != 0) {
                val crcBytes = chunk.copyOfRange(BLEPacketMultiplexer.HEADER_SIZE, chunk.size)
                if (crcBytes.size >= 4) {
                    ackReceivedCrc = ByteBuffer.wrap(crcBytes).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                }
            }
            true
        }

        val chunks = multiplexer.createChunks(
            channel = BLEPacketMultiplexer.CHANNEL_BINARY_FILE,
            payload = filePayload,
            maxChunkSize = 128
        )

        for (chunk in chunks) {
            multiplexer.processIncomingChunk(chunk)
        }

        assertNotNull(ackReceivedCrc)
        assertEquals(expectedCrc, ackReceivedCrc)
    }
}

