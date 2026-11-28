package com.jarvis.assistant.voice

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.debug.JarvisLogStore
import com.jarvis.assistant.engine.JarvisCommandExecutor
import com.jarvis.assistant.memory.JarvisMemoryManager

/**
 * Permanent 24/7 hands-free voice listening service.
 * Stays active across background minimize, app swipe-away (START_STICKY + Alarm watchdog),
 * screen OFF / lockscreen (PARTIAL_WAKE_LOCK), and device reboot until user explicitly clicks STOP in MainActivity.
 */
class AlwaysOnVoiceService : Service() {

    companion object {
        private const val PREFS_NAME = "jarvis_config"
        private const val KEY_ALWAYS_ON_MIC = "always_on_mic_enabled"
        private const val CHANNEL_ID = "jarvis_always_on_mic"
        private const val NOTIFICATION_ID = 2001
        private const val WATCHDOG_REQ_CODE = 4201
        private const val WATCHDOG_INTERVAL_MS = 10 * 60 * 1000L

        const val ACTION_START = "com.jarvis.assistant.ACTION_START_PERMANENT_MIC"
        const val ACTION_STOP = "com.jarvis.assistant.ACTION_STOP_PERMANENT_MIC"
        const val ACTION_RESTART_WATCHDOG = "com.jarvis.assistant.RESTART_ALWAYS_ON"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var onStatusChanged: (() -> Unit)? = null

        fun isEnabled(context: Context): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
                    if (um != null && !um.isUserUnlocked) return false
                }
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_ALWAYS_ON_MIC, false)
            } catch (_: Exception) {
                false
            }
        }

        private fun setEnabled(context: Context, enabled: Boolean) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_ALWAYS_ON_MIC, enabled)
                    .commit()
            } catch (_: Exception) {
            }
        }

        fun startPermanent(context: Context) {
            val appCtx = context.applicationContext
            JarvisLogStore.init(appCtx)
            setEnabled(appCtx, true)
            JarvisLogStore.log("ALWAYS_ON", "Permanent 24/7 mic STARTED by user.")
            val intent = Intent(appCtx, AlwaysOnVoiceService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (e: Exception) {
                JarvisLogStore.log("ALWAYS_ON_ERR", "Start failed: ${e.message}")
            }
            scheduleWatchdogAlarm(appCtx, WATCHDOG_INTERVAL_MS)
            notifyUi()
        }

        fun stopPermanent(context: Context) {
            val appCtx = context.applicationContext
            JarvisLogStore.init(appCtx)
            setEnabled(appCtx, false)
            cancelWatchdogAlarm(appCtx)
            JarvisLogStore.log("ALWAYS_ON", "Permanent 24/7 mic STOPPED by user from app.")
            try {
                appCtx.stopService(Intent(appCtx, AlwaysOnVoiceService::class.java))
            } catch (_: Exception) {
            }
            isRunning = false
            notifyUi()
        }

        fun ensureRunningIfEnabled(context: Context, reason: String) {
            val appCtx = context.applicationContext
            if (!isEnabled(appCtx)) return
            JarvisLogStore.init(appCtx)
            if (isRunning) return
            JarvisLogStore.log("ALWAYS_ON", "Auto-restarting permanent mic ($reason)...")
            val intent = Intent(appCtx, AlwaysOnVoiceService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (e: Exception) {
                JarvisLogStore.log("ALWAYS_ON_WARN", "Deferred auto-restart ($reason): ${e.message}")
                scheduleWatchdogAlarm(appCtx, 5000L)
            }
        }

        fun scheduleWatchdogAlarm(context: Context, delayMs: Long) {
            val appCtx = context.applicationContext
            if (!isEnabled(appCtx)) return
            val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(appCtx, BootRestartReceiver::class.java).setAction(ACTION_RESTART_WATCHDOG)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(appCtx, WATCHDOG_REQ_CODE, intent, flags)
            val triggerAt = System.currentTimeMillis() + delayMs.coerceAtLeast(1000L)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } catch (_: Exception) {
            }
        }

        fun cancelWatchdogAlarm(context: Context) {
            val appCtx = context.applicationContext
            val am = appCtx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(appCtx, BootRestartReceiver::class.java).setAction(ACTION_RESTART_WATCHDOG)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_NO_CREATE
            }
            val pi = PendingIntent.getBroadcast(appCtx, WATCHDOG_REQ_CODE, intent, flags)
            if (pi != null) {
                try {
                    am.cancel(pi)
                    pi.cancel()
                } catch (_: Exception) {
                }
            }
        }

        private fun notifyUi() {
            try {
                onStatusChanged?.invoke()
            } catch (_: Exception) {
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var speakerHelper: SpeakerHelper
    private lateinit var commandExecutor: JarvisCommandExecutor
    private var speechRecognizerHelper: SpeechRecognizerHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager

    @Volatile
    private var stopping = false

    @Volatile
    private var needsMicTypeUpgrade = false

    @Volatile
    private var loggedPermMissing = false

    @Volatile
    private var loggedNetworkDown = false

    private var consecutiveSilenceCount = 0
    private var consecutiveErrorCount = 0
    private var lastHeardText = ""
    private var lastHeardAtMs = 0L

    private val listenRunnable = Runnable { attemptStartListening() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!stopping && isEnabled(this@AlwaysOnVoiceService)) {
                if (needsMicTypeUpgrade) {
                    promoteToForegroundNotification()
                }
                scheduleListen(250L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        JarvisLogStore.init(this)
        stopping = false
        isRunning = true
        notifyUi()

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        promoteToForegroundNotification()
        acquirePartialWakeLock()

        speakerHelper = SpeakerHelper(this).apply {
            onSpeakingStateChanged = { speaking ->
                mainHandler.post {
                    if (speaking) {
                        mainHandler.removeCallbacks(listenRunnable)
                        speechRecognizerHelper?.cancel()
                    } else if (!stopping && isEnabled(this@AlwaysOnVoiceService)) {
                        scheduleListen(380L)
                    }
                }
            }
        }
        val memoryManager = JarvisMemoryManager(this)
        commandExecutor = JarvisCommandExecutor(this, speakerHelper, memoryManager)

        speechRecognizerHelper = SpeechRecognizerHelper(
            this,
            onResult = { heard -> onVoiceHeard(heard) },
            onError = { /* handled via onErrorCode cleanly without noisy TTS */ },
            onErrorCode = { code -> onVoiceError(code) }
        )

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenReceiver, filter)
            }
        } catch (_: Exception) {
        }

        JarvisLogStore.log("ALWAYS_ON", "AlwaysOnVoiceService active (24/7 loop initialized).")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        JarvisLogStore.init(this)
        if (intent?.action == ACTION_STOP || !isEnabled(this)) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }

        stopping = false
        isRunning = true
        notifyUi()
        promoteToForegroundNotification()
        acquirePartialWakeLock()
        scheduleWatchdogAlarm(this, WATCHDOG_INTERVAL_MS)
        scheduleListen(250L)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!stopping && isEnabled(this)) {
            JarvisLogStore.log("ALWAYS_ON", "App removed from recents; keeping permanent mic running.")
            scheduleWatchdogAlarm(this, 1200L)
        }
    }

    private fun acquirePartialWakeLock() {
        try {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:PermanentMicWakeLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            JarvisLogStore.log("ALWAYS_ON_WARN", "WakeLock notice: ${e.message}")
        }
    }

    private fun promoteToForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "J.A.R.V.I.S. 24/7 Listening",
                    NotificationManager.IMPORTANCE_LOW
                )
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }

            val openIntent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val openPending = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

            // Ongoing persistent notification with NO Stop button (user stops strictly inside MainActivity)
            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("J.A.R.V.I.S. Mic ON (24/7)")
                .setContentText("Hamesha sun raha hoon Sir — stop karne ke liye app kholein.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    needsMicTypeUpgrade = false
                } catch (sec: SecurityException) {
                    needsMicTypeUpgrade = true
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    JarvisLogStore.log("ALWAYS_ON_WARN", "FGS mic deferred at boot; specialUse active until unlocked.")
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            JarvisLogStore.log("ALWAYS_ON_ERR", "Foreground notification failed: ${e.message}")
        }
    }

    private fun scheduleListen(delayMs: Long) {
        if (stopping || !isEnabled(this)) return
        mainHandler.removeCallbacks(listenRunnable)
        mainHandler.postDelayed(listenRunnable, delayMs)
    }

    private fun attemptStartListening() {
        if (stopping || !isEnabled(this)) return

        val hasAudioPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasAudioPerm) {
            if (!loggedPermMissing) {
                loggedPermMissing = true
                JarvisLogStore.log("ALWAYS_ON_ERR", "RECORD_AUDIO permission missing; waiting...")
            }
            scheduleListen(4000L)
            return
        }
        loggedPermMissing = false

        if (speakerHelper.isCurrentlySpeaking()) {
            scheduleListen(350L)
            return
        }

        val audioMode = try {
            audioManager.mode
        } catch (_: Exception) {
            AudioManager.MODE_NORMAL
        }
        if (audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION) {
            scheduleListen(1200L)
            return
        }

        if (needsMicTypeUpgrade) {
            promoteToForegroundNotification()
        }

        try {
            if (consecutiveSilenceCount >= 10) {
                consecutiveSilenceCount = 0
                speechRecognizerHelper?.recreate()
            }
            speechRecognizerHelper?.startListening()
        } catch (e: Exception) {
            JarvisLogStore.log("ALWAYS_ON_WARN", "startListening exception: ${e.message}")
            speechRecognizerHelper?.recreate()
            scheduleListen(1000L)
        }
    }

    private fun onVoiceHeard(rawText: String) {
        val text = rawText.trim()
        consecutiveSilenceCount = 0
        consecutiveErrorCount = 0
        loggedNetworkDown = false

        if (text.length < 2 || SpeakerHelper.isAnySpeaking()) {
            scheduleListen(350L)
            return
        }

        val now = System.currentTimeMillis()
        if (text.equals(lastHeardText, ignoreCase = true) && (now - lastHeardAtMs) < 2000L) {
            scheduleListen(300L)
            return
        }
        lastHeardText = text
        lastHeardAtMs = now

        commandExecutor.executeUserCommand(text, sourceTag = "USER_VOICE") {
            if (!stopping && isEnabled(this@AlwaysOnVoiceService)) {
                scheduleListen(420L)
            }
        }
    }

    private fun onVoiceError(errorCode: Int) {
        if (stopping || !isEnabled(this)) return

        when (errorCode) {
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                consecutiveSilenceCount++
                consecutiveErrorCount = 0
                scheduleListen(260L)
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_CLIENT -> {
                consecutiveErrorCount++
                speechRecognizerHelper?.recreate()
                scheduleListen(650L)
            }
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> {
                consecutiveErrorCount++
                if (!loggedNetworkDown) {
                    loggedNetworkDown = true
                    JarvisLogStore.log("ALWAYS_ON_WARN", "Speech service waiting on network/server ($errorCode); backing off...")
                }
                val shift = minOf(consecutiveErrorCount, 3)
                val backoff = (1500L shl shift).coerceAtMost(8000L)
                scheduleListen(backoff)
            }
            SpeechRecognizer.ERROR_AUDIO -> {
                consecutiveErrorCount++
                speechRecognizerHelper?.recreate()
                scheduleListen(1000L)
            }
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                JarvisLogStore.log("ALWAYS_ON_ERR", "Mic permission denied by OS.")
                scheduleListen(5000L)
            }
            else -> {
                consecutiveErrorCount++
                speechRecognizerHelper?.recreate()
                scheduleListen(800L)
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        try {
            speechRecognizerHelper?.destroy()
        } catch (_: Exception) {
        }
        try {
            speakerHelper.shutdown()
        } catch (_: Exception) {
        }
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        isRunning = false
        notifyUi()

        // If stopped unexpectedly by OEM killer while still enabled, resurrect immediately
        if (!stopping && isEnabled(this)) {
            JarvisLogStore.log("ALWAYS_ON", "Service destroyed unexpectedly while enabled; scheduling instant restart...")
            scheduleWatchdogAlarm(this, 1200L)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
