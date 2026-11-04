import Foundation
import Network
import Combine
import CommonCrypto

/// Information about a GitHub release asset.
public struct GitHubReleaseInfo: Identifiable, Codable {
    public var id: String { tagName }
    public let tagName: String
    public let name: String
    public let body: String
    public let apkDownloadUrl: String?
    public let apkFileName: String?
    public let apkSize: Int64
    public let publishedAt: String

    public var formattedSize: String {
        let kb = Double(apkSize) / 1024.0
        if kb >= 1024.0 {
            return String(format: "%.1f MB", kb / 1024.0)
        }
        return String(format: "%.0f KB", kb)
    }
}

/// State of an ongoing OTA update.
public enum OtaState: Equatable {
    case idle
    case checking
    case downloadingToPhone(progress: Double)
    case servingLocalWifi(url: String)
    case streamingOverBle(progress: Double)
    case watchInstalling
    case completed(version: String)
    case failed(message: String)
}

/// Manages watch self-OTA updates: local Wi-Fi/Hotspot HTTP server,
/// GitHub releases discovery, and BLE Channel 0x07 fallback.
public class WatchOtaManager: ObservableObject {

    public static let shared = WatchOtaManager()

    @Published public var installedWatchVersion: String = "2.0.0 (Build 200)"
    @Published public var latestRelease: GitHubReleaseInfo? = nil
    @Published public var otaState: OtaState = .idle
    @Published public var otaStatusMessage: String = "Up to date"
    @Published public var localServerUrl: String? = nil

    // Configurable repository
    @Published public var githubRepoOwner: String = "wearos-ancs"
    @Published public var githubRepoName: String = "WearBridge"

    private var listener: NWListener?
    private var activeConnection: NWConnection?
    private var servingData: Data?

    private init() {}

