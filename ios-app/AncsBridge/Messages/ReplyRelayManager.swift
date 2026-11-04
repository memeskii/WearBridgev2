import Foundation
import UIKit
import UserNotifications
import os.log
import Combine

/**
 * Configurable outbound reply relay modes.
 */
public enum ReplyRelayMode: String, CaseIterable, Identifiable {
    case webhook = "webhook"
    case shortcuts = "shortcuts"
    case deeplink = "deeplink"

    public static let deepLink = ReplyRelayMode.deeplink

    public var id: String { rawValue }

    public var displayName: String {
        switch self {
        case .webhook: return "Webhook Bridge (Silent Background)"
        case .shortcuts: return "iOS Shortcuts Automation"
        case .deeplink: return "Direct Deep-Link (WhatsApp/SMS)"
        }
    }
}

/**
 * Inbound message reply payload received from Wear OS over Channel 0x06.
 */
public struct MessageReplyPayload: Codable, Equatable {
    public let replyId: String
    public let threadKey: String
    public let appBundleId: String
    public let recipient: String
    public let senderName: String
    public let replyText: String
    public let ancsUid: Int64
    public let timestamp: Int64

    public init(
        replyId: String,
        threadKey: String,
        appBundleId: String,
        recipient: String,
        senderName: String,
        replyText: String,
        ancsUid: Int64,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.replyId = replyId
        self.threadKey = threadKey
        self.appBundleId = appBundleId
        self.recipient = recipient
        self.senderName = senderName
        self.replyText = replyText
        self.ancsUid = ancsUid
        self.timestamp = timestamp
    }
}

/**
 * Outbound ACK payload sent back to Wear OS over Channel 0x06.
 */
public struct MessageReplyAckPayload: Codable, Equatable {
    public let replyId: String
    public let status: String // "SENT", "QUEUED", "FAILED"
    public let detail: String

    public init(replyId: String, status: String, detail: String) {
        self.replyId = replyId
        self.status = status
        self.detail = detail
    }
}

/**
 * Synthetic message injection payload sent to Wear OS over Channel 0x06.
 */
public struct SyntheticMessagePayload: Codable {
    public let cmd: String
    public let appBundleId: String
    public let appDisplayName: String
    public let senderName: String
    public let senderIdentifier: String
    public let body: String
    public let timestamp: Int64

    public init(
        cmd: String = "INJECT_MESSAGE",
        appBundleId: String,
        appDisplayName: String,
        senderName: String,
        senderIdentifier: String,
        body: String,
        timestamp: Int64 = Int64(Date().timeIntervalSince1970 * 1000)
    ) {
        self.cmd = cmd
        self.appBundleId = appBundleId
        self.appDisplayName = appDisplayName
        self.senderName = senderName
        self.senderIdentifier = senderIdentifier
        self.body = body
        self.timestamp = timestamp
    }
}

public typealias RelayMode = ReplyRelayMode

/**
 * Manages outbound message replies received from Wear OS over Channel 0x06.
 *
 * Supports three relay modes:
 * 1. Webhook Bridge Mode (Silent Background): POSTs payload JSON to user-configured HTTP endpoint.
 * 2. iOS Shortcuts Automation Mode: Invokes a custom iOS Shortcut or posts an actionable notification when locked.
 * 3. Direct Deep-Link Mode: Opens WhatsApp or SMS app directly with pre-filled text.
 */
public class ReplyRelayManager: ObservableObject {

    public static let shared = ReplyRelayManager()

    private let logger = Logger(subsystem: "com.wearos.ancsbridge", category: "ReplyRelayManager")

    private let keyRelayMode = "wearbridge_reply_relay_mode"
    private let keyWebhookUrl = "wearbridge_reply_webhook_url"
    private let keyShortcutName = "wearbridge_reply_shortcut_name"

    @Published public var relayMode: ReplyRelayMode = .webhook
    @Published public var currentRelayMode: ReplyRelayMode = .webhook
    @Published public var webhookUrl: String = "http://localhost:8080/reply"
    @Published public var shortcutName: String = "WearBridgeReply"
    @Published public var lastRelayedReply: MessageReplyPayload? = nil
    @Published public var lastRelayStatus: String = "Idle"

    private init() {
        loadSettings()
    }

