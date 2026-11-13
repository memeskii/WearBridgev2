import Foundation
import MediaPlayer
import AVFoundation
import UIKit
import os.log
import Combine

/**
 * Media QoL Payload transmitted over custom BLE GATT Channel 0x05.
 */
public struct MediaQoLPayload: Codable, Equatable {
    public let trackKey: String
    public let durationSeconds: Double
    public let audioRouteName: String
    public let artworkBase64: String

    public init(
        trackKey: String,
        durationSeconds: Double,
        audioRouteName: String,
        artworkBase64: String
    ) {
        self.trackKey = trackKey
        self.durationSeconds = durationSeconds
        self.audioRouteName = audioRouteName
        self.artworkBase64 = artworkBase64
    }
}

/**
 * Full Media State Sync payload streamed over Channel 0x05 to guarantee
 * instant, active playback tracking even when AMS is unbonded or delayed.
 */
public struct MediaStateSyncPayload: Codable {
    public let cmd: String
    public let title: String
    public let artist: String
    public let album: String
    public let isPlaying: Bool
    public let playbackRate: Float
    public let elapsedSeconds: Double
    public let durationSeconds: Double
    public let volume: Float
    public let audioRouteName: String
    public let artworkBase64: String
    public let trackKey: String

    public init(
        cmd: String = "MEDIA_STATE",
        title: String,
        artist: String,
        album: String,
        isPlaying: Bool,
        playbackRate: Float,
        elapsedSeconds: Double,
        durationSeconds: Double,
        volume: Float,
        audioRouteName: String,
        artworkBase64: String,
        trackKey: String
    ) {
        self.cmd = cmd
        self.title = title
        self.artist = artist
        self.album = album
        self.isPlaying = isPlaying
        self.playbackRate = playbackRate
        self.elapsedSeconds = elapsedSeconds
        self.durationSeconds = durationSeconds
        self.volume = volume
        self.audioRouteName = audioRouteName
        self.artworkBase64 = artworkBase64
        self.trackKey = trackKey
    }
}

/**
 * Inbound command received from Wear OS over Channel 0x05.
 * Format: {"cmd": "FETCH_ART", "title": "...", "artist": "...", "album": "..."}
 */
public struct MediaFetchArtCommand: Codable {
    public let cmd: String?
    public let title: String?
    public let artist: String?
    public let album: String?

    public init(cmd: String?, title: String?, artist: String?, album: String? = nil) {
        self.cmd = cmd
        self.title = title
        self.artist = artist
        self.album = album
    }
}

/// Model representing currently active now-playing track on the companion app.
public struct ActiveMediaItem {
    public var title: String
    public var artist: String
    public var album: String
    public var audioRoute: String
    public var artworkBase64: String?

    public init(
        title: String = "",
        artist: String = "",
        album: String = "",
        audioRoute: String = "iPhone Speaker",
        artworkBase64: String? = nil
    ) {
        self.title = title
        self.artist = artist
        self.album = album
        self.audioRoute = audioRoute
        self.artworkBase64 = artworkBase64
    }
}

/**
 * Manages the Custom GATT Media QoL Bridge (Channel 0x05).
 *
 * Responsibilities:
 * - Listens for incoming FETCH_ART requests from Wear OS.
 * - Resolves album artwork via MPMusicPlayerController or iTunes Search API fallback.
 * - Resizes and compresses artwork to a 96x96 JPEG (< 8KB).
 * - Detects active audio route (e.g. "AirPods Pro", "iPhone Speaker").
 * - Transmits MediaQoLPayload over BLE Channel 0x05, caching trackKey to avoid redundant transmissions.
 */
public class MediaQoLManager: ObservableObject {

