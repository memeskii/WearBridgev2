package com.wearos.ancsbridge.ancs

import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import com.wearos.ancsbridge.R
import java.util.Locale

/**
 * Brand metadata representing an iOS application bundle.
 */
data class BrandMetadata(
    val bundleId: String,
    val displayName: String,
    @ColorInt val primaryColor: Int,
    @ColorInt val accentColor: Int = primaryColor,
    @DrawableRes val iconRes: Int,
    val monogram: String = ""
)

/**
 * Centralized registry mapping iOS bundle identifiers to official brand names,
 * brand accent colors, custom Android vector drawables, and monogram fallbacks.
 */
object AppBrandRegistry {

    const val BUNDLE_TIKTOK_GLOBAL = "com.zhiliaoapp.musically"
    const val BUNDLE_TIKTOK_AME = "com.ss.iphone.ugc.Ame"
    const val BUNDLE_TIKTOK_BD = "com.bd.iphone.tiktok"
    const val BUNDLE_WHATSAPP = "net.whatsapp.WhatsApp"
    const val BUNDLE_WHATSAPP_SMB = "net.whatsapp.WhatsAppSMB"
    const val BUNDLE_INSTAGRAM = "com.burbn.instagram"
    const val BUNDLE_THREADS = "com.burbn.barcelona"
    const val BUNDLE_TELEGRAM = "ph.telegra.Telegraph"
    const val BUNDLE_TELEGRAM_ORG = "org.telegram.messenger"
    const val BUNDLE_MESSENGER = "com.facebook.Messenger"
    const val BUNDLE_FACEBOOK = "com.facebook.Facebook"
    const val BUNDLE_DISCORD = "com.hammerandchisel.discord"
    const val BUNDLE_SNAPCHAT = "com.toyopagroup.picaboo"
    const val BUNDLE_X_TWITTER = "com.atebits.Tweetie2"
    const val BUNDLE_REDDIT = "com.reddit.Reddit"
    const val BUNDLE_LINKEDIN = "com.linkedin.LinkedIn"
    const val BUNDLE_TWITCH = "tv.twitch"
    const val BUNDLE_BEREAL = "com.bereal.ft"
    const val BUNDLE_SPOTIFY = "com.spotify.client"
    const val BUNDLE_YOUTUBE = "com.google.ios.youtube"
    const val BUNDLE_MUSIC = "com.apple.Music"
    const val BUNDLE_PODCASTS = "com.apple.podcasts"
    const val BUNDLE_VIDEO = "com.apple.tv"
    const val BUNDLE_MAIL = "com.apple.mobilemail"
    const val BUNDLE_PHONE = "com.apple.mobilephone"
    const val BUNDLE_FACETIME = "com.apple.facetime"
    const val BUNDLE_CALENDAR = "com.apple.mobilecal"
    const val BUNDLE_WALLET = "com.apple.Passbook"
    const val BUNDLE_HEALTH = "com.apple.Health"
    const val BUNDLE_IMESSAGE = "com.apple.MobileSMS"

    fun parseHexColor(hex: String): Int {
        val clean = if (hex.startsWith("#")) hex.substring(1) else hex
        val colorLong = clean.toLong(16)
        return if (clean.length <= 6) {
            (0xFF000000 or colorLong).toInt()
        } else {
            colorLong.toInt()
        }
    }


    private val fallbackPalette = intArrayOf(
        parseHexColor("#4F46E5"), // Indigo
        parseHexColor("#0EA5E9"), // Sky
        parseHexColor("#10B981"), // Emerald
        parseHexColor("#F59E0B"), // Amber
        parseHexColor("#EF4444"), // Red
        parseHexColor("#8B5CF6"), // Violet
        parseHexColor("#EC4899"), // Pink
        parseHexColor("#14B8A6"), // Teal
        parseHexColor("#F97316"), // Orange
        parseHexColor("#06B6D4")  // Cyan
    )

