package com.wearos.ancsbridge

import com.wearos.ancsbridge.ancs.AppBrandRegistry
import com.wearos.ancsbridge.messages.MessageReplyAckPayload
import com.wearos.ancsbridge.messages.MessageReplyPayload
import com.wearos.ancsbridge.messages.MessageSyncManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class Phase5MessagesTest {

    @Test
    fun testMessagingBundleFiltering() {
        assertTrue("iMessage should be recognized as a messaging app",
            MessageSyncManager.isMessagingApp(MessageSyncManager.BUNDLE_IMESSAGE))

        assertTrue("WhatsApp should be recognized as a messaging app",
            MessageSyncManager.isMessagingApp(MessageSyncManager.BUNDLE_WHATSAPP))

        assertTrue("WhatsApp Business should be recognized as a messaging app",
            MessageSyncManager.isMessagingApp(MessageSyncManager.BUNDLE_WHATSAPP_SMB))

        assertTrue("Telegram should be recognized as a messaging app",
            MessageSyncManager.isMessagingApp(MessageSyncManager.BUNDLE_TELEGRAM))

        assertTrue("Messenger should be recognized as a messaging app",
            MessageSyncManager.isMessagingApp(MessageSyncManager.BUNDLE_MESSENGER))

        assertTrue("Instagram should be recognized as a messaging app",
            MessageSyncManager.isMessagingApp(MessageSyncManager.BUNDLE_INSTAGRAM))

        assertFalse("Calendar should not be treated as a messaging app",
            MessageSyncManager.isMessagingApp("com.apple.mobilecal"))

        assertFalse("Mail should not be treated as a messaging app",
            MessageSyncManager.isMessagingApp("com.apple.mobilemail"))
    }

    @Test
    fun testThreadKeyComputation() {
        // Standard phone number
        val key1 = MessageSyncManager.computeThreadKey(
            appBundleId = MessageSyncManager.BUNDLE_WHATSAPP,
            senderIdentifier = "+1 (555) 019-2834",
            fallbackSenderName = "Jane Doe"
        )
        assertEquals("net.whatsapp.WhatsApp:15550192834", key1)

        // Contact name fallback
        val key2 = MessageSyncManager.computeThreadKey(
            appBundleId = MessageSyncManager.BUNDLE_IMESSAGE,
            senderIdentifier = "John Appleseed",
            fallbackSenderName = "John Appleseed"
        )
        assertEquals("com.apple.MobileSMS:john appleseed", key2)

        // Eastern Arabic numerals normalization
        val keyArabic = MessageSyncManager.computeThreadKey(
            appBundleId = MessageSyncManager.BUNDLE_WHATSAPP,
            senderIdentifier = "+٩٦٦ ٥٠ ١٢٣ ٤٥٦٧",
            fallbackSenderName = "Omar"
        )
        assertEquals("net.whatsapp.WhatsApp:966501234567", keyArabic)
    }

    @Test
    fun testMessageReplyPayloadSerialization() {
        val replyId = UUID.randomUUID().toString()
        val payload = MessageReplyPayload(
            replyId = replyId,
            threadKey = "net.whatsapp.WhatsApp:15550192834",
            appBundleId = MessageSyncManager.BUNDLE_WHATSAPP,
            recipient = "+15550192834",
            senderName = "Jane Doe",
            replyText = "Running 5 minutes late!",
            ancsUid = 1042L,
            timestamp = 1730650000000L
        )

        val json = JSONObject().apply {
            put("replyId", payload.replyId)
            put("threadKey", payload.threadKey)
            put("appBundleId", payload.appBundleId)
            put("recipient", payload.recipient)
            put("senderName", payload.senderName)
            put("replyText", payload.replyText)
            put("ancsUid", payload.ancsUid)
            put("timestamp", payload.timestamp)
        }

        val jsonString = json.toString()
        val parsed = JSONObject(jsonString)

        assertEquals(replyId, parsed.getString("replyId"))
        assertEquals("net.whatsapp.WhatsApp:15550192834", parsed.getString("threadKey"))
        assertEquals(MessageSyncManager.BUNDLE_WHATSAPP, parsed.getString("appBundleId"))
        assertEquals("+15550192834", parsed.getString("recipient"))
        assertEquals("Jane Doe", parsed.getString("senderName"))
        assertEquals("Running 5 minutes late!", parsed.getString("replyText"))
        assertEquals(1042L, parsed.getLong("ancsUid"))
        assertEquals(1730650000000L, parsed.getLong("timestamp"))
    }

    @Test
    fun testMessageReplyAckParsing() {
        val replyId = UUID.randomUUID().toString()
        val ackJsonStr = """
            {
                "replyId": "$replyId",
                "status": "SENT",
                "detail": "Relayed via Webhook (HTTP 200)"
            }
        """.trimIndent()

        val json = JSONObject(ackJsonStr)
        val ack = MessageReplyAckPayload(
            replyId = json.getString("replyId"),
            status = json.getString("status"),
            detail = json.getString("detail")
        )

        assertEquals(replyId, ack.replyId)
        assertEquals("SENT", ack.status)
        assertEquals("Relayed via Webhook (HTTP 200)", ack.detail)
    }

    @Test
    fun testSyntheticMessageInjectionParsing() {
        val syntheticJson = """
            {
                "cmd": "INJECT_MESSAGE",
                "appBundleId": "net.whatsapp.WhatsApp",
                "appDisplayName": "WhatsApp",
                "senderName": "Sarah Connor",
                "senderIdentifier": "+1 (555) 019-2834",
                "body": "System diagnostics verification",
                "timestamp": 1730650000000
            }
        """.trimIndent()

        val json = JSONObject(syntheticJson)
        assertEquals("INJECT_MESSAGE", json.getString("cmd"))
        assertEquals("net.whatsapp.WhatsApp", json.getString("appBundleId"))
        assertEquals("WhatsApp", json.getString("appDisplayName"))
        assertEquals("Sarah Connor", json.getString("senderName"))
        assertEquals("System diagnostics verification", json.getString("body"))
    }

    @Test
    fun testAppBrandRegistryResolution() {
        // TikTok Global & Regional variants
        val tiktokGlobal = AppBrandRegistry.resolve(AppBrandRegistry.BUNDLE_TIKTOK_GLOBAL)
        assertEquals("TikTok", tiktokGlobal.displayName)
        assertEquals(R.drawable.ic_app_tiktok, tiktokGlobal.iconRes)
        assertEquals(0xFF00F2FE.toInt(), tiktokGlobal.primaryColor)
        assertEquals(0xFFFE2C55.toInt(), tiktokGlobal.accentColor)

        val tiktokAme = AppBrandRegistry.resolve(AppBrandRegistry.BUNDLE_TIKTOK_AME)
        assertEquals("TikTok", tiktokAme.displayName)
        assertEquals(R.drawable.ic_app_tiktok, tiktokAme.iconRes)

        val tiktokBd = AppBrandRegistry.resolve(AppBrandRegistry.BUNDLE_TIKTOK_BD)
        assertEquals("TikTok", tiktokBd.displayName)
        assertEquals(R.drawable.ic_app_tiktok, tiktokBd.iconRes)

        // WhatsApp
        val whatsapp = AppBrandRegistry.resolve(AppBrandRegistry.BUNDLE_WHATSAPP)
        assertEquals("WhatsApp", whatsapp.displayName)
        assertEquals(R.drawable.ic_app_whatsapp, whatsapp.iconRes)
        assertEquals(0xFF25D366.toInt(), whatsapp.primaryColor)

        // Instagram
        val instagram = AppBrandRegistry.resolve(AppBrandRegistry.BUNDLE_INSTAGRAM)
        assertEquals("Instagram", instagram.displayName)
        assertEquals(R.drawable.ic_app_instagram, instagram.iconRes)

        // Telegram
        val telegram = AppBrandRegistry.resolve(AppBrandRegistry.BUNDLE_TELEGRAM)
        assertEquals("Telegram", telegram.displayName)
        assertEquals(R.drawable.ic_app_telegram, telegram.iconRes)

        // Discord
        val discord = AppBrandRegistry.resolve(AppBrandRegistry.BUNDLE_DISCORD)
        assertEquals("Discord", discord.displayName)
        assertEquals(R.drawable.ic_app_discord, discord.iconRes)

        // iMessage
        val imessage = AppBrandRegistry.resolve(AppBrandRegistry.BUNDLE_IMESSAGE)
        assertEquals("iMessage", imessage.displayName)
        assertEquals(R.drawable.ic_app_imessage, imessage.iconRes)
    }

    @Test
    fun testTikTokMessagingSupport() {
        assertTrue("TikTok Global should be supported for messaging inbox",
            MessageSyncManager.isMessagingApp(MessageSyncManager.BUNDLE_TIKTOK_GLOBAL))
        assertTrue("TikTok Ame regional should be supported for messaging inbox",
            MessageSyncManager.isMessagingApp(MessageSyncManager.BUNDLE_TIKTOK_AME))
        assertTrue("TikTok BD regional should be supported for messaging inbox",
            MessageSyncManager.isMessagingApp(MessageSyncManager.BUNDLE_TIKTOK_BD))
        assertTrue("Discord should be supported for messaging inbox",
            MessageSyncManager.isMessagingApp(MessageSyncManager.BUNDLE_DISCORD))

        assertEquals("TikTok", MessageSyncManager.resolveAppDisplayName(MessageSyncManager.BUNDLE_TIKTOK_GLOBAL))
        assertEquals("TikTok", MessageSyncManager.resolveAppDisplayName(MessageSyncManager.BUNDLE_TIKTOK_AME))
        assertEquals("Discord", MessageSyncManager.resolveAppDisplayName(MessageSyncManager.BUNDLE_DISCORD))
    }
}