    // MARK: - SHA-256 Calculation
    public static func computeSha256(data: Data) -> String {
        var hash = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        data.withUnsafeBytes {
            _ = CC_SHA256($0.baseAddress, CC_LONG(data.count), &hash)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - Version Comparison
    public static func isUpdateAvailable(current: String, candidate: String) -> Bool {
        let cClean = current.replacingOccurrences(of: "v", with: "").components(separatedBy: " ").first ?? current
        let candClean = candidate.replacingOccurrences(of: "v", with: "").components(separatedBy: " ").first ?? candidate

        let cParts = cClean.split(separator: ".").compactMap { Int($0) }
        let candParts = candClean.split(separator: ".").compactMap { Int($0) }

        let count = max(cParts.count, candParts.count)
        for i in 0..<count {
            let candVal = i < candParts.count ? candParts[i] : 0
            let cVal = i < cParts.count ? cParts[i] : 0
            if candVal > cVal { return true }
            if candVal < cVal { return false }
        }
        return false
    }

    // MARK: - 1. Local Wi-Fi / Hotspot Turbo Server
    /// Starts a lightweight embedded HTTP server on the iPhone and pushes OTA_UPDATE_URL over BLE.
    public func startTurboServerAndPush(
        apkData: Data,
        targetVersion: String,
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        stopTurboServer()

        self.servingData = apkData
        let sha256 = Self.computeSha256(data: apkData)

        do {
            let parameters = NWParameters.tcp
            let newListener = try NWListener(using: parameters)

            newListener.newConnectionHandler = { [weak self] connection in
                self?.handleIncomingConnection(connection)
            }

            newListener.stateUpdateHandler = { [weak self] state in
                guard let self = self else { return }
                switch state {
                case .ready:
                    if let port = newListener.port?.rawValue,
                       let ip = self.getLocalIpAddress() {
                        let urlString = "http://\(ip):\(port)/wearbridge-ota.apk"
                        DispatchQueue.main.async {
                            self.localServerUrl = urlString
                            self.otaState = .servingLocalWifi(url: urlString)
                            self.otaStatusMessage = "Serving APK over Turbo Wi-Fi: \(urlString)"
                        }

                        // Send OTA_UPDATE_URL command over BLE Channel 0x01
                        self.sendOtaCommandOverBle(url: urlString, versionName: targetVersion, sha256: sha256)
                        completion(.success(urlString))
                    } else {
                        let err = NSError(domain: "WatchOtaManager", code: -2, userInfo: [NSLocalizedDescriptionKey: "Could not obtain local Wi-Fi or Hotspot IP address"])
                        DispatchQueue.main.async {
                            self.otaState = .failed(message: err.localizedDescription)
                        }
                        completion(.failure(err))
                    }
                case .failed(let error):
                    DispatchQueue.main.async {
                        self.otaState = .failed(message: error.localizedDescription)
                    }
                    completion(.failure(error))
                default:
                    break
                }
            }

            self.listener = newListener
            newListener.start(queue: .global(qos: .userInitiated))

        } catch {
            DispatchQueue.main.async {
                self.otaState = .failed(message: error.localizedDescription)
            }
            completion(.failure(error))
        }
    }

    public func stopTurboServer() {
        activeConnection?.cancel()
        activeConnection = nil
        listener?.cancel()
        listener = nil
        servingData = nil
        localServerUrl = nil
    }

    private func handleIncomingConnection(_ connection: NWConnection) {
        self.activeConnection = connection
        connection.start(queue: .global(qos: .userInitiated))

        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) { [weak self] data, context, isComplete, error in
            guard let self = self, let _ = data, let apk = self.servingData else {
                connection.cancel()
                return
            }

            // HTTP 200 response with Content-Length and APK binary payload
            let header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: \(apk.count)\r\nConnection: close\r\n\r\n"
            var responseData = header.data(using: .utf8)!
            responseData.append(apk)

            connection.send(content: responseData, completion: .contentProcessed({ sendError in
                if let err = sendError {
                    print("[WatchOtaManager] HTTP send error: \(err)")
                } else {
                    print("[WatchOtaManager] Successfully streamed \(apk.count) bytes to watch over Wi-Fi")
                    DispatchQueue.main.async {
                        self.otaState = .watchInstalling
                        self.otaStatusMessage = "APK received by Watch. Tap 'Update' on watch screen."
                    }
                }
                connection.cancel()
            }))
        }
    }

    // MARK: - Local IP Address Discovery
    private func getLocalIpAddress() -> String? {
        var address: String?
        var ifaddr: UnsafeMutablePointer<ifaddrs>?

        guard getifaddrs(&ifaddr) == 0, let firstAddr = ifaddr else { return nil }

        for ptr in sequence(first: firstAddr, next: { $0.pointee.ifa_next }) {
            let interface = ptr.pointee
            let addrFamily = interface.ifa_addr.pointee.sa_family

            if addrFamily == UInt8(AF_INET) { // IPv4
                let name = String(cString: interface.ifa_name)
                // en0 = Wi-Fi, bridge100/ap0 = Personal Hotspot, pdp_ip0 = Cellular
                if name == "en0" || name.starts(with: "bridge") || name.starts(with: "ap") {
                    var hostname = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    getnameinfo(
                        interface.ifa_addr,
                        socklen_t(interface.ifa_addr.pointee.sa_len),
                        &hostname,
                        socklen_t(hostname.count),
                        nil,
                        socklen_t(0),
                        NI_NUMERICHOST
                    )
                    address = String(cString: hostname)
                    if name == "en0" { break } // prioritize Wi-Fi
                }
            }
        }
        freeifaddrs(ifaddr)
        return address
    }

    // MARK: - 2. GitHub Releases Integration
    public func checkForGitHubRelease(completion: @escaping (Result<GitHubReleaseInfo, Error>) -> Void) {
        guard let url = URL(string: "https://api.github.com/repos/\(githubRepoOwner)/\(githubRepoName)/releases/latest") else {
            completion(.failure(NSError(domain: "WatchOtaManager", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid GitHub repository URL"])))
            return
        }

        DispatchQueue.main.async {
            self.otaState = .checking
            self.otaStatusMessage = "Checking GitHub Releases..."
        }

        var request = URLRequest(url: url)
        request.setValue("application/vnd.github.v3+json", forHTTPHeaderField: "Accept")
        request.setValue("WearBridge-iOS", forHTTPHeaderField: "User-Agent")

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self else { return }

            if let error = error {
                DispatchQueue.main.async {
                    self.otaState = .failed(message: error.localizedDescription)
                    self.otaStatusMessage = "Update check failed: \(error.localizedDescription)"
                }
                completion(.failure(error))
                return
            }

            guard let data = data,
                  let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let tagName = json["tag_name"] as? String else {
                let err = NSError(domain: "WatchOtaManager", code: -3, userInfo: [NSLocalizedDescriptionKey: "No releases found on GitHub"])
                DispatchQueue.main.async {
                    self.otaState = .failed(message: err.localizedDescription)
                }
                completion(.failure(err))
                return
            }

            let releaseName = json["name"] as? String ?? tagName
            let body = json["body"] as? String ?? ""
            let publishedAt = json["published_at"] as? String ?? ""

            // Find APK asset
            var apkUrl: String? = nil
            var apkName: String? = nil
            var apkSize: Int64 = 0

            if let assets = json["assets"] as? [[String: Any]] {
                for asset in assets {
                    if let name = asset["name"] as? String, name.hasSuffix(".apk") {
                        apkUrl = asset["browser_download_url"] as? String
                        apkName = name
                        apkSize = (asset["size"] as? NSNumber)?.int64Value ?? 0
                        // Prefer release over debug if available
                        if name.contains("release") { break }
                    }
                }
            }

            let release = GitHubReleaseInfo(
                tagName: tagName,
                name: releaseName,
                body: body,
                apkDownloadUrl: apkUrl,
                apkFileName: apkName,
                apkSize: apkSize,
                publishedAt: publishedAt
            )

            DispatchQueue.main.async {
                self.latestRelease = release
                let updateAvail = Self.isUpdateAvailable(current: self.installedWatchVersion, candidate: tagName)
                if updateAvail {
                    self.otaState = .idle
                    self.otaStatusMessage = "New version available: \(tagName)"
                } else {
                    self.otaState = .idle
                    self.otaStatusMessage = "Watch is up to date (\(tagName))"
                }
            }

            completion(.success(release))
        }.resume()
    }

    // MARK: - Download Remote APK
    public func downloadRemoteApk(from url: URL, progressHandler: @escaping (Double) -> Void, completion: @escaping (Result<Data, Error>) -> Void) {
        DispatchQueue.main.async {
            self.otaState = .downloadingToPhone(progress: 0.0)
            self.otaStatusMessage = "Downloading APK from GitHub to iPhone..."
        }

        let task = URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            guard let self = self else { return }

            if let error = error {
                DispatchQueue.main.async {
                    self.otaState = .failed(message: error.localizedDescription)
                }
                completion(.failure(error))
                return
            }

            guard let data = data, !data.isEmpty else {
                let err = NSError(domain: "WatchOtaManager", code: -4, userInfo: [NSLocalizedDescriptionKey: "Downloaded empty file"])
                DispatchQueue.main.async {
                    self.otaState = .failed(message: err.localizedDescription)
                }
                completion(.failure(err))
                return
            }

            DispatchQueue.main.async {
                self.otaState = .idle
                self.otaStatusMessage = "Downloaded \(Double(data.count) / 1024.0 / 1024.0) MB. Ready for Turbo Push."
            }
            completion(.success(data))
        }
        task.resume()
    }

    // MARK: - 3. Pure BLE Fallback (Channel 0x07)
    public func pushOtaUpdateViaBle(
        apkData: Data,
        targetVersion: String,
        progressHandler: @escaping (Double) -> Void,
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        DispatchQueue.main.async {
            self.otaState = .streamingOverBle(progress: 0.0)
            self.otaStatusMessage = "Streaming update over BLE (Channel 0x07)..."
        }

        FileTransferManager.shared.startFileTransfer(
            fileData: apkData,
            fileName: "wearbridge_ota_v\(targetVersion.replacingOccurrences(of: ".", with: "_")).apk",
            fileType: "WATCH_APP_OTA",
            onProgress: { [weak self] p in
                progressHandler(p)
                DispatchQueue.main.async {
                    self?.otaState = .streamingOverBle(progress: p)
                    self?.otaStatusMessage = "Streaming over BLE: \(Int(p * 100))%"
                }
            },
            completion: { [weak self] result in
                guard let self = self else { return }
                switch result {
                case .success(let info):
                    DispatchQueue.main.async {
                        self.otaState = .watchInstalling
                        self.otaStatusMessage = "Transferred over BLE! Tap 'Update' on watch screen."
                    }
                    completion(.success(info))
                case .failure(let err):
                    DispatchQueue.main.async {
                        self.otaState = .failed(message: err.localizedDescription)
                        self.otaStatusMessage = "BLE Transfer failed: \(err.localizedDescription)"
                    }
                    completion(.failure(err))
                }
            }
        )
    }

    // MARK: - BLE Command Dispatcher
    public static func makeOtaCommandJson(url: String, versionName: String, sha256: String) -> [String: Any] {
        return [
            "cmd": "OTA_UPDATE_URL",
            "url": url,
            "versionName": versionName,
            "sha256": sha256
        ]
    }

    public func sendOtaCommandOverBle(url: String, versionName: String, sha256: String) {
        let json = Self.makeOtaCommandJson(url: url, versionName: versionName, sha256: sha256)

        guard let data = try? JSONSerialization.data(withJSONObject: json) else { return }
        BLEServerManager.shared.sendPacket(channel: .pingControl, payload: data)
        print("[WatchOtaManager] Dispatched OTA_UPDATE_URL over Channel 0x01: \(url)")
    }
}