    public static let shared = MediaQoLManager()

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "MediaQoLManager")
    private var cancellables = Set<AnyCancellable>()

    @Published public var lastSentTrackKey: String? = nil
    @Published public var lastAudioRouteName: String = "iPhone"
    @Published public var isResolvingArt: Bool = false
    @Published public var activeMediaItem: ActiveMediaItem? = nil

    private var pollingTimer: Timer?
    private var lastSentStateHash: String = ""
    private var lastSentAt = Date.distantPast

    private init() {
        setupNowPlayingObservers()
        MPMusicPlayerController.systemMusicPlayer.beginGeneratingPlaybackNotifications()
        startPeriodicPolling()
    }

    deinit {
        pollingTimer?.invalidate()
        MPMusicPlayerController.systemMusicPlayer.endGeneratingPlaybackNotifications()
    }

    private func setupNowPlayingObservers() {
        // Observe local music player changes
        NotificationCenter.default.publisher(for: .MPMusicPlayerControllerNowPlayingItemDidChange)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.handleLocalNowPlayingChange()
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: .MPMusicPlayerControllerPlaybackStateDidChange)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.pollAndPushMediaState()
            }
            .store(in: &cancellables)

        // Observe audio route changes (e.g. AirPods connected/disconnected)
        NotificationCenter.default.publisher(for: AVAudioSession.routeChangeNotification)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.handleAudioRouteChange()
            }
            .store(in: &cancellables)
    }

    private func startPeriodicPolling() {
        pollingTimer?.invalidate()
        pollingTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            self?.pollAndPushMediaState()
        }
    }

    /**
     * Polls MPMusicPlayerController.systemMusicPlayer and pushes a live MediaStateSyncPayload
     * over Channel 0x05 whenever playing or when metadata changes.
     */
    public func pollAndPushMediaState(force: Bool = false) {
        guard BLEServerManager.shared.hasCompanionSubscriber else { return }
        let player = MPMusicPlayerController.systemMusicPlayer
        let isPlaying = player.playbackState == .playing
        let item = player.nowPlayingItem
        guard item != nil || isPlaying || !lastSentStateHash.isEmpty else { return }
        let title = item?.title ?? (isPlaying ? "Audio Playing" : "")
        let artist = item?.artist ?? ""
        let album = item?.albumTitle ?? ""
        let duration = item?.playbackDuration ?? 0
        let elapsed = player.currentPlaybackTime
        let volume = AVAudioSession.sharedInstance().outputVolume
        let route = getCurrentAudioRoute()
        let state = [title, artist, album, String(isPlaying), String(volume), route].joined(separator: "|")
        let now = Date()
        guard force || state != lastSentStateHash ||
            (isPlaying && now.timeIntervalSince(lastSentAt) >= 15) else { return }
        lastSentStateHash = state
        lastSentAt = now
        pushMediaStatePayload(
            title: title,
            artist: artist,
            album: album,
            isPlaying: isPlaying,
            playbackRate: isPlaying ? Float(player.currentPlaybackRate) : 0,
            elapsedSeconds: elapsed.isFinite ? max(0, elapsed) : 0,
            durationSeconds: duration.isFinite ? max(0, duration) : 0,
            volume: volume,
            audioRouteName: route,
            artworkBase64: ""
        )
    }

    private func pushMediaStatePayload(
        title: String,
        artist: String,
        album: String,
        isPlaying: Bool,
        playbackRate: Float,
        elapsedSeconds: Double,
        durationSeconds: Double,
        volume: Float,
        audioRouteName: String,
        artworkBase64: String
    ) {
        let trackKey = "\(artist)_\(title)"
        let payload = MediaStateSyncPayload(
            cmd: "MEDIA_STATE",
            title: title,
            artist: artist,
            album: album,
            isPlaying: isPlaying,
            playbackRate: playbackRate,
            elapsedSeconds: elapsedSeconds,
            durationSeconds: durationSeconds,
            volume: volume,
            audioRouteName: audioRouteName,
            artworkBase64: artworkBase64,
            trackKey: trackKey
        )

        do {
            let data = try JSONEncoder().encode(payload)
            BLEServerManager.shared.sendMediaQoLPayload(data)
            DispatchQueue.main.async {
                self.lastSentTrackKey = trackKey
                self.lastAudioRouteName = audioRouteName
                self.activeMediaItem = ActiveMediaItem(
                    title: title,
                    artist: artist,
                    album: album,
                    audioRoute: audioRouteName,
                    artworkBase64: artworkBase64.isEmpty ? nil : artworkBase64
                )
            }
            logger.debug("Pushed MediaStateSyncPayload over Channel 0x05: '\(title)' by '\(artist)' (isPlaying=\(isPlaying))")
        } catch {
            logger.error("Failed to encode MediaStateSyncPayload: \(error.localizedDescription)")
        }
    }

    private func handleLocalNowPlayingChange() {
        pollAndPushMediaState()
    }

    private func handleAudioRouteChange() {
        let currentRoute = getCurrentAudioRoute()
        DispatchQueue.main.async {
            self.lastAudioRouteName = currentRoute
        }
        logger.info("Audio route changed to: \(currentRoute)")
        pollAndPushMediaState()
    }

    /// Handles incoming reassembled payload on Channel 0x05 from Wear OS
    public func handleIncomingRequest(data: Data) {
        // 1. Check for MEDIA_CONTROL command from Watch
        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let cmd = json["cmd"] as? String {
            if cmd == "MEDIA_CONTROL" {
                let action = json["action"] as? String ?? ""
                logger.info("Received MEDIA_CONTROL action from Watch: \(action)")
                handleMediaControlAction(action)
                return
            }
        }

        // 2. Check for legacy FETCH_ART command
        do {
            let decoder = JSONDecoder()
            let command = try decoder.decode(MediaFetchArtCommand.self, from: data)

            logger.info("Received Channel 0x05 command: \(command.cmd ?? "unknown") for '\(command.title ?? "")' by '\(command.artist ?? "")'")

            guard command.cmd == "FETCH_ART" else {
                logger.warning("Unrecognized Channel 0x05 command: \(command.cmd ?? "")")
                return
            }

            let title = command.title ?? ""
            let artist = command.artist ?? ""
            let album = command.album

            Task { [weak self] in
                await self?.resolveAndPushMediaQoL(title: title, artist: artist, album: album, duration: 0.0)
            }
        } catch {
            logger.error("Failed to parse Channel 0x05 request JSON: \(error.localizedDescription)")
        }
    }

    private func handleMediaControlAction(_ action: String) {
        DispatchQueue.main.async {
            let player = MPMusicPlayerController.systemMusicPlayer
            switch action {
            case "TOGGLE_PLAY_PAUSE":
                if player.playbackState == .playing {
                    player.pause()
                } else {
                    player.play()
                }
            case "PLAY":
                player.play()
            case "PAUSE":
                player.pause()
            case "NEXT":
                player.skipToNextItem()
            case "PREV":
                player.skipToPreviousItem()
            case "VOL_UP", "VOL_DOWN":
                // Handled natively by iOS volume hardware
                break
            default:
                self.logger.warning("Unknown media control action: \(action)")
            }

            // Immediately poll and push updated state
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { [weak self] in
                self?.pollAndPushMediaState()
            }
        }
    }

    /**
     * Resolves artwork, audio route, and duration, then streams MediaQoLPayload over Channel 0x05.
     */
    public func resolveAndPushMediaQoL(
        title: String,
        artist: String,
        album: String?,
        duration: Double
    ) async {
        let trackKey = "\(artist)_\(title)"
        let routeName = getCurrentAudioRoute()

        DispatchQueue.main.async { [weak self] in
            self?.isResolvingArt = true
            self?.lastAudioRouteName = routeName
        }

        // 1. Resolve artwork
        let artworkData = await resolveArtwork(title: title, artist: artist, album: album)
        let base64Art = artworkData?.base64EncodedString() ?? ""

        // 2. Resolve duration if not provided
        var durationSec = duration
        if durationSec <= 0.0, let nowPlaying = MPMusicPlayerController.systemMusicPlayer.nowPlayingItem {
            durationSec = nowPlaying.playbackDuration
        }

        let payload = MediaQoLPayload(
            trackKey: trackKey,
            durationSeconds: durationSec,
            audioRouteName: routeName,
            artworkBase64: base64Art
        )

        // 3. Serialize and transmit over BLE Channel 0x05
        do {
            let encoder = JSONEncoder()
            let jsonData = try encoder.encode(payload)

            DispatchQueue.main.async { [weak self] in
                self?.lastSentTrackKey = trackKey
                self?.isResolvingArt = false
                self?.activeMediaItem = ActiveMediaItem(
                    title: title,
                    artist: artist,
                    album: album ?? "",
                    audioRoute: routeName,
                    artworkBase64: base64Art.isEmpty ? nil : base64Art
                )
            }

            BLEServerManager.shared.sendMediaQoLPayload(jsonData)
            logger.info("Pushed MediaQoLPayload over Channel 0x05 for '\(trackKey)' (art size=\(artworkData?.count ?? 0) B, route=\(routeName))")
        } catch {
            logger.error("Failed to encode MediaQoLPayload: \(error.localizedDescription)")
            DispatchQueue.main.async {
                self.isResolvingArt = false
            }
        }
    }

    /**
     * Resolves 96x96 compressed JPEG artwork data:
     * 1. Checks local MPMusicPlayerController nowPlayingItem.artwork
     * 2. Falls back to iTunes Search API query
     */
    public func resolveArtwork(title: String, artist: String, album: String?) async -> Data? {
        // Step 1: Check local system player artwork
        if let item = MPMusicPlayerController.systemMusicPlayer.nowPlayingItem,
           let artwork = item.artwork,
           let image = artwork.image(at: CGSize(width: 96, height: 96)) {
            logger.info("Found local system music player artwork for '\(title)'")
            return resizeAndCompress(image: image)
        }

        // Step 2: Query iTunes Search API fallback
        guard !title.isEmpty || !artist.isEmpty else { return nil }

        let term = "\(artist) \(title)".trimmingCharacters(in: .whitespacesAndNewlines)
        guard let encodedTerm = term.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed),
              let searchUrl = URL(string: "https://itunes.apple.com/search?term=\(encodedTerm)&entity=song&limit=1") else {
            return nil
        }

        do {
            logger.info("Querying iTunes Search API for artwork: \(term)")
            let (data, response) = try await URLSession.shared.data(from: searchUrl)
            guard let httpResponse = response as? HTTPURLResponse, httpResponse.statusCode == 200 else {
                logger.warning("iTunes search API returned non-200 HTTP status")
                return nil
            }

            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let results = json["results"] as? [[String: Any]],
                  let firstMatch = results.first else {
                logger.warning("iTunes search returned 0 results for '\(term)'")
                return nil
            }

            // Extract artwork URL (prefer 100x100 or 60x60)
            guard let artUrlString = (firstMatch["artworkUrl100"] as? String) ?? (firstMatch["artworkUrl60"] as? String),
                  let artUrl = URL(string: artUrlString) else {
                return nil
            }

            logger.info("Downloading album artwork from iTunes: \(artUrlString)")
            let (artData, artResp) = try await URLSession.shared.data(from: artUrl)
            guard let artHttpResp = artResp as? HTTPURLResponse, artHttpResp.statusCode == 200,
                  let image = UIImage(data: artData) else {
                return nil
            }

            return resizeAndCompress(image: image)
        } catch {
            logger.warning("iTunes artwork query failed: \(error.localizedDescription)")
            return nil
        }
    }

    /**
     * Resizes image to 96x96 and compresses to JPEG (quality: 0.65, targeting < 8KB).
     */
    public func resizeAndCompress(
        image: UIImage,
        targetSize: CGSize = CGSize(width: 96, height: 96),
        quality: CGFloat = 0.65
    ) -> Data? {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1.0 // 1:1 pixel grid for exact 96x96
        let renderer = UIGraphicsImageRenderer(size: targetSize, format: format)

        let resized = renderer.image { _ in
            image.draw(in: CGRect(origin: .zero, size: targetSize))
        }

        return resized.jpegData(compressionQuality: quality)
    }

    /**
     * Returns current audio route output port name (e.g. "AirPods Pro", "Speaker").
     */
    public func getCurrentAudioRoute() -> String {
        let route = AVAudioSession.sharedInstance().currentRoute
        if let port = route.outputs.first {
            return port.portName
        }
        return "iPhone Speaker"
    }

    /**
     * Generates a 96x96 test image for diagnostics.
     */
    public func generateMockArtworkImage() -> UIImage {
        let size = CGSize(width: 96, height: 96)
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { ctx in
            // Gradient background
            let colors = [UIColor.systemIndigo.cgColor, UIColor.systemPurple.cgColor] as CFArray
            let colorSpace = CGColorSpaceCreateDeviceRGB()
            let gradient = CGGradient(colorsSpace: colorSpace, colors: colors, locations: [0.0, 1.0])!
            ctx.cgContext.drawLinearGradient(
                gradient,
                start: CGPoint(x: 0, y: 0),
                end: CGPoint(x: 96, y: 96),
                options: []
            )

            // Music note icon
            let font = UIFont.systemFont(ofSize: 42, weight: .bold)
            let text = "🎵" as NSString
            let textAttributes: [NSAttributedString.Key: Any] = [
                .font: font
            ]
            let textSize = text.size(withAttributes: textAttributes)
            let rect = CGRect(
                x: (size.width - textSize.width) / 2,
                y: (size.height - textSize.height) / 2,
                width: textSize.width,
                height: textSize.height
            )
            text.draw(in: rect, withAttributes: textAttributes)
        }
    }

    /**
     * Diagnostic helper: Sends a mock MediaQoLPayload over Channel 0x05 to verify
     * watch reception, JSON deserialization, and bitmap decoding.
     */
    public func sendMockMediaQoLPayload(completion: @escaping (Result<String, Error>) -> Void) {
        let mockImage = generateMockArtworkImage()
        guard let jpegData = resizeAndCompress(image: mockImage) else {
            completion(.failure(NSError(domain: "WearBridge", code: -10, userInfo: [NSLocalizedDescriptionKey: "Failed to compress mock image"])))
            return
        }

        let routeName = getCurrentAudioRoute()
        let payload = MediaQoLPayload(
            trackKey: "WearBridge_TestTrack",
            durationSeconds: 214.5,
            audioRouteName: routeName,
            artworkBase64: jpegData.base64EncodedString()
        )

        do {
            let jsonData = try JSONEncoder().encode(payload)
            BLEServerManager.shared.sendMediaQoLPayload(jsonData)
            lastSentTrackKey = payload.trackKey
            completion(.success("Sent 96x96 artwork (\(jpegData.count) B, route: \(routeName))"))
        } catch {
            completion(.failure(error))
        }
    }
}

