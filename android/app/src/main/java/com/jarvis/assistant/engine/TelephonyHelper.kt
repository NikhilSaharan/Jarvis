package com.jarvis.assistant.engine

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

data class DeviceContact(
    val name: String,
    val number: String,
    val isMobile: Boolean
)

class TelephonyHelper(private val context: Context) {

    private val contactList = mutableListOf<DeviceContact>()
    private var lastSyncTime = 0L

    fun resolveContactNumber(nameOrNumber: String): Pair<String?, String> {
        val query = nameOrNumber.trim()
        val digitsOnly = query.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 7) {
            return Pair(digitsOnly, query)
        }

        syncContactsIfNeeded()

        val lowerQuery = query.lowercase().trim()
        if (lowerQuery.isEmpty()) {
            return Pair(null, query)
        }

        // 1. EXACT Match (Case-insensitive)
        val exactMatches = contactList.filter { it.name.equals(lowerQuery, ignoreCase = true) }
        if (exactMatches.isNotEmpty()) {
            val best = exactMatches.find { it.isMobile } ?: exactMatches.first()
            return Pair(best.number, best.name.replaceFirstChar { it.uppercase() })
        }

        // 2. Full Word Match (e.g. "Manish Saharan" matches query "Manish")
        val wordMatches = contactList.filter { contact ->
            contact.name.split(Regex("\\s+")).any { word -> word.equals(lowerQuery, ignoreCase = true) }
        }
        if (wordMatches.isNotEmpty()) {
            val best = wordMatches.find { it.isMobile } ?: wordMatches.first()
            return Pair(best.number, best.name.replaceFirstChar { it.uppercase() })
        }

        // 3. Name Starts-With Match (e.g. "Manish..." matches query "Manish")
        val prefixMatches = contactList.filter { contact ->
            contact.name.startsWith(lowerQuery, ignoreCase = true)
        }
        if (prefixMatches.isNotEmpty()) {
            val best = prefixMatches.find { it.isMobile } ?: prefixMatches.first()
            return Pair(best.number, best.name.replaceFirstChar { it.uppercase() })
        }

        // 4. Substring Match in Contact Name (Only if query is at least 3 letters)
        if (lowerQuery.length >= 3) {
            val subMatches = contactList.filter { contact ->
                contact.name.contains(lowerQuery, ignoreCase = true)
            }
            if (subMatches.isNotEmpty()) {
                val best = subMatches.find { it.isMobile } ?: subMatches.first()
                return Pair(best.number, best.name.replaceFirstChar { it.uppercase() })
            }
        }

        return Pair(null, query)
    }

    private fun syncContactsIfNeeded() {
        if (contactList.isNotEmpty() && System.currentTimeMillis() - lastSyncTime < 300_000) {
            return
        }

        try {
            contactList.clear()
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC, ${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val typeIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)

                while (it.moveToNext()) {
                    val rawName = it.getString(nameIndex)?.trim() ?: continue
                    val rawNum = it.getString(numIndex)?.replace(Regex("[^0-9+]"), "") ?: continue
                    val phoneType = if (typeIndex >= 0) it.getInt(typeIndex) else -1

                    if (rawName.isEmpty() || rawNum.length < 5) continue

                    // Classify whether this is a genuine mobile number (10 digits, starts with 6-9, or TYPE_MOBILE)
                    val cleanDigits = rawNum.replace(Regex("[^0-9]"), "")
                    val isMobile = (phoneType == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE) ||
                            (cleanDigits.length == 10 && cleanDigits.first() in '6'..'9') ||
                            (cleanDigits.length == 12 && cleanDigits.startsWith("91") && cleanDigits[2] in '6'..'9')

                    contactList.add(DeviceContact(rawName.lowercase(), rawNum, isMobile))
                }
            }
            lastSyncTime = System.currentTimeMillis()
            Log.d("TelephonyHelper", "Loaded ${contactList.size} phone numbers from device contacts.")
        } catch (e: Exception) {
            Log.w("TelephonyHelper", "Contacts sync error: ${e.message}")
        }
    }
}
