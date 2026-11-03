package com.wearos.ancsbridge.ble

import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * BLE Packet Multiplexer for Wear OS.
 *
 * Implements a 4-byte framing protocol over custom BLE GATT characteristics:
 * - Byte 0: ChannelID (0x01..0x07)
 * - Byte 1: Flags (Bit 0 = SOT, Bit 1 = EOT, Bit 2 = ACK, Bit 3 = CRC Error)
 * - Bytes 2-3: SequenceNumber (UInt16, Big-Endian)
 *
 * SOT/EOT framing handles multi-chunk transfers with automatic MTU-aware chunking
 * and verifies trailing 4-byte CRC32 checksums upon reassembly.
 */
class BLEPacketMultiplexer {

    companion object {
        private const val TAG = "BLEPacketMultiplexer"
        const val HEADER_SIZE = 4

        // Channel Identifiers
        const val CHANNEL_PING_CONTROL: Byte = 0x01
        const val CHANNEL_CONTACTS_JSON: Byte = 0x02
        const val CHANNEL_WEATHER_JSON: Byte = 0x03
        const val CHANNEL_HEALTH_TELEMETRY_JSON: Byte = 0x04
        const val CHANNEL_MEDIA_QOL: Byte = 0x05
        const val CHANNEL_MESSAGE_REPLY: Byte = 0x06
        const val CHANNEL_BINARY_FILE: Byte = 0x07

        // Packet Flags
        const val FLAG_START: Byte = 0x01      // Bit 0: Start of Transfer
        const val FLAG_END: Byte = 0x02        // Bit 1: End of Transfer
        const val FLAG_ACK: Byte = 0x04        // Bit 2: ACK
        const val FLAG_CRC_ERROR: Byte = 0x08  // Bit 3: CRC Error

        fun getChannelName(channel: Byte): String = when (channel) {
            CHANNEL_PING_CONTROL -> "Ping/Control (0x01)"
            CHANNEL_CONTACTS_JSON -> "Contacts JSON (0x02)"
            CHANNEL_WEATHER_JSON -> "Weather JSON (0x03)"
            CHANNEL_HEALTH_TELEMETRY_JSON -> "Health Telemetry (0x04)"
            CHANNEL_MEDIA_QOL -> "Media QoL (0x05)"
            CHANNEL_MESSAGE_REPLY -> "Message Reply (0x06)"
            CHANNEL_BINARY_FILE -> "Binary File/APK (0x07)"
            else -> "Unknown (0x%02X)".format(channel)
        }

        fun isMultiplexedPacket(data: ByteArray): Boolean {
            if (data.size < HEADER_SIZE) return false
            val channel = data[0]
            if (channel !in 0x01..0x07) return false
            val flags = data[1].toInt() and 0xFF
            // Only bits 0..3 are allowed flags
            if ((flags and 0xF0) != 0) return false
            return true
        }

        /**
         * Calculate standard IEEE 802.3 CRC32 checksum.
         */
        fun calculateCrc32(data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
            val crc = CRC32()
            crc.update(data, offset, length)
            return crc.value
        }
    }

    // Per-channel reassembly buffers
    private val reassemblyBuffers = mutableMapOf<Byte, ByteArrayOutputStream>()

    // Callbacks
    var onPayloadReceived: ((channel: Byte, payload: ByteArray) -> Unit)? = null
    var onAckReceived: ((channel: Byte, sequenceNumber: Short, payload: ByteArray) -> Unit)? = null
    var onCrcError: ((channel: Byte, sequenceNumber: Short) -> Unit)? = null

    // Outbound chunk dispatch injected by BLE connection layer
    var outboundChunkWriter: ((ByteArray) -> Boolean)? = null

