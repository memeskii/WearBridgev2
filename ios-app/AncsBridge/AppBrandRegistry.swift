import SwiftUI

/// Brand metadata representing an iOS application bundle.
public struct BrandInfo: Equatable {
    public let bundleId: String
    public let displayName: String
    public let brandColor: Color
    public let brandColorHex: String
    public let accentColor: Color
    public let sfSymbolName: String
    public let category: String

    public init(
        bundleId: String,
        displayName: String,
        brandColorHex: String,
        accentColorHex: String? = nil,
        sfSymbolName: String,
        category: String
    ) {
        self.bundleId = bundleId
        self.displayName = displayName
        self.brandColorHex = brandColorHex
        self.brandColor = Color(hex: brandColorHex)
        self.accentColor = Color(hex: accentColorHex ?? brandColorHex)
        self.sfSymbolName = sfSymbolName
        self.category = category
    }
}

/// Centralized registry mapping iOS bundle identifiers to brand names, brand colors,
/// SF Symbols, and category metadata for both the iOS companion and watch bridge.
public struct AppBrandRegistry {

    private static let registry: [String: BrandInfo] = [
        // TikTok (All Regional Variants)
        "com.zhiliaoapp.musically": BrandInfo(
            bundleId: "com.zhiliaoapp.musically",
            displayName: "TikTok",
            brandColorHex: "#00F2FE",
            accentColorHex: "#FE2C55",
            sfSymbolName: "music.note",
            category: "Social"
        ),
        "com.ss.iphone.ugc.Ame": BrandInfo(
            bundleId: "com.ss.iphone.ugc.Ame",
            displayName: "TikTok",
            brandColorHex: "#00F2FE",
            accentColorHex: "#FE2C55",
            sfSymbolName: "music.note",
            category: "Social"
        ),
        "com.bd.iphone.tiktok": BrandInfo(
            bundleId: "com.bd.iphone.tiktok",
            displayName: "TikTok",
            brandColorHex: "#00F2FE",
            accentColorHex: "#FE2C55",
            sfSymbolName: "music.note",
            category: "Social"
        ),

        // Core Messaging & Social Media
        "net.whatsapp.WhatsApp": BrandInfo(
            bundleId: "net.whatsapp.WhatsApp",
            displayName: "WhatsApp",
            brandColorHex: "#25D366",
            accentColorHex: "#128C7E",
            sfSymbolName: "message.fill",
            category: "Messaging"
        ),
        "net.whatsapp.WhatsAppSMB": BrandInfo(
            bundleId: "net.whatsapp.WhatsAppSMB",
            displayName: "WhatsApp Business",
            brandColorHex: "#25D366",
            accentColorHex: "#128C7E",
            sfSymbolName: "message.badge.filled.fill",
            category: "Messaging"
        ),
        "com.burbn.instagram": BrandInfo(
            bundleId: "com.burbn.instagram",
            displayName: "Instagram",
            brandColorHex: "#E1306C",
            accentColorHex: "#FD1D1D",
            sfSymbolName: "camera.fill",
            category: "Social"
        ),
        "com.burbn.barcelona": BrandInfo(
            bundleId: "com.burbn.barcelona",
            displayName: "Threads",
            brandColorHex: "#FFFFFF",
            accentColorHex: "#757575",
            sfSymbolName: "at",
            category: "Social"
        ),
        "ph.telegra.Telegraph": BrandInfo(
            bundleId: "ph.telegra.Telegraph",
            displayName: "Telegram",
            brandColorHex: "#2AABEE",
            accentColorHex: "#229ED9",
            sfSymbolName: "paperplane.fill",
            category: "Messaging"
        ),
        "org.telegram.messenger": BrandInfo(
            bundleId: "org.telegram.messenger",
            displayName: "Telegram",
            brandColorHex: "#2AABEE",
            accentColorHex: "#229ED9",
            sfSymbolName: "paperplane.fill",
            category: "Messaging"
        ),
        "com.facebook.Messenger": BrandInfo(
            bundleId: "com.facebook.Messenger",
            displayName: "Messenger",
            brandColorHex: "#00B2FF",
            accentColorHex: "#006AFF",
            sfSymbolName: "bolt.horizontal.fill",
            category: "Messaging"
        ),
        "com.facebook.Facebook": BrandInfo(
            bundleId: "com.facebook.Facebook",
            displayName: "Facebook",
            brandColorHex: "#1877F2",
            accentColorHex: "#0E5AC4",
            sfSymbolName: "person.2.fill",
            category: "Social"
        ),
        "com.hammerandchisel.discord": BrandInfo(
            bundleId: "com.hammerandchisel.discord",
            displayName: "Discord",
            brandColorHex: "#5865F2",
            accentColorHex: "#4752C4",
            sfSymbolName: "bubble.left.and.bubble.right.fill",
            category: "Community"
        ),
        "com.toyopagroup.picaboo": BrandInfo(
            bundleId: "com.toyopagroup.picaboo",
            displayName: "Snapchat",
            brandColorHex: "#FFFC00",
            accentColorHex: "#E5E200",
            sfSymbolName: "bell.fill",
            category: "Social"
        ),
        "com.atebits.Tweetie2": BrandInfo(
            bundleId: "com.atebits.Tweetie2",
            displayName: "X",
            brandColorHex: "#FFFFFF",
            accentColorHex: "#14171A",
            sfSymbolName: "xmark",
            category: "Social"
        ),
        "com.reddit.Reddit": BrandInfo(
            bundleId: "com.reddit.Reddit",
            displayName: "Reddit",
            brandColorHex: "#FF4500",
            accentColorHex: "#D93A00",
            sfSymbolName: "bubble.middle.bottom.fill",
            category: "Community"
        ),
        "com.linkedin.LinkedIn": BrandInfo(
            bundleId: "com.linkedin.LinkedIn",
            displayName: "LinkedIn",
            brandColorHex: "#0A66C2",
            accentColorHex: "#084E96",
            sfSymbolName: "briefcase.fill",
            category: "Professional"
        ),
        "tv.twitch": BrandInfo(
            bundleId: "tv.twitch",
            displayName: "Twitch",
            brandColorHex: "#9146FF",
            accentColorHex: "#772CE8",
            sfSymbolName: "tv.fill",
            category: "Entertainment"
        ),
        "com.bereal.ft": BrandInfo(
            bundleId: "com.bereal.ft",
            displayName: "BeReal",
            brandColorHex: "#FFFFFF",
            accentColorHex: "#212121",
            sfSymbolName: "camera.viewfinder",
            category: "Social"
        ),

        // Music, Video & Entertainment
        "com.spotify.client": BrandInfo(
            bundleId: "com.spotify.client",
            displayName: "Spotify",
            brandColorHex: "#1DB954",
            accentColorHex: "#169C46",
            sfSymbolName: "waveform",
            category: "Music"
        ),
        "com.google.ios.youtube": BrandInfo(
            bundleId: "com.google.ios.youtube",
            displayName: "YouTube",
            brandColorHex: "#FF0000",
            accentColorHex: "#CC0000",
            sfSymbolName: "play.rectangle.fill",
            category: "Video"
        ),
        "com.google.ios.youtubemusic": BrandInfo(
            bundleId: "com.google.ios.youtubemusic",
            displayName: "YT Music",
            brandColorHex: "#FF0000",
            accentColorHex: "#CC0000",
            sfSymbolName: "music.note.tv.fill",
            category: "Music"
        ),
        "com.anghami.anghami": BrandInfo(
            bundleId: "com.anghami.anghami",
            displayName: "Anghami",
            brandColorHex: "#A855F7",
            accentColorHex: "#7E22CE",
            sfSymbolName: "headphones",
            category: "Music"
        ),
        "com.soundcloud.TouchApp": BrandInfo(
            bundleId: "com.soundcloud.TouchApp",
            displayName: "SoundCloud",
            brandColorHex: "#FF5500",
            accentColorHex: "#CC4400",
            sfSymbolName: "cloud.fill",
            category: "Music"
        ),
        "com.netflix.Netflix": BrandInfo(
            bundleId: "com.netflix.Netflix",
            displayName: "Netflix",
            brandColorHex: "#E50914",
            accentColorHex: "#B20710",
            sfSymbolName: "film.fill",
            category: "Entertainment"
        ),

        // Daily Utilities, Delivery & Productivity
        "com.google.Gmail": BrandInfo(
            bundleId: "com.google.Gmail",
            displayName: "Gmail",
            brandColorHex: "#EA4335",
            accentColorHex: "#C5221F",
            sfSymbolName: "envelope.fill",
            category: "Productivity"
        ),
        "com.microsoft.Office.Outlook": BrandInfo(
            bundleId: "com.microsoft.Office.Outlook",
            displayName: "Outlook",
            brandColorHex: "#0078D4",
            accentColorHex: "#005A9E",
            sfSymbolName: "envelope.badge.fill",
            category: "Productivity"
        ),
        "com.ubercab.UberClient": BrandInfo(
            bundleId: "com.ubercab.UberClient",
            displayName: "Uber",
            brandColorHex: "#FFFFFF",
            accentColorHex: "#1F1F1F",
            sfSymbolName: "car.fill",
            category: "Navigation"
        ),
        "com.ubercab.UberEats": BrandInfo(
            bundleId: "com.ubercab.UberEats",
            displayName: "Uber Eats",
            brandColorHex: "#06C167",
            accentColorHex: "#048C4A",
            sfSymbolName: "bag.fill",
            category: "Delivery"
        ),
        "com.didiglobal.passenger": BrandInfo(
            bundleId: "com.didiglobal.passenger",
            displayName: "DiDi",
            brandColorHex: "#FF7733",
            accentColorHex: "#D95514",
            sfSymbolName: "car.2.fill",
            category: "Navigation"
        ),
        "com.talabat.Talabat": BrandInfo(
            bundleId: "com.talabat.Talabat",
            displayName: "Talabat",
            brandColorHex: "#FF5A00",
            accentColorHex: "#D44800",
            sfSymbolName: "bag.fill",
            category: "Delivery"
        ),
        "com.instabug.instashop": BrandInfo(
            bundleId: "com.instabug.instashop",
            displayName: "InstaShop",
            brandColorHex: "#E60023",
            accentColorHex: "#B3001B",
            sfSymbolName: "cart.fill",
            category: "Delivery"
        ),
        "com.truesoftware.TrueCallerOther": BrandInfo(
            bundleId: "com.truesoftware.TrueCallerOther",
            displayName: "Truecaller",
            brandColorHex: "#0087FF",
            accentColorHex: "#0060B8",
            sfSymbolName: "phone.badge.checkmark",
            category: "Utility"
        ),

        // Core Native iOS System Apps
        "com.apple.MobileSMS": BrandInfo(
            bundleId: "com.apple.MobileSMS",
            displayName: "iMessage",
            brandColorHex: "#34C759",
            accentColorHex: "#28A745",
            sfSymbolName: "message.fill",
            category: "System"
        ),
        "com.apple.mobilephone": BrandInfo(
            bundleId: "com.apple.mobilephone",
            displayName: "Phone",
            brandColorHex: "#34C759",
            accentColorHex: "#28A745",
            sfSymbolName: "phone.fill",
            category: "System"
        ),
        "com.apple.facetime": BrandInfo(
            bundleId: "com.apple.facetime",
            displayName: "FaceTime",
            brandColorHex: "#30D158",
            accentColorHex: "#249D42",
            sfSymbolName: "video.fill",
            category: "System"
        ),
        "com.apple.mobilemail": BrandInfo(
            bundleId: "com.apple.mobilemail",
            displayName: "Apple Mail",
            brandColorHex: "#007AFF",
            accentColorHex: "#0056B3",
            sfSymbolName: "mail.fill",
            category: "System"
        ),
        "com.apple.mobilecal": BrandInfo(
            bundleId: "com.apple.mobilecal",
            displayName: "Calendar",
            brandColorHex: "#FF3B30",
            accentColorHex: "#C82319",
            sfSymbolName: "calendar",
            category: "System"
        ),
        "com.apple.reminders": BrandInfo(
            bundleId: "com.apple.reminders",
            displayName: "Reminders",
            brandColorHex: "#FF9500",
            accentColorHex: "#C77300",
            sfSymbolName: "checklist",
            category: "System"
        ),
        "com.apple.Passbook": BrandInfo(
            bundleId: "com.apple.Passbook",
            displayName: "Apple Wallet",
            brandColorHex: "#F59E0B",
            accentColorHex: "#D97706",
            sfSymbolName: "wallet.pass.fill",
            category: "System"
        ),
        "com.apple.Health": BrandInfo(
            bundleId: "com.apple.Health",
            displayName: "Apple Health",
            brandColorHex: "#FF2D55",
            accentColorHex: "#CC1E3E",
            sfSymbolName: "heart.fill",
            category: "System"
        ),
        "com.apple.Fitness": BrandInfo(
            bundleId: "com.apple.Fitness",
            displayName: "Fitness",
            brandColorHex: "#A4FF00",
            accentColorHex: "#7ABF00",
            sfSymbolName: "flame.fill",
            category: "System"
        ),
        "com.apple.Music": BrandInfo(
            bundleId: "com.apple.Music",
            displayName: "Apple Music",
            brandColorHex: "#FA243C",
            accentColorHex: "#C7152B",
            sfSymbolName: "music.note",
            category: "System"
        ),
        "com.apple.podcasts": BrandInfo(
            bundleId: "com.apple.podcasts",
            displayName: "Podcasts",
            brandColorHex: "#9933FF",
            accentColorHex: "#7711CC",
            sfSymbolName: "antenna.radiowaves.left.and.right",
            category: "System"
        )
    ]

