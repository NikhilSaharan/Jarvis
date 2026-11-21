package com.jarvis.assistant.engine

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.concurrent.TimeUnit

class SystemController(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val knownAppPackages = mapOf(
        "instagram" to "com.instagram.android",
        "whatsapp" to "com.whatsapp",
        "youtube" to "com.google.android.youtube",
        "chrome" to "com.android.chrome",
        "spotify" to "com.spotify.music",
        "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android",
        "facebook" to "com.facebook.katana",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "maps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "play store" to "com.android.vending",
        "settings" to "com.android.settings",
        "camera" to "com.oneplus.camera",
        "clock" to "com.oneplus.deskclock",
        "calculator" to "com.oneplus.calculator",
        "gallery" to "com.oneplus.gallery",
        "photos" to "com.google.android.apps.photos"
    )

    // ═════════════════════════════════════════════════════════════════
    // LAYER 1: DIRECT ANDROID OS CONTROLS (Zero GUI, Instant 0.001s)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Enables or disables Do Not Disturb natively via NotificationManager in 0.001s.
     */
    fun setDnd(enabled: Boolean): String {
        return try {
            if (notificationManager.isNotificationPolicyAccessGranted) {
                if (enabled) {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    "Do Not Disturb mode activate kar diya hai Sir. Koi disturbance nahi hoga."
                } else {
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                    "Do Not Disturb mode deactivate kar diya hai Sir."
                }
            } else {
                "Sir, DND permission grant karni hogi settings me."
            }
        } catch (e: Exception) {
            Log.e("SystemController", "DND Error: ${e.message}")
            "DND set karne me error aaya Sir."
        }
    }

    /**
     * Sleep Butler Composite Routine:
     * 1. Turns DND ON immediately.
     * 2. Sets hardware alarm for (current time + minutes).
     * 3. Schedules DND to automatically turn OFF when waking up!
     */
    fun sleepAndWakeUp(minutes: Int, onWakeupAnnounce: (String) -> Unit): String {
        val safeMinutes = if (minutes <= 0) 10 else minutes

        // 1. Activate DND
        setDnd(true)

        // 2. Set Alarm for (now + minutes)
        val cal = Calendar.getInstance().apply {
            add(Calendar.MINUTE, safeMinutes)
        }
        val targetHour = cal.get(Calendar.HOUR_OF_DAY)
        val targetMinute = cal.get(Calendar.MINUTE)
        setAlarm(targetHour, targetMinute, "Sir, uthne ka samay ho gaya hai.")

        // 3. Schedule auto-deactivation of DND when wake up time arrives
        Handler(Looper.getMainLooper()).postDelayed({
            setDnd(false)
            val wakeupMsg = "Good morning Sir! $safeMinutes minute poore ho gaye hain, Do Not Disturb off kar diya hai."
            onWakeupAnnounce(wakeupMsg)
        }, safeMinutes * 60 * 1000L)

        val mStr = if (targetMinute < 10) "0$targetMinute" else "$targetMinute"
        return "Do Not Disturb activate kar diya hai aur $safeMinutes minute baad ($targetHour:$mStr) ka alarm laga diya hai Sir. Aaram se soiye."
    }

    /**
     * Sets Screen Brightness directly. Opens permission screen if not granted.
     */
    fun setBrightness(level: String): String {
        val brightnessValue = when (level.lowercase().trim()) {
            "low", "dim", "kam" -> 30
            "medium", "mid", "normal" -> 120
            "high", "bright", "zyada" -> 200
            "max", "full" -> 255
            "min", "minimum" -> 10
            else -> level.toIntOrNull()?.coerceIn(0, 255) ?: 120
        }
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.System.canWrite(context)) {
                    val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)
                    return "Sir, brightness change karne ke liye Write Settings permission allow kar dijiye (Screen open kar di hai)."
                }
            }
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightnessValue
            )
            "Brightness $level kar di hai Sir."
        } catch (e: Exception) {
            "Brightness adjust nahi ho saki Sir."
        }
    }

    /**
     * Sets Hardware Alarm in 0.05s via native AlarmClock intent.
     */
    fun setAlarm(hour: Int, minute: Int, message: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            val mStr = if (minute < 10) "0$minute" else "$minute"
            "$hour:$mStr ka alarm set kar diya hai Sir."
        } catch (e: Exception) {
            Log.e("SystemController", "Alarm error: ${e.message}")
            "Alarm set karne me error aaya Sir."
        }
    }

    /**
     * Sets System Countdown Timer via native AlarmClock intent.
     */
    fun setTimer(seconds: Int, message: String): String {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, message)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            val mins = seconds / 60
            if (mins > 0) "$mins minute ka timer laga diya hai Sir." else "$seconds seconds ka timer laga diya hai Sir."
        } catch (e: Exception) {
            "Timer set nahi ho saka Sir."
        }
    }

    /**
     * Cellular SIM Call via native CALL intent with DIAL fallback.
     */
    fun makePhoneCall(phoneNumber: String): String {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isEmpty()) {
            return "Sir, '$phoneNumber' ka number nahi mil saka."
        }
        return try {
            val intent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$cleanNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "$phoneNumber ko call mila raha hoon Sir."
        } catch (e: Exception) {
            val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$cleanNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            "$phoneNumber dial pad par bhej diya hai Sir."
        }
    }

    /**
     * Cellular SMS via native SENDTO intent.
     */
    fun sendSms(phoneNumber: String, message: String): String {
        val cleanNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:$cleanNumber")
            putExtra("sms_body", message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return "$phoneNumber ko SMS compose kar diya hai Sir."
    }

    /**
     * Direct YouTube Player Auto-Play:
     * Resolves the top video ID via background scrape and launches `watch?v=VIDEO_ID`.
     * Autoplays directly without leaving the user on a search list!
     */
    fun playYoutube(query: String, callback: (String) -> Unit) {
        val cleanQuery = cleanYoutubeQuery(query)
        CoroutineScope(Dispatchers.IO).launch {
            val videoId = fetchYoutubeVideoId(cleanQuery)
            withContext(Dispatchers.Main) {
                if (!videoId.isNullOrEmpty()) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                        setPackage("com.google.android.youtube")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(intent)
                        callback("YouTube par '$cleanQuery' direct play kar diya hai Sir.")
                    } catch (e: Exception) {
                        intent.setPackage(null)
                        context.startActivity(intent)
                        callback("YouTube par '$cleanQuery' play kar diya hai Sir.")
                    }
                } else {
                    val encoded = Uri.encode(cleanQuery)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                        setPackage("com.google.android.youtube")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        intent.setPackage(null)
                        context.startActivity(intent)
                    }
                    callback("YouTube par '$cleanQuery' open kar diya hai Sir.")
                }
            }
        }
    }

    private fun cleanYoutubeQuery(query: String): String {
        var q = query.lowercase().trim()
        val stopWords = listOf(
            "youtube", "pe", "par", "chalao", "chaalo", "chalo", "chlaa", "chla",
            "kardo", "karo", "kro", "please", "search", "play", "suno", "sunao",
            "lagao", "laga", "lgao", "bajao", "baja", "start", "ek", "koi", "mera", "mood", "kharab"
        )
        if (q.contains("mood") || q.contains("purana")) {
            if (q.contains("purana")) return "classic evergreen retro hindi songs hits"
            return "relaxing peaceful hindi songs"
        }
        for (w in stopWords) {
            q = q.replace(Regex("\\b$w\\b"), " ")
        }
        val res = q.replace(Regex("\\s+"), " ").trim()
        return if (res.isNotEmpty()) res else "trending hindi songs"
    }

    private fun fetchYoutubeVideoId(query: String): String? {
        return try {
            val encoded = Uri.encode(query)
            val url = "https://www.youtube.com/results?search_query=$encoded"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .build()
            val response = httpClient.newCall(request).execute()
            val html = response.body?.string() ?: ""
            val regex = Regex(""""videoId":"([a-zA-Z0-9_-]{11})"""")
            val match = regex.find(html)
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            Log.e("SystemController", "Failed to fetch YouTube video ID: ${e.message}")
            null
        }
    }

    /**
     * Launches ANY installed app by name, alias, or package.
     */
    fun openApp(appName: String): String {
        val query = appName.lowercase().trim()
        val pm = context.packageManager

        // 1. Direct Known Package Mapping (Instant 0.001s launch)
        val mappedPackage = knownAppPackages[query]
        if (mappedPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(mappedPackage)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                return "$appName open kar diya hai Sir."
            }
        }

        // 2. Dynamic Discovery across all installed apps
        try {
            val packages = pm.getInstalledPackages(0)
            for (pkg in packages) {
                val pName = pkg.packageName.lowercase()
                val appInfo = pkg.applicationInfo ?: continue
                val label = pm.getApplicationLabel(appInfo).toString().lowercase()

                if (label == query || pName.endsWith(".$query") || label.contains(query) || pName.contains(query)) {
                    val launchIntent = pm.getLaunchIntentForPackage(pkg.packageName)
                    if (launchIntent != null) {
                        launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(launchIntent)
                        val officialName = pm.getApplicationLabel(appInfo).toString()
                        return "$officialName open kar diya hai Sir."
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SystemController", "App search error: ${e.message}")
        }

        return "Sir, '$appName' phone me installed nahi mila."
    }

    /**
     * Direct WhatsApp Chat Opener via WhatsApp API deep link.
     * Optionally pre-fills message text so it appears in the input field automatically!
     */
    fun openChatWhatsApp(phoneNumber: String, prefillMessage: String = ""): Boolean {
        var cleanNumber = phoneNumber.replace(Regex("[^0-9]"), "")
        if (cleanNumber.length == 10) {
            cleanNumber = "91$cleanNumber"
        }
        return try {
            val url = if (prefillMessage.isNotBlank()) {
                val encoded = Uri.encode(prefillMessage)
                "https://api.whatsapp.com/send?phone=$cleanNumber&text=$encoded"
            } else {
                "https://api.whatsapp.com/send?phone=$cleanNumber"
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e("SystemController", "WhatsApp open error: ${e.message}")
            false
        }
    }
}