    /**
     * Process an incoming raw BLE chunk.
     */
    @Synchronized
    fun processIncomingChunk(chunk: ByteArray) {
        if (chunk.size < HEADER_SIZE) {
            Log.w(TAG, "Chunk too short (${chunk.size} bytes)")
            return
        }

        val channel = chunk[0]
        val flags = chunk[1]
        val seq = (((chunk[2].toInt() and 0xFF) shl 8) or (chunk[3].toInt() and 0xFF)).toShort()
        val payload = chunk.copyOfRange(HEADER_SIZE, chunk.size)

        val isStart = (flags.toInt() and FLAG_START.toInt()) != 0
        val isEnd = (flags.toInt() and FLAG_END.toInt()) != 0
        val isAck = (flags.toInt() and FLAG_ACK.toInt()) != 0
        val isCrcError = (flags.toInt() and FLAG_CRC_ERROR.toInt()) != 0

        Log.d(TAG, "RX [${getChannelName(channel)}] Seq #$seq Flags=[${formatFlags(flags)}] (${payload.size} payload bytes)")

        // Handle ACK
        if (isAck) {
            Log.i(TAG, "ACK received for ${getChannelName(channel)} seq=$seq")
            onAckReceived?.invoke(channel, seq, payload)
            return
        }

        // Handle CRC Error from remote peer
        if (isCrcError) {
            Log.e(TAG, "CRC Error reported by remote peer for ${getChannelName(channel)} seq=$seq")
            onCrcError?.invoke(channel, seq)
            return
        }

        // Reassembly handling
        if (isStart) {
            reassemblyBuffers[channel] = ByteArrayOutputStream()
        }

        val buffer = reassemblyBuffers.getOrPut(channel) { ByteArrayOutputStream() }
        buffer.write(payload)

        if (isEnd) {
            val fullBytes = buffer.toByteArray()
            reassemblyBuffers.remove(channel)

            if (fullBytes.size < 4) {
                Log.e(TAG, "Reassembled buffer on ${getChannelName(channel)} too short for CRC32")
                sendCrcError(channel, seq)
                return
            }

            val payloadLength = fullBytes.size - 4
            val expectedCrc = ByteBuffer.wrap(fullBytes, payloadLength, 4)
                .order(ByteOrder.BIG_ENDIAN)
                .int.toLong() and 0xFFFFFFFFL

            val actualCrc = calculateCrc32(fullBytes, 0, payloadLength)

            if (actualCrc == expectedCrc) {
                val extractedPayload = fullBytes.copyOfRange(0, payloadLength)
                Log.i(TAG, "CRC32 verified on ${getChannelName(channel)}! Size: $payloadLength bytes, CRC=0x%08X".format(actualCrc))
                handleVerifiedPayload(channel, seq, extractedPayload, actualCrc)
            } else {
                Log.e(TAG, "CRC32 mismatch on ${getChannelName(channel)}! Actual: 0x%08X, Expected: 0x%08X".format(actualCrc, expectedCrc))
                sendCrcError(channel, seq)
            }
        }
    }

    /**
     * Internal handler to dispatch verified payloads and auto-reply with ACKs where required.
     */
    private fun handleVerifiedPayload(channel: Byte, sequenceNumber: Short, payload: ByteArray, crc32: Long) {
        when (channel) {
            CHANNEL_PING_CONTROL -> {
                // If it's a Ping packet, reply with an ACK carrying the same payload (timestamp)
                val str = String(payload, Charsets.UTF_8)
                if (str.contains("\"type\":\"ping\"") || str.startsWith("PING:")) {
                    Log.i(TAG, "Ping received, replying with ACK")
                    sendAck(channel, sequenceNumber, payload)
                }
            }
            CHANNEL_MESSAGE_REPLY -> {
                // If loopback test, echo back the message payload on the same channel
                val str = String(payload, Charsets.UTF_8)
                if (str.contains("\"type\":\"loopback_test\"")) {
                    Log.i(TAG, "Loopback test received, echoing payload back to iOS")
                    val echoJson = "{\"type\":\"loopback_echo\",\"message\":\"$str\"}".toByteArray(Charsets.UTF_8)
                    val chunks = createChunks(CHANNEL_MESSAGE_REPLY, echoJson, 508)
                    for (c in chunks) {
                        outboundChunkWriter?.invoke(c)
                    }
                }
            }
            CHANNEL_BINARY_FILE -> {
                // File transfer completed and verified: send ACK containing verified CRC32
                val crcBytes = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(crc32.toInt()).array()
                Log.i(TAG, "File transfer completed, sending CRC32 ACK")
                sendAck(channel, sequenceNumber, crcBytes)
            }
        }

        onPayloadReceived?.invoke(channel, payload)
    }

