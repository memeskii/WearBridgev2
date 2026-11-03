import Foundation
import Combine
import os.log

// MARK: - Channel Identifiers
public enum ChannelID: UInt8, CaseIterable, Identifiable {
    case pingControl          = 0x01
    case contactsJson         = 0x02
    case weatherJson          = 0x03
    case healthTelemetryJson  = 0x04
    case mediaQoL             = 0x05
    case messageReply         = 0x06
    case binaryFile           = 0x07

    public var id: UInt8 { rawValue }

    public var name: String {
        switch self {
        case .pingControl:         return "Ping/Control"
        case .contactsJson:        return "Contacts JSON"
        case .weatherJson:         return "Weather JSON"
        case .healthTelemetryJson: return "Health Telemetry"
        case .mediaQoL:            return "Media QoL"
        case .messageReply:        return "Message Reply"
        case .binaryFile:          return "Binary File/APK"
        }
    }

    public var hexString: String {
        String(format: "0x%02X", rawValue)
    }
}

// MARK: - Packet Header Flags
public struct PacketFlags: OptionSet, CustomStringConvertible {
    public let rawValue: UInt8

    public init(rawValue: UInt8) {
        self.rawValue = rawValue
    }

    public static let startOfTransfer = PacketFlags(rawValue: 0x01) // Bit 0 (0x01)
    public static let endOfTransfer   = PacketFlags(rawValue: 0x02) // Bit 1 (0x02)
    public static let ack             = PacketFlags(rawValue: 0x04) // Bit 2 (0x04)
    public static let crcError        = PacketFlags(rawValue: 0x08) // Bit 3 (0x08)

    public var description: String {
        var names: [String] = []
        if contains(.startOfTransfer) { names.append("SOT") }
        if contains(.endOfTransfer)   { names.append("EOT") }
        if contains(.ack)             { names.append("ACK") }
        if contains(.crcError)        { names.append("CRC_ERR") }
        return names.isEmpty ? "NONE" : names.joined(separator: "|")
    }
}

// MARK: - Packet Direction
public enum PacketDirection: String {
    case tx = "TX"
    case rx = "RX"
}

// MARK: - Packet Log Entry
public struct PacketLogEntry: Identifiable {
    public let id = UUID()
    public let timestamp = Date()
    public let direction: PacketDirection
    public let channel: ChannelID
    public let flags: PacketFlags
    public let sequenceNumber: UInt16
    public let payloadSize: Int
    public let summary: String

    public var formattedTime: String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss.SSS"
        return formatter.string(from: timestamp)
    }
}

// MARK: - IEEE 802.3 CRC32 Checksum
public enum CRC32 {
    private static let table: [UInt32] = {
        (0..<256).map { i -> UInt32 in
            var c = UInt32(i)
            for _ in 0..<8 {
                c = (c & 1 != 0) ? (0xEDB88320 ^ (c >> 1)) : (c >> 1)
            }
            return c
        }
    }()

    public static func checksum(data: Data) -> UInt32 {
        var crc: UInt32 = 0xFFFFFFFF
        for byte in data {
            let index = Int((crc ^ UInt32(byte)) & 0xFF)
            crc = (crc >> 8) ^ table[index]
        }
        return ~crc
    }
}

