package com.wearos.ancsbridge.messages

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an individual message in a conversation thread.
 * Supports incoming ANCS notifications as well as outgoing watch replies.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["threadKey"]),
        Index(value = ["timestamp"]),
        Index(value = ["ancsUid"])
    ]
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val ancsUid: Long = 0L,
    val threadKey: String,
    val appBundleId: String,
    val appDisplayName: String,
    val senderName: String,
    val senderIdentifier: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isOutgoing: Boolean = false,
    val deliveryStatus: String = "DELIVERED", // "DELIVERED", "SENDING", "SENT", "FAILED"
    val isRead: Boolean = false
)