    /**
     * Chunk a full payload into MTU-sized packets with a trailing 4-byte CRC32.
     */
    fun createChunks(channel: Byte, payload: ByteArray, maxChunkSize: Int): List<ByteArray> {
        val maxPayloadPerChunk = (maxChunkSize - HEADER_SIZE).coerceAtLeast(1)

        // Trailing CRC32
        val crc = calculateCrc32(payload)
        val fullData = ByteBuffer.allocate(payload.size + 4)
            .order(ByteOrder.BIG_ENDIAN)
            .put(payload)
            .putInt(crc.toInt())
            .array()

        val chunks = mutableListOf<ByteArray>()
        val totalBytes = fullData.size
        var offset = 0
        var seq: Short = 0

        while (offset < totalBytes) {
            val sliceEnd = (offset + maxPayloadPerChunk).coerceAtMost(totalBytes)
            val sliceSize = sliceEnd - offset

            var flags: Byte = 0
            if (offset == 0) {
                flags = (flags.toInt() or FLAG_START.toInt()).toByte()
            }
            if (sliceEnd == totalBytes) {
                flags = (flags.toInt() or FLAG_END.toInt()).toByte()
            }

            val chunk = ByteArray(HEADER_SIZE + sliceSize)
            chunk[0] = channel
            chunk[1] = flags
            chunk[2] = ((seq.toInt() shr 8) and 0xFF).toByte()
            chunk[3] = (seq.toInt() and 0xFF).toByte()

            System.arraycopy(fullData, offset, chunk, HEADER_SIZE, sliceSize)
            chunks.add(chunk)

            offset = sliceEnd
            seq++
        }

        return chunks
    }

    /**
     * Create a single-chunk ACK packet.
     */
    fun createAckChunk(channel: Byte, sequenceNumber: Short, payload: ByteArray = ByteArray(0)): ByteArray {
        val flags = (FLAG_ACK.toInt() or FLAG_START.toInt() or FLAG_END.toInt()).toByte()
        val chunk = ByteArray(HEADER_SIZE + payload.size)
        chunk[0] = channel
        chunk[1] = flags
        chunk[2] = ((sequenceNumber.toInt() shr 8) and 0xFF).toByte()
        chunk[3] = (sequenceNumber.toInt() and 0xFF).toByte()
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, chunk, HEADER_SIZE, payload.size)
        }
        return chunk
    }

    /**
     * Create a single-chunk CRC Error packet.
     */
    fun createCrcErrorChunk(channel: Byte, sequenceNumber: Short): ByteArray {
        val flags = (FLAG_CRC_ERROR.toInt() or FLAG_START.toInt() or FLAG_END.toInt()).toByte()
        val chunk = ByteArray(HEADER_SIZE)
        chunk[0] = channel
        chunk[1] = flags
        chunk[2] = ((sequenceNumber.toInt() shr 8) and 0xFF).toByte()
        chunk[3] = (sequenceNumber.toInt() and 0xFF).toByte()
        return chunk
    }

    fun sendAck(channel: Byte, sequenceNumber: Short, payload: ByteArray = ByteArray(0)) {
        val chunk = createAckChunk(channel, sequenceNumber, payload)
        outboundChunkWriter?.invoke(chunk)
    }

    fun sendCrcError(channel: Byte, sequenceNumber: Short) {
        val chunk = createCrcErrorChunk(channel, sequenceNumber)
        outboundChunkWriter?.invoke(chunk)
    }

    private fun formatFlags(flags: Byte): String {
        val list = mutableListOf<String>()
        if ((flags.toInt() and FLAG_START.toInt()) != 0) list.add("SOT")
        if ((flags.toInt() and FLAG_END.toInt()) != 0) list.add("EOT")
        if ((flags.toInt() and FLAG_ACK.toInt()) != 0) list.add("ACK")
        if ((flags.toInt() and FLAG_CRC_ERROR.toInt()) != 0) list.add("CRC_ERR")
        return if (list.isEmpty()) "NONE" else list.joinToString("|")
    }
}