// MARK: - BLE Packet Multiplexer
public class BLEPacketMultiplexer: ObservableObject {
    public static let shared = BLEPacketMultiplexer()

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "BLEMultiplexer")

    public static let headerSize = 4

    /// Live log of transmitted and received packets
    @Published public var packetLogs: [PacketLogEntry] = []
    private let maxLogEntries = 200

    /// Reassembly state per channel
    private var reassemblyBuffers: [ChannelID: Data] = [:]
    private var expectedSeqNumbers: [ChannelID: UInt16] = [:]

    /// Callbacks for reassembled full payloads
    public var onPayloadReceived: ((ChannelID, Data) -> Void)?
    public var onAckReceived: ((ChannelID, UInt16, Data) -> Void)?
    public var onCrcErrorReceived: ((ChannelID, UInt16) -> Void)?

    /// Outbound chunk writer closure injected by transport layer
    public var outboundChunkWriter: ((Data) -> Bool)?

    public init() {}

    // MARK: - Packet Inspection
    /// Determines whether the raw BLE data has our 4-byte framing header
    public static func isMultiplexedPacket(_ data: Data) -> Bool {
        guard data.count >= headerSize else { return false }
        let channelByte = data[0]
        guard ChannelID(rawValue: channelByte) != nil else { return false }
        // Verify flags: only bits 0..3 are valid flags
        let flags = data[1]
        guard (flags & 0xF0) == 0 else { return false }
        return true
    }

    // MARK: - Packet Ingestion (RX)
    /// Feed a raw BLE chunk received from GATT write or notify
    public func processIncomingChunk(_ data: Data) {
        guard data.count >= Self.headerSize else {
            logger.warning("Incoming packet too short: \(data.count) bytes")
            return
        }

        let channelByte = data[0]
        guard let channel = ChannelID(rawValue: channelByte) else {
            logger.warning("Unknown channel ID: \(channelByte)")
            return
        }

        let flags = PacketFlags(rawValue: data[1])
        let seq = (UInt16(data[2]) << 8) | UInt16(data[3])
        let chunkPayload = data.dropFirst(Self.headerSize)

        logPacket(
            direction: .rx,
            channel: channel,
            flags: flags,
            sequenceNumber: seq,
            payloadSize: chunkPayload.count,
            summary: summarizePayload(chunkPayload, channel: channel, flags: flags)
        )

        // Handle ACK
        if flags.contains(.ack) {
            logger.info("ACK received on \(channel.name) seq=\(seq)")
            onAckReceived?(channel, seq, Data(chunkPayload))
            return
        }

        // Handle CRC Error notification
        if flags.contains(.crcError) {
            logger.error("CRC Error reported by remote peer on \(channel.name) seq=\(seq)")
            onCrcErrorReceived?(channel, seq)
            return
        }

        // Handle Reassembly
        if flags.contains(.startOfTransfer) {
            reassemblyBuffers[channel] = Data()
            expectedSeqNumbers[channel] = 0
        }

        if reassemblyBuffers[channel] == nil {
            reassemblyBuffers[channel] = Data()
        }

        reassemblyBuffers[channel]?.append(chunkPayload)
        expectedSeqNumbers[channel] = seq + 1

        if flags.contains(.endOfTransfer) {
            guard let fullBuffer = reassemblyBuffers[channel] else { return }
            reassemblyBuffers.removeValue(forKey: channel)
            expectedSeqNumbers.removeValue(forKey: channel)

            // Verify trailing CRC32
            guard fullBuffer.count >= 4 else {
                logger.error("Reassembled buffer too short for trailing CRC32 on \(channel.name)")
                sendCrcError(channel: channel, sequenceNumber: seq)
                return
            }

            let payloadLength = fullBuffer.count - 4
            let payload = Data(fullBuffer.prefix(payloadLength))
            let suffixBytes = fullBuffer.suffix(4)
            let expectedCrc = suffixBytes.reduce(0 as UInt32) { ($0 << 8) | UInt32($1) }

            let computedCrc = CRC32.checksum(data: payload)

            if computedCrc == expectedCrc {
                logger.info("CRC32 Verified for \(channel.name) (\(payload.count) bytes, CRC=0x\(String(format: "%08X", computedCrc)))")
                onPayloadReceived?(channel, payload)
            } else {
                logger.error("CRC32 Mismatch on \(channel.name)! Computed: 0x\(String(format: "%08X", computedCrc)), Expected: 0x\(String(format: "%08X", expectedCrc))")
                sendCrcError(channel: channel, sequenceNumber: seq)
            }
        }
    }

    // MARK: - Packet Generation & Chunking (TX)
    /// Frames a complete payload into MTU-sized chunks with trailing CRC32
    public func createChunks(
        channel: ChannelID,
        payload: Data,
        maxChunkSize: Int
    ) -> [Data] {
        let maxPayloadPerChunk = max(1, maxChunkSize - Self.headerSize)

        // Compute trailing CRC32
        let crc = CRC32.checksum(data: payload)
        var fullData = payload
        fullData.append(UInt8((crc >> 24) & 0xFF))
        fullData.append(UInt8((crc >> 16) & 0xFF))
        fullData.append(UInt8((crc >> 8) & 0xFF))
        fullData.append(UInt8(crc & 0xFF))

        var chunks: [Data] = []
        let totalBytes = fullData.count
        var offset = 0
        var seq: UInt16 = 0

        while offset < totalBytes {
            let sliceEnd = min(offset + maxPayloadPerChunk, totalBytes)
            let slice = fullData.subdata(in: offset..<sliceEnd)

            var flags: PacketFlags = []
            if offset == 0 {
                flags.insert(.startOfTransfer)
            }
            if sliceEnd == totalBytes {
                flags.insert(.endOfTransfer)
            }

            var chunk = Data()
            chunk.append(channel.rawValue)
            chunk.append(flags.rawValue)
            chunk.append(UInt8((seq >> 8) & 0xFF))
            chunk.append(UInt8(seq & 0xFF))
            chunk.append(slice)

            chunks.append(chunk)
            offset = sliceEnd
            seq += 1
        }

        return chunks
    }

    /// Creates an immediate single-chunk ACK packet
    public func createAckChunk(
        channel: ChannelID,
        sequenceNumber: UInt16,
        payload: Data = Data()
    ) -> Data {
        var chunk = Data()
        let flags: PacketFlags = [.ack, .startOfTransfer, .endOfTransfer]
        chunk.append(channel.rawValue)
        chunk.append(flags.rawValue)
        chunk.append(UInt8((sequenceNumber >> 8) & 0xFF))
        chunk.append(UInt8(sequenceNumber & 0xFF))
        chunk.append(payload)
        return chunk
    }

    /// Creates an immediate CRC Error chunk
    public func createCrcErrorChunk(
        channel: ChannelID,
        sequenceNumber: UInt16
    ) -> Data {
        var chunk = Data()
        let flags: PacketFlags = [.crcError, .startOfTransfer, .endOfTransfer]
        chunk.append(channel.rawValue)
        chunk.append(flags.rawValue)
        chunk.append(UInt8((sequenceNumber >> 8) & 0xFF))
        chunk.append(UInt8(sequenceNumber & 0xFF))
        return chunk
    }

    // MARK: - Outbound Transmission
    public func sendAck(channel: ChannelID, sequenceNumber: UInt16, payload: Data = Data()) {
        let chunk = createAckChunk(channel: channel, sequenceNumber: sequenceNumber, payload: payload)
        logPacket(
            direction: .tx,
            channel: channel,
            flags: [.ack, .startOfTransfer, .endOfTransfer],
            sequenceNumber: sequenceNumber,
            payloadSize: payload.count,
            summary: "ACK (seq=\(sequenceNumber))"
        )
        _ = outboundChunkWriter?(chunk)
    }

    public func sendCrcError(channel: ChannelID, sequenceNumber: UInt16) {
        let chunk = createCrcErrorChunk(channel: channel, sequenceNumber: sequenceNumber)
        logPacket(
            direction: .tx,
            channel: channel,
            flags: [.crcError, .startOfTransfer, .endOfTransfer],
            sequenceNumber: sequenceNumber,
            payloadSize: 0,
            summary: "CRC Error (seq=\(sequenceNumber))"
        )
        _ = outboundChunkWriter?(chunk)
    }

    // MARK: - Logging & Utilities
    public func logPacket(
        direction: PacketDirection,
        channel: ChannelID,
        flags: PacketFlags,
        sequenceNumber: UInt16,
        payloadSize: Int,
        summary: String
    ) {
        let entry = PacketLogEntry(
            direction: direction,
            channel: channel,
            flags: flags,
            sequenceNumber: sequenceNumber,
            payloadSize: payloadSize,
            summary: summary
        )

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.packetLogs.insert(entry, at: 0)
            if self.packetLogs.count > self.maxLogEntries {
                self.packetLogs.removeLast(self.packetLogs.count - self.maxLogEntries)
            }
        }
    }

    public func clearLogs() {
        DispatchQueue.main.async { [weak self] in
            self?.packetLogs.removeAll()
        }
    }

    private func summarizePayload(_ data: Data, channel: ChannelID, flags: PacketFlags) -> String {
        if flags.contains(.ack) {
            return "ACK Packet"
        }
        if flags.contains(.crcError) {
            return "CRC Error Packet"
        }
        if let string = String(data: data, encoding: .utf8) {
            let sanitized = string.replacingOccurrences(of: "\n", with: " ")
            return sanitized.count > 40 ? String(sanitized.prefix(37)) + "..." : sanitized
        }
        return "\(data.count) binary bytes"
    }
}

