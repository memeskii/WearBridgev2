import XCTest
@testable import AncsBridge

final class AncsBridgeTests: XCTestCase {

    // MARK: - CRC32 Tests
    func testCrc32StandardVector() {
        let testString = "123456789"
        let data = testString.data(using: .utf8)!
        let crc = CRC32.checksum(data: data)
        // Standard IEEE 802.3 CRC32 for "123456789" is 0xCBF43926
        XCTAssertEqual(crc, 0xCBF43926, "CRC32 checksum does not match standard test vector")
    }

    func testCrc32EmptyData() {
        let data = Data()
        let crc = CRC32.checksum(data: data)
        XCTAssertEqual(crc, 0x00000000)
    }

    // MARK: - Multiplexer Framing Tests
    func testSingleChunkFraming() {
        let multiplexer = BLEPacketMultiplexer()
        let payload = "Hello, Watch!".data(using: .utf8)!
        let chunks = multiplexer.createChunks(channel: .messageReply, payload: payload, maxChunkSize: 100)

        XCTAssertEqual(chunks.count, 1)
        let chunk = chunks[0]

        // Header verification
        XCTAssertEqual(chunk[0], ChannelID.messageReply.rawValue)
        let flags = PacketFlags(rawValue: chunk[1])
        XCTAssertTrue(flags.contains(.startOfTransfer))
        XCTAssertTrue(flags.contains(.endOfTransfer))
        XCTAssertFalse(flags.contains(.ack))
        XCTAssertFalse(flags.contains(.crcError))

        // Sequence Number 0
        let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
        XCTAssertEqual(seq, 0)

        // Payload + 4 bytes CRC
        XCTAssertEqual(chunk.count, 4 + payload.count + 4)
    }

    func testMultiChunkFraming() {
        let multiplexer = BLEPacketMultiplexer()
        let largePayload = Data(repeating: 0xAB, count: 100)
        // Max chunk size 30 bytes -> each chunk payload slice = 30 - 4 = 26 bytes
        let chunks = multiplexer.createChunks(channel: .binaryFile, payload: largePayload, maxChunkSize: 30)

        XCTAssertGreaterThan(chunks.count, 1)

        // First chunk must have SOT and not EOT
        let firstFlags = PacketFlags(rawValue: chunks[0][1])
        XCTAssertTrue(firstFlags.contains(.startOfTransfer))
        XCTAssertFalse(firstFlags.contains(.endOfTransfer))

        // Last chunk must have EOT and not SOT
        let lastFlags = PacketFlags(rawValue: chunks.last![1])
        XCTAssertFalse(lastFlags.contains(.startOfTransfer))
        XCTAssertTrue(lastFlags.contains(.endOfTransfer))

        // Sequence numbers must be strictly sequential
        for (index, chunk) in chunks.enumerated() {
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
            XCTAssertEqual(seq, UInt16(index))
        }
    }

    // MARK: - Reassembly & CRC32 Verification Tests
    func testReassemblySuccess() {
        let multiplexer = BLEPacketMultiplexer()
        let originalPayload = "WearBridge Multiplexer Test Payload for Reassembly Verification".data(using: .utf8)!

        var receivedChannel: ChannelID?
        var receivedPayload: Data?

        multiplexer.onPayloadReceived = { channel, data in
            receivedChannel = channel
            receivedPayload = data
        }

        // Chunk with small MTU to force multiple chunks
        let chunks = multiplexer.createChunks(channel: .pingControl, payload: originalPayload, maxChunkSize: 20)
        XCTAssertGreaterThan(chunks.count, 1)

        for chunk in chunks {
            multiplexer.processIncomingChunk(chunk)
        }

        XCTAssertEqual(receivedChannel, .pingControl)
        XCTAssertEqual(receivedPayload, originalPayload)
    }

    func testReassemblyCrcMismatch() {
        let multiplexer = BLEPacketMultiplexer()
        let originalPayload = "Payload to be corrupted".data(using: .utf8)!

        var crcErrorReported = false
        multiplexer.outboundChunkWriter = { chunk in
            let flags = PacketFlags(rawValue: chunk[1])
            if flags.contains(.crcError) {
                crcErrorReported = true
            }
            return true
        }

        var chunks = multiplexer.createChunks(channel: .weatherJson, payload: originalPayload, maxChunkSize: 30)
        XCTAssertGreaterThan(chunks.count, 0)

        // Corrupt one byte of data in the first chunk (after the 4-byte header)
        chunks[0][5] ^= 0xFF

        for chunk in chunks {
            multiplexer.processIncomingChunk(chunk)
        }

        XCTAssertTrue(crcErrorReported, "Multiplexer should have reported CRC Error upon corruption")
    }

    func testAckChunkCreation() {
        let multiplexer = BLEPacketMultiplexer()
        let ackChunk = multiplexer.createAckChunk(channel: .pingControl, sequenceNumber: 42)

        XCTAssertEqual(ackChunk[0], ChannelID.pingControl.rawValue)
        let flags = PacketFlags(rawValue: ackChunk[1])
        XCTAssertTrue(flags.contains(.ack))
        let seq = (UInt16(ackChunk[2]) << 8) | UInt16(ackChunk[3])
        XCTAssertEqual(seq, 42)
    }
}
