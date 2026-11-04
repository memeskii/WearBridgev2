import Foundation
import CoreBluetooth
import Combine
import UIKit
import UserNotifications
import os.log

/// Model representing a quick reply received from the Wear OS watch.
public struct WatchReply: Identifiable, Hashable {
    public let id: UUID
    public let notificationUid: Int
    public let message: String
    public let timestamp: Date

    public init(id: UUID = UUID(), notificationUid: Int, message: String, timestamp: Date = Date()) {
        self.id = id
        self.notificationUid = notificationUid
        self.message = message
        self.timestamp = timestamp
    }
}

/// Consolidated BLE Peripheral Server Manager for WearBridge iOS Companion App.
///
/// Upgraded to host the BLE Packet Multiplexer with MTU-aware chunking,
/// trailing CRC32 reassembly, and live diagnostics support while preserving
/// backward-compatible legacy GATT pipelines.
public class BLEServerManager: NSObject, ObservableObject {

    public static let shared = BLEServerManager()

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "BLEServerManager")

    // Service & Characteristic UUIDs
    public static let companionServiceUUID = CBUUID(string: "A0E3D5B1-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
    public static let connectionStatusUUID = CBUUID(string: "A0E3D5B2-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
    public static let quickReplyCharacteristicUUID = CBUUID(string: "7F8A0003-9B3C-4D2A-B9E1-8C5F6D7E8A01")
    public static let callStateCharacteristicUUID = CBUUID(string: "A0E3D5B4-4C6F-4E8A-9B2D-7F1E3A5C8D0B")
    public static let advertisingLocalName = "WearBridge"

    private var peripheralManager: CBPeripheralManager!
    private var statusCharacteristic: CBMutableCharacteristic?
    private var quickReplyCharacteristic: CBMutableCharacteristic?
    private var callStateCharacteristic: CBMutableCharacteristic?
    private var serviceAdded = false

    // Multiplexer instance
    public let multiplexer = BLEPacketMultiplexer.shared

    // Subscribed Centrals & MTU
    private var subscribedCentrals = Set<CBCentral>()
    @Published public var negotiatedMTU: Int = 512
    @Published public var rssiValue: Int? = nil

    // Outbound chunk queue for MTU-sized chunks
    private var outboundQueue: [Data] = []
    private var isFlushingQueue = false

    // Active test callbacks
    private var activePingCompletion: ((Result<Int, Error>) -> Void)?
    private var activePingStartMs: Int64?

    private var activeFileTransferCompletion: ((Result<Double, Error>) -> Void)?
    private var activeFileTransferProgress: ((Double) -> Void)?
    private var activeFileTransferStartTime: CFAbsoluteTime?
    private var activeFileTransferTotalBytes: Int = 0

    private var activeLoopbackCompletion: ((Result<String, Error>) -> Void)?
    private var activePullTelemetryCompletion: ((Result<HealthTelemetryPayload, Error>) -> Void)?

    // MARK: - Published Properties
    @Published public var incomingReplies: [WatchReply] = []
    @Published public var watchBatteryLevel: Int? = nil
    @Published public var isWatchCharging: Bool = false
    @Published public var watchSteps: Int = 0
    @Published public var watchHeartRate: Int = 0
    @Published public var watchActiveCalories: Int = 0
    @Published public var watchRestingCalories: Int = 0
    @Published public var watchTotalCalories: Int = 0
    @Published public var watchBloodOxygen: Int? = nil
    @Published public var watchStressLevel: Int? = nil
    @Published public var watchSleepStatus: String? = nil
    @Published public var watchSleepDurationMinutes: Int? = nil
    @Published public var watchSleepEfficiency: Int? = nil
    @Published public var lastHealthSyncDate: Date? = nil
    @Published public var isAdvertising = false
    @Published public var bluetoothState: CBManagerState = .unknown

    override public init() {
        super.init()
        HealthKitManager.shared.requestAuthorization()
        setupMultiplexerBindings()

        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: nil,
            options: [
                CBPeripheralManagerOptionRestoreIdentifierKey: BLEConstants.restoreIdentifier
            ]
        )
    }

    private func setupMultiplexerBindings() {
        // Wire multiplexer chunk sender to GATT notification pipeline
        multiplexer.outboundChunkWriter = { [weak self] chunk in
            self?.enqueueOutboundChunk(chunk) ?? false
        }

        // Wire reassembled payloads
        multiplexer.onPayloadReceived = { [weak self] channel, payload in
            self?.handleReassembledPayload(channel: channel, payload: payload)
        }

        // Wire incoming ACKs
        multiplexer.onAckReceived = { [weak self] channel, seq, payload in
            self?.handleIncomingAck(channel: channel, sequenceNumber: seq, payload: payload)
        }
    }

    private func setupServices() {
        guard !serviceAdded else { return }

        statusCharacteristic = CBMutableCharacteristic(
            type: Self.connectionStatusUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )

        // Updated with notify & read permissions for bi-directional multiplexer
        quickReplyCharacteristic = CBMutableCharacteristic(
            type: Self.quickReplyCharacteristicUUID,
            properties: [.write, .writeWithoutResponse, .notify, .read],
            value: nil,
            permissions: [.readable, .writeable]
        )

        callStateCharacteristic = CBMutableCharacteristic(
            type: Self.callStateCharacteristicUUID,
            properties: [.read, .notify],
            value: nil,
            permissions: [.readable]
        )

        let service = CBMutableService(type: Self.companionServiceUUID, primary: true)
        service.characteristics = [
            statusCharacteristic!,
            quickReplyCharacteristic!,
            callStateCharacteristic!
        ]

        peripheralManager.add(service)
        serviceAdded = true
        logger.info("Configured consolidated GATT service with Status, Multiplexed 7F8A0003, and Call State characteristics")
    }

    // MARK: - Outbound Multiplexer Queue & Chunking
    @discardableResult
    private func enqueueOutboundChunk(_ chunk: Data) -> Bool {
        guard !subscribedCentrals.isEmpty else {
            logger.debug("No subscribed centrals. Dropping outbound chunk.")
            return false
        }
        outboundQueue.append(chunk)
        flushOutboundQueue()
        return true
    }

    private func flushOutboundQueue() {
        guard !isFlushingQueue else { return }
        guard let char = quickReplyCharacteristic else { return }
        guard !subscribedCentrals.isEmpty else {
            outboundQueue.removeAll()
            return
        }

        isFlushingQueue = true

        while !outboundQueue.isEmpty {
            let nextChunk = outboundQueue.first!
            let success = peripheralManager.updateValue(nextChunk, for: char, onSubscribedCentrals: nil)
            if success {
                outboundQueue.removeFirst()
            } else {
                // Buffer full — wait for peripheralManagerIsReady(toUpdateSubscribers:)
                logger.debug("CoreBluetooth peripheral buffer full, waiting for ready event")
                isFlushingQueue = false
                return
            }
        }

        isFlushingQueue = false
    }

    // MARK: - Inbound Reassembly Handling
    private func handleReassembledPayload(channel: ChannelID, payload: Data) {
        logger.info("Received reassembled payload on channel \(channel.name) (\(payload.count) bytes)")

        switch channel {
        case .pingControl:
            // Check for Ping or Alert or Call State
            if let json = try? JSONSerialization.jsonObject(with: payload) as? [String: Any],
               let type = json["type"] as? String {
                if type == "ping" {
                    // Send ACK back
                    multiplexer.sendAck(channel: .pingControl, sequenceNumber: 0, payload: payload)
                }
            }

        case .messageReply:
            if let json = try? JSONSerialization.jsonObject(with: payload) as? [String: Any] {
                if json["replyId"] != nil {
                    // Phase 5 MessageReplyPayload from Watch
                    ReplyRelayManager.shared.handleIncomingReplyPacket(data: payload)
                } else if let type = json["type"] as? String {
                    if type == "loopback_echo", let echoMsg = json["message"] as? String {
                        logger.info("Confirmed loopback echo: \(echoMsg)")
                        DispatchQueue.main.async { [weak self] in
                            self?.activeLoopbackCompletion?(.success(echoMsg))
                            self?.activeLoopbackCompletion = nil
                        }
                    } else if type == "reply", let uid = json["uid"] as? Int, let msg = json["message"] as? String {
                        let reply = WatchReply(notificationUid: uid, message: msg, timestamp: Date())
                        DispatchQueue.main.async { [weak self] in
                            self?.incomingReplies.insert(reply, at: 0)
                        }
                    }
                }
            } else if let replyString = String(data: payload, encoding: .utf8) {
                // Fallback string reply
                if replyString.hasPrefix("ECHO:") {
                    let echo = String(replyString.dropFirst(5))
                    DispatchQueue.main.async { [weak self] in
                        self?.activeLoopbackCompletion?(.success(echo))
                        self?.activeLoopbackCompletion = nil
                    }
                }
            }

        case .binaryFile:
            if let json = try? JSONSerialization.jsonObject(with: payload) as? [String: Any], json["cmd"] != nil {
                FileTransferManager.shared.handleIncomingControlPacket(payload)
            } else {
                // Peer completed sending a file — acknowledge receipt
                let computedCrc = CRC32.checksum(data: payload)
                var ackData = Data()
                ackData.append(UInt8((computedCrc >> 24) & 0xFF))
                ackData.append(UInt8((computedCrc >> 16) & 0xFF))
                ackData.append(UInt8((computedCrc >> 8) & 0xFF))
                ackData.append(UInt8(computedCrc & 0xFF))
                multiplexer.sendAck(channel: .binaryFile, sequenceNumber: 0, payload: ackData)
            }

        case .healthTelemetryJson:
            processHealthJson(payload)

        case .mediaQoL:
            MediaQoLManager.shared.handleIncomingRequest(data: payload)

        default:
            logger.info("Payload received on channel \(channel.name)")
        }
    }

    private func handleIncomingAck(channel: ChannelID, sequenceNumber: UInt16, payload: Data) {
        switch channel {
        case .pingControl:
            if let startMs = activePingStartMs {
                let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
                let latency = max(1, Int(nowMs - startMs))
                logger.info("Ping ACK received! RTT latency: \(latency) ms")
                DispatchQueue.main.async { [weak self] in
                    self?.activePingCompletion?(.success(latency))
                    self?.activePingCompletion = nil
                    self?.activePingStartMs = nil
                }
            }

        case .binaryFile:
            if let startTime = activeFileTransferStartTime {
                let elapsed = CFAbsoluteTimeGetCurrent() - startTime
                let sizeKB = Double(activeFileTransferTotalBytes) / 1024.0
                let speedKBps = sizeKB / max(0.001, elapsed)
                logger.info("File transfer ACK received! Speed: \(String(format: "%.2f", speedKBps)) KB/s")
                DispatchQueue.main.async { [weak self] in
                    self?.activeFileTransferCompletion?(.success(speedKBps))
                    self?.activeFileTransferCompletion = nil
                    self?.activeFileTransferStartTime = nil
                }
            }

        default:
            logger.info("Generic ACK received on \(channel.name)")
        }
    }

    // MARK: - Functional Diagnostics Test Triggers
    /// "Test Ping (0x01)": Sends a timestamp and measures round-trip ACK latency in ms.
    public func sendTestPing(completion: @escaping (Result<Int, Error>) -> Void) {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        activePingStartMs = nowMs
        activePingCompletion = completion

        let pingDict: [String: Any] = [
            "type": "ping",
            "timestamp": nowMs
        ]

        guard let payload = try? JSONSerialization.data(withJSONObject: pingDict) else {
            completion(.failure(NSError(domain: "WearBridge", code: -1, userInfo: [NSLocalizedDescriptionKey: "Failed to serialize Ping JSON"])))
            return
        }

        let mtu = currentWriteMtu()
        let chunks = multiplexer.createChunks(channel: .pingControl, payload: payload, maxChunkSize: mtu)
        for chunk in chunks {
            let flags = PacketFlags(rawValue: chunk[1])
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
            multiplexer.logPacket(
                direction: .tx,
                channel: .pingControl,
                flags: flags,
                sequenceNumber: seq,
                payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                summary: "Ping Request (t=\(nowMs))"
            )
            enqueueOutboundChunk(chunk)
        }

        // Timeout safety
        DispatchQueue.main.asyncAfter(deadline: .now() + 5.0) { [weak self] in
            if self?.activePingCompletion != nil {
                self?.activePingCompletion?(.failure(NSError(domain: "WearBridge", code: -2, userInfo: [NSLocalizedDescriptionKey: "Ping timed out after 5.0s"])))
                self?.activePingCompletion = nil
                self?.activePingStartMs = nil
            }
        }
    }

    /// Generic packet transmitter for any Multiplexer Channel.
    public func sendPacket(channel: ChannelID, payload: Data) {
        let mtu = currentWriteMtu()
        let chunks = multiplexer.createChunks(channel: channel, payload: payload, maxChunkSize: mtu)
        for chunk in chunks {
            enqueueOutboundChunk(chunk)
        }
    }

    /// "Test Notification": Triggers both a local iOS UNNotification (to test ANCS) and a custom GATT alert packet.
    public func sendTestNotification(title: String = "Test Notification", message: String = "Custom GATT Alert from iPhone") {
        // 1. Schedule local UNNotification
        let center = UNUserNotificationCenter.current()
        center.requestAuthorization(options: [.alert, .sound, .badge]) { granted, _ in
            if granted {
                let content = UNMutableNotificationContent()
                content.title = title
                content.body = message
                content.sound = .default

                let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1.0, repeats: false)
                let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: trigger)
                center.add(request) { [weak self] error in
                    if let error = error {
                        self?.logger.error("Failed to add UNNotification: \(error.localizedDescription)")
                    }
                }
            }
        }

        // 2. Custom GATT alert packet over Channel 0x01
        let alertDict: [String: Any] = [
            "type": "alert",
            "title": title,
            "message": message,
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
        ]

        if let data = try? JSONSerialization.data(withJSONObject: alertDict) {
            let mtu = currentWriteMtu()
            let chunks = multiplexer.createChunks(channel: .pingControl, payload: data, maxChunkSize: mtu)
            for chunk in chunks {
                let flags = PacketFlags(rawValue: chunk[1])
                let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
                multiplexer.logPacket(
                    direction: .tx,
                    channel: .pingControl,
                    flags: flags,
                    sequenceNumber: seq,
                    payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                    summary: "GATT Alert: \(title)"
                )
                enqueueOutboundChunk(chunk)
            }
        }
    }

    /// "Test Incoming Call": Sends a mock call state packet (callerName, phoneNumber, state: RINGING) to trigger the watch call UI.
    public func sendMockIncomingCall(callerName: String = "Jane Doe", phoneNumber: String = "+1 (555) 019-2834") {
        // 1. Push via call state characteristic (standard)
        notifyCallState(BLEConstants.callStateRinging)

        // 2. Push multiplexed call state packet on Channel 0x01
        let callDict: [String: Any] = [
            "type": "call_state",
            "callerName": callerName,
            "phoneNumber": phoneNumber,
            "state": "RINGING",
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
        ]

        if let data = try? JSONSerialization.data(withJSONObject: callDict) {
            let mtu = currentWriteMtu()
            let chunks = multiplexer.createChunks(channel: .pingControl, payload: data, maxChunkSize: mtu)
            for chunk in chunks {
                let flags = PacketFlags(rawValue: chunk[1])
                let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
                multiplexer.logPacket(
                    direction: .tx,
                    channel: .pingControl,
                    flags: flags,
                    sequenceNumber: seq,
                    payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                    summary: "Mock Call: \(callerName) (RINGING)"
                )
                enqueueOutboundChunk(chunk)
            }
        }
    }

    /// "Test 100KB File Transfer (0x07)": Generates 100KB of random bytes, streams it to the watch, verifies the watch's CRC32 ACK, and displays transfer speed in KB/s.
    public func sendTestFileTransfer(
        sizeBytes: Int = 100 * 1024,
        onProgress: @escaping (Double) -> Void,
        completion: @escaping (Result<Double, Error>) -> Void
    ) {
        activeFileTransferCompletion = completion
        activeFileTransferProgress = onProgress
        activeFileTransferTotalBytes = sizeBytes
        activeFileTransferStartTime = CFAbsoluteTimeGetCurrent()

        // Generate pseudo-random bytes
        var randomBytes = [UInt8](repeating: 0, count: sizeBytes)
        for i in 0..<sizeBytes {
            randomBytes[i] = UInt8((i * 31 + 17) & 0xFF)
        }
        let fileData = Data(randomBytes)

        let mtu = currentWriteMtu()
        let chunks = multiplexer.createChunks(channel: .binaryFile, payload: fileData, maxChunkSize: mtu)
        let totalChunks = chunks.count

        for (index, chunk) in chunks.enumerated() {
            let flags = PacketFlags(rawValue: chunk[1])
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])

            multiplexer.logPacket(
                direction: .tx,
                channel: .binaryFile,
                flags: flags,
                sequenceNumber: seq,
                payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                summary: "File Chunk \(index + 1)/\(totalChunks)"
            )
            enqueueOutboundChunk(chunk)

            let progressFraction = Double(index + 1) / Double(totalChunks)
            DispatchQueue.main.async {
                onProgress(progressFraction)
            }
        }

        // Safety timeout for 100KB transfer (30s)
        DispatchQueue.main.asyncAfter(deadline: .now() + 30.0) { [weak self] in
            if self?.activeFileTransferCompletion != nil {
                self?.activeFileTransferCompletion?(.failure(NSError(domain: "WearBridge", code: -3, userInfo: [NSLocalizedDescriptionKey: "File transfer timed out after 30s"])))
                self?.activeFileTransferCompletion = nil
                self?.activeFileTransferStartTime = nil
            }
        }
    }

    /// "Test Reply Loopback (0x06)": Instructs the watch to echo back a mock reply payload and logs receipt on iOS.
    public func sendTestReplyLoopback(
        message: String = "Hello from iPhone Diagnostics!",
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        activeLoopbackCompletion = completion

        let loopbackDict: [String: Any] = [
            "type": "loopback_test",
            "message": message,
            "timestamp": Int64(Date().timeIntervalSince1970 * 1000)
        ]

        guard let data = try? JSONSerialization.data(withJSONObject: loopbackDict) else {
            completion(.failure(NSError(domain: "WearBridge", code: -4, userInfo: [NSLocalizedDescriptionKey: "Failed to serialize loopback JSON"])))
            return
        }

        let mtu = currentWriteMtu()
        let chunks = multiplexer.createChunks(channel: .messageReply, payload: data, maxChunkSize: mtu)
        for chunk in chunks {
            let flags = PacketFlags(rawValue: chunk[1])
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
            multiplexer.logPacket(
                direction: .tx,
                channel: .messageReply,
                flags: flags,
                sequenceNumber: seq,
                payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                summary: "Loopback Request: '\(message)'"
            )
            enqueueOutboundChunk(chunk)
        }

        // 10s timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 10.0) { [weak self] in
            if self?.activeLoopbackCompletion != nil {
                self?.activeLoopbackCompletion?(.failure(NSError(domain: "WearBridge", code: -5, userInfo: [NSLocalizedDescriptionKey: "Reply loopback timed out after 10s"])))
                self?.activeLoopbackCompletion = nil
            }
        }
    }

    // MARK: - Contacts and Weather Sync Dispatch
    /// Sends a serialized contacts array over BLE Channel 0x02
    public func sendContactsPayload(_ payload: Data) {
        let mtu = currentWriteMtu()
        let chunks = multiplexer.createChunks(channel: .contactsJson, payload: payload, maxChunkSize: mtu)
        logger.info("Transmitting contacts payload (\(payload.count) bytes) in \(chunks.count) chunks over Channel 0x02")
        for chunk in chunks {
            let flags = PacketFlags(rawValue: chunk[1])
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
            multiplexer.logPacket(
                direction: .tx,
                channel: .contactsJson,
                flags: flags,
                sequenceNumber: seq,
                payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                summary: "Contacts Sync (\(payload.count) B)"
            )
            enqueueOutboundChunk(chunk)
        }
    }

    /// Sends a compact weather forecast over BLE Channel 0x03
    public func sendWeatherPayload(_ payload: Data) {
        let mtu = currentWriteMtu()
        let chunks = multiplexer.createChunks(channel: .weatherJson, payload: payload, maxChunkSize: mtu)
        logger.info("Transmitting weather payload (\(payload.count) bytes) in \(chunks.count) chunks over Channel 0x03")
        for chunk in chunks {
            let flags = PacketFlags(rawValue: chunk[1])
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
            multiplexer.logPacket(
                direction: .tx,
                channel: .weatherJson,
                flags: flags,
                sequenceNumber: seq,
                payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                summary: "Weather Sync (\(payload.count) B)"
            )
            enqueueOutboundChunk(chunk)
        }
    }

    /// Sends a "PULL_TELEMETRY" request over BLE Channel 0x04 and awaits watch telemetry response.
    public func sendPullTelemetryCommand(completion: @escaping (Result<HealthTelemetryPayload, Error>) -> Void) {
        activePullTelemetryCompletion = completion

        let commandData = "PULL_TELEMETRY".data(using: .utf8)!
        let mtu = currentWriteMtu()
        let chunks = multiplexer.createChunks(channel: .healthTelemetryJson, payload: commandData, maxChunkSize: mtu)
        logger.info("Transmitting PULL_TELEMETRY command over Channel 0x04")

        for chunk in chunks {
            let flags = PacketFlags(rawValue: chunk[1])
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
            multiplexer.logPacket(
                direction: .tx,
                channel: .healthTelemetryJson,
                flags: flags,
                sequenceNumber: seq,
                payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                summary: "Pull Telemetry Command"
            )
            enqueueOutboundChunk(chunk)
        }

        // 10s timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 10.0) { [weak self] in
            if self?.activePullTelemetryCompletion != nil {
                self?.activePullTelemetryCompletion?(.failure(NSError(domain: "WearBridge", code: -6, userInfo: [NSLocalizedDescriptionKey: "Pull telemetry timed out after 10s"])))
                self?.activePullTelemetryCompletion = nil
            }
        }
    }

    /// Sends Media QoL payload (artwork, audio route, duration) over BLE Channel 0x05
    public func sendMediaQoLPayload(_ payload: Data) {
        let mtu = currentWriteMtu()
        let chunks = multiplexer.createChunks(channel: .mediaQoL, payload: payload, maxChunkSize: mtu)
        logger.info("Transmitting Media QoL payload (\(payload.count) bytes) in \(chunks.count) chunks over Channel 0x05")

        for chunk in chunks {
            let flags = PacketFlags(rawValue: chunk[1])
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
            multiplexer.logPacket(
                direction: .tx,
                channel: .mediaQoL,
                flags: flags,
                sequenceNumber: seq,
                payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                summary: "Media QoL Sync (\(payload.count) B)"
            )
            enqueueOutboundChunk(chunk)
        }
    }

    /// Sends a message payload (ACK or synthetic message injection) over BLE Channel 0x06
    public func sendMessageReplyData(_ payload: Data) {
        let mtu = currentWriteMtu()
        let chunks = multiplexer.createChunks(channel: .messageReply, payload: payload, maxChunkSize: mtu)
        logger.info("Transmitting Message Reply payload (\(payload.count) bytes) in \(chunks.count) chunks over Channel 0x06")

        for chunk in chunks {
            let flags = PacketFlags(rawValue: chunk[1])
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
            multiplexer.logPacket(
                direction: .tx,
                channel: .messageReply,
                flags: flags,
                sequenceNumber: seq,
                payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                summary: "Message Reply Sync (\(payload.count) B)"
            )
            enqueueOutboundChunk(chunk)
        }
    }

    /// Sends a file transfer payload (control packet or binary window) on Channel 0x07
    public func sendFileTransferPayload(_ payload: Data) {
        let mtu = currentWriteMtu()
        let chunks = multiplexer.createChunks(channel: .binaryFile, payload: payload, maxChunkSize: mtu)
        for chunk in chunks {
            let flags = PacketFlags(rawValue: chunk[1])
            let seq = (UInt16(chunk[2]) << 8) | UInt16(chunk[3])
            multiplexer.logPacket(
                direction: .tx,
                channel: .binaryFile,
                flags: flags,
                sequenceNumber: seq,
                payloadSize: chunk.count - BLEPacketMultiplexer.headerSize,
                summary: "File Transfer Payload (\(payload.count) B)"
            )
            enqueueOutboundChunk(chunk)
        }
    }

    public func currentWriteMtu() -> Int {
        if let central = subscribedCentrals.first {
            return max(20, central.maximumUpdateValueLength)
        }
        return max(20, negotiatedMTU)
    }

    // MARK: - Advertising Controls
    public func startAdvertising() {
        guard peripheralManager.state == .poweredOn else {
            logger.warning("Cannot advertise: Bluetooth not powered on")
            return
        }

        if peripheralManager.isAdvertising {
            isAdvertising = true
            return
        }

        peripheralManager.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.companionServiceUUID],
            CBAdvertisementDataLocalNameKey: Self.advertisingLocalName
        ])
        logger.info("Requested advertising for companion service: \(Self.companionServiceUUID)")
    }

    public func stopAdvertising() {
        peripheralManager.stopAdvertising()
        isAdvertising = false
        logger.info("Stopped companion advertising")
    }

    // MARK: - Call State Push
    public func notifyCallState(_ state: UInt8) {
        guard let char = callStateCharacteristic else { return }
        let data = Data([state])
        let sent = peripheralManager.updateValue(data, for: char, onSubscribedCentrals: nil)
        logger.info("Call state notify: \(state), sent: \(sent)")
    }

    // MARK: - Health JSON Processor
    private func processHealthJson(_ jsonData: Data) {
        // Forward to Phase 3 HealthTelemetryManager for SwiftData persistence & cache on @MainActor
        Task { @MainActor in
            HealthTelemetryManager.shared.handleIncomingTelemetry(data: jsonData)
        }

        if activePullTelemetryCompletion != nil {
            if let payload = try? JSONDecoder().decode(HealthTelemetryPayload.self, from: jsonData) {
                DispatchQueue.main.async { [weak self] in
                    self?.activePullTelemetryCompletion?(.success(payload))
                    self?.activePullTelemetryCompletion = nil
                }
            }
        }

        guard let json = try? JSONSerialization.jsonObject(with: jsonData) as? [String: Any],
              let metrics = json["metrics"] as? [String: Any] else {
            return
        }

        let steps = metrics["steps"] as? Int ?? 0
        let heartRate = metrics["heart_rate"] as? Int ?? 0
        let bloodOxygen = metrics["blood_oxygen"] as? Int ?? 0
        let stress = metrics["stress_level"] as? Int ?? 0
        let totalCalories = metrics["calories_burnt"] as? Int ?? 0
        let activeCalories = metrics["active_calories"] as? Int ?? (totalCalories > 0 ? totalCalories : 0)
        let restingCalories = metrics["resting_calories"] as? Int ?? 0

        var sleepStatus: String? = nil
        var sleepDuration: Int? = nil
        var sleepEfficiency: Int? = nil

        if let sleepData = metrics["sleep_data"] as? [String: Any] {
            sleepStatus = sleepData["status"] as? String
            sleepDuration = sleepData["duration_minutes"] as? Int
            sleepEfficiency = sleepData["efficiency"] as? Int
        }

        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.watchSteps = steps
            self.watchHeartRate = heartRate
            self.watchActiveCalories = activeCalories
            self.watchRestingCalories = restingCalories
            self.watchTotalCalories = totalCalories
            if bloodOxygen > 0 { self.watchBloodOxygen = bloodOxygen }
            if stress > 0 { self.watchStressLevel = stress }
            self.watchSleepStatus = sleepStatus
            self.watchSleepDurationMinutes = sleepDuration
            self.watchSleepEfficiency = sleepEfficiency
            self.lastHealthSyncDate = Date()
        }

        HealthKitManager.shared.recordFullHealthTelemetry(
            steps: steps,
            heartRateBpm: heartRate,
            bloodOxygen: bloodOxygen,
            stressLevel: stress,
            activeKcal: activeCalories,
            restingKcal: restingCalories,
            sleepStatus: sleepStatus,
            sleepDurationMinutes: sleepDuration,
            sleepEfficiency: sleepEfficiency
        )
    }
}

