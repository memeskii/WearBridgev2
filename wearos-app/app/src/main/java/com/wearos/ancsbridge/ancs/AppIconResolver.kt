package com.wearos.ancsbridge.ancs

import android.graphics.Color
import com.wearos.ancsbridge.R

enum class GlyphType {
    VECTOR_DRAWABLE,
    MONOGRAM
}

/**
 * Rich brand metadata for incoming iOS applications.
 */
data class AppBrandInfo(
    val displayName: String,
    val primaryColorHex: Int,
    val secondaryColorHex: Int = primaryColorHex,
    val glyphType: GlyphType = GlyphType.VECTOR_DRAWABLE,
    val monogramText: String = "",
    val iconResId: Int? = null
)

/**
 * Maps iOS ANCS appIdentifier bundle IDs to rich brand metadata:
 * Display Name, Primary Brand Color, Secondary Accent Color, and Vector/Monogram Icon.
 */
object AppIconResolver {

    // Curated aesthetic fallback palette for third-party monograms
    private val fallbackPalette = intArrayOf(
        Color.parseColor("#4F46E5"), // Indigo
        Color.parseColor("#0EA5E9"), // Sky
        Color.parseColor("#10B981"), // Emerald
        Color.parseColor("#F59E0B"), // Amber
        Color.parseColor("#EF4444"), // Red
        Color.parseColor("#8B5CF6"), // Violet
        Color.parseColor("#EC4899"), // Pink
        Color.parseColor("#14B8A6"), // Teal
        Color.parseColor("#F97316"), // Orange
        Color.parseColor("#06B6D4"), // Cyan
        Color.parseColor("#6366F1"), // Blue-Violet
        Color.parseColor("#84CC16")  // Lime
    )