    private val registry: Map<String, BrandMetadata> = mapOf(
        // TikTok (All Regional Variants)
        "com.zhiliaoapp.musically" to BrandMetadata(
            bundleId = "com.zhiliaoapp.musically",
            displayName = "TikTok",
            primaryColor = parseHexColor("#00F2FE"),
            accentColor = parseHexColor("#FE2C55"),
            iconRes = R.drawable.ic_app_tiktok,
            monogram = "TT"
        ),
        "com.ss.iphone.ugc.Ame" to BrandMetadata(
            bundleId = "com.ss.iphone.ugc.Ame",
            displayName = "TikTok",
            primaryColor = parseHexColor("#00F2FE"),
            accentColor = parseHexColor("#FE2C55"),
            iconRes = R.drawable.ic_app_tiktok,
            monogram = "TT"
        ),
        "com.bd.iphone.tiktok" to BrandMetadata(
            bundleId = "com.bd.iphone.tiktok",
            displayName = "TikTok",
            primaryColor = parseHexColor("#00F2FE"),
            accentColor = parseHexColor("#FE2C55"),
            iconRes = R.drawable.ic_app_tiktok,
            monogram = "TT"
        ),

        // Core Messaging & Social Media
        "net.whatsapp.WhatsApp" to BrandMetadata(
            bundleId = "net.whatsapp.WhatsApp",
            displayName = "WhatsApp",
            primaryColor = parseHexColor("#25D366"),
            accentColor = parseHexColor("#128C7E"),
            iconRes = R.drawable.ic_app_whatsapp,
            monogram = "WA"
        ),
        "net.whatsapp.WhatsAppSMB" to BrandMetadata(
            bundleId = "net.whatsapp.WhatsAppSMB",
            displayName = "WhatsApp Business",
            primaryColor = parseHexColor("#25D366"),
            accentColor = parseHexColor("#128C7E"),
            iconRes = R.drawable.ic_app_whatsapp,
            monogram = "WB"
        ),
        "com.burbn.instagram" to BrandMetadata(
            bundleId = "com.burbn.instagram",
            displayName = "Instagram",
            primaryColor = parseHexColor("#E1306C"),
            accentColor = parseHexColor("#FD1D1D"),
            iconRes = R.drawable.ic_app_instagram,
            monogram = "IG"
        ),
        "com.burbn.barcelona" to BrandMetadata(
            bundleId = "com.burbn.barcelona",
            displayName = "Threads",
            primaryColor = parseHexColor("#FFFFFF"),
            accentColor = parseHexColor("#757575"),
            iconRes = R.drawable.ic_app_threads,
            monogram = "TH"
        ),
        "ph.telegra.Telegraph" to BrandMetadata(
            bundleId = "ph.telegra.Telegraph",
            displayName = "Telegram",
            primaryColor = parseHexColor("#2AABEE"),
            accentColor = parseHexColor("#229ED9"),
            iconRes = R.drawable.ic_app_telegram,
            monogram = "TG"
        ),
        "org.telegram.messenger" to BrandMetadata(
            bundleId = "org.telegram.messenger",
            displayName = "Telegram",
            primaryColor = parseHexColor("#2AABEE"),
            accentColor = parseHexColor("#229ED9"),
            iconRes = R.drawable.ic_app_telegram,
            monogram = "TG"
        ),
        "org.telegram.Telegram" to BrandMetadata(
            bundleId = "org.telegram.Telegram",
            displayName = "Telegram",
            primaryColor = parseHexColor("#2AABEE"),
            accentColor = parseHexColor("#229ED9"),
            iconRes = R.drawable.ic_app_telegram,
            monogram = "TG"
        ),
        "com.facebook.Messenger" to BrandMetadata(
            bundleId = "com.facebook.Messenger",
            displayName = "Messenger",
            primaryColor = parseHexColor("#00B2FF"),
            accentColor = parseHexColor("#006AFF"),
            iconRes = R.drawable.ic_app_messenger,
            monogram = "MS"
        ),
        "com.facebook.Facebook" to BrandMetadata(
            bundleId = "com.facebook.Facebook",
            displayName = "Facebook",
            primaryColor = parseHexColor("#1877F2"),
            accentColor = parseHexColor("#0E5AC4"),
            iconRes = R.drawable.ic_app_facebook,
            monogram = "FB"
        ),
        "com.hammerandchisel.discord" to BrandMetadata(
            bundleId = "com.hammerandchisel.discord",
            displayName = "Discord",
            primaryColor = parseHexColor("#5865F2"),
            accentColor = parseHexColor("#4752C4"),
            iconRes = R.drawable.ic_app_discord,
            monogram = "DC"
        ),
        "com.toyopagroup.picaboo" to BrandMetadata(
            bundleId = "com.toyopagroup.picaboo",
            displayName = "Snapchat",
            primaryColor = parseHexColor("#FFFC00"),
            accentColor = parseHexColor("#E5E200"),
            iconRes = R.drawable.ic_app_snapchat,
            monogram = "SC"
        ),
        "com.atebits.Tweetie2" to BrandMetadata(
            bundleId = "com.atebits.Tweetie2",
            displayName = "X",
            primaryColor = parseHexColor("#FFFFFF"),
            accentColor = parseHexColor("#14171A"),
            iconRes = R.drawable.ic_app_x_twitter,
            monogram = "X"
        ),
        "com.reddit.Reddit" to BrandMetadata(
            bundleId = "com.reddit.Reddit",
            displayName = "Reddit",
            primaryColor = parseHexColor("#FF4500"),
            accentColor = parseHexColor("#D93A00"),
            iconRes = R.drawable.ic_app_reddit,
            monogram = "RD"
        ),
        "com.linkedin.LinkedIn" to BrandMetadata(
            bundleId = "com.linkedin.LinkedIn",
            displayName = "LinkedIn",
            primaryColor = parseHexColor("#0A66C2"),
            accentColor = parseHexColor("#084E96"),
            iconRes = R.drawable.ic_app_linkedin,
            monogram = "IN"
        ),
        "tv.twitch" to BrandMetadata(
            bundleId = "tv.twitch",
            displayName = "Twitch",
            primaryColor = parseHexColor("#9146FF"),
            accentColor = parseHexColor("#772CE8"),
            iconRes = R.drawable.ic_app_twitch,
            monogram = "TW"
        ),
        "com.bereal.ft" to BrandMetadata(
            bundleId = "com.bereal.ft",
            displayName = "BeReal",
            primaryColor = parseHexColor("#FFFFFF"),
            accentColor = parseHexColor("#212121"),
            iconRes = R.drawable.ic_app_bereal,
            monogram = "BR"
        ),

        // Music, Video & Entertainment
        "com.spotify.client" to BrandMetadata(
            bundleId = "com.spotify.client",
            displayName = "Spotify",
            primaryColor = parseHexColor("#1DB954"),
            accentColor = parseHexColor("#169C46"),
            iconRes = R.drawable.ic_app_spotify,
            monogram = "SP"
        ),
        "com.google.ios.youtube" to BrandMetadata(
            bundleId = "com.google.ios.youtube",
            displayName = "YouTube",
            primaryColor = parseHexColor("#FF0000"),
            accentColor = parseHexColor("#CC0000"),
            iconRes = R.drawable.ic_app_youtube,
            monogram = "YT"
        ),
        "com.google.ios.youtubemusic" to BrandMetadata(
            bundleId = "com.google.ios.youtubemusic",
            displayName = "YT Music",
            primaryColor = parseHexColor("#FF0000"),
            accentColor = parseHexColor("#CC0000"),
            iconRes = R.drawable.ic_app_youtube,
            monogram = "YM"
        ),
        "com.anghami.anghami" to BrandMetadata(
            bundleId = "com.anghami.anghami",
            displayName = "Anghami",
            primaryColor = parseHexColor("#A855F7"),
            accentColor = parseHexColor("#7E22CE"),
            iconRes = R.drawable.ic_app_music,
            monogram = "AG"
        ),
        "com.soundcloud.TouchApp" to BrandMetadata(
            bundleId = "com.soundcloud.TouchApp",
            displayName = "SoundCloud",
            primaryColor = parseHexColor("#FF5500"),
            accentColor = parseHexColor("#CC4400"),
            iconRes = R.drawable.ic_app_music,
            monogram = "SC"
        ),
        "com.netflix.Netflix" to BrandMetadata(
            bundleId = "com.netflix.Netflix",
            displayName = "Netflix",
            primaryColor = parseHexColor("#E50914"),
            accentColor = parseHexColor("#B20710"),
            iconRes = R.drawable.ic_app_video,
            monogram = "NF"
        ),

        // Daily Utilities, Delivery & Productivity
        "com.google.Gmail" to BrandMetadata(
            bundleId = "com.google.Gmail",
            displayName = "Gmail",
            primaryColor = parseHexColor("#EA4335"),
            accentColor = parseHexColor("#C5221F"),
            iconRes = R.drawable.ic_app_mail,
            monogram = "GM"
        ),
        "com.microsoft.Office.Outlook" to BrandMetadata(
            bundleId = "com.microsoft.Office.Outlook",
            displayName = "Outlook",
            primaryColor = parseHexColor("#0078D4"),
            accentColor = parseHexColor("#005A9E"),
            iconRes = R.drawable.ic_app_mail,
            monogram = "OL"
        ),
        "com.ubercab.UberClient" to BrandMetadata(
            bundleId = "com.ubercab.UberClient",
            displayName = "Uber",
            primaryColor = parseHexColor("#FFFFFF"),
            accentColor = parseHexColor("#1F1F1F"),
            iconRes = R.drawable.ic_rideshare,
            monogram = "UB"
        ),
        "com.ubercab.UberEats" to BrandMetadata(
            bundleId = "com.ubercab.UberEats",
            displayName = "Uber Eats",
            primaryColor = parseHexColor("#06C167"),
            accentColor = parseHexColor("#048C4A"),
            iconRes = R.drawable.ic_rideshare,
            monogram = "UE"
        ),
        "com.didiglobal.passenger" to BrandMetadata(
            bundleId = "com.didiglobal.passenger",
            displayName = "DiDi",
            primaryColor = parseHexColor("#FF7733"),
            accentColor = parseHexColor("#D95514"),
            iconRes = R.drawable.ic_rideshare,
            monogram = "DD"
        ),
        "com.talabat.Talabat" to BrandMetadata(
            bundleId = "com.talabat.Talabat",
            displayName = "Talabat",
            primaryColor = parseHexColor("#FF5A00"),
            accentColor = parseHexColor("#D44800"),
            iconRes = R.drawable.ic_shopping,
            monogram = "TL"
        ),
        "com.instabug.instashop" to BrandMetadata(
            bundleId = "com.instabug.instashop",
            displayName = "InstaShop",
            primaryColor = parseHexColor("#E60023"),
            accentColor = parseHexColor("#B3001B"),
            iconRes = R.drawable.ic_shopping,
            monogram = "IS"
        ),
        "com.truesoftware.TrueCallerOther" to BrandMetadata(
            bundleId = "com.truesoftware.TrueCallerOther",
            displayName = "Truecaller",
            primaryColor = parseHexColor("#0087FF"),
            accentColor = parseHexColor("#0060B8"),
            iconRes = R.drawable.ic_app_phone,
            monogram = "TC"
        ),

        // Core Native iOS System Apps
        "com.apple.MobileSMS" to BrandMetadata(
            bundleId = "com.apple.MobileSMS",
            displayName = "iMessage",
            primaryColor = parseHexColor("#34C759"),
            accentColor = parseHexColor("#28A745"),
            iconRes = R.drawable.ic_app_imessage,
            monogram = "iM"
        ),
        "com.apple.mobilephone" to BrandMetadata(
            bundleId = "com.apple.mobilephone",
            displayName = "Phone",
            primaryColor = parseHexColor("#34C759"),
            accentColor = parseHexColor("#28A745"),
            iconRes = R.drawable.ic_app_phone,
            monogram = "PH"
        ),
        "com.apple.facetime" to BrandMetadata(
            bundleId = "com.apple.facetime",
            displayName = "FaceTime",
            primaryColor = parseHexColor("#30D158"),
            accentColor = parseHexColor("#249D42"),
            iconRes = R.drawable.ic_app_facetime,
            monogram = "FT"
        ),
        "com.apple.mobilemail" to BrandMetadata(
            bundleId = "com.apple.mobilemail",
            displayName = "Apple Mail",
            primaryColor = parseHexColor("#007AFF"),
            accentColor = parseHexColor("#0056B3"),
            iconRes = R.drawable.ic_app_mail,
            monogram = "ML"
        ),
        "com.apple.mobilecal" to BrandMetadata(
            bundleId = "com.apple.mobilecal",
            displayName = "Calendar",
            primaryColor = parseHexColor("#FF3B30"),
            accentColor = parseHexColor("#C82319"),
            iconRes = R.drawable.ic_app_calendar,
            monogram = "CA"
        ),
        "com.apple.reminders" to BrandMetadata(
            bundleId = "com.apple.reminders",
            displayName = "Reminders",
            primaryColor = parseHexColor("#FF9500"),
            accentColor = parseHexColor("#C77300"),
            iconRes = R.drawable.ic_schedule,
            monogram = "RM"
        ),
        "com.apple.Passbook" to BrandMetadata(
            bundleId = "com.apple.Passbook",
            displayName = "Apple Wallet",
            primaryColor = parseHexColor("#F59E0B"),
            accentColor = parseHexColor("#D97706"),
            iconRes = R.drawable.ic_app_wallet,
            monogram = "WL"
        ),
        "com.apple.Health" to BrandMetadata(
            bundleId = "com.apple.Health",
            displayName = "Apple Health",
            primaryColor = parseHexColor("#FF2D55"),
            accentColor = parseHexColor("#CC1E3E"),
            iconRes = R.drawable.ic_app_health,
            monogram = "AH"
        ),
        "com.apple.Fitness" to BrandMetadata(
            bundleId = "com.apple.Fitness",
            displayName = "Fitness",
            primaryColor = parseHexColor("#A4FF00"),
            accentColor = parseHexColor("#7ABF00"),
            iconRes = R.drawable.ic_app_health,
            monogram = "FT"
        ),
        "com.apple.Music" to BrandMetadata(
            bundleId = "com.apple.Music",
            displayName = "Apple Music",
            primaryColor = parseHexColor("#FA243C"),
            accentColor = parseHexColor("#C7152B"),
            iconRes = R.drawable.ic_app_music,
            monogram = "AM"
        ),
        "com.apple.podcasts" to BrandMetadata(
            bundleId = "com.apple.podcasts",
            displayName = "Podcasts",
            primaryColor = parseHexColor("#9933FF"),
            accentColor = parseHexColor("#7711CC"),
            iconRes = R.drawable.ic_podcast,
            monogram = "PC"
        )
    )

