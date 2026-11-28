package com.jarvis.assistant.security

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Rect
import android.os.PowerManager
import android.util.Log
import com.jarvis.assistant.JarvisAccessibilityService
import com.jarvis.assistant.brain.GeminiBrain
import kotlinx.coroutines.delay

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

    fun parsePatternDots(raw: String): List<Int> {
        return raw.split(Regex("[,\\-\\s]+")).mapNotNull { it.toIntOrNull() }.filter { it in 1..9 }
    }

    fun formatPatternDots(dots: List<Int>): String {
        return dots.joinToString("-")
    }

    fun isScreenLockedOrOff(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val locked = km?.isKeyguardLocked == true
        val screenOff = pm?.isInteractive == false
        return locked || screenOff
    }

    /**
     * Checks if screen is locked/off and dynamically unlocks it BEFORE returning.
     * If already awake & unlocked, returns true immediately in <1ms without delay.
     */
    suspend fun ensureScreenUnlocked(context: Context): Boolean {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        if (km?.isKeyguardLocked != true && pm?.isInteractive == true) {
            return true
        }

        val service = JarvisAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "Cannot unlock screen: Accessibility service not active.")
            return false
        }

        val lockType = getScreenLockType(context)
        val lockValue = getScreenLockValue(context)

        Log.d(TAG, "Screen locked/off detected. Waking & unlocking (configured=$lockType)...")

        // Only swipe up if screen is asleep or keypad/pattern isn't visible yet
        if (pm?.isInteractive == false || !service.hasVisibleUnlockPrompt()) {
            service.wakeScreenAndSwipeUp()
            // Poll fast (45ms) for keypad/pattern prompt or instant swipe/biometric unlock
            val promptWaitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - promptWaitStart < 750L) {
                if (km?.isKeyguardLocked != true) {
                    Log.d(TAG, "Screen unlocked after swipe-up!")
                    return true
                }
                if (service.hasVisibleUnlockPrompt()) break
                delay(45)
            }

            // One crisp retry swipe if screen just finished waking from Deep Sleep / AOD
            if (km?.isKeyguardLocked == true && !service.hasVisibleUnlockPrompt()) {
                service.wakeScreenAndSwipeUp()
                val retryWaitStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - retryWaitStart < 700L) {
                    if (km?.isKeyguardLocked != true) return true
                    if (service.hasVisibleUnlockPrompt()) break
                    delay(45)
                }
            }
        }

        if (km?.isKeyguardLocked != true) return true

        if (lockType == TYPE_NONE || lockValue.isBlank()) {
            Log.w(TAG, "Phone has lock prompt, but no screen PIN/Pattern configured.")
            return false
        }

        val entered = when (lockType) {
            TYPE_PIN -> service.enterPinDynamic(lockValue) { km?.isKeyguardLocked == true }
            TYPE_PATTERN -> {
                val dots = parsePatternDots(lockValue)
                if (dots.size >= 2) service.drawPatternDynamic(dots) else false
            }
            else -> false
        }

        if (!entered) {
            Log.w(TAG, "Could not locate lock screen unlock UI nodes dynamically.")
            return false
        }

        // Fast dynamic poll until Keyguard dismisses & SystemUI lock overlay clears
        val dismissStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - dismissStart < 1200L) {
            if (km?.isKeyguardLocked != true) {
                val settleStart = System.currentTimeMillis()
                while (service.hasVisibleUnlockPrompt() && System.currentTimeMillis() - settleStart < 380L) {
                    delay(40)
                }
                Log.d(TAG, "Screen successfully unlocked in ${System.currentTimeMillis() - dismissStart}ms!")
                return true
            }
            delay(45)
        }
        return km?.isKeyguardLocked != true
    }

    /**
     * Dynamically detects whether ANY active window is currently showing an App Lock / Privacy Lock / Biometric Lock.
     * Inspects ALL windows (including system overlays & OEM safecenter) and guards against normal login pages.
     */
    fun isAppLockScreen(service: JarvisAccessibilityService): Boolean {
        val activePackages = service.getAllActiveWindowPackages().map { it.lowercase() }

        val oemAppLockPkgMatch = activePackages.any { pkg ->
            pkg.contains("safecenter") ||
                pkg.contains("applock") ||
                pkg.contains("securitycenter") ||
                pkg.contains("privacy") ||
                pkg.contains("biometric")
        }
        if (oemAppLockPkgMatch) return true

        val summary = service.dumpAllWindowsElementsSummary().lowercase()
        if (summary.isBlank()) return false

        // Guard against normal in-app login forms (Instagram Login, Email Sign-In, etc.)
        val looksLikeAccountLogin = summary.contains("sign up") ||
            summary.contains("create account") ||
            summary.contains("forgot password") ||
            summary.contains("username") ||
            summary.contains("email address") ||
            summary.contains("log in with")
        if (looksLikeAccountLogin) return false

        val strongLockKeywords = listOf(
            "use privacy password",
            "privacy password",
            "enter privacy password",
            "app lock",
            "unlock whatsapp",
            "whatsapp locked",
            "confirm your pin",
            "confirm your pattern",
            "confirm screen lock",
            "use screen lock",
            "verify your identity",
            "verify it's you",
            "touch the fingerprint",
            "fingerprint sensor",
            "use fingerprint",
            "draw unlock pattern",
            "draw your pattern"
        )
        if (strongLockKeywords.any { summary.contains(it) }) return true

        // Fallback/biometric switch button paired with lock prompt context
        val hasSwitchAndLockContext = (summary.contains("use pin") || summary.contains("use password") || summary.contains("use pattern")) &&
            (summary.contains("lock") || summary.contains("unlock") || summary.contains("verify") || summary.contains("biometric") || summary.contains("fingerprint"))

        return hasSwitchAndLockContext
    }

    /**
     * Automatically taps "Use privacy password / Use PIN / Use Pattern" across ANY phone's App Lock,
     * waits for credential entry screen, and dynamically unlocks using saved App Lock PIN or Pattern.
     */
    suspend fun unlockAppLockIfPresent(
        context: Context,
        geminiBrain: GeminiBrain? = null
    ): Boolean {
        val service = JarvisAccessibilityService.instance ?: return false
        if (!isAppLockScreen(service)) return false

        val appLockType = getAppLockType(context)
        val (effType, effValue) = if (appLockType == TYPE_SAME_AS_SCREEN) {
            Pair(getScreenLockType(context), getScreenLockValue(context))
        } else {
            Pair(appLockType, getAppLockValue(context))
        }

        if (effType == TYPE_NONE || effValue.isBlank()) {
            Log.w(TAG, "App Lock detected, but no App Lock PIN/Pattern configured.")
            return false
        }

        Log.d(TAG, "App Lock screen detected. Attempting dynamic unlock with $effType...")

        // Step 1: Only if PIN/Pattern input isn't visible yet (e.g. Fingerprint prompt first),
        // dynamically tap "Use privacy password / Use PIN / Use Pattern"
        if (!service.hasVisibleUnlockPrompt()) {
            val switched = trySwitchToPasswordEntry(service, geminiBrain)
            if (switched) {
                val switchWaitStart = System.currentTimeMillis()
                while (System.currentTimeMillis() - switchWaitStart < 1000L) {
                    if (service.hasVisibleUnlockPrompt() || !isAppLockScreen(service)) break
                    delay(45)
                }
            }
        }

        if (!isAppLockScreen(service)) return true

        // Step 2: Dynamically enter PIN or Pattern from live window nodes (zero hardcoded coordinates)
        val entered = when (effType) {
            TYPE_PIN -> service.enterPinDynamic(effValue) { isAppLockScreen(service) }
            TYPE_PATTERN -> {
                val dots = parsePatternDots(effValue)
                if (dots.size >= 2) service.drawPatternDynamic(dots) else false
            }
            else -> false
        }

        if (!entered) {
            Log.w(TAG, "Dynamic App Lock input could not find accessible PIN/Pattern nodes on this OEM window.")
            return false
        }

        val clearWaitStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - clearWaitStart < 1100L) {
            if (!isAppLockScreen(service)) {
                Log.d(TAG, "App Lock unlocked successfully!")
                return true
            }
            delay(45)
        }

        val unlocked = !isAppLockScreen(service)
        Log.d(TAG, "App Lock unlock completed. Unlocked=$unlocked")
        return unlocked
    }

    /**
     * Finds and taps the dynamic button that switches biometric/fingerprint prompts
     * to Privacy Password / PIN / Pattern entry across OnePlus, Xiaomi, Samsung, Pixel, WhatsApp, etc.
     */
    private suspend fun trySwitchToPasswordEntry(
        service: JarvisAccessibilityService,
        geminiBrain: GeminiBrain?
    ): Boolean {
        val switchPhrases = listOf(
            "use privacy password",
            "enter privacy password",
            "verify privacy password",
            "use screen lock",
            "use password",
            "use pattern",
            "use pin",
            "privacy password",
            "verify password",
            "enter password",
            "enter pin"
        )
        val forbiddenWords = listOf("forgot", "reset", "cancel", "help", "change")

        // Deterministic fast search across all active windows
        for (phrase in switchPhrases) {
            val btn = service.findNodeAcrossWindows(resolveClickableParent = true) { node ->
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() <= 0 || bounds.height() <= 0) return@findNodeAcrossWindows false

                val txt = node.text?.toString()?.trim()?.lowercase() ?: ""
                val cd = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                val combined = "$txt $cd".trim()

                if (forbiddenWords.any { combined.contains(it) }) return@findNodeAcrossWindows false
                val matches = txt == phrase || cd == phrase || txt.contains(phrase) || cd.contains(phrase)
                val clickableSelfOrParent = node.isClickable || node.parent?.isClickable == true
                matches && clickableSelfOrParent
            }
            if (btn != null) {
                Log.d(TAG, "Tapping dynamic App Lock switch button matching '$phrase'")
                service.tapNodeDynamically(btn, allowDualTap = true)
                return true
            }
        }

        // Fallback: If unfamiliar OEM wording is shown and no keypad/pattern is visible yet, ask Gemini ONLY on screen UI labels
        if (!service.hasVisibleUnlockPrompt() && geminiBrain != null) {
            val elements = service.dumpAllWindowsElements()
            val summary = service.dumpAllWindowsElementsSummary()
            if (summary.isNotBlank() && !summary.contains("secure window")) {
                val prompt = """
You are looking at an Android App Lock / Biometric prompt screen:
$summary

Task: If there is a button to switch from fingerprint/face unlock to entering Privacy Password, PIN, Pattern, or Password, choose "tap_tag" with its [tag].
NEVER tap Cancel, Forgot password, or Emergency.
If password/PIN/pattern input is already ready on screen or no switch button exists, choose "done".
OUTPUT ONLY RAW JSON:
{"action":"tap_tag"|"done","tag":<number or null>,"text":null,"direction":null,"summary":null}
""".trimIndent()
                val decision = geminiBrain.decideUIAction(prompt)
                if (decision.action.equals("tap_tag", ignoreCase = true) && decision.tag != null) {
                    val target = elements.find { it.tag == decision.tag }
                    if (target != null) {
                        Log.d(TAG, "Gemini dynamic fallback tapping App Lock tag [${decision.tag}]")
                        service.tapNodeDynamically(target.node, allowDualTap = true)
                        return true
                    }
                }
            }
        }

        return false
    }
}
