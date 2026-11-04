package com.wearos.ancsbridge.messages

/**
 * Summary of a conversation thread displayed on the Wear OS Messages Inbox screen.
 */
data class ConversationSummary(
    val threadKey: String,
    val appBundleId: String,
    val appDisplayName: String,
    val senderName: String,
    val senderIdentifier: String,
    val latestMessageBody: String,
    val timestamp: Long,
    val unreadCount: Int,
    val lastDeliveryStatus: String,
    val isOutgoing: Boolean
)

/**
 * Outbound message reply payload transmitted from Watch to iOS over Channel 0x06.
 */
data class MessageReplyPayload(
    val replyId: String,
    val threadKey: String,
    val appBundleId: String,
    val recipient: String,
    val senderName: String,
    val replyText: String,
    val ancsUid: Long,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Inbound ACK received from iOS over Channel 0x06 after relaying a message reply.
 */
data class MessageReplyAckPayload(
    val replyId: String,
    val status: String, // "SENT", "QUEUED", "FAILED"
    val detail: String
)

