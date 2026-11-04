package com.wearos.ancsbridge.contacts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository managing synced contacts in Room database with an in-memory cache
 * for ultra-fast name resolution on incoming calls and notifications.
 */
class ContactRepository private constructor(private val context: Context) {

    companion object {
        private const val TAG = "ContactRepository"

        @Volatile
        private var INSTANCE: ContactRepository? = null

        fun getInstance(context: Context): ContactRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = ContactRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }

        /**
         * Normalizes a phone number for matching:
         * 1. Strips Unicode directional marks (\u200E, \u200F, \u202A–\u202E).
         * 2. Converts Eastern Arabic & Persian numerals to ASCII digits.
         * 3. Strips whitespace, dashes, parentheses, dots.
         * 4. Retains leading '+' if present.
         */
        fun normalizePhoneNumber(raw: String): String {
            val sb = java.lang.StringBuilder()
            for (char in raw) {
                if (char == '\u200E' || char == '\u200F' || char in '\u202A'..'\u202E') {
                    continue
                }
                when (char) {
                    '٠', '۰' -> sb.append('0')
                    '١', '۱' -> sb.append('1')
                    '٢', '۲' -> sb.append('2')
                    '٣', '۳' -> sb.append('3')
                    '٤', '۴' -> sb.append('4')
                    '٥', '۵' -> sb.append('5')
                    '٦', '۶' -> sb.append('6')
                    '٧', '۷' -> sb.append('7')
                    '٨', '۸' -> sb.append('8')
                    '٩', '۹' -> sb.append('9')
                    else -> sb.append(char)
                }
            }

            val trimmed = sb.toString().trim()
            if (trimmed.isEmpty()) return ""

            val hasPlus = trimmed.startsWith("+")
            val digitsOnly = trimmed.filter { it.isDigit() }
            if (digitsOnly.isEmpty()) return ""

            return if (hasPlus) "+$digitsOnly" else digitsOnly
        }

        /**
         * Extracts significant suffix (last 7 to 10 digits) for fuzzy matching
         * international vs local dialing variants.
         */
        fun extractSignificantSuffix(normalized: String): String {
            val digits = normalized.filter { it.isDigit() }
            return if (digits.length >= 7) {
                digits.takeLast(digits.length.coerceAtMost(10))
            } else {
                digits
            }
        }
    }

    private val db = AppDatabase.getInstance(context)
    private val dao = db.syncedContactDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Memory cache for instantaneous lookup: normalizedPhone -> Full Name
    private val memoryCache = ConcurrentHashMap<String, String>()

    // Suffix index: last 7 digits -> Full Name
    private val suffixCache = ConcurrentHashMap<String, String>()

    init {
        // Pre-warm the cache from Room on startup
        scope.launch {
            warmUpCache()
        }
    }

    private suspend fun warmUpCache() {
        try {
            val contacts = dao.getAllContactsSnapshot()
            for (contact in contacts) {
                cacheContact(contact.phoneNumber, contact.fullName)
            }
            Log.i(TAG, "Cache warmed up with ${contacts.size} contacts from Room")
        } catch (e: Exception) {
            Log.e(TAG, "Error warming up contacts cache: ${e.message}")
        }
    }

    private fun cacheContact(phone: String, name: String) {
        val normalized = normalizePhoneNumber(phone)
        if (normalized.isNotEmpty()) {
            memoryCache[normalized] = name
            val suffix = extractSignificantSuffix(normalized)
            if (suffix.length >= 7) {
                suffixCache[suffix] = name
            }
        }
    }

    val allContactsFlow: Flow<List<SyncedContact>> = dao.getAllContactsFlow()

    /**
     * Synchronously resolves a contact's display name from phone number.
     * Checks exact in-memory match, then significant digits suffix match.
     *
     * @param phoneNumber Raw phone number string from incoming call/message.
     * @return Contact's full name, or null if not found.
     */
    fun resolveContactName(phoneNumber: String): String? {
        val normalized = normalizePhoneNumber(phoneNumber)
        if (normalized.isEmpty()) return null

        // 1. Direct match
        memoryCache[normalized]?.let { return it }

        // 2. Suffix match (e.g. comparing "+15551234567" with "5551234567")
        val suffix = extractSignificantSuffix(normalized)
        if (suffix.length >= 7) {
            suffixCache[suffix]?.let { return it }

            // Iterative suffix check in memory cache
            for ((cachedPhone, cachedName) in memoryCache) {
                val cachedSuffix = extractSignificantSuffix(cachedPhone)
                if (cachedSuffix == suffix) {
                    return cachedName
                }
            }
        }

        return null
    }

    /**
     * Upserts contacts received over BLE Channel 0x02 into Room database
     * and updates memory cache.
     *
     * Supports both:
     * - `[{"fullName": "...", "phoneNumbers": ["..."]}]`
     * - `[{"name": "...", "phones": ["..."]}]`
     * - `[{"name": "...", "phone": "..."}]`
     */
    fun upsertContacts(payload: ByteArray, onComplete: ((Int) -> Unit)? = null) {
        scope.launch {
            try {
                val jsonStr = String(payload, Charsets.UTF_8)
                val jsonArray = JSONArray(jsonStr)
                val now = System.currentTimeMillis()
                val contactEntities = mutableListOf<SyncedContact>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val fullName = obj.optString("fullName", obj.optString("name", "Unknown"))

                    val phoneList = mutableListOf<String>()

                    if (obj.has("phoneNumbers")) {
                        val arr = obj.optJSONArray("phoneNumbers")
                        if (arr != null) {
                            for (j in 0 until arr.length()) {
                                phoneList.add(arr.getString(j))
                            }
                        }
                    } else if (obj.has("phones")) {
                        val arr = obj.optJSONArray("phones")
                        if (arr != null) {
                            for (j in 0 until arr.length()) {
                                phoneList.add(arr.getString(j))
                            }
                        }
                    } else if (obj.has("phone")) {
                        phoneList.add(obj.getString("phone"))
                    }

                    for (rawPhone in phoneList) {
                        val normalized = normalizePhoneNumber(rawPhone)
                        if (normalized.isNotEmpty()) {
                            contactEntities.add(
                                SyncedContact(
                                    phoneNumber = normalized,
                                    fullName = fullName,
                                    updatedAt = now
                                )
                            )
                            cacheContact(normalized, fullName)
                        }
                    }
                }

                if (contactEntities.isNotEmpty()) {
                    dao.replaceAllContacts(contactEntities)
                    memoryCache.clear()
                    suffixCache.clear()
                    for (entity in contactEntities) {
                        cacheContact(entity.phoneNumber, entity.fullName)
                    }
                    Log.i(TAG, "Successfully replaced all contacts with ${contactEntities.size} phone entries in Room and refreshed memory cache")
                } else {
                    dao.clearAll()
                    memoryCache.clear()
                    suffixCache.clear()
                }

                onComplete?.invoke(contactEntities.size)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse/upsert contacts JSON: ${e.message}", e)
                onComplete?.invoke(0)
            }
        }
    }
}

