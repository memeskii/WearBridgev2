package com.wearos.ancsbridge.messages

import android.content.Context
import android.util.Log
import com.wearos.ancsbridge.ble.BLEPacketMultiplexer
import com.wearos.ancsbridge.contacts.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages outbound message replies from Wear OS to iOS over Channel 0x06.
 *
 * Responsibilities:
 * 1. Inserts optimistic outgoing MessageEntity ("SENDING") into Room.
 * 2. Serializes and transmits MessageReplyPayload JSON over Channel 0x06.
 * 3. Handles incoming MessageReplyAckPayload from iOS and updates deliveryStatus to "SENT" or "FAILED".
 * 4. Implements a 10-second timeout fallback that updates status to "FAILED" if no ACK is received.
 */
class MessageReplyManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "MessageReplyManager"
        private const val ACK_TIMEOUT_MS = 10_000L

        @Volatile
        private var instance: MessageReplyManager? = null

        fun getInstance(context: Context): MessageReplyManager {
            return instance ?: synchronized(this) {
                instance ?: MessageReplyManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val messageDao = AppDatabase.getInstance(context).messageDao()

    // Outbound dispatcher for Channel 0x06 chunks (wired to BleConnectionManager)
    var outboundReplyDispatcher: ((ByteArray) -> Boolean)? = null

    // Pending replies tracking: replyId -> Pair(messageId, timeoutJob)
    private val pendingReplies = ConcurrentHashMap<String, Pair<Long, Job>>()

    /**
     * Sends an outbound reply for a given conversation thread.
     */
    fun sendReply(
        threadKey: String,
        appBundleId: String,
        recipient: String,
        senderName: String,
        replyText: String,
        ancsUid: Long = 0L
    ): String {
        val replyId = UUID.randomUUID().toString()
        val appDisplayName = MessageSyncManager.resolveAppDisplayName(appBundleId)
        val now = System.currentTimeMillis()

        scope.launch {
            // 1. Optimistic Room insert
            val outgoingEntity = MessageEntity(
                ancsUid = ancsUid,
                threadKey = threadKey,
                appBundleId = appBundleId,
                appDisplayName = appDisplayName,
                senderName = senderName,
                senderIdentifier = recipient,
                body = replyText,
                timestamp = now,
                isOutgoing = true,
                deliveryStatus = "SENDING",
                isRead = true
            )
            val messageId = messageDao.insertMessage(outgoingEntity)
            Log.i(TAG, "Inserted optimistic outgoing message #$messageId (replyId=$replyId) in thread '$threadKey'")

            // 2. Schedule 10-second timeout fallback
            val timeoutJob = scope.launch {
                delay(ACK_TIMEOUT_MS)
                if (pendingReplies.remove(replyId) != null) {
                    messageDao.updateDeliveryStatus(messageId, "FAILED")
                    Log.w(TAG, "Reply $replyId timed out after 10s without ACK. Marked deliveryStatus as FAILED.")
                }
            }

            pendingReplies[replyId] = Pair(messageId, timeoutJob)

            // 3. Construct and transmit MessageReplyPayload JSON over Channel 0x06
            try {
                val json = JSONObject().apply {
                    put("replyId", replyId)
                    put("threadKey", threadKey)
                    put("appBundleId", appBundleId)
                    put("recipient", recipient)
                    put("senderName", senderName)
                    put("replyText", replyText)
                    put("ancsUid", ancsUid)
                    put("timestamp", now)
                }
                val payloadBytes = json.toString().toByteArray(Charsets.UTF_8)
                val sent = outboundReplyDispatcher?.invoke(payloadBytes) ?: false
                if (!sent) {
                    Log.w(TAG, "Failed to dispatch reply payload over Channel 0x06 (GATT write deferred or disconnected)")
                } else {
                    Log.i(TAG, "Transmitted reply $replyId over Channel 0x06 for '$recipient'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error serializing reply payload: ${e.message}")
                timeoutJob.cancel()
                pendingReplies.remove(replyId)
                messageDao.updateDeliveryStatus(messageId, "FAILED")
            }
        }

        return replyId
    }

    /**
     * Sends quick reply originating from an ANCS notification UID.
     */
    fun sendQuickReplyForUid(ancsUid: Long, replyText: String) {
        scope.launch {
            val existing = messageDao.getMessageByAncsUid(ancsUid)
            if (existing != null) {
                sendReply(
                    threadKey = existing.threadKey,
                    appBundleId = existing.appBundleId,
                    recipient = existing.senderIdentifier,
                    senderName = existing.senderName,
                    replyText = replyText,
                    ancsUid = ancsUid
                )
            } else {
                // Fallback default SMS thread
                val threadKey = "${MessageSyncManager.BUNDLE_IMESSAGE}:unknown"
                sendReply(
                    threadKey = threadKey,
                    appBundleId = MessageSyncManager.BUNDLE_IMESSAGE,
                    recipient = "Unknown",
                    senderName = "Unknown",
                    replyText = replyText,
                    ancsUid = ancsUid
                )
            }
        }
    }

    /**
     * Handles inbound Channel 0x06 payloads from iOS:
     * - MessageReplyAckPayload: {"replyId": "...", "status": "SENT"|"QUEUED"|"FAILED", "detail": "..."}
     * - Synthetic Message Injection: {"cmd": "INJECT_MESSAGE", ...}
     */
    fun handleInboundChannelPayload(payload: ByteArray) {
        try {
            val jsonStr = String(payload, Charsets.UTF_8)
            Log.d(TAG, "Inbound Channel 0x06 payload: $jsonStr")

            val json = JSONObject(jsonStr)

            // Check if this is a synthetic message injection from Diagnostics
            if (json.optString("cmd") == "INJECT_MESSAGE") {
                MessageSyncManager.getInstance(context).injectSyntheticMessage(jsonStr)
                return
            }

            // Otherwise, handle MessageReplyAckPayload
            val replyId = json.optString("replyId", "")
            val status = json.optString("status", "SENT")
            val detail = json.optString("detail", "")

            if (replyId.isNotBlank()) {
                val pending = pendingReplies.remove(replyId)
                if (pending != null) {
                    val (messageId, timeoutJob) = pending
                    timeoutJob.cancel()
                    scope.launch {
                        val mappedStatus = if (status.equals("FAILED", ignoreCase = true)) "FAILED" else "SENT"
                        messageDao.updateDeliveryStatus(messageId, mappedStatus)
                        Log.i(TAG, "Processed Reply ACK for $replyId: status=$mappedStatus (detail='$detail')")
                    }
                } else {
                    Log.d(TAG, "Received ACK for untracked or timed-out replyId: $replyId")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing Channel 0x06 payload: ${e.message}")
        }
    }
}

