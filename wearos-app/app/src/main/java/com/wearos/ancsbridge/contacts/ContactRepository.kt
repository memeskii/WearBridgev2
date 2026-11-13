package com.wearos.ancsbridge.contacts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

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
                INSTANCE?.let { return it }
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

    private val writeLock = Mutex()

    @Volatile
    private var contactNames: Map<String, String> = emptyMap()

    init {
        scope.launch {
            writeLock.withLock {
                try {
                    contactNames = dao.getAllContactsSnapshot().associate { it.phoneNumber to it.fullName }
                } catch (e: android.database.sqlite.SQLiteException) {
                    Log.e(TAG, "Could not load contacts from Room", e)
                }
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

        val names = contactNames
        names[normalized]?.let { return it }
        val suffix = extractSignificantSuffix(normalized)
        if (suffix.length >= 7) {
            val matches = names.asSequence()
                .filter { extractSignificantSuffix(it.key) == suffix }
                .map { it.value }.distinct().take(2).toList()
            return matches.singleOrNull()
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
                        }
                    }
                }

                val entries = contactEntities.distinctBy { it.phoneNumber }
                require(jsonArray.length() == 0 || entries.isNotEmpty()) { "No valid contact numbers in snapshot" }
                writeLock.withLock {
                    dao.replaceAllContacts(entries)
                    contactNames = entries.associate { it.phoneNumber to it.fullName }
                }

                onComplete?.invoke(entries.size)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse/upsert contacts JSON: ${e.message}", e)
                if (e is kotlinx.coroutines.CancellationException) throw e
                onComplete?.invoke(-1)
            }
        }
    }
}

