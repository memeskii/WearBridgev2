package com.wearos.ancsbridge.model

/**
 * Fully resolved notification with attributes fetched from ANCS Data Source.
 */
data class AncsNotification(
    val uid: Long,
    val appIdentifier: String = "",
    val appDisplayName: String? = null,
    val title: String = "",
    val subtitle: String? = null,
    val message: String = "",
    val date: String? = null, // ANCS format: yyyyMMdd'T'HHmmss
    val categoryId: Int = 0,
    val eventFlags: Int = 0,
    val positiveActionLabel: String? = null,
    val negativeActionLabel: String? = null
)