    private func loadSettings() {
        if let modeStr = UserDefaults.standard.string(forKey: keyRelayMode),
           let mode = ReplyRelayMode(rawValue: modeStr) {
            self.relayMode = mode
            self.currentRelayMode = mode
        }
        if let url = UserDefaults.standard.string(forKey: keyWebhookUrl), !url.isEmpty {
            self.webhookUrl = url
        }
        if let name = UserDefaults.standard.string(forKey: keyShortcutName), !name.isEmpty {
            self.shortcutName = name
        }
    }

    public func updateRelayMode(_ mode: ReplyRelayMode) {
        self.relayMode = mode
        self.currentRelayMode = mode
        UserDefaults.standard.set(mode.rawValue, forKey: keyRelayMode)
    }

    public func updateWebhookUrl(_ url: String) {
        self.webhookUrl = url
        UserDefaults.standard.set(url, forKey: keyWebhookUrl)
    }

    public func updateShortcutName(_ name: String) {
        self.shortcutName = name
        UserDefaults.standard.set(name, forKey: keyShortcutName)
    }

    /**
     * Handles an incoming Channel 0x06 payload from Wear OS.
     */
    public func handleIncomingReplyPacket(data: Data) {
        do {
            let decoder = JSONDecoder()
            let reply = try decoder.decode(MessageReplyPayload.self, from: data)

            logger.info("Received outbound message reply #\(reply.replyId) for '\(reply.recipient)' via \(reply.appBundleId): '\(reply.replyText)'")

            DispatchQueue.main.async {
                self.lastRelayedReply = reply
                self.lastRelayStatus = "Relaying reply..."
            }

            Task {
                await self.executeReplyRelay(reply: reply, rawData: data)
            }
        } catch {
            logger.error("Failed to decode Channel 0x06 reply payload: \(error.localizedDescription)")
        }
    }

    /**
     * Executes the reply relay according to current mode and sends back an ACK over Channel 0x06.
     */
    public func executeReplyRelay(reply: MessageReplyPayload, rawData: Data) async {
        let (status, detail): (String, String)

        switch self.relayMode {
        case .webhook:
            (status, detail) = await relayViaWebhook(reply: reply, rawData: rawData)
        case .shortcuts:
            (status, detail) = await relayViaShortcuts(reply: reply, rawData: rawData)
        case .deeplink:
            (status, detail) = await relayViaDeepLink(reply: reply)
        }

        DispatchQueue.main.async { [weak self] in
            self?.lastRelayStatus = "\(status): \(detail)"
        }

        // Send ACK back to watch over Channel 0x06
        let ack = MessageReplyAckPayload(replyId: reply.replyId, status: status, detail: detail)
        sendReplyAck(ack)
    }

