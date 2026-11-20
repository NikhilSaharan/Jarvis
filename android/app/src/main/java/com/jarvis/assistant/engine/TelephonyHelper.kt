package com.jarvis.assistant.engine

import android.content.Context
import android.provider.ContactsContract
import android.util.Log

class TelephonyHelper(private val context: Context) {

    private val contactsCache = mutableMapOf<String, String>()
    private var lastSyncTime = 0L

    fun resolveContactNumber(nameOrNumber: String): Pair<String?, String> {
        val query = nameOrNumber.trim()
        val digitsOnly = query.replace(Regex("[^0-9+]"), "")
        if (digitsOnly.length >= 7) {
            return Pair(digitsOnly, query)
        }

        syncContactsIfNeeded()

        val lowerQuery = query.lowercase()
        // Exact match
        contactsCache[lowerQuery]?.let {
            return Pair(it, query)
        }

        // Substring / Token match
        for ((name, num) in contactsCache) {
            if (name.contains(lowerQuery) || lowerQuery.contains(name)) {
                return Pair(num, name.replaceFirstChar { it.uppercase() })
            }
        }

        return Pair(null, query)
    }

    private fun syncContactsIfNeeded() {
        if (contactsCache.isNotEmpty() && System.currentTimeMillis() - lastSyncTime < 300_000) {
            return
        }

        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                null
            )

            cursor?.use {
                val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val name = it.getString(nameIndex)?.trim()?.lowercase() ?: continue
                    val num = it.getString(numIndex)?.replace(Regex("[^0-9+]"), "") ?: continue
                    if (name.isNotEmpty() && num.isNotEmpty()) {
                        contactsCache[name] = num
                    }
                }
            }
            lastSyncTime = System.currentTimeMillis()
            Log.d("TelephonyHelper", "Synced ${contactsCache.size} contacts from device.")
        } catch (e: Exception) {
            Log.w("TelephonyHelper", "Contacts sync error: ${e.message}")
        }
    }
}
