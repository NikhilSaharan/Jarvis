package com.jarvis.assistant.engine

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.TimeUnit

data class AppCandidate(val label: String, val packageName: String, val score: Int)

class SystemController(private val context: Context) {

    val appContext: Context
        get() = context.applicationContext

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var torchEnabledState = false

    @Volatile
    private var torchCallbackRegistered = false

    private fun ensureTorchCallbackRegistered() {
        val cm = cameraManager ?: return
        if (torchCallbackRegistered) return
        try {
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    torchEnabledState = enabled
                }
            }, Handler(Looper.getMainLooper()))
            torchCallbackRegistered = true
        } catch (_: Exception) {
        }
    }

    init {
        ensureTorchCallbackRegistered()
    }

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
        "photos" to "com.google.android.apps.photos",
        "cricbuzz" to "com.cricbuzz.android",
        "notes" to "com.oneplus.note",
        "keep" to "com.google.android.keep"
    )

    // ═════════════════════════════════════════════════════════════════
    // LAYER 1: DIRECT ANDROID OS CONTROLS (Zero GUI, Instant 0.001s)
    // ═════════════════════════════════════════════════════════════════

    fun doesPackageMatchAppName(activePkg: String, appName: String): Boolean {
        val pkgLower = activePkg.lowercase().trim()
        val q = appName.lowercase().trim()
        if (pkgLower.isBlank() || q.isBlank()) return false
        if (knownAppPackages[q]?.lowercase() == pkgLower) return true
        if (pkgLower.contains(q)) return true
        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(activePkg, 0)
            val label = pm.getApplicationLabel(appInfo).toString().lowercase()
            label == q || label.contains(q) || q.contains(label)
        } catch (_: Exception) {
            false
        }
    }

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
    fun sleepAndWakeUp(minutes: Int, onWakeupAnnounce: ((String) -> Unit)? = null): String {
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
            onWakeupAnnounce?.invoke(wakeupMsg)
        }, safeMinutes * 60 * 1000L)

        val mStr = if (targetMinute < 10) "0$targetMinute" else "$targetMinute"
        return "Do Not Disturb activate kar diya hai aur $safeMinutes minute baad ($targetHour:$mStr) ka alarm laga diya hai Sir. Aaram se soiye."
    }

    /**
     * Dynamically resolves the device's real brightness range (e.g., 0..255 on Pixel/AOSP,
     * 0..4095 on OnePlus/OxygenOS/ColorOS) and adjusts screen brightness accurately.
     */
    fun setBrightness(level: String): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return "Sir, brightness change karne ke liye Write Settings permission allow kar dijiye (Screen open kar di hai)."
            }

            val current = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 120)
            val sysRes = Resources.getSystem()
            val maxResId = sysRes.getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
            val minResId = sysRes.getIdentifier("config_screenBrightnessSettingMinimum", "integer", "android")
            var minB = if (minResId != 0) sysRes.getInteger(minResId).coerceAtLeast(1) else 1
            var maxB = if (maxResId != 0) sysRes.getInteger(maxResId).coerceAtLeast(255) else 255
            if (current > maxB) {
                maxB = when {
                    current > 2047 -> 4095
                    current > 1023 -> 2047
                    else -> 1023
                }
            }
            if (minB >= maxB) minB = 1

            val norm = level.lowercase().trim().removeSuffix("%").trim()
            val targetBrightness = when (norm) {
                "min", "minimum", "zero", "0", "bilkul kam", "lowest" -> minB
                "low", "dim", "kam" -> (minB + (maxB - minB) * 0.12f).toInt()
                "medium", "mid", "normal", "half", "50" -> (minB + (maxB - minB) * 0.48f).toInt()
                "high", "bright", "zyada", "tezz" -> (minB + (maxB - minB) * 0.82f).toInt()
                "max", "full", "highest", "100" -> maxB
                "up", "increase", "badhao" -> (current + ((maxB - minB) * 0.30f).toInt()).coerceAtMost(maxB)
                "down", "decrease", "kam karo", "thoda kam" -> (current - ((maxB - minB) * 0.30f).toInt()).coerceAtLeast(minB)
                else -> {
                    val num = norm.toIntOrNull()
                    when {
                        num == null -> (minB + (maxB - minB) * 0.48f).toInt()
                        num <= 0 -> minB
                        num <= 100 -> (minB + (maxB - minB) * (num / 100f)).toInt()
                        else -> num.coerceIn(minB, maxB)
                    }
                }
            }.coerceIn(minB, maxB)

            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                targetBrightness
            )
            val pct = ((targetBrightness - minB) * 100f / (maxB - minB).coerceAtLeast(1)).toInt().coerceIn(0, 100)
            "Brightness $pct% kar di hai Sir."
        } catch (e: Exception) {
            Log.e("SystemController", "Brightness error: ${e.message}")
            "Brightness adjust nahi ho saki Sir."
        }
    }

    /**
     * Toggles or explicitly sets the device camera flashlight/torch via CameraManager.
     */
    fun setFlashlight(enabled: Boolean?): String {
        val cm = cameraManager ?: return "Sir, flashlight control available nahi hai."
        ensureTorchCallbackRegistered()
        return try {
            var chosenId: String? = null
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (!hasFlash) continue
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    chosenId = id
                    break
                }
                if (chosenId == null) chosenId = id
            }
            val camId = chosenId ?: return "Is device par flashlight unit nahi mila Sir."
            val target = enabled ?: !torchEnabledState
            cm.setTorchMode(camId, target)
            torchEnabledState = target
            if (target) "Flashlight on kar di hai Sir." else "Flashlight off kar di hai Sir."
        } catch (e: Exception) {
            Log.e("SystemController", "Flashlight error: ${e.message}")
            "Flashlight toggle nahi ho saki Sir (camera busy ho sakta hai)."
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
     * Resolves the top video ID via InnerTube ANDROID API / Web search with consent cookies,
     * and launches `watch?v=VIDEO_ID`. Reports whether direct playback succeeded so caller
     * can auto-tap first video via Accessibility if search UI had to be used as fallback.
     */
    fun playYoutube(query: String, callback: ((String, Boolean) -> Unit)? = null) {
        val cleanQuery = cleanYoutubeQuery(query)
        CoroutineScope(Dispatchers.IO).launch {
            val videoId = fetchYoutubeVideoId(cleanQuery)
            withContext(Dispatchers.Main) {
                if (!videoId.isNullOrEmpty()) {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                        setPackage("com.google.android.youtube")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    try {
                        context.startActivity(intent)
                        callback?.invoke("YouTube par '$cleanQuery' direct play kar diya hai Sir.", true)
                    } catch (e: Exception) {
                        intent.setPackage(null)
                        context.startActivity(intent)
                        callback?.invoke("YouTube par '$cleanQuery' play kar diya hai Sir.", true)
                    }
                } else {
                    val encoded = Uri.encode(cleanQuery)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
                        setPackage("com.google.android.youtube")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        intent.setPackage(null)
                        context.startActivity(intent)
                    }
                    callback?.invoke("YouTube par '$cleanQuery' open kar diya hai Sir.", false)
                }
            }
        }
    }

    private fun cleanYoutubeQuery(query: String): String {
        val q = query.trim()
        return if (q.isNotEmpty()) q else "trending songs"
    }

    private fun fetchYoutubeVideoId(query: String): String? {
        // 1. Primary: YouTube InnerTube ANDROID search API (zero consent/HTML challenge)
        try {
            val jsonMedia = "application/json; charset=utf-8".toMediaType()
            val payload = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID")
                        put("clientVersion", "19.09.37")
                        put("deviceMake", Build.MANUFACTURER ?: "Google")
                        put("deviceModel", Build.MODEL ?: "Android")
                        put("osName", "ANDROID")
                        put("osVersion", Build.VERSION.RELEASE ?: "14")
                        put("hl", "en")
                        put("gl", "IN")
                    })
                })
                put("query", query)
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
                .header("User-Agent", "com.google.android.youtube/19.09.37 (Linux; U; Android 14; en_IN) gzip")
                .post(payload.toString().toRequestBody(jsonMedia))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    val organicMatch = Regex(""""videoWithContextRenderer":\{.*?"videoId":"([a-zA-Z0-9_-]{11})"""").find(body)
                        ?: Regex(""""compactVideoRenderer":\{.*?"videoId":"([a-zA-Z0-9_-]{11})"""").find(body)
                        ?: Regex(""""videoId":"([a-zA-Z0-9_-]{11})"""").find(body)
                    val vid = organicMatch?.groupValues?.get(1)
                    if (!vid.isNullOrBlank()) return vid
                }
            }
        } catch (e: Exception) {
            Log.w("SystemController", "InnerTube search notice: ${e.message}")
        }

        // 2. Fallback: Web results page with Consent cookies
        return try {
            val encoded = Uri.encode(query)
            val url = "https://www.youtube.com/results?search_query=$encoded&hl=en&gl=IN"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-IN,en;q=0.9,hi;q=0.8")
                .header("Cookie", "CONSENT=YES+cb.20210328-17-p0.en+FX+678; SOCS=CAI")
                .build()
            httpClient.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                val match = Regex(""""videoRenderer":\{.*?"videoId":"([a-zA-Z0-9_-]{11})"""").find(html)
                    ?: Regex(""""videoId":"([a-zA-Z0-9_-]{11})"""").find(html)
                match?.groupValues?.get(1)
            }
        } catch (e: Exception) {
            Log.e("SystemController", "Failed to fetch YouTube video ID: ${e.message}")
            null
        }
    }

    /**
     * Launches ANY installed app by name, alias, or package.
     * Uses strict ranking (exact label -> prefix -> substring -> package) so correct apps launch without accidental misfires.
     */
    fun openApp(appName: String): String {
        val rawQuery = appName.lowercase().trim()
        val pm = context.packageManager

        // 1. Dedicated Notes Handler (OnePlus Notes, ColorOS Notes, Google Keep)
        if (rawQuery.contains("note") || rawQuery.contains("keep")) {
            val notePackages = listOf("com.oneplus.note", "com.coloros.note", "com.google.android.keep", "com.google.android.apps.keep")
            for (np in notePackages) {
                val launchIntent = pm.getLaunchIntentForPackage(np)
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    return "Notes open kar diya hai Sir."
                }
            }
        }

        // Clean query of Hindi/English conversational prefixes/suffixes
        val stopWords = setOf("ek", "kam", "karo", "mera", "meri", "mere", "open", "kholo", "chalao", "start", "pe", "par", "app", "application", "ko")
        val cleanTokens = rawQuery.split(Regex("\\s+")).filter { it.isNotEmpty() && !stopWords.contains(it) }
        val cleanQuery = cleanTokens.joinToString(" ").ifEmpty { rawQuery }

        // 2. Direct Known Package Mapping (Instant 0.001s launch)
        val mappedPackage = knownAppPackages[cleanQuery] ?: knownAppPackages[rawQuery]
        if (mappedPackage != null) {
            val launchIntent = pm.getLaunchIntentForPackage(mappedPackage)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(launchIntent)
                return "$appName open kar diya hai Sir."
            }
        }

        // 3. Dynamic Discovery across all launchable apps with strict priority ranking
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val activities = pm.queryIntentActivities(launcherIntent, 0)

            val candidates = mutableListOf<AppCandidate>()

            for (ri in activities) {
                val pName = ri.activityInfo.packageName.lowercase()
                val label = ri.loadLabel(pm).toString()
                val lowerLabel = label.lowercase()
                if ((pName.contains("traceur") || lowerLabel == "system tracing") && !rawQuery.contains("tracing")) {
                    continue
                }

                val score = when {
                    lowerLabel == cleanQuery || lowerLabel == rawQuery -> 100
                    lowerLabel.startsWith(cleanQuery) -> 80
                    lowerLabel.split(Regex("\\s+")).any { it == cleanQuery } -> 70
                    lowerLabel.contains(cleanQuery) -> 60
                    pName == cleanQuery || pName.endsWith(".$cleanQuery") -> 50
                    pName.contains(cleanQuery) -> 40
                    cleanTokens.any { token -> token.length >= 3 && lowerLabel.contains(token) } -> 30
                    else -> 0
                }

                if (score > 0) {
                    candidates.add(AppCandidate(label, ri.activityInfo.packageName, score))
                }
            }

            // Also check installed packages if not found in launcher activities
            if (candidates.isEmpty()) {
                val installed = pm.getInstalledPackages(0)
                for (pkg in installed) {
                    val pName = pkg.packageName.lowercase()
                    val appInfo = pkg.applicationInfo ?: continue
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val lowerLabel = label.lowercase()

                    val score = when {
                        lowerLabel == cleanQuery || lowerLabel == rawQuery -> 90
                        lowerLabel.startsWith(cleanQuery) -> 75
                        lowerLabel.contains(cleanQuery) -> 55
                        pName.contains(cleanQuery) -> 35
                        else -> 0
                    }

                    if (score > 0 && pm.getLaunchIntentForPackage(pkg.packageName) != null) {
                        candidates.add(AppCandidate(label, pkg.packageName, score))
                    }
                }
            }

            val bestMatch = candidates.maxByOrNull { it.score }
            if (bestMatch != null) {
                val launchIntent = pm.getLaunchIntentForPackage(bestMatch.packageName)
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    return "${bestMatch.label} open kar diya hai Sir."
                }
            }
        } catch (e: Exception) {
            Log.e("SystemController", "App search error: ${e.message}")
        }

        return "Sir, '$appName' phone me installed nahi mila."
    }

    /**
     * Direct WhatsApp Chat Opener via native `whatsapp://send` URI (zero AppLinks web-check delay),
     * falling back cleanly to HTTPS deep link if needed.
     */
    fun openChatWhatsApp(phoneNumber: String, prefillMessage: String = ""): Boolean {
        var cleanNumber = phoneNumber.replace(Regex("[^0-9]"), "")
        if (cleanNumber.length == 10) {
            cleanNumber = "91$cleanNumber"
        }
        val encoded = if (prefillMessage.isNotBlank()) Uri.encode(prefillMessage) else ""
        val uris = if (encoded.isNotBlank()) {
            listOf(
                "whatsapp://send?phone=$cleanNumber&text=$encoded",
                "https://api.whatsapp.com/send?phone=$cleanNumber&text=$encoded"
            )
        } else {
            listOf(
                "whatsapp://send?phone=$cleanNumber",
                "https://api.whatsapp.com/send?phone=$cleanNumber"
            )
        }
        for (url in uris) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.w("SystemController", "WhatsApp URI failed ($url): ${e.message}")
            }
        }
        return false
    }

    /**
     * Answers an incoming phone or VoIP/WhatsApp call.
     */
    fun answerCall(): String {
        val success = com.jarvis.assistant.JarvisAccessibilityService.instance?.answerCall() ?: false
        return if (success) "Call pick kar diya hai Sir." else "Call pick nahi ho saka Sir."
    }

    /**
     * Cuts/ends an active ongoing call or declines an incoming call.
     */
    fun endCall(): String {
        val success = com.jarvis.assistant.JarvisAccessibilityService.instance?.endCall() ?: false
        return if (success) "Call cut kar diya hai Sir." else "Call cut nahi ho saka Sir."
    }

    /**
     * Closes, stops, or kills the specified app or active app.
     */
    fun closeApp(appName: String? = null): String {
        val targetApp = appName?.lowercase()?.trim()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = context.packageManager

        // 1. If target is YouTube or media/music (or playing in PiP/background):
        if (targetApp.isNullOrBlank() || targetApp.contains("youtube") || targetApp.contains("song") || targetApp.contains("music") || targetApp.contains("video")) {
            try {
                // Send hardware media pause & stop to immediately halt playback and dismiss PiP audio
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
                audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))
            } catch (e: Exception) {
                Log.w("SystemController", "Media stop key event error: ${e.message}")
            }

            // Kill YouTube background processes
            try {
                activityManager.killBackgroundProcesses("com.google.android.youtube")
            } catch (e: Exception) {}
        }

        // 2. Resolve package name for target app if specified
        var targetPackage: String? = null
        if (!targetApp.isNullOrBlank()) {
            targetPackage = knownAppPackages[targetApp]
            if (targetPackage == null) {
                try {
                    val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
                    val activities = pm.queryIntentActivities(launcherIntent, 0)
                    for (ri in activities) {
                        val label = ri.loadLabel(pm).toString().lowercase()
                        if (label.contains(targetApp) || targetApp.contains(label)) {
                            targetPackage = ri.activityInfo.packageName
                            break
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        // 3. Kill background processes of target app if known
        if (targetPackage != null && targetPackage != context.packageName) {
            try {
                activityManager.killBackgroundProcesses(targetPackage)
            } catch (e: Exception) {}
        }

        // 4. Handle navigation / closing:
        // Only perform Home if:
        // - No specific app was named, OR
        // - The target app is currently in foreground (NOT Jarvis!)
        val isJarvisInForeground = (context as? android.app.Activity) != null
        val shouldGoHome = targetApp.isNullOrBlank() || (!isJarvisInForeground)

        if (shouldGoHome) {
            com.jarvis.assistant.JarvisAccessibilityService.instance?.performHome()
        }

        val nameToSpeak = if (!appName.isNullOrBlank()) appName else "App"
        return "$nameToSpeak band kar diya hai Sir."
    }

    /**
     * Instantly locks the phone screen using Android Accessibility GLOBAL_ACTION_LOCK_SCREEN.
     */
    fun lockScreen(): String {
        val service = com.jarvis.assistant.JarvisAccessibilityService.instance
            ?: return "Screen lock karne ke liye Accessibility Service active chahiye Sir."
        val ok = service.lockScreen()
        return if (ok) "" else "Sir, screen lock Android 9+ par hi direct support hota hai."
    }

    /**
     * Dispatches hardware media transport key events (play/pause/next/previous/stop) silently
     * to whichever media player is currently active (Hotstar, YouTube, Spotify, etc.).
     */
    fun controlMedia(action: String): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keyCode = when (action.lowercase().trim()) {
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next", "skip", "forward" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        return try {
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            ""
        } catch (e: Exception) {
            Log.w("SystemController", "Media key error: ${e.message}")
            "Media control karne me dikkat aayi Sir."
        }
    }

    /**
     * Toggles speakerphone ON or OFF during an active phone or WhatsApp call.
     * Prefers tapping the live call-screen Speaker UI node across windows (reliable on Android 12-14),
     * and falls back to AudioManager routing APIs.
     */
    fun setSpeakerphone(enabled: Boolean): String {
        val accUiResult = com.jarvis.assistant.JarvisAccessibilityService.instance?.setCallSpeakerUi(enabled)
        if (accUiResult == true) {
            return if (enabled) "Speaker on kar diya hai Sir." else "Speaker off kar diya hai Sir."
        }
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            var applied = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val targetDevice = if (enabled) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
                if (targetDevice != null) {
                    applied = audioManager.setCommunicationDevice(targetDevice)
                }
            }
            @Suppress("DEPRECATION")
            if (!applied) {
                audioManager.isSpeakerphoneOn = enabled
                applied = (audioManager.isSpeakerphoneOn == enabled)
            }
            if (applied) {
                if (enabled) "Speaker on kar diya hai Sir." else "Speaker off kar diya hai Sir."
            } else {
                "Call screen par speaker button nahi mila Sir — kripya screen se tap karein."
            }
        } catch (e: Exception) {
            Log.w("SystemController", "Speakerphone toggle failed: ${e.message}")
            "Speaker change nahi ho saka Sir."
        }
    }

    /**
     * Adjusts device volume cleanly on the active stream (Call/Ring/Music) without launching external UI.
     */
    fun controlVolume(action: String, percent: Int? = null): String {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return try {
            val mode = audioManager.mode
            val stream = when (mode) {
                AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
                AudioManager.MODE_RINGTONE -> AudioManager.STREAM_RING
                else -> AudioManager.STREAM_MUSIC
            }
            val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
            val curVol = audioManager.getStreamVolume(stream)
            val norm = action.lowercase().trim()
            val step = maxOf(2, (maxVol * 0.22f).toInt())

            when {
                percent != null -> {
                    val clamped = percent.coerceIn(0, 100)
                    val target = ((clamped * maxVol) / 100.0).toInt().coerceIn(0, maxVol)
                    audioManager.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                }
                norm == "mute" || norm == "zero" || norm == "band" -> {
                    val minAllowed = if (stream == AudioManager.STREAM_VOICE_CALL) 1 else 0
                    audioManager.setStreamVolume(stream, minAllowed, AudioManager.FLAG_SHOW_UI)
                }
                norm == "max" || norm == "full" -> {
                    audioManager.setStreamVolume(stream, maxVol, AudioManager.FLAG_SHOW_UI)
                }
                norm == "low" || norm == "kam" -> {
                    val target = minOf((curVol - step).coerceAtLeast(1), (maxVol * 0.25f).toInt().coerceAtLeast(1))
                    audioManager.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                }
                norm == "unmute" -> {
                    if (curVol <= 1) {
                        audioManager.setStreamVolume(stream, (maxVol / 3).coerceAtLeast(1), AudioManager.FLAG_SHOW_UI)
                    }
                }
                norm == "up" || norm == "increase" || norm == "high" || norm == "badhao" -> {
                    val target = (curVol + step).coerceAtMost(maxVol)
                    audioManager.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                }
                else -> {
                    val minAllowed = if (stream == AudioManager.STREAM_VOICE_CALL) 1 else 0
                    val target = (curVol - step).coerceAtLeast(minAllowed)
                    audioManager.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                }
            }
            ""
        } catch (e: Exception) {
            Log.w("SystemController", "Volume control error: ${e.message}")
            "Volume change nahi ho saka Sir."
        }
    }
}
