package com.wearos.ancsbridge.messages

import android.content.Context
import android.util.Log
import com.wearos.ancsbridge.contacts.AppDatabase
import com.wearos.ancsbridge.contacts.ContactRepository
import com.wearos.ancsbridge.model.AncsNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Manages the Persistent iPhone Message Inbox on Wear OS.
 * Intercepts incoming messaging ANCS notifications, resolves contacts,
 * and maintains reactive conversation threads in Room.
 */
class MessageSyncManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MessageSyncManager"

        const val BUNDLE_IMESSAGE = "com.apple.MobileSMS"
        const val BUNDLE_WHATSAPP = "net.whatsapp.WhatsApp"
        const val BUNDLE_WHATSAPP_SMB = "net.whatsapp.WhatsAppSMB"
        const val BUNDLE_TELEGRAM = "ph.telegra.Telegraph"
        const val BUNDLE_MESSENGER = "com.facebook.Messenger"
        const val BUNDLE_INSTAGRAM = "com.burbn.instagram"
        const val BUNDLE_TIKTOK_GLOBAL = "com.zhiliaoapp.musically"
        const val BUNDLE_TIKTOK_AME = "com.ss.iphone.ugc.Ame"
        const val BUNDLE_TIKTOK_BD = "com.bd.iphone.tiktok"
        const val BUNDLE_DISCORD = "com.hammerandchisel.discord"

        val SUPPORTED_MESSAGING_BUNDLES = setOf(
            BUNDLE_IMESSAGE,
            BUNDLE_WHATSAPP,
            BUNDLE_WHATSAPP_SMB,
            BUNDLE_TELEGRAM,
            BUNDLE_MESSENGER,
            BUNDLE_INSTAGRAM,
            BUNDLE_TIKTOK_GLOBAL,
            BUNDLE_TIKTOK_AME,
            BUNDLE_TIKTOK_BD,
            BUNDLE_DISCORD
        )

        @Volatile
        private var instance: MessageSyncManager? = null

        fun getInstance(context: Context): MessageSyncManager {
            return instance ?: synchronized(this) {
                instance ?: MessageSyncManager(context.applicationContext).also { instance = it }
            }
        }

        fun isMessagingApp(bundleId: String): Boolean {
            return SUPPORTED_MESSAGING_BUNDLES.contains(bundleId)
        }

        fun resolveAppDisplayName(bundleId: String): String {
            return com.wearos.ancsbridge.ancs.AppBrandRegistry.resolve(bundleId).displayName
        }

        fun computeThreadKey(appBundleId: String, senderIdentifier: String, fallbackSenderName: String): String {
            val phone = ContactRepository.normalizePhoneNumber(senderIdentifier)
                .ifBlank { ContactRepository.normalizePhoneNumber(fallbackSenderName) }
            val normalized = if (phone.isNotBlank()) {
                phone.removePrefix("+")
            } else {
                fallbackSenderName.trim().lowercase()
            }
            return "$appBundleId:$normalized"
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageDao = AppDatabase.getInstance(context).messageDao()
    private val contactRepository = ContactRepository.getInstance(context)

    /**
     * Intercepts incoming ANCS notification. If from a supported messaging app,
     * resolves contact name, computes thread key, and persists into Room.
     */
    fun handleIncomingAncsNotification(notification: AncsNotification) {
        val bundleId = notification.appIdentifier
        if (!isMessagingApp(bundleId)) return

        val body = notification.message.ifBlank { notification.subtitle ?: "" }
        if (body.isBlank()) {
            Log.d(TAG, "Skipping empty message notification from $bundleId (uid=${notification.uid})")
            return
        }

        scope.launch {
            val rawSender = notification.title.trim()
            val resolvedSender = contactRepository.resolveContactName(rawSender)
            val displayName = resolvedSender ?: rawSender.ifBlank { "Unknown" }
            val appDisplayName = resolveAppDisplayName(bundleId)
            val threadKey = computeThreadKey(bundleId, rawSender, displayName)

            // Safeguard against ANCS duplicate replay upon reconnect:
            // 1. Check if same ancsUid already exists
            if (notification.uid > 0) {
                val existing = messageDao.getMessageByAncsUid(notification.uid.toLong())
                if (existing != null) {
                    Log.d(TAG, "Deduplicated incoming ANCS message by UID ${notification.uid} on thread $threadKey")
                    return@launch
                }
            }

            // 2. Check if identical threadKey and body arrived within the last 60 seconds
            val cutoff = System.currentTimeMillis() - 60_000L
            if (messageDao.countRecentMatchingMessages(threadKey, body, cutoff) > 0) {
                Log.d(TAG, "Deduplicated incoming ANCS message by thread/body within 60s on thread $threadKey")
                return@launch
            }

            val entity = MessageEntity(
                ancsUid = notification.uid.toLong(),
                threadKey = threadKey,
                appBundleId = bundleId,
                appDisplayName = appDisplayName,
                senderName = displayName,
                senderIdentifier = rawSender,
                body = body,
                timestamp = System.currentTimeMillis(),
                isOutgoing = false,
                deliveryStatus = "DELIVERED",
                isRead = false
            )

            val id = messageDao.insertMessage(entity)
            Log.i(TAG, "Persisted incoming message #$id for thread '$threadKey' ($displayName: '$body')")
        }
    }

    /**
     * Injects synthetic / mock message into Room from Channel 0x06 diagnostic packet.
     */
    fun injectSyntheticMessage(jsonStr: String) {
        scope.launch {
            try {
                val json = JSONObject(jsonStr)
                val bundleId = json.optString("appBundleId", BUNDLE_WHATSAPP)
                val appDisplayName = json.optString("appDisplayName", resolveAppDisplayName(bundleId))
                val senderIdentifier = json.optString("senderIdentifier", "+1 (555) 019-2834")
                val resolvedName = contactRepository.resolveContactName(senderIdentifier)
                val senderName = json.optString("senderName", resolvedName ?: "Test Sender")
                val body = json.optString("body", "Hello from iOS Diagnostics!")
                val timestamp = json.optLong("timestamp", System.currentTimeMillis())
                val threadKey = computeThreadKey(bundleId, senderIdentifier, senderName)

                val entity = MessageEntity(
                    ancsUid = 0L,
                    threadKey = threadKey,
                    appBundleId = bundleId,
                    appDisplayName = appDisplayName,
                    senderName = senderName,
                    senderIdentifier = senderIdentifier,
                    body = body,
                    timestamp = timestamp,
                    isOutgoing = false,
                    deliveryStatus = "DELIVERED",
                    isRead = false
                )

                val id = messageDao.insertMessage(entity)
                Log.i(TAG, "Injected synthetic diagnostic message #$id into thread '$threadKey'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to inject synthetic message: ${e.message}")
            }
        }
    }

    /**
     * Reactive Flow returning conversation threads with latest messages, unread counts, and timestamps.
     */
    fun getConversationThreads(): Flow<List<ConversationSummary>> {
        return messageDao.getAllMessages().map { messages ->
            messages.groupBy { it.threadKey }.map { (threadKey, threadMessages) ->
                val sorted = threadMessages.sortedByDescending { it.timestamp }
                val latest = sorted.first()
                val unreadCount = threadMessages.count { !it.isRead && !it.isOutgoing }

                ConversationSummary(
                    threadKey = threadKey,
                    appBundleId = latest.appBundleId,
                    appDisplayName = latest.appDisplayName,
                    senderName = latest.senderName,
                    senderIdentifier = latest.senderIdentifier,
                    latestMessageBody = latest.body,
                    timestamp = latest.timestamp,
                    unreadCount = unreadCount,
                    lastDeliveryStatus = latest.deliveryStatus,
                    isOutgoing = latest.isOutgoing
                )
            }.sortedByDescending { it.timestamp }
        }
    }

    /**
     * Reactive Flow of messages for a given threadKey.
     */
    fun getMessagesForThread(threadKey: String): Flow<List<MessageEntity>> {
        return messageDao.getMessagesForThread(threadKey)
    }

    /**
     * Marks all messages in a thread as read.
     */
    suspend fun markThreadAsRead(threadKey: String) {
        messageDao.markThreadAsRead(threadKey)
    }

    /**
     * Clears all message history.
     */
    suspend fun clearAllMessages() {
        messageDao.clearAllMessages()
    }
}