    // Primary registry of known iOS bundle IDs
    private val brandRegistry: Map<String, AppBrandInfo> = mapOf(
        // WhatsApp
        "net.whatsapp.WhatsApp" to AppBrandInfo(
            displayName = "WhatsApp",
            primaryColorHex = Color.parseColor("#25D366"),
            secondaryColorHex = Color.parseColor("#128C7E"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "WA",
            iconResId = R.drawable.ic_whatsapp
        ),
        "net.whatsapp.WhatsAppSMB" to AppBrandInfo(
            displayName = "WhatsApp Business",
            primaryColorHex = Color.parseColor("#25D366"),
            secondaryColorHex = Color.parseColor("#128C7E"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "WB",
            iconResId = R.drawable.ic_whatsapp
        ),

        // Apple Messages
        "com.apple.MobileSMS" to AppBrandInfo(
            displayName = "Messages",
            primaryColorHex = Color.parseColor("#34C759"),
            secondaryColorHex = Color.parseColor("#30B750"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "M",
            iconResId = R.drawable.ic_messages
        ),

        // Telegram
        "org.telegram.Telegram" to AppBrandInfo(
            displayName = "Telegram",
            primaryColorHex = Color.parseColor("#24A1DE"),
            secondaryColorHex = Color.parseColor("#1D8ACB"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "TG",
            iconResId = R.drawable.ic_telegram
        ),
        "ph.telegra.Telegraph" to AppBrandInfo(
            displayName = "Telegram",
            primaryColorHex = Color.parseColor("#24A1DE"),
            secondaryColorHex = Color.parseColor("#1D8ACB"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "TG",
            iconResId = R.drawable.ic_telegram
        ),

        // Discord
        "com.hammerandchisel.discord" to AppBrandInfo(
            displayName = "Discord",
            primaryColorHex = Color.parseColor("#5865F2"),
            secondaryColorHex = Color.parseColor("#4752C4"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "DC",
            iconResId = R.drawable.ic_discord
        ),

        // Instagram
        "com.burbn.instagram" to AppBrandInfo(
            displayName = "Instagram",
            primaryColorHex = Color.parseColor("#E1306C"),
            secondaryColorHex = Color.parseColor("#C13584"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "IG",
            iconResId = R.drawable.ic_instagram
        ),

        // Messenger
        "com.facebook.Messenger" to AppBrandInfo(
            displayName = "Messenger",
            primaryColorHex = Color.parseColor("#0084FF"),
            secondaryColorHex = Color.parseColor("#0066CC"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "FB",
            iconResId = R.drawable.ic_messenger
        ),

        // Signal
        "org.whispersystems.signal" to AppBrandInfo(
            displayName = "Signal",
            primaryColorHex = Color.parseColor("#3A76F0"),
            secondaryColorHex = Color.parseColor("#2C5EC7"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "SG",
            iconResId = R.drawable.ic_signal
        ),

        // Slack
        "com.slack.Slack" to AppBrandInfo(
            displayName = "Slack",
            primaryColorHex = Color.parseColor("#4A154B"),
            secondaryColorHex = Color.parseColor("#611F69"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "SL",
            iconResId = R.drawable.ic_slack
        ),
        "com.tinyspeck.chatlyio" to AppBrandInfo(
            displayName = "Slack",
            primaryColorHex = Color.parseColor("#4A154B"),
            secondaryColorHex = Color.parseColor("#611F69"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "SL",
            iconResId = R.drawable.ic_slack
        ),

        // Gmail
        "com.google.Gmail" to AppBrandInfo(
            displayName = "Gmail",
            primaryColorHex = Color.parseColor("#EA4335"),
            secondaryColorHex = Color.parseColor("#C5221F"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "GM",
            iconResId = R.drawable.ic_gmail
        ),

        // Microsoft Outlook
        "com.microsoft.Office.Outlook" to AppBrandInfo(
            displayName = "Outlook",
            primaryColorHex = Color.parseColor("#0078D4"),
            secondaryColorHex = Color.parseColor("#005A9E"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "OL",
            iconResId = R.drawable.ic_email
        ),

        // Spotify
        "com.spotify.client" to AppBrandInfo(
            displayName = "Spotify",
            primaryColorHex = Color.parseColor("#1DB954"),
            secondaryColorHex = Color.parseColor("#15883E"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "SP",
            iconResId = R.drawable.ic_music
        ),

        // YouTube
        "com.google.ios.youtube" to AppBrandInfo(
            displayName = "YouTube",
            primaryColorHex = Color.parseColor("#FF0000"),
            secondaryColorHex = Color.parseColor("#CC0000"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "YT",
            iconResId = R.drawable.ic_youtube
        ),

        // X / Twitter
        "com.atebits.Tweetie2" to AppBrandInfo(
            displayName = "X",
            primaryColorHex = Color.parseColor("#1DA1F2"),
            secondaryColorHex = Color.parseColor("#0D8BD9"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "X",
            iconResId = R.drawable.ic_social
        ),

        // Uber Client
        "com.ubercab.UberClient" to AppBrandInfo(
            displayName = "Uber",
            primaryColorHex = Color.parseColor("#1A1A1A"),
            secondaryColorHex = Color.parseColor("#000000"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "UB",
            iconResId = R.drawable.ic_rideshare
        ),
        "com.ubercab.UberEats" to AppBrandInfo(
            displayName = "Uber Eats",
            primaryColorHex = Color.parseColor("#06C167"),
            secondaryColorHex = Color.parseColor("#059A52"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "UE",
            iconResId = R.drawable.ic_rideshare
        ),

        // Apple Phone & FaceTime
        "com.apple.mobilephone" to AppBrandInfo(
            displayName = "Phone",
            primaryColorHex = Color.parseColor("#34C759"),
            secondaryColorHex = Color.parseColor("#28A745"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "PH",
            iconResId = R.drawable.ic_phone
        ),
        "com.apple.facetime" to AppBrandInfo(
            displayName = "FaceTime",
            primaryColorHex = Color.parseColor("#34C759"),
            secondaryColorHex = Color.parseColor("#28A745"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "FT",
            iconResId = R.drawable.ic_phone
        ),

        // Apple Calendar
        "com.apple.mobilecal" to AppBrandInfo(
            displayName = "Calendar",
            primaryColorHex = Color.parseColor("#FF3B30"),
            secondaryColorHex = Color.parseColor("#D72820"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "CAL",
            iconResId = R.drawable.ic_calendar
        ),

        // Apple Mail
        "com.apple.mobilemail" to AppBrandInfo(
            displayName = "Mail",
            primaryColorHex = Color.parseColor("#007AFF"),
            secondaryColorHex = Color.parseColor("#0056B3"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "ML",
            iconResId = R.drawable.ic_email
        ),

        // Apple Reminders
        "com.apple.reminders" to AppBrandInfo(
            displayName = "Reminders",
            primaryColorHex = Color.parseColor("#FF9500"),
            secondaryColorHex = Color.parseColor("#CC7700"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "RM",
            iconResId = R.drawable.ic_schedule
        ),

        // Apple Notes
        "com.apple.mobilenotes" to AppBrandInfo(
            displayName = "Notes",
            primaryColorHex = Color.parseColor("#FFCC00"),
            secondaryColorHex = Color.parseColor("#CCA300"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "NT",
            iconResId = R.drawable.ic_notes
        ),

        // Apple Health & Fitness
        "com.apple.Health" to AppBrandInfo(
            displayName = "Health",
            primaryColorHex = Color.parseColor("#FF2D55"),
            secondaryColorHex = Color.parseColor("#D91B40"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "H",
            iconResId = R.drawable.ic_health
        ),
        "com.apple.Fitness" to AppBrandInfo(
            displayName = "Fitness",
            primaryColorHex = Color.parseColor("#FA114F"),
            secondaryColorHex = Color.parseColor("#CC0E40"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "FIT",
            iconResId = R.drawable.ic_fitness
        ),

        // Reddit
        "com.reddit.Reddit" to AppBrandInfo(
            displayName = "Reddit",
            primaryColorHex = Color.parseColor("#FF4500"),
            secondaryColorHex = Color.parseColor("#D03700"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "RD",
            iconResId = R.drawable.ic_social
        ),

        // LinkedIn
        "com.linkedin.LinkedIn" to AppBrandInfo(
            displayName = "LinkedIn",
            primaryColorHex = Color.parseColor("#0A66C2"),
            secondaryColorHex = Color.parseColor("#084E96"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "IN",
            iconResId = R.drawable.ic_social
        ),

        // Threads
        "com.burbn.barcelona" to AppBrandInfo(
            displayName = "Threads",
            primaryColorHex = Color.parseColor("#1A1A1A"),
            secondaryColorHex = Color.parseColor("#000000"),
            glyphType = GlyphType.VECTOR_DRAWABLE,
            monogramText = "TH",
            iconResId = R.drawable.ic_threads
        )
    )

    /**
     * Resolve rich brand info for an iOS bundle ID.
     * Uses exact match, then case-insensitive substring matching, and falls back to a smart monogram.
     */
    fun resolve(bundleId: String): AppBrandInfo {
        if (bundleId.isBlank()) {
            return createMonogramFallback("App", "APP")
        }

        // 1. Exact match
        brandRegistry[bundleId]?.let { return it }

        // 2. Case-insensitive substring fallback for beta / regional bundle IDs
        val lower = bundleId.lowercase()
        for ((key, info) in brandRegistry) {
            val keyTerm = key.substringAfterLast(".").lowercase()
            if (lower.contains(keyTerm)) {
                return info
            }
        }

        // Specific keyword checks
        when {
            lower.contains("whatsapp") -> return brandRegistry["net.whatsapp.WhatsApp"]!!
            lower.contains("telegram") -> return brandRegistry["org.telegram.Telegram"]!!
            lower.contains("discord") -> return brandRegistry["com.hammerandchisel.discord"]!!
            lower.contains("instagram") -> return brandRegistry["com.burbn.instagram"]!!
            lower.contains("messenger") -> return brandRegistry["com.facebook.Messenger"]!!
            lower.contains("signal") -> return brandRegistry["org.whispersystems.signal"]!!
            lower.contains("slack") -> return brandRegistry["com.slack.Slack"]!!
            lower.contains("gmail") -> return brandRegistry["com.google.Gmail"]!!
            lower.contains("outlook") -> return brandRegistry["com.microsoft.Office.Outlook"]!!
            lower.contains("spotify") -> return brandRegistry["com.spotify.client"]!!
            lower.contains("youtube") -> return brandRegistry["com.google.ios.youtube"]!!
            lower.contains("uber") -> return brandRegistry["com.ubercab.UberClient"]!!
            lower.contains("calendar") -> return brandRegistry["com.apple.mobilecal"]!!
            lower.contains("notes") -> return brandRegistry["com.apple.mobilenotes"]!!
            lower.contains("reddit") -> return brandRegistry["com.reddit.Reddit"]!!
            lower.contains("linkedin") -> return brandRegistry["com.linkedin.LinkedIn"]!!
            lower.contains("threads") -> return brandRegistry["com.burbn.barcelona"]!!
        }

        // 3. Smart monogram fallback for unknown third-party iOS bundle IDs
        val resolvedName = extractDisplayName(bundleId)
        val monogram = generateMonogram(resolvedName)
        return createMonogramFallback(bundleId, resolvedName, monogram)
    }

    private fun extractDisplayName(bundleId: String): String {
        val lastSegment = bundleId.substringAfterLast(".")
        if (lastSegment.isEmpty()) return "App"

        // Insert spaces before capitals in camelCase strings (e.g. "AcmeBank" -> "Acme Bank")
        val withSpaces = lastSegment.replace(Regex("(?<=[a-z])(?=[A-Z])"), " ")
        return withSpaces.trim().ifEmpty { lastSegment }
    }

    private fun generateMonogram(name: String): String {
        val words = name.split(" ", "_", "-").filter { it.isNotBlank() }
        return when {
            words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
            name.isNotEmpty() -> name.take(2).uppercase()
            else -> "A"
        }
    }

    private fun createMonogramFallback(
        bundleId: String,
        displayName: String,
        monogram: String = generateMonogram(displayName)
    ): AppBrandInfo {
        val hash = kotlin.math.abs(bundleId.hashCode())
        val primaryColor = fallbackPalette[hash % fallbackPalette.size]

        // Derive darker secondary gradient color
        val hsv = FloatArray(3)
        Color.colorToHSV(primaryColor, hsv)
        hsv[2] = (hsv[2] * 0.82f).coerceIn(0f, 1f)
        val secondaryColor = Color.HSVToColor(hsv)

        return AppBrandInfo(
            displayName = displayName,
            primaryColorHex = primaryColor,
            secondaryColorHex = secondaryColor,
            glyphType = GlyphType.MONOGRAM,
            monogramText = monogram,
            iconResId = R.drawable.ic_notification_default
        )
    }
}
