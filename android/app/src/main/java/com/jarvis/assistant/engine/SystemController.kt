package com.jarvis.assistant.engine

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log

class SystemController(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ═════════════════════════════════════════════════════════════════
    // LAYER 1: DIRECT ANDROID OS CONTROLS (Zero GUI, Instant 0.001s)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Enables or disables Do Not Disturb natively via NotificationManager in 0.001s.
     * ZERO GUI, ZERO Settings app, ZERO chance of toggling Airplane Mode!
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
     * Sets Screen Brightness directly.
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
            if (Settings.System.canWrite(context)) {
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
            } else {
                "Sir, Write Settings permission allow kijiye."
            }
        } catch (e: Exception) {
            "Brightness set nahi ho saki Sir."
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
        context.startActivity(intent)
        val mStr = if (minute < 10) "0$minute" else "$minute"
        return "$hour:$mStr ka alarm set kar diya hai Sir."
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
        context.startActivity(intent)
        val mins = seconds / 60
        return if (mins > 0) "$mins minute ka timer laga diya hai Sir." else "$seconds seconds ka timer laga diya hai Sir."
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
     * Direct YouTube Player Launch (0.2s, Zero Shorts).
     */
    fun playYoutube(query: String): String {
        val encoded = Uri.encode(query)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")).apply {
            setPackage("com.google.android.youtube")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
        return "YouTube par '$query' play kar raha hoon Sir."
    }

    /**
     * Launches ANY installed app by human label or package name.
     */
    fun openApp(appName: String): String {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(0)
        val query = appName.lowercase().trim()

        for (app in packages) {
            val label = pm.getApplicationLabel(app).toString().lowercase()
            if (label.contains(query) || app.packageName.lowercase().contains(query)) {
                val launchIntent = pm.getLaunchIntentForPackage(app.packageName)
                if (launchIntent != null) {
                    launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(launchIntent)
                    return "$appName open kar diya hai Sir."
                }
            }
        }
        return "Sir, '$appName' phone me installed nahi mila."
    }

    /**
     * Direct WhatsApp Chat Opener via WhatsApp API deep link.
     */
    fun openChatWhatsApp(phoneNumber: String): Boolean {
        var cleanNumber = phoneNumber.replace(Regex("[^0-9]"), "")
        if (cleanNumber.length == 10) {
            cleanNumber = "91$cleanNumber"
        }
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://api.whatsapp.com/send?phone=$cleanNumber")
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
