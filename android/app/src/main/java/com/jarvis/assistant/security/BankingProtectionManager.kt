package com.jarvis.assistant.security

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.JarvisAccessibilityService

object BankingProtectionManager {

    private const val TAG = "BankingShield"

    // Supported Indian UPI & Banking apps
    val PROTECTED_BANKING_PACKAGES = setOf(
        "com.phonepe.app",                                // PhonePe
        "com.google.android.apps.nbu.paisa.user",          // Google Pay (Tez)
        "net.one97.paytm",                                 // Paytm
        "in.org.npci.upiapp",                              // BHIM
        "com.sbi.upi",                                     // BHIM SBI Pay
        "com.msf.kbank.mobile",                            // Kotak 811
        "com.axis.mobile",                                 // Axis Mobile
        "com.csam.icici.bank.imobile",                     // iMobile Pay (ICICI)
        "com.snapwork.hdfc",                               // HDFC Bank MobileBanking
        "com.hdfcbank.payzapp",                            // PayZapp
        "com.dreamplug.androidapp",                        // CRED
        "com.mobikwik_new",                                // MobiKwik
        "com.freecharge.android"                           // Freecharge
    )

    @Volatile
    var isShieldActive: Boolean = false
        private set

    @Volatile
    var lastProtectedApp: String? = null
        private set

    fun isBankingApp(packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        val clean = packageName.trim().lowercase()
        return PROTECTED_BANKING_PACKAGES.contains(clean)
    }

    fun hasSecureSettingsPermission(context: Context): Boolean {
        return context.checkCallingOrSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED
    }

    fun hasUsageStatsPermission(context: Context): Boolean {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val time = System.currentTimeMillis()
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
        return !stats.isNullOrEmpty()
    }

    /**
     * Triggered immediately when a window state change indicates a banking app is opening.
     * Detaches Jarvis from the active accessibility services so the banking app sees a clean OS.
     */
    fun onPackageOpened(context: Context, packageName: String?): Boolean {
        if (!isBankingApp(packageName)) return false
        if (!hasSecureSettingsPermission(context)) {
            Log.w(TAG, "Banking app '$packageName' opened, but WRITE_SECURE_SETTINGS permission is missing!")
            return false
        }

        lastProtectedApp = packageName
        return detachAccessibility(context)
    }

    /**
     * Temporarily unregisters JarvisAccessibilityService from Android OS.
     */
    fun detachAccessibility(context: Context): Boolean {
        try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val myService = "${context.packageName}/${JarvisAccessibilityService::class.java.name}"
            val myServiceShort = "${context.packageName}/.JarvisAccessibilityService"

            if (!current.contains(context.packageName)) {
                isShieldActive = true
                return true
            }

            val filtered = current.split(":")
                .filter { it.isNotBlank() && it != myService && it != myServiceShort }
                .joinToString(":")

            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, filtered)
            if (filtered.isBlank()) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            }

            isShieldActive = true
            Log.i(TAG, "Jarvis Accessibility successfully detached for banking protection!")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching accessibility: ${e.message}")
            return false
        }
    }

    /**
     * Re-registers JarvisAccessibilityService in Android OS once banking app is exited.
     */
    fun reattachAccessibility(context: Context): Boolean {
        try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val myService = "${context.packageName}/${JarvisAccessibilityService::class.java.name}"

            val list = current.split(":").filter { it.isNotBlank() }.toMutableSet()
            list.add(myService)

            val updated = list.joinToString(":")
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)

            isShieldActive = false
            lastProtectedApp = null
            Log.i(TAG, "Jarvis Accessibility re-attached successfully!")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error reattaching accessibility: ${e.message}")
            return false
        }
    }

    /**
     * Checks what app is currently in foreground via UsageStatsManager.
     */
    fun getForegroundPackage(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val time = System.currentTimeMillis()
        val events = usm.queryEvents(time - 6000, time) ?: return null
        val event = UsageEvents.Event()
        var lastPkg: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                lastPkg = event.packageName
            }
        }
        return lastPkg
    }
}
