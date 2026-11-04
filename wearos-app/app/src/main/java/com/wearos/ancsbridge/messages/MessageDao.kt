package com.wearos.ancsbridge.messages

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity): Long

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :id")
    suspend fun updateDeliveryStatus(id: Long, status: String)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE threadKey = :threadKey AND isOutgoing = 1 AND deliveryStatus = 'SENDING'")
    suspend fun updatePendingRepliesStatus(threadKey: String, status: String)

    @Query("SELECT * FROM messages WHERE threadKey = :threadKey ORDER BY timestamp ASC")
    fun getMessagesForThread(threadKey: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE ancsUid = :ancsUid ORDER BY timestamp DESC LIMIT 1")
    suspend fun getMessageByAncsUid(ancsUid: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: Long): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE isRead = 0 AND isOutgoing = 0")
    fun getTotalUnreadCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM messages WHERE threadKey = :threadKey AND body = :body AND timestamp >= :sinceTimestamp")
    suspend fun countRecentMatchingMessages(threadKey: String, body: String, sinceTimestamp: Long): Int

    @Query("UPDATE messages SET isRead = 1 WHERE threadKey = :threadKey")
    suspend fun markThreadAsRead(threadKey: String)

    @Query("DELETE FROM messages WHERE threadKey = :threadKey")
    suspend fun deleteThread(threadKey: String)

    @Query("DELETE FROM messages")
    suspend fun clearAllMessages()
}