// MARK: - CBPeripheralManagerDelegate
extension BLEServerManager: CBPeripheralManagerDelegate {

    public func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        bluetoothState = peripheral.state
        logger.info("Peripheral state updated: \(peripheral.state.rawValue)")

        if peripheral.state == .poweredOn {
            setupServices()
        } else {
            isAdvertising = false
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            logger.error("Failed to add companion service: \(error.localizedDescription)")
            return
        }

        logger.info("Companion service added. Starting advertising...")
        startAdvertising()
    }

    public func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        if let error = error {
            logger.error("Failed to start advertising: \(error.localizedDescription)")
            isAdvertising = false
        } else {
            isAdvertising = true
            logger.info("Consolidated BLE advertising active for \(Self.companionServiceUUID)")
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        subscribedCentrals.insert(central)
        let centralMtu = central.maximumUpdateValueLength
        negotiatedMTU = centralMtu
        logger.info("Central subscribed to \(characteristic.uuid). Central MTU: \(centralMtu)")

        if characteristic.uuid == Self.quickReplyCharacteristicUUID || characteristic.uuid == Self.connectionStatusUUID {
            // Clear any stale outbound queue from previous disconnected session
            outboundQueue.removeAll()
            isFlushingQueue = false

            // Trigger auto-sync for contacts (delta hash check) and weather on watch connection
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
                ContactsSyncManager.shared.syncContacts(force: false)
                WeatherSyncService.shared.syncWeather(force: false)
            }
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentrals.remove(central)
        logger.info("Central unsubscribed from \(characteristic.uuid)")
        if subscribedCentrals.isEmpty {
            outboundQueue.removeAll()
            isFlushingQueue = false
            FileTransferManager.shared.cancelTransfer()
        }
    }

    public func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        logger.debug("peripheralManagerIsReady: Resuming outbound queue transmission")
        flushOutboundQueue()
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        if request.characteristic.uuid == Self.connectionStatusUUID {
            request.value = Data([0x01])
            peripheral.respond(to: request, withResult: .success)
        } else if request.characteristic.uuid == Self.callStateCharacteristicUUID {
            request.value = Data([0x00])
            peripheral.respond(to: request, withResult: .success)
        } else if request.characteristic.uuid == Self.quickReplyCharacteristicUUID {
            request.value = Data()
            peripheral.respond(to: request, withResult: .success)
        } else {
            peripheral.respond(to: request, withResult: .attributeNotFound)
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        guard let firstRequest = requests.first else { return }
        var result: CBATTError.Code = .success

        for request in requests {
            if request.characteristic.uuid == Self.quickReplyCharacteristicUUID {
                guard let data = request.value else { continue }

                // 1. Multiplexed Packet Check
                if BLEPacketMultiplexer.isMultiplexedPacket(data) {
                    multiplexer.processIncomingChunk(data)
                    continue
                }

                // 2. Backward Compatibility String Checks
                if let payload = String(data: data, encoding: .utf8) {
                    logger.info("Received legacy payload on 7F8A0003: \(payload)")

                    if payload.hasPrefix("REPLY:") {
                        let content = String(payload.dropFirst("REPLY:".count))
                        let parts = content.split(separator: ":", maxSplits: 1, omittingEmptySubsequences: false)
                        if parts.count == 2, let uid = Int(parts[0]) {
                            let message = String(parts[1])
                            let reply = WatchReply(notificationUid: uid, message: message, timestamp: Date())
                            DispatchQueue.main.async { [weak self] in
                                self?.incomingReplies.insert(reply, at: 0)
                            }
                        }
                        handleQuickReplyShortcut(payload)
                    } else if payload.hasPrefix("BATT:") {
                        let content = String(payload.dropFirst("BATT:".count)).trimmingCharacters(in: .whitespacesAndNewlines)
                        let parts = content.split(separator: ":").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                        if parts.count >= 2, let level = Int(parts[0]), let chargingInt = Int(parts[1]) {
                            let isCharging = (chargingInt == 1)
                            DispatchQueue.main.async { [weak self] in
                                self?.watchBatteryLevel = level
                                self?.isWatchCharging = isCharging
                            }
                        }
                    } else if payload.hasPrefix("HEALTH_JSON:") || payload.trimmingCharacters(in: .whitespacesAndNewlines).hasPrefix("{") {
                        let jsonStr = payload.hasPrefix("HEALTH_JSON:") ? String(payload.dropFirst("HEALTH_JSON:".count)).trimmingCharacters(in: .whitespacesAndNewlines) : payload.trimmingCharacters(in: .whitespacesAndNewlines)
                        if let jsonData = jsonStr.data(using: .utf8) {
                            processHealthJson(jsonData)
                        }
                    } else if payload.hasPrefix("HEALTH:") {
                        let content = String(payload.dropFirst("HEALTH:".count)).trimmingCharacters(in: .whitespacesAndNewlines)
                        let parts = content.split(separator: ":").map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
                        if parts.count >= 3, let steps = Int(parts[0]), let bpm = Int(parts[1]), let kcal = Int(parts[2]) {
                            DispatchQueue.main.async { [weak self] in
                                self?.watchSteps = steps
                                self?.watchHeartRate = bpm
                                self?.watchActiveCalories = kcal
                                self?.watchTotalCalories = kcal
                                self?.lastHealthSyncDate = Date()
                            }
                            HealthKitManager.shared.recordHealthTelemetry(steps: steps, heartRateBpm: bpm, activeKcal: kcal)
                        }
                    } else {
                        handleQuickReplyShortcut(payload)
                    }
                }
            } else {
                result = .attributeNotFound
                break
            }
        }

        peripheral.respond(to: firstRequest, withResult: result)
    }

    private func handleQuickReplyShortcut(_ payload: String) {
        let encoded = payload.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? payload
        let urlString = "shortcuts://run-shortcut?name=WearBridge%20Reply&input=text&text=\(encoded)"
        if let url = URL(string: urlString) {
            DispatchQueue.main.async {
                UIApplication.shared.open(url) { _ in }
            }
        }
    }

    public func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        logger.info("Restoring consolidated peripheral manager state")
        if let services = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService] {
            serviceAdded = !services.isEmpty
        }
        if peripheral.isAdvertising {
            isAdvertising = true
        }
    }
}
