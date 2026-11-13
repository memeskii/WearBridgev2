import Foundation
import Combine
import CryptoKit
import os.log

public enum FileTransferStatus: String, Codable {
    case idle = "IDLE"
    case preparing = "PREPARING"
    case transferring = "TRANSFERRING"
    case verifying = "VERIFYING"
    case completed = "COMPLETED"
    case failed = "FAILED"
    case cancelled = "CANCELLED"
}

public struct FileTransferState {
    public let transferId: String
    public let fileName: String
    public let fileSize: Int
    public let fileType: String
    public fileprivate(set) var sha256: String
    public let totalWindows: Int
    public var currentWindow: Int
    public var bytesTransferred: Int
    public var progress: Double
    public var speedKBps: Double
    public var status: FileTransferStatus
    public var statusMessage: String
    public var startTime: Date
}

public final class FileTransferManager: ObservableObject {
    public static let shared = FileTransferManager()
    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "FileTransferManager")

    public static let headerControlJson: UInt8 = 0x01
    public static let headerRawBinary: UInt8 = 0x02
    public static let windowSizeBytes = 32 * 1024 // 32KB windows

    @Published public var currentTransfer: FileTransferState?
    @Published public var transferProgress: Double = 0.0
    @Published public var transferSpeedKBps: Double = 0.0
    @Published public var transferStatusText: String = "Idle"
    @Published public var isTransferring: Bool = false

    private var activeFileData: Data?
    private var activeProgress: ((Double) -> Void)?
    private var activeCompletion: ((Result<String, Error>) -> Void)?
    private var waitingForConnection = false
    private var timeoutWorkItem: DispatchWorkItem?

    private init() {}

    // MARK: - SHA-256 Hashing Utilities
    public static func computeSha256(data: Data) -> String {
        let digest = SHA256.hash(data: data)
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    public static func computeSha256(url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }

        var hasher = SHA256()
        while true {
            let chunk = handle.readData(ofLength: 64 * 1024)
            if chunk.isEmpty { break }
            hasher.update(data: chunk)
        }
        let digest = hasher.finalize()
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Transfer Initiation
    public func startTransfer(
        fileUrl: URL,
        fileType: String = "WATCH_FACE",
        completion: ((Result<String, Error>) -> Void)? = nil
    ) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                let data = try Data(contentsOf: fileUrl, options: .mappedIfSafe)
                self.startTransfer(fileData: data, fileName: fileUrl.lastPathComponent, fileType: fileType, completion: completion)
            } catch {
                DispatchQueue.main.async { completion?(.failure(error)) }
            }
        }
    }

    public func startFileTransfer(
        fileData: Data,
        fileName: String,
        fileType: String = "WATCH_FACE",
        onProgress: ((Double) -> Void)? = nil,
        completion: ((Result<String, Error>) -> Void)? = nil
    ) {
        startTransfer(fileData: fileData, fileName: fileName, fileType: fileType, onProgress: onProgress, completion: completion)
    }

    public func startTransfer(
        fileData: Data,
        fileName: String,
        fileType: String = "WATCH_FACE",
        onProgress: ((Double) -> Void)? = nil,
        completion: ((Result<String, Error>) -> Void)? = nil
    ) {
        if !Thread.isMainThread {
            DispatchQueue.main.async {
                self.startTransfer(fileData: fileData, fileName: fileName, fileType: fileType, onProgress: onProgress, completion: completion)
            }
            return
        }
        guard !isTransferring else {
            completion?(.failure(NSError(domain: "WearBridge", code: -11, userInfo: [NSLocalizedDescriptionKey: "Another file transfer is already in progress"])))
            return
        }

        let transferId = UUID().uuidString
        let totalWindows = max(1, Int(ceil(Double(fileData.count) / Double(Self.windowSizeBytes))))

        self.activeFileData = fileData
        self.activeCompletion = completion
        self.activeProgress = onProgress

        let state = FileTransferState(
            transferId: transferId,
            fileName: fileName,
            fileSize: fileData.count,
            fileType: fileType,
            sha256: "",
            totalWindows: totalWindows,
            currentWindow: 0,
            bytesTransferred: 0,
            progress: 0.0,
            speedKBps: 0.0,
            status: .preparing,
            statusMessage: "Starting transfer...",
            startTime: Date()
        )

        self.currentTransfer = state
        self.transferProgress = 0.0
        self.transferSpeedKBps = 0.0
        self.transferStatusText = "Preparing \(fileName)..."
        self.isTransferring = true

        DispatchQueue.global(qos: .userInitiated).async {
            let hash = Self.computeSha256(data: fileData)
            DispatchQueue.main.async {
                guard self.isTransferring, self.currentTransfer?.transferId == transferId else { return }
                self.currentTransfer?.sha256 = hash
                self.announceTransfer()
            }
        }
    }

    private func announceTransfer() {
        guard let transfer = currentTransfer else { return }
        let startDict: [String: Any] = [
            "cmd": "FILE_START",
            "transferId": transfer.transferId,
            "fileName": transfer.fileName,
            "fileSize": transfer.fileSize,
            "fileType": transfer.fileType,
            "sha256": transfer.sha256,
            "totalWindows": transfer.totalWindows,
            "resumeSupported": true
        ]

        guard let startData = try? JSONSerialization.data(withJSONObject: startDict) else {
            failTransfer(errorMessage: "Failed to serialize FILE_START")
            return
        }

        var framedStart = Data([Self.headerControlJson])
        framedStart.append(startData)

        // Send FILE_START control packet
        BLEServerManager.shared.sendFileTransferPayload(framedStart)

        // Reset safety watchdog timeout (15s waiting for watch FILE_START_ACK)
        resetTimeoutWatchdog(duration: 15.0)
    }

    // MARK: - Stage 2: Binary Window Streaming
    private func sendWindow(index: Int) {
        guard let data = activeFileData, isTransferring else { return }

        let startOffset = index * Self.windowSizeBytes
        if startOffset >= data.count {
            // All windows sent. Initiate Stage 3: FILE_END.
            finishStreamingWindows()
            return
        }

        let endOffset = min(data.count, startOffset + Self.windowSizeBytes)
        let windowChunk = data.subdata(in: startOffset..<endOffset)

        self.currentTransfer?.currentWindow = index
        self.transferStatusText = "Streaming window \(index + 1)/\(self.currentTransfer?.totalWindows ?? 1)..."

        logger.debug("Streaming window \(index) (\(windowChunk.count) bytes, offset \(startOffset)..\(endOffset))")

        // Prepend 0x02 Raw Binary Window sub-header
        var framedWindow = Data([Self.headerRawBinary])
        framedWindow.append(windowChunk)

        // Send binary window chunk over Channel 0x07 (buffered with flow control in BLEServerManager)
        BLEServerManager.shared.sendFileTransferPayload(framedWindow)
        resetTimeoutWatchdog()
    }

    // MARK: - Incoming Packet & Window ACK Handler
    public func handleIncomingControlPacket(_ data: Data) {
        if !Thread.isMainThread {
            DispatchQueue.main.async { self.handleIncomingControlPacket(data) }
            return
        }
        var rawData = data
        if rawData.first == Self.headerControlJson {
            rawData = rawData.dropFirst()
        }

        guard let json = try? JSONSerialization.jsonObject(with: rawData) as? [String: Any],
              let cmd = json["cmd"] as? String else {
            logger.warning("Unrecognized Channel 0x07 control packet: \(data.count) bytes")
            return
        }

        guard isTransferring, let transfer = currentTransfer, !transfer.sha256.isEmpty,
              let transferId = json["transferId"] as? String,
              transferId == transfer.transferId else { return }

        switch cmd {
        case "FILE_START_ACK":
            guard transfer.status == .preparing else { return }
            guard json["status"] as? String == "READY" else {
                failTransfer(errorMessage: json["message"] as? String ?? "Watch rejected the transfer")
                return
            }
            let nextWindow = json["nextWindow"] as? Int ?? 0
            let received = json["bytesReceived"] as? Int ?? 0
            guard (0...transfer.totalWindows).contains(nextWindow),
                  received == min(transfer.fileSize, nextWindow * Self.windowSizeBytes) else {
                failTransfer(errorMessage: "Invalid transfer checkpoint")
                return
            }
            currentTransfer?.bytesTransferred = received
            transferProgress = transfer.fileSize > 0 ? Double(received) / Double(transfer.fileSize) : 0
            currentTransfer?.progress = transferProgress
            beginStreaming(at: nextWindow)

        case "FILE_WINDOW_ACK":
            if json["windowIndex"] as? Int == -1, transfer.status == .preparing,
               json["bytesReceived"] as? Int == 0 {
                beginStreaming()
            } else {
                handleWindowAck(json: json, transferId: transferId)
            }

        case "FILE_COMPLETE":
            handleFileComplete(json: json, transferId: transferId)

        case "FILE_CANCEL":
            logger.info("Watch cancelled file transfer #\(transferId)")
            failTransfer(errorMessage: "Transfer cancelled by watch", status: .cancelled)

        default:
            logger.debug("Received command \(cmd) on Channel 0x07")
        }
    }

    private func beginStreaming(at index: Int = 0) {
        currentTransfer?.status = .transferring
        transferStatusText = "Streaming to watch..."
        sendWindow(index: index)
    }

    private func handleWindowAck(json: [String: Any], transferId: String) {
        guard isTransferring, currentTransfer?.transferId == transferId else { return }

        guard let transfer = currentTransfer, transfer.status == .transferring,
              let windowIndex = json["windowIndex"] as? Int,
              windowIndex == transfer.currentWindow,
              let bytesReceived = json["bytesReceived"] as? Int,
              bytesReceived == min(transfer.fileSize, (windowIndex + 1) * Self.windowSizeBytes) else { return }
        let elapsed = max(0.001, Date().timeIntervalSince(transfer.startTime))
        let speed = (Double(bytesReceived) / 1024.0) / elapsed
        let progressFraction = transfer.fileSize > 0 ? min(1.0, Double(bytesReceived) / Double(transfer.fileSize)) : 0.0

        self.currentTransfer?.bytesTransferred = bytesReceived
        self.currentTransfer?.progress = progressFraction
        self.currentTransfer?.speedKBps = speed
        self.currentTransfer?.status = .transferring
        self.transferProgress = progressFraction
        self.transferSpeedKBps = speed
        self.transferStatusText = String(format: "Transferring: %.1f%% (%.1f KB/s)", progressFraction * 100, speed)

        activeProgress?(progressFraction)
        guard isTransferring, currentTransfer?.transferId == transferId else { return }
        let nextWindow = windowIndex + 1
        if nextWindow < transfer.totalWindows {
            sendWindow(index: nextWindow)
        } else {
            finishStreamingWindows()
        }
    }

    private func finishStreamingWindows() {
        guard let transfer = currentTransfer, isTransferring else { return }

        self.currentTransfer?.status = .verifying
        self.transferStatusText = "Verifying SHA-256 on watch..."

        logger.info("All windows sent for #\(transfer.transferId). Sending FILE_END.")

        // 3. Send Stage 3: FILE_END prefixed with 0x01
        let endDict: [String: Any] = [
            "cmd": "FILE_END",
            "transferId": transfer.transferId
        ]
        if let endData = try? JSONSerialization.data(withJSONObject: endDict) {
            var framed = Data([Self.headerControlJson])
            framed.append(endData)
            BLEServerManager.shared.sendFileTransferPayload(framed)
        }

        resetTimeoutWatchdog(duration: 45.0) // Allow 45s for watch file hashing
    }

    private func handleFileComplete(json: [String: Any], transferId: String) {
        guard isTransferring, currentTransfer?.transferId == transferId else { return }

        let status = json["status"] as? String ?? ""
        let savedPath = json["savedPath"] as? String ?? ""

        if status == "VERIFIED" {
            guard currentTransfer?.status == .verifying else { return }
            logger.info("File transfer verified successfully on watch! Path: \(savedPath)")
            self.currentTransfer?.status = .completed
            self.currentTransfer?.progress = 1.0
            self.currentTransfer?.statusMessage = "Verified on Watch"
            self.transferProgress = 1.0
            self.transferStatusText = "Transfer Complete (Verified)"
            complete(.success(savedPath))
        } else {
            logger.error("File transfer verification failed: status=\(status)")
            failTransfer(errorMessage: "SHA-256 verification failed on watch: \(status)")
        }
    }

    // MARK: - Cancellation & Failures
    func companionDisconnected() {
        guard isTransferring, let transfer = currentTransfer, !transfer.sha256.isEmpty else { return }
        waitingForConnection = true
        currentTransfer?.status = .preparing
        transferStatusText = "Waiting for watch to reconnect..."
        resetTimeoutWatchdog(duration: 55)
    }

    func companionReconnected() {
        guard waitingForConnection, isTransferring else { return }
        waitingForConnection = false
        transferStatusText = "Restoring transfer..."
        announceTransfer()
    }

    public func cancelTransfer() {
        if !Thread.isMainThread {
            DispatchQueue.main.async { self.cancelTransfer() }
            return
        }
        guard isTransferring, let transferId = currentTransfer?.transferId else { return }

        logger.info("Cancelling active file transfer #\(transferId)")

        let cancelDict: [String: Any] = [
            "cmd": "FILE_CANCEL",
            "transferId": transferId
        ]
        if let cancelData = try? JSONSerialization.data(withJSONObject: cancelDict) {
            var framed = Data([Self.headerControlJson])
            framed.append(cancelData)
            BLEServerManager.shared.sendFileTransferPayload(framed)
        }

        failTransfer(errorMessage: "Transfer cancelled by user", status: .cancelled)
    }

    private func failTransfer(errorMessage: String, status: FileTransferStatus = .failed) {
        currentTransfer?.status = status
        currentTransfer?.statusMessage = errorMessage
        transferStatusText = errorMessage
        complete(.failure(NSError(domain: "WearBridge", code: -12, userInfo: [NSLocalizedDescriptionKey: errorMessage])))
    }

    private func complete(_ result: Result<String, Error>) {
        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil
        let completion = activeCompletion
        waitingForConnection = false
        activeFileData = nil
        activeProgress = nil
        activeCompletion = nil
        isTransferring = false
        completion?(result)
    }

    private func resetTimeoutWatchdog(duration: TimeInterval = 30.0) {
        timeoutWorkItem?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self = self, self.isTransferring else { return }
            self.logger.error("File transfer watchdog timed out after \(duration)s")
            self.failTransfer(errorMessage: "Transfer timed out")
        }
        timeoutWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + duration, execute: item)
    }
}