    /// Resolves brand metadata by iOS bundle ID with deterministic fallback.
    public static func resolve(_ bundleId: String?) -> BrandInfo {
        guard let id = bundleId, !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return fallback(for: "Unknown")
        }

        if let exact = registry[id] {
            return exact
        }

        // Fuzzy search by last component
        let lowerId = id.lowercased()
        for (key, info) in registry {
            let simpleKey = key.components(separatedBy: ".").last?.lowercased() ?? ""
            if !simpleKey.isEmpty && (lowerId.contains(simpleKey) || lowerId.hasSuffix(simpleKey)) {
                return BrandInfo(
                    bundleId: id,
                    displayName: info.displayName,
                    brandColorHex: info.brandColorHex,
                    sfSymbolName: info.sfSymbolName,
                    category: info.category
                )
            }
        }

        let name = id.components(separatedBy: ".").last?.capitalized ?? id
        return fallback(for: id, displayName: name)
    }

    public static func isRegistered(_ bundleId: String) -> BooleanLiteralType {
        return registry[bundleId] != nil
    }

    private static func fallback(for bundleId: String, displayName: String = "App") -> BrandInfo {
        return BrandInfo(
            bundleId: bundleId,
            displayName: displayName,
            brandColorHex: "#7C4DFF",
            sfSymbolName: "app.fill",
            category: "Application"
        )
    }
}

// Helper extension to initialize SwiftUI Color from Hex
extension Color {
    init(hex: String) {
        let hexClean = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hexClean).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hexClean.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: // RGB (24-bit)
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: // ARGB (32-bit)
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (255, 124, 77, 255)
        }
        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}

