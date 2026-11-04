import Foundation
import Combine
import CryptoKit
import os.log

public struct WatchFaceItem: Identifiable, Codable {
    public let id: String
    public let title: String
    public let author: String
    public let version: String
    public let description: String
    public let fileSize: Int
    public let fileType: String
    public let fileName: String
    public let previewUrl: String?
    public let downloadUrl: String?
    public let isBuiltIn: Bool
    public var sha256: String?

    public var formattedSize: String {
        let kb = Double(fileSize) / 1024.0
        if kb > 1024.0 {
            return String(format: "%.2f MB", kb / 1024.0)
        } else {
            return String(format: "%.1f KB", kb)
        }
    }
}

public final class WatchFaceStoreManager: ObservableObject {
    public static let shared = WatchFaceStoreManager()
    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "WatchFaceStoreManager")

    private let repoUrlKey = "wearbridge_watchface_repo_url"
    public static let defaultRepoUrl = "https://raw.githubusercontent.com/wearos/watchfaces-catalog/main/feed.json"

    @Published public var catalog: [WatchFaceItem] = []
    @Published public var downloadedFiles: [URL] = []
    @Published public var isLoadingCatalog: Bool = false
    @Published public var repositoryUrl: String {
        didSet {
            UserDefaults.standard.set(repositoryUrl, forKey: repoUrlKey)
        }
    }

    public var watchFacesDirectory: URL {
        let docDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let facesDir = docDir.appendingPathComponent("WatchFaces", isDirectory: true)
        if !FileManager.default.fileExists(atPath: facesDir.path) {
            try? FileManager.default.createDirectory(at: facesDir, withIntermediateDirectories: true)
        }
        return facesDir
    }

    private init() {
        self.repositoryUrl = UserDefaults.standard.string(forKey: repoUrlKey) ?? Self.defaultRepoUrl
        loadCatalog()
        refreshDownloadedFiles()
    }

    // MARK: - Catalog Fetching
    public func loadCatalog() {
        isLoadingCatalog = true

        guard let url = URL(string: repositoryUrl), url.scheme?.hasPrefix("http") == true else {
            loadBuiltInShowcase()
            isLoadingCatalog = false
            return
        }

        URLSession.shared.dataTask(with: url) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.isLoadingCatalog = false

                if let data = data,
                   let items = try? JSONDecoder().decode([WatchFaceItem].self, from: data),
                   !items.isEmpty {
                    self.catalog = items
                    self.logger.info("Loaded \(items.count) watch faces from remote repository: \(url.absoluteString)")
                } else {
                    self.logger.info("Remote catalog unreachable or invalid, using built-in showcase catalog")
                    self.loadBuiltInShowcase()
                }
            }
        }.resume()
    }

    public func loadBuiltInShowcase() {
        self.catalog = [
            WatchFaceItem(
                id: "chronos_minimal",
                title: "Chronos Minimal",
                author: "WearBridge Labs",
                version: "1.4.0",
                description: "Clean typography with battery & step count complications for AMOLED displays.",
                fileSize: 142 * 1024,
                fileType: "WATCH_FACE",
                fileName: "chronos_minimal.apk",
                previewUrl: nil,
                downloadUrl: nil,
                isBuiltIn: true,
                sha256: nil
            ),
            WatchFaceItem(
                id: "cyber_dial",
                title: "Cyber Dial WFF",
                author: "PixelCraft",
                version: "2.1.0",
                description: "Futuristic neon telemetry face rendered in native Wear OS Watch Face Format.",
                fileSize: 98 * 1024,
                fileType: "WATCH_FACE",
                fileName: "cyber_dial.wff",
                previewUrl: nil,
                downloadUrl: nil,
                isBuiltIn: true,
                sha256: nil
            ),
            WatchFaceItem(
                id: "retro_digital_80s",
                title: "Retro Digital 80s",
                author: "Vintage Wear",
                version: "1.0.2",
                description: "Classic LCD digital aesthetic with hourly chime and retro glow backlight mode.",
                fileSize: 185 * 1024,
                fileType: "WATCH_FACE",
                fileName: "retro_digital.apk",
                previewUrl: nil,
                downloadUrl: nil,
                isBuiltIn: true,
                sha256: nil
            ),
            WatchFaceItem(
                id: "astronomy_pulse",
                title: "Astronomy Pulse",
                author: "Cosmos Design",
                version: "3.0.1",
                description: "Live planetary orbits with moon phases and astronomical sunrise/sunset calculations.",
                fileSize: 230 * 1024,
                fileType: "WATCH_FACE",
                fileName: "astronomy_pulse.apk",
                previewUrl: nil,
                downloadUrl: nil,
                isBuiltIn: true,
                sha256: nil
            ),
            WatchFaceItem(
                id: "tactical_sport_pro",
                title: "Tactical Sport Pro",
                author: "FitForge",
                version: "2.2.0",
                description: "Rugged high-contrast sport chronograph with heart rate zone gauges and altimeter.",
                fileSize: 310 * 1024,
                fileType: "WATCH_FACE",
                fileName: "tactical_sport.apk",
                previewUrl: nil,
                downloadUrl: nil,
                isBuiltIn: true,
                sha256: nil
            )
        ]
    }

    // MARK: - Download & Mock Generation
    public func downloadOrPreparePackage(for item: WatchFaceItem, completion: @escaping (Result<URL, Error>) -> Void) {
        let destination = watchFacesDirectory.appendingPathComponent(item.fileName)

        if FileManager.default.fileExists(atPath: destination.path) {
            completion(.success(destination))
            return
        }

        if let downloadUrlString = item.downloadUrl, let downloadUrl = URL(string: downloadUrlString) {
            URLSession.shared.downloadTask(with: downloadUrl) { tempUrl, response, error in
                if let error = error {
                    DispatchQueue.main.async { completion(.failure(error)) }
                    return
                }
                guard let tempUrl = tempUrl else {
                    DispatchQueue.main.async {
                        completion(.failure(NSError(domain: "WearBridge", code: -20, userInfo: [NSLocalizedDescriptionKey: "Download failed"])))
                    }
                    return
                }
                do {
                    if FileManager.default.fileExists(atPath: destination.path) {
                        try FileManager.default.removeItem(at: destination)
                    }
                    try FileManager.default.moveItem(at: tempUrl, to: destination)
                    DispatchQueue.main.async {
                        self.refreshDownloadedFiles()
                        completion(.success(destination))
                    }
                } catch {
                    DispatchQueue.main.async { completion(.failure(error)) }
                }
            }.resume()
        } else {
            // Built-in package: generate deterministic synthetic package on disk
            generateMockPackage(item: item, destination: destination, completion: completion)
        }
    }

    private func generateMockPackage(item: WatchFaceItem, destination: URL, completion: @escaping (Result<URL, Error>) -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            var data = Data()

            // Standard ZIP / APK file header: 0x50 0x4B 0x03 0x04 ("PK\03\04")
            data.append(contentsOf: [0x50, 0x4B, 0x03, 0x04])
            data.append(contentsOf: [0x14, 0x00, 0x00, 0x00, 0x08, 0x00]) // version & flags

            // Meta payload: manifest JSON
            let manifestJson = """
            {
              "packageName": "com.wearbridge.watchface.\(item.id)",
              "title": "\(item.title)",
              "author": "\(item.author)",
              "version": "\(item.version)",
              "type": "\(item.fileType)"
            }
            """
            if let manifestData = manifestJson.data(using: .utf8) {
                data.append(manifestData)
            }

            // Pad deterministic bytes up to item.fileSize
            let remaining = max(0, item.fileSize - data.count)
            var filler = [UInt8](repeating: 0, count: remaining)
            for i in 0..<remaining {
                filler[i] = UInt8((i * 37 + 13) & 0xFF)
            }
            data.append(contentsOf: filler)

            do {
                if FileManager.default.fileExists(atPath: destination.path) {
                    try FileManager.default.removeItem(at: destination)
                }
                try data.write(to: destination, options: .atomic)
                DispatchQueue.main.async {
                    self.refreshDownloadedFiles()
                    completion(.success(destination))
                }
            } catch {
                DispatchQueue.main.async {
                    completion(.failure(error))
                }
            }
        }
    }

    // MARK: - File Management
    public func saveImportedFile(sourceUrl: URL) -> URL? {
        let fileName = sourceUrl.lastPathComponent
        let destination = watchFacesDirectory.appendingPathComponent(fileName)
        do {
            if FileManager.default.fileExists(atPath: destination.path) {
                try FileManager.default.removeItem(at: destination)
            }
            try FileManager.default.copyItem(at: sourceUrl, to: destination)
            refreshDownloadedFiles()
            return destination
        } catch {
            logger.error("Failed to copy imported file: \(error.localizedDescription)")
            return nil
        }
    }

    public func refreshDownloadedFiles() {
        guard let files = try? FileManager.default.contentsOfDirectory(at: watchFacesDirectory, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey]) else {
            downloadedFiles = []
            return
        }
        downloadedFiles = files.sorted {
            let date0 = (try? $0.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date.distantPast
            let date1 = (try? $1.resourceValues(forKeys: [.contentModificationDateKey]).contentModificationDate) ?? Date.distantPast
            return date0 > date1
        }
    }

    public func deleteDownloadedFile(url: URL) {
        try? FileManager.default.removeItem(at: url)
        refreshDownloadedFiles()
    }
}