    /**
     * Look up brand metadata by iOS bundle ID. If unknown, returns a deterministic
     * fallback with clean monogram and palette color.
     */
    fun resolve(bundleId: String?): BrandMetadata {
        if (bundleId.isNullOrBlank()) {
            return fallbackMetadata("Unknown", "app")
        }

        registry[bundleId]?.let { return it }

        // Fuzzy match: check if bundle contains known brand keyword
        for ((key, meta) in registry) {
            val simpleKey = key.substringAfterLast(".").lowercase(Locale.ROOT)
            val simpleId = bundleId.lowercase(Locale.ROOT)
            if (simpleId.contains(simpleKey) || simpleId.endsWith(simpleKey)) {
                return meta.copy(bundleId = bundleId)
            }
        }

        val name = bundleId.substringAfterLast(".")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        return fallbackMetadata(bundleId, name)
    }

    private fun fallbackMetadata(bundleId: String, name: String): BrandMetadata {
        val hash = bundleId.hashCode()
        val colorIndex = Math.abs(hash) % fallbackPalette.size
        val color = fallbackPalette[colorIndex]
        val monogram = if (name.length >= 2) name.take(2).uppercase(Locale.ROOT) else name.uppercase(Locale.ROOT)

        return BrandMetadata(
            bundleId = bundleId,
            displayName = name,
            primaryColor = color,
            accentColor = color,
            iconRes = R.drawable.ic_notification_default,
            monogram = monogram
        )
    }

    fun isRegistered(bundleId: String): Boolean = registry.containsKey(bundleId)
}
