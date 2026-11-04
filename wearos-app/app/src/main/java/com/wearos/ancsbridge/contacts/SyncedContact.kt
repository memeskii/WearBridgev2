package com.wearos.ancsbridge.contacts

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a contact synchronized from iPhone via BLE Channel 0x02.
 *
 * @param phoneNumber Primary key, normalized phone number (e.g., "+15550192834" or "5550192834").
 * @param fullName Display name of the contact.
 * @param updatedAt Epoch timestamp in milliseconds of when the contact was upserted.
 */
@Entity(tableName = "synced_contacts")
data class SyncedContact(
    @PrimaryKey
    val phoneNumber: String,
    val fullName: String,
    val updatedAt: Long = System.currentTimeMillis()
)

