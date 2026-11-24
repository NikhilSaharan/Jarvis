package com.jarvis.assistant.security

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.jarvis.assistant.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object AutoUnlockManager {

    private const val TAG = "AutoUnlockManager"
    private const val PREFS_NAME = "jarvis_security_vault"

    const val TYPE_NONE = "NONE"
    const val TYPE_PIN = "PIN"
    const val TYPE_PATTERN = "PATTERN"
    const val TYPE_SAME_AS_SCREEN = "SAME_AS_SCREEN"

    private const val KEY_SCREEN_LOCK_TYPE = "screen_lock_type"
    private const val KEY_SCREEN_LOCK_VALUE = "screen_lock_value"
    private const val KEY_APP_LOCK_TYPE = "app_lock_type"
    private const val KEY_APP_LOCK_VALUE = "app_lock_value"

    private val scope = CoroutineScope(Dispatchers.Main)

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveCredentials(
        context: Context,
        screenLockType: String,
        screenLockValue: String,
        appLockType: String,
        appLockValue: String
    ) {
        getPrefs(context).edit().apply {
            putString(KEY_SCREEN_LOCK_TYPE, screenLockType)
            putString(KEY_SCREEN_LOCK_VALUE, screenLockValue.trim())
            putString(KEY_APP_LOCK_TYPE, appLockType)
            putString(KEY_APP_LOCK_VALUE, appLockValue.trim())
            apply()
        }
        Log.d(TAG, "Credentials saved: screenLockType=$screenLockType, appLockType=$appLockType")
    }

    fun getScreenLockType(context: Context): String {
        return getPrefs(context).getString(KEY_SCREEN_LOCK_TYPE, TYPE_NONE) ?: TYPE_NONE
    }

    fun getScreenLockValue(context: Context): String {
        return getPrefs(context).getString(KEY_SCREEN_LOCK_VALUE, "") ?: ""
    }

    fun getAppLockType(context: Context): String {
        return getPrefs(context).getString(KEY_APP_LOCK_TYPE, TYPE_SAME_AS_SCREEN) ?: TYPE_SAME_AS_SCREEN
    }

    fun getAppLockValue(context: Context): String {
        return getPrefs(context).getString(KEY_APP_LOCK_VALUE, "") ?: ""
    }

    /**
     * Automatically wakes up screen, swipes up, and enters PIN or Pattern if screen is locked.
     */
    fun unlockScreenIfLocked(
        context: Context,
        service: JarvisAccessibilityService,
        onComplete: (Boolean) -> Unit
    ) {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = km?.isKeyguardLocked == true

        if (!isLocked) {
            onComplete(true)
            return
        }

        val lockType = getScreenLockType(context)
        val lockValue = getScreenLockValue(context)

        if (lockType == TYPE_NONE || lockValue.isBlank()) {
            Log.w(TAG, "Phone is locked, but no unlock credentials configured in J.A.R.V.I.S.")
            onComplete(false)
            return
        }

        scope.launch {
            Log.d(TAG, "Unlocking screen with $lockType...")
            service.wakeAndSwipeUp()
            delay(500) // Allow keypad / pattern transition

            when (lockType) {
                TYPE_PIN -> {
                    service.tapPinDigits(lockValue)
                }
                TYPE_PATTERN -> {
                    val dots = parsePatternDots(lockValue)
                    if (dots.size >= 2) {
                        service.drawPattern(dots)
                    }
                }
            }

            delay(700)
            val stillLocked = km?.isKeyguardLocked == true
            Log.d(TAG, "Screen unlock finished. Still locked: $stillLocked")
            onComplete(!stillLocked)
        }
    }

    /**
     * Checks if current foreground window is an App Lock or Privacy Password prompt.
     */
    fun isAppLockScreen(service: JarvisAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        val pkg = root.packageName?.toString()?.lowercase() ?: ""

        // Common OEM App Lock packages (ColorOS/OxygenOS safecenter, Samsung Secure Folder, Xiaomi, etc.)
        if (pkg.contains("safecenter") || pkg.contains("privacy") || pkg.contains("applock") || pkg.contains("password")) {
            return true
        }

        // Screen text heuristic for in-app biometric/pin lock (WhatsApp, Instagram, etc.)
        val summary = service.dumpScreenElementsSummary().lowercase()
        val appLockKeywords = listOf(
            "privacy password",
            "enter pin",
            "draw pattern",
            "app lock",
            "unlock whatsapp",
            "touch the fingerprint",
            "verify it's you",
            "enter password to unlock",
            "use pin",
            "use password"
        )

        return appLockKeywords.any { summary.contains(it) }
    }

    /**
     * Automatically enters App Lock PIN or Pattern if an App Lock screen is visible.
     */
    fun unlockAppLockIfPresent(
        context: Context,
        service: JarvisAccessibilityService,
        onComplete: (Boolean) -> Unit
    ) {
        if (!isAppLockScreen(service)) {
            onComplete(false)
            return
        }

        val appLockType = getAppLockType(context)
        val (effType, effValue) = if (appLockType == TYPE_SAME_AS_SCREEN) {
            Pair(getScreenLockType(context), getScreenLockValue(context))
        } else {
            Pair(appLockType, getAppLockValue(context))
        }

        if (effType == TYPE_NONE || effValue.isBlank()) {
            Log.w(TAG, "App Lock detected, but no app lock credentials configured.")
            onComplete(false)
            return
        }

        scope.launch {
            Log.d(TAG, "Auto-unlocking App Lock using $effType...")

            // If a "Use PIN" or "Use password" button is shown (e.g. fingerprint fallback), tap it
            val usePinBtn = service.findClickableNode("Use PIN")
                ?: service.findClickableNode("Use password")
                ?: service.findClickableNode("Password")
                ?: service.findClickableNode("PIN")
            usePinBtn?.let {
                service.clickNode(it)
                delay(300)
            }

            when (effType) {
                TYPE_PIN -> {
                    service.tapPinDigits(effValue)
                }
                TYPE_PATTERN -> {
                    val dots = parsePatternDots(effValue)
                    if (dots.size >= 2) {
                        service.drawPattern(dots)
                    }
                }
            }

            delay(800) // Allow app to open after unlocking
            val stillLocked = isAppLockScreen(service)
            Log.d(TAG, "App Lock unlock finished. Still locked: $stillLocked")
            onComplete(!stillLocked)
        }
    }

    private fun parsePatternDots(raw: String): List<Int> {
        return raw.split(Regex("[,\\-\\s]+")).mapNotNull { it.toIntOrNull() }.filter { it in 1..9 }
    }
}
