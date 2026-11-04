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
    public let sha256: String
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

    public static let windowSizeBytes = 32 * 1024 // 32KB windows

    @Published public var currentTransfer: FileTransferState?
    @Published public var transferProgress: Double = 0.0
    @Published public var transferSpeedKBps: Double = 0.0
    @Published public var transferStatusText: String = "Idle"
    @Published public var isTransferring: Bool = false

    private var activeFileData: Data?
    private var activeCompletion: ((Result<String, Error>) -> Void)?
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
        guard let data = try? Data(contentsOf: fileUrl) else {
            completion?(.failure(NSError(domain: "WearBridge", code: -10, userInfo: [NSLocalizedDescriptionKey: "Failed to read file at \(fileUrl.path)"])))
            return
        }
        let fileName = fileUrl.lastPathComponent
        startTransfer(fileData: data, fileName: fileName, fileType: fileType, completion: completion)
    }

    public func startFileTransfer(
        fileData: Data,
        fileName: String,
        fileType: String = "WATCH_FACE",
        onProgress: ((Double) -> Void)? = nil,
        completion: ((Result<String, Error>) -> Void)? = nil
    ) {
        startTransfer(fileData: fileData, fileName: fileName, fileType: fileType, completion: completion)
    }

    public func startTransfer(
        fileData: Data,
        fileName: String,
        fileType: String = "WATCH_FACE",
        completion: ((Result<String, Error>) -> Void)? = nil
    ) {
        guard !isTransferring else {
            completion?(.failure(NSError(domain: "WearBridge", code: -11, userInfo: [NSLocalizedDescriptionKey: "Another file transfer is already in progress"])))
            return
        }

        let transferId = UUID().uuidString
        let hash = Self.computeSha256(data: fileData)
        let totalWindows = max(1, Int(ceil(Double(fileData.count) / Double(Self.windowSizeBytes))))

        self.activeFileData = fileData
        self.activeCompletion = completion

        let state = FileTransferState(
            transferId: transferId,
            fileName: fileName,
            fileSize: fileData.count,
            fileType: fileType,
            sha256: hash,
            totalWindows: totalWindows,
            currentWindow: 0,
            bytesTransferred: 0,
            progress: 0.0,
            speedKBps: 0.0,
            status: .preparing,
            statusMessage: "Starting transfer...",
            startTime: Date()
        )

        DispatchQueue.main.async {
            self.currentTransfer = state
            self.transferProgress = 0.0
            self.transferSpeedKBps = 0.0
            self.transferStatusText = "Preparing \(fileName)..."
            self.isTransferring = true
        }

        logger.info("Starting Channel 0x07 file transfer for '\(fileName)' (\(fileData.count) bytes, \(totalWindows) windows, sha256=\(hash))")

        // 1. Send Stage 1: FILE_START JSON packet
        let startDict: [String: Any] = [
            "cmd": "FILE_START",
            "transferId": transferId,
            "fileName": fileName,
            "fileSize": fileData.count,
            "fileType": fileType,
            "sha256": hash,
            "totalWindows": totalWindows
        ]

        guard let startData = try? JSONSerialization.data(withJSONObject: startDict) else {
            failTransfer(errorMessage: "Failed to serialize FILE_START")
            return
        }

        // Send FILE_START control packet
        BLEServerManager.shared.sendFileTransferPayload(startData)

        // Reset safety watchdog timeout (60s)
        resetTimeoutWatchdog()

        // Give watch 150ms to open file descriptor, then send window 0
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { [weak self] in
            guard let self = self, self.isTransferring else { return }
            self.sendWindow(index: 0)
        }
    }

    // MARK: - Stage 2: Binary Window Streaming
    private func sendWindow(index: Int) {
        guard let data = activeFileData, isTransferring else { return }

        let startOffset = index * Self.windowSizeBytes
        if startOffset >= data.count {
            // All windows sent — initiate Stage 3: FILE_END
            finishStreamingWindows()
            return
        }

        let endOffset = min(data.count, startOffset + Self.windowSizeBytes)
        let windowChunk = data.subdata(in: startOffset..<endOffset)

        DispatchQueue.main.async {
            self.currentTransfer?.currentWindow = index
            self.transferStatusText = "Streaming window \(index + 1)/\(self.currentTransfer?.totalWindows ?? 1)..."
        }

        logger.debug("Streaming window \(index) (\(windowChunk.count) bytes, offset \(startOffset)..\(endOffset))")

        // Send binary window chunk over Channel 0x07 (buffered with flow control in BLEServerManager)
        BLEServerManager.shared.sendFileTransferPayload(windowChunk)
        resetTimeoutWatchdog()
    }

    // MARK: - Incoming Packet & Window ACK Handler
    public func handleIncomingControlPacket(_ data: Data) {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let cmd = json["cmd"] as? String else {
            logger.warning("Unrecognized Channel 0x07 control packet: \(data.count) bytes")
            return
        }

        let transferId = json["transferId"] as? String ?? ""

        switch cmd {
        case "FILE_WINDOW_ACK":
            handleWindowAck(json: json, transferId: transferId)

        case "FILE_COMPLETE":
            handleFileComplete(json: json, transferId: transferId)

        case "FILE_CANCEL":
            logger.info("Watch cancelled file transfer #\(transferId)")
            failTransfer(errorMessage: "Transfer cancelled by watch")

        default:
            logger.debug("Received command \(cmd) on Channel 0x07")
        }
    }

    private func handleWindowAck(json: [String: Any], transferId: String) {
        guard isTransferring, currentTransfer?.transferId == transferId else { return }

        let windowIndex = json["windowIndex"] as? Int ?? -1
        let bytesReceived = json["bytesReceived"] as? Int ?? 0

        guard let transfer = currentTransfer else { return }

        let elapsed = max(0.001, Date().timeIntervalSince(transfer.startTime))
        let speed = (Double(bytesReceived) / 1024.0) / elapsed
        let progressFraction = transfer.fileSize > 0 ? min(1.0, Double(bytesReceived) / Double(transfer.fileSize)) : 0.0

        DispatchQueue.main.async {
            self.currentTransfer?.bytesTransferred = bytesReceived
            self.currentTransfer?.progress = progressFraction
            self.currentTransfer?.speedKBps = speed
            self.currentTransfer?.status = .transferring
            self.transferProgress = progressFraction
            self.transferSpeedKBps = speed
            self.transferStatusText = String(format: "Transferring: %.1f%% (%.1f KB/s)", progressFraction * 100, speed)
        }

        resetTimeoutWatchdog()

        if windowIndex >= 0 {
            let nextWindow = windowIndex + 1
            if nextWindow < transfer.totalWindows {
                sendWindow(index: nextWindow)
            } else {
                finishStreamingWindows()
            }
        }
    }

    private func finishStreamingWindows() {
        guard let transfer = currentTransfer, isTransferring else { return }

        DispatchQueue.main.async {
            self.currentTransfer?.status = .verifying
            self.transferStatusText = "Verifying SHA-256 on watch..."
        }

        logger.info("All windows sent for #\(transfer.transferId). Sending FILE_END.")

        // 3. Send Stage 3: FILE_END
        let endDict: [String: Any] = [
            "cmd": "FILE_END",
            "transferId": transfer.transferId
        ]
        if let endData = try? JSONSerialization.data(withJSONObject: endDict) {
            BLEServerManager.shared.sendFileTransferPayload(endData)
        }

        resetTimeoutWatchdog(duration: 45.0) // Allow 45s for watch file hashing
    }

    private func handleFileComplete(json: [String: Any], transferId: String) {
        guard isTransferring, currentTransfer?.transferId == transferId else { return }

        let status = json["status"] as? String ?? ""
        let savedPath = json["savedPath"] as? String ?? ""

        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil

        if status == "VERIFIED" {
            logger.info("File transfer verified successfully on watch! Path: \(savedPath)")
            DispatchQueue.main.async {
                self.currentTransfer?.status = .completed
                self.currentTransfer?.progress = 1.0
                self.currentTransfer?.statusMessage = "Verified & Installed on Watch"
                self.transferProgress = 1.0
                self.transferStatusText = "Transfer Complete (Verified)"
                self.isTransferring = false

                self.activeCompletion?(.success(savedPath))
                self.cleanup()
            }
        } else {
            logger.error("File transfer verification failed: status=\(status)")
            failTransfer(errorMessage: "SHA-256 verification failed on watch: \(status)")
        }
    }

    // MARK: - Cancellation & Failures
    public func cancelTransfer() {
        guard isTransferring, let transferId = currentTransfer?.transferId else { return }

        logger.info("Cancelling active file transfer #\(transferId)")

        let cancelDict: [String: Any] = [
            "cmd": "FILE_CANCEL",
            "transferId": transferId
        ]
        if let cancelData = try? JSONSerialization.data(withJSONObject: cancelDict) {
            BLEServerManager.shared.sendFileTransferPayload(cancelData)
        }

        failTransfer(errorMessage: "Transfer cancelled by user")
    }

    private func failTransfer(errorMessage: String) {
        timeoutWorkItem?.cancel()
        timeoutWorkItem = nil

        DispatchQueue.main.async {
            self.currentTransfer?.status = .failed
            self.currentTransfer?.statusMessage = errorMessage
            self.transferStatusText = "Failed: \(errorMessage)"
            self.isTransferring = false

            self.activeCompletion?(.failure(NSError(domain: "WearBridge", code: -12, userInfo: [NSLocalizedDescriptionKey: errorMessage])))
            self.cleanup()
        }
    }

    private func cleanup() {
        activeFileData = nil
        activeCompletion = nil
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