    private func relayViaWebhook(reply: MessageReplyPayload, rawData: Data) async -> (String, String) {
        guard let url = URL(string: webhookUrl) else {
            return ("FAILED", "Invalid Webhook URL: \(webhookUrl)")
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = rawData
        request.timeoutInterval = 8.0

        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let httpResp = response as? HTTPURLResponse, (200...299).contains(httpResp.statusCode) {
                logger.info("Webhook bridge successfully relayed reply #\(reply.replyId) (HTTP \(httpResp.statusCode))")
                return ("SENT", "Relayed via Webhook (HTTP \(httpResp.statusCode))")
            } else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? -1
                logger.warning("Webhook bridge failed with HTTP status: \(code)")
                return ("FAILED", "Webhook returned HTTP \(code)")
            }
        } catch {
            logger.error("Webhook bridge request failed: \(error.localizedDescription)")
            return ("FAILED", "Webhook error: \(error.localizedDescription)")
        }
    }

    private func relayViaShortcuts(reply: MessageReplyPayload, rawData: Data) async -> (String, String) {
        let jsonString = String(data: rawData, encoding: .utf8) ?? ""
        let encodedJson = jsonString.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""
        let encodedName = shortcutName.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""

        let isAppActive = await MainActor.run {
            UIApplication.shared.applicationState == .active
        }

        if isAppActive {
            if let url = URL(string: "shortcuts://run-shortcut?name=\(encodedName)&input=text&text=\(encodedJson)") {
                await MainActor.run {
                    UIApplication.shared.open(url, options: [:], completionHandler: nil)
                }
                logger.info("Invoked iOS Shortcut '\(self.shortcutName)' directly while app is active")
                return ("SENT", "Triggered Shortcut '\(shortcutName)'")
            } else {
                return ("FAILED", "Failed to construct Shortcut URL")
            }
        } else {
            // Queue high-priority actionable notification when locked / backgrounded
            let content = UNMutableNotificationContent()
            content.title = "Outbound Reply: \(reply.senderName)"
            content.subtitle = "Target: \(reply.recipient)"
            content.body = "\(reply.replyText)\n(Tap to trigger Shortcut '\(shortcutName)')"
            content.sound = .default
            content.userInfo = ["replyId": reply.replyId, "shortcutJson": jsonString]

            let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 0.5, repeats: false)
            let req = UNNotificationRequest(identifier: "reply_shortcut_\(reply.replyId)", content: content, trigger: trigger)

            do {
                try await UNUserNotificationCenter.current().add(req)
                logger.info("Queued actionable Shortcut notification for locked iPhone")
                return ("QUEUED", "Queued Actionable Notification for Shortcut")
            } catch {
                logger.error("Failed to post shortcut notification: \(error.localizedDescription)")
                return ("FAILED", "Notification trigger error: \(error.localizedDescription)")
            }
        }
    }

    private func relayViaDeepLink(reply: MessageReplyPayload) async -> (String, String) {
        let isWhatsApp = reply.appBundleId.lowercased().contains("whatsapp")
        let encodedText = reply.replyText.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? ""

        let urlString: String
        if isWhatsApp {
            let digitsOnly = reply.recipient.components(separatedBy: CharacterSet.decimalDigits.inverted).joined()
            urlString = "whatsapp://send?phone=\(digitsOnly)&text=\(encodedText)"
        } else {
            urlString = "sms:\(reply.recipient)&body=\(encodedText)"
        }

        guard let url = URL(string: urlString) else {
            return ("FAILED", "Invalid deep-link URL for \(reply.appBundleId)")
        }

        let opened = await MainActor.run {
            if UIApplication.shared.canOpenURL(url) {
                UIApplication.shared.open(url, options: [:], completionHandler: nil)
                return true
            }
            return false
        }

        if opened {
            logger.info("Opened deep-link successfully: \(urlString)")
            return ("SENT", "Opened \(isWhatsApp ? "WhatsApp" : "SMS") deep-link")
        } else {
            logger.warning("Cannot open deep-link URL: \(urlString)")
            return ("FAILED", "Application cannot open deep-link scheme")
        }
    }

    /**
     * Transmits a MessageReplyAckPayload back to the watch over Channel 0x06.
     */
    public func sendReplyAck(_ ack: MessageReplyAckPayload) {
        do {
            let data = try JSONEncoder().encode(ack)
            BLEServerManager.shared.sendMessageReplyData(data)
            logger.info("Sent Reply ACK #\(ack.replyId) over Channel 0x06 (status: \(ack.status))")
        } catch {
            logger.error("Failed to serialize Reply ACK: \(error.localizedDescription)")
        }
    }

    /**
     * Injects a synthetic / mock incoming message to the Watch Room inbox over Channel 0x06.
     */
    public func sendSyntheticMessageToWatch(
        appBundleId: String = "net.whatsapp.WhatsApp",
        appDisplayName: String = "WhatsApp",
        senderName: String = "Sarah Connor",
        senderIdentifier: String = "+1 (555) 019-2834",
        body: String = "Are you available for a quick sync?",
        completion: @escaping (Result<String, Error>) -> Void
    ) {
        let payload = SyntheticMessagePayload(
            cmd: "INJECT_MESSAGE",
            appBundleId: appBundleId,
            appDisplayName: appDisplayName,
            senderName: senderName,
            senderIdentifier: senderIdentifier,
            body: body
        )

        do {
            let data = try JSONEncoder().encode(payload)
            BLEServerManager.shared.sendMessageReplyData(data)
            logger.info("Injected synthetic message for '\(senderName)' over Channel 0x06")
            completion(.success("Injected '\(body)' (\(appDisplayName))"))
        } catch {
            logger.error("Failed to serialize synthetic message: \(error.localizedDescription)")
            completion(.failure(error))
        }
    }
}

