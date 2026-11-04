package com.wearos.ancsbridge.contacts

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for synced contacts table in Room.
 */
@Dao
interface SyncedContactDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertContacts(contacts: List<SyncedContact>)

    @Query("SELECT fullName FROM synced_contacts WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getContactName(phoneNumber: String): String?

    @Query("SELECT * FROM synced_contacts WHERE phoneNumber = :phoneNumber LIMIT 1")
    suspend fun getContactByPhone(phoneNumber: String): SyncedContact?

    @Query("SELECT * FROM synced_contacts ORDER BY fullName ASC")
    fun getAllContactsFlow(): Flow<List<SyncedContact>>

    @Query("SELECT * FROM synced_contacts")
    suspend fun getAllContactsSnapshot(): List<SyncedContact>

    @Query("SELECT COUNT(*) FROM synced_contacts")
    suspend fun getContactCount(): Int

    @Query("DELETE FROM synced_contacts")
    suspend fun clearAll()

    @Transaction
    suspend fun replaceAllContacts(contacts: List<SyncedContact>) {
        clearAll()
        if (contacts.isNotEmpty()) {
            upsertContacts(contacts)
        }
    }
}

