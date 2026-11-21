package com.jarvis.assistant.memory

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

class JarvisMemoryManager(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("jarvis_living_memory", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ═════════════════════════════════════════════════════════════════
    // 1. VIP PRIORITY CONTACTS (Bypass Silent & DND)
    // ═════════════════════════════════════════════════════════════════

    fun getVipContacts(): List<String> {
        val json = prefs.getString("vip_contacts_list", null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun addVipContact(contactNameOrNumber: String): Boolean {
        val clean = contactNameOrNumber.trim()
        if (clean.isBlank()) return false
        val current = getVipContacts().toMutableList()
        if (!current.any { it.equals(clean, ignoreCase = true) }) {
            current.add(clean)
            prefs.edit().putString("vip_contacts_list", gson.toJson(current)).apply()
            return true
        }
        return false
    }

    fun removeVipContact(contactNameOrNumber: String): Boolean {
        val clean = contactNameOrNumber.trim()
        val current = getVipContacts().toMutableList()
        val removed = current.removeAll { it.equals(clean, ignoreCase = true) }
        if (removed) {
            prefs.edit().putString("vip_contacts_list", gson.toJson(current)).apply()
        }
        return removed
    }

    fun isVip(nameOrNumber: String): Boolean {
        val clean = nameOrNumber.lowercase().trim()
        val digits = clean.replace(Regex("[^0-9]"), "")
        val vips = getVipContacts()
        return vips.any { vip ->
            val vClean = vip.lowercase().trim()
            val vDigits = vClean.replace(Regex("[^0-9]"), "")
            vClean.equals(clean, ignoreCase = true) ||
                    (clean.isNotBlank() && clean.contains(vClean)) ||
                    (vDigits.length >= 7 && digits.contains(vDigits))
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 2. LEARNED PREFERENCES & HABITS (Self-Evolving Brain)
    // ═════════════════════════════════════════════════════════════════

    fun getPreferences(): List<String> {
        val json = prefs.getString("user_preferences_list", null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun learnPreference(preferenceText: String) {
        val clean = preferenceText.trim()
        if (clean.isBlank()) return
        val current = getPreferences().toMutableList()
        if (!current.contains(clean)) {
            current.add(clean)
            prefs.edit().putString("user_preferences_list", gson.toJson(current)).apply()
        }
    }

    // ═════════════════════════════════════════════════════════════════
    // 3. MISSED VIP CALLS TRACKER
    // ═════════════════════════════════════════════════════════════════

    fun recordMissedVipCall(callerName: String) {
        val list = getMissedVipCalls().toMutableList()
        list.add(callerName)
        prefs.edit().putString("missed_vip_calls", gson.toJson(list)).apply()
    }

    fun getMissedVipCalls(): List<String> {
        val json = prefs.getString("missed_vip_calls", null) ?: return emptyList()
        val type = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    fun popPendingMissedCallNotice(): String? {
        val list = getMissedVipCalls()
        if (list.isEmpty()) return null
        prefs.edit().remove("missed_vip_calls").apply()
        val names = list.distinct().joinToString(", ")
        return "Sir, $names ka zaroori VIP call miss hua hai."
    }

    // ═════════════════════════════════════════════════════════════════
    // 4. SCREEN ACTIVITY & WELLNESS CONTEXT (Tony Stark's J.A.R.V.I.S.)
    // ═════════════════════════════════════════════════════════════════

    fun recordScreenFocus(appLabel: String) {
        val now = System.currentTimeMillis()
        val lastApp = prefs.getString("active_app_label", "")
        val lastStartTime = prefs.getLong("active_app_start", now)

        if (lastApp != appLabel) {
            prefs.edit()
                .putString("active_app_label", appLabel)
                .putLong("active_app_start", now)
                .apply()
        }
    }

    fun getLivingMemoryContext(): String {
        val sb = StringBuilder()

        // VIP list
        val vips = getVipContacts()
        if (vips.isNotEmpty()) {
            sb.append("- Sir's VIP Contacts (High Priority, Bypass Silent/DND): ").append(vips.joinToString(", ")).append("\n")
        }

        // Learned Habits
        val prefsList = getPreferences()
        if (prefsList.isNotEmpty()) {
            sb.append("- Sir's Learned Habits & Rules: ").append(prefsList.joinToString("; ")).append("\n")
        }

        // Time and Wellness
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val activeApp = prefs.getString("active_app_label", "")
        val startTime = prefs.getLong("active_app_start", System.currentTimeMillis())
        val minutesOnApp = ((System.currentTimeMillis() - startTime) / 60000L).coerceAtLeast(0)

        if (hour in 0..4) {
            sb.append("- Wellness Notice: It is currently late at night ($hour:00). Sir is active on $activeApp. Be caring, polite, and mindful of Sir's rest.\n")
        } else if (minutesOnApp > 90) {
            sb.append("- Wellness Notice: Sir has been continuously active on $activeApp for over $minutesOnApp minutes. Offer gentle butler care if relevant.\n")
        }

        return sb.toString().trim()
    }
}
