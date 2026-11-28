package com.jarvis.assistant.voice

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
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.UserManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.debug.JarvisLogStore
import com.jarvis.assistant.engine.JarvisCommandExecutor
import com.jarvis.assistant.memory.JarvisMemoryManager

/**
 * Permanent 24/7 hands-free voice listening service.
 * Runs 100% Zero-AudioFocus passive AudioRecord + Vosk SpeakerModel voiceprint verification
 * so Hotstar, Instagram Reels, YouTube, and music NEVER pause or mute, with NO wake word required.
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
        const val ACTION_PAUSE_ENROLL = "com.jarvis.assistant.ACTION_PAUSE_FOR_ENROLL"
        const val ACTION_RESUME_ENROLL = "com.jarvis.assistant.ACTION_RESUME_AFTER_ENROLL"

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
            if (isRunning) {
                scheduleWatchdogAlarm(appCtx, WATCHDOG_INTERVAL_MS)
                return
            }
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

        fun pauseForEnrollment(context: Context) {
            val appCtx = context.applicationContext
            if (!isEnabled(appCtx) || !isRunning) return
            try {
                val intent = Intent(appCtx, AlwaysOnVoiceService::class.java).setAction(ACTION_PAUSE_ENROLL)
                appCtx.startService(intent)
            } catch (_: Exception) {
            }
        }

        fun resumeAfterEnrollment(context: Context) {
            val appCtx = context.applicationContext
            if (!isEnabled(appCtx)) return
            try {
                val intent = Intent(appCtx, AlwaysOnVoiceService::class.java).setAction(ACTION_RESUME_ENROLL)
                ContextCompat.startForegroundService(appCtx, intent)
            } catch (_: Exception) {
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var speakerHelper: SpeakerHelper
    private lateinit var commandExecutor: JarvisCommandExecutor
    private var voiceCaptureEngine: PassiveVoiceCaptureEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var audioManager: AudioManager

    @Volatile
    private var stopping = false

    @Volatile
    private var needsMicTypeUpgrade = false

    @Volatile
    private var executingCommand = false

    @Volatile
    private var pausedForEnrollment = false

    @Volatile
    private var awaitingSecretCode = false

    private var pendingDoubtDecision: com.jarvis.assistant.brain.ActionDecision? = null
    private var pendingDoubtTranscript: String = ""

    @Volatile
    private var lastNotificationText = "Hotstar/Reels ke saath bina ruke sun raha hoon"

    private val resumeListenRunnable = Runnable { resumePassiveListeningNow() }
    private val secretChallengeTimeoutRunnable = Runnable {
        if (awaitingSecretCode) {
            awaitingSecretCode = false
            voiceCaptureEngine?.forceForwardForChallenge = false
            pendingDoubtDecision = null
            pendingDoubtTranscript = ""
            JarvisLogStore.log("VOICE_LOCK", "Secret code challenge timed out (16s); held command cancelled.")
            updateNotificationText(
                if (VoiceprintManager.isLockActive(this)) "Hotstar/Reels ke saath sun raha hoon — Sirf Aapki Awaaz"
                else "Hotstar/Reels ke saath bina ruke sun raha hoon"
            )
            schedulePassiveResume(250L)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!stopping && isEnabled(this@AlwaysOnVoiceService)) {
                if (needsMicTypeUpgrade) {
                    promoteToForegroundNotification(lastNotificationText)
                }
                if (!executingCommand && !pausedForEnrollment) {
                    schedulePassiveResume(250L)
                }
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
        promoteToForegroundNotification(lastNotificationText)
        acquirePartialWakeLock()

        speakerHelper = SpeakerHelper(this).apply {
            onSpeakingStateChanged = { speaking ->
                mainHandler.post {
                    if (!speaking && !stopping && isEnabled(this@AlwaysOnVoiceService) &&
                        !executingCommand && !pausedForEnrollment
                    ) {
                        schedulePassiveResume(320L)
                    }
                }
            }
        }
        val memoryManager = JarvisMemoryManager(this)
        commandExecutor = JarvisCommandExecutor(this, speakerHelper, memoryManager)
        commandExecutor.prewarmConnections()

        voiceCaptureEngine = PassiveVoiceCaptureEngine(
            context = this,
            onUtteranceReady = { captured -> onVerifiedSpeechCaptured(captured) },
            onStatusUpdate = { state, msg ->
                if (!executingCommand && !stopping && !pausedForEnrollment) {
                    if (state == PassiveVoiceCaptureEngine.State.LISTENING ||
                        state == PassiveVoiceCaptureEngine.State.DOWNLOADING ||
                        state == PassiveVoiceCaptureEngine.State.WAITING_NETWORK ||
                        state == PassiveVoiceCaptureEngine.State.WAITING_PERMISSION
                    ) {
                        updateNotificationText(msg)
                    }
                }
            }
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

        JarvisLogStore.log("ALWAYS_ON", "AlwaysOnVoiceService active (Direct Voice Lock + Zero-AudioFocus mode).")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        JarvisLogStore.init(this)
        val action = intent?.action
        if (action == ACTION_STOP || !isEnabled(this)) {
            stopping = true
            stopSelf()
            return START_NOT_STICKY
        }

        if (action == ACTION_PAUSE_ENROLL) {
            pausedForEnrollment = true
            mainHandler.removeCallbacks(resumeListenRunnable)
            voiceCaptureEngine?.pauseForProcessing("Voice Lock training chal rahi hai...")
            updateNotificationText("Voice Lock training chal rahi hai...")
            return START_STICKY
        }

        if (action == ACTION_RESUME_ENROLL) {
            pausedForEnrollment = false
            schedulePassiveResume(300L)
            return START_STICKY
        }

        stopping = false
        pausedForEnrollment = false
        isRunning = true
        notifyUi()
        promoteToForegroundNotification(lastNotificationText)
        acquirePartialWakeLock()
        scheduleWatchdogAlarm(this, WATCHDOG_INTERVAL_MS)
        if (!executingCommand) {
            schedulePassiveResume(200L)
        }
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

    private fun updateNotificationText(contentText: String) {
        if (contentText == lastNotificationText) return
        lastNotificationText = contentText
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIFICATION_ID, buildForegroundNotification(contentText))
        } catch (_: Exception) {
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S. Mic ON (24/7)")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun promoteToForegroundNotification(contentText: String = lastNotificationText) {
        lastNotificationText = contentText
        try {
            val notification = buildForegroundNotification(contentText)
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                    needsMicTypeUpgrade = false
                } catch (_: SecurityException) {
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

    private fun schedulePassiveResume(delayMs: Long) {
        if (stopping || !isEnabled(this) || pausedForEnrollment) return
        mainHandler.removeCallbacks(resumeListenRunnable)
        mainHandler.postDelayed(resumeListenRunnable, delayMs)
    }

    private fun resumePassiveListeningNow() {
        if (stopping || !isEnabled(this) || executingCommand || pausedForEnrollment) return
        if (SpeakerHelper.isAnySpeaking()) {
            schedulePassiveResume(240L)
            return
        }
        if (needsMicTypeUpgrade) {
            promoteToForegroundNotification(lastNotificationText)
        }
        voiceCaptureEngine?.setDispatchInFlight(false)
        voiceCaptureEngine?.startListening()
    }

    private fun releaseMicDispatchLock(delayMs: Long = 80L) {
        executingCommand = false
        if (stopping || !isEnabled(this) || pausedForEnrollment) return
        mainHandler.postDelayed({
            if (!executingCommand && !stopping && isEnabled(this) && !pausedForEnrollment) {
                voiceCaptureEngine?.setDispatchInFlight(false)
                voiceCaptureEngine?.startListening()
            }
        }, delayMs)
    }

    private fun onVerifiedSpeechCaptured(captured: PassiveVoiceCaptureEngine.CapturedUtterance) {
        if (stopping || !isEnabled(this) || executingCommand || pausedForEnrollment) {
            if (!executingCommand && !pausedForEnrollment) {
                voiceCaptureEngine?.setDispatchInFlight(false)
            }
            return
        }

        val audioMode = try {
            audioManager.mode
        } catch (_: Exception) {
            AudioManager.MODE_NORMAL
        }
        val inCallNow = audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION
        if (inCallNow && !com.jarvis.assistant.JarvisAccessibilityService.isServiceRunning) {
            voiceCaptureEngine?.setDispatchInFlight(false)
            schedulePassiveResume(1200L)
            return
        }

        executingCommand = true
        mainHandler.removeCallbacks(resumeListenRunnable)

        // 1. Handling response to an active Secret Code verification challenge
        if (awaitingSecretCode) {
            mainHandler.removeCallbacks(secretChallengeTimeoutRunnable)
            awaitingSecretCode = false
            voiceCaptureEngine?.forceForwardForChallenge = false
            val held = pendingDoubtDecision
            val heldTranscript = pendingDoubtTranscript
            pendingDoubtDecision = null
            pendingDoubtTranscript = ""

            val expectedCode = VoiceprintManager.getSecretCode(this)
            val hintLower = captured.voskHint.lowercase()
            if (hintLower in listOf("cancel", "chhodo", "rehne do", "stop")) {
                updateNotificationText("Cancelled.")
                releaseMicDispatchLock(180L)
                return
            }
            updateNotificationText("Secret code verify kar raha hoon...")

            commandExecutor.verifySecretCodeAudio(
                wavBytes = captured.wavBytes,
                expectedSecretCode = expectedCode,
                voskHint = captured.voskHint,
                offlineFallbackMatch = VoiceprintManager::matchesSecretCodeTextOffline
            ) { checkRes ->
                if (checkRes.matched && held != null) {
                    VoiceprintManager.learnOwnerAcousticSample(this, captured.rawPcm)
                    JarvisLogStore.log("VOICE_LOCK", "Secret code VERIFIED ('${checkRes.heardText}')! Running held command: '$heldTranscript'")
                    updateNotificationText("Verified! Execute kar raha hoon...")
                    commandExecutor.executePreParsedDecision(held, heldTranscript, sourceTag = "USER_VOICE") {
                        releaseMicDispatchLock(140L)
                    }
                } else {
                    JarvisLogStore.log("VOICE_LOCK", "Secret code REJECTED (heard='${checkRes.heardText}'). Held command blocked.")
                    val reply = checkRes.dynamicReply.ifBlank {
                        "Secret code match nahi hua — command cancel kar diya hai."
                    }
                    commandExecutor.speakDynamic(reply)
                    releaseMicDispatchLock(450L)
                }
            }
            return
        }

        // 2. Normal 3-Tier routing
        when (captured.verification.tier) {
            VoiceprintManager.MatchTier.TRUSTED -> {
                val speakerVerified = VoiceprintManager.isLockActive(this) && captured.verification.allowed
                if (speakerVerified && !VoiceprintManager.hasAcousticProfile(this)) {
                    VoiceprintManager.learnOwnerAcousticSample(this, captured.rawPcm)
                }
                commandExecutor.executeAudioCommand(
                    wavBytes = captured.wavBytes,
                    voskHint = captured.voskHint,
                    isMediaPlaying = captured.isMediaPlaying,
                    speakerVerified = speakerVerified,
                    isInActiveCall = captured.isInActiveCall || inCallNow,
                    sourceTag = "USER_VOICE"
                ) { ignored, transcript ->
                    if (!ignored && transcript.isNotBlank()) {
                        updateNotificationText("Executed: $transcript")
                    }
                    releaseMicDispatchLock(if (ignored) 15L else 120L)
                }
            }

            VoiceprintManager.MatchTier.DOUBT -> {
                updateNotificationText("Voice check ho rahi hai...")
                commandExecutor.evaluateDoubtOrRejectedAudio(
                    wavBytes = captured.wavBytes,
                    voskHint = captured.voskHint,
                    tier = "DOUBT",
                    similarity = captured.verification.similarity,
                    acceptThreshold = captured.verification.acceptThreshold,
                    isMediaPlaying = captured.isMediaPlaying
                ) { eval ->
                    if (!eval.isCommandAttempt || eval.heldDecision == null ||
                        (eval.heldDecision.toolName == null && eval.heldDecision.speech == null)
                    ) {
                        releaseMicDispatchLock(40L)
                        return@evaluateDoubtOrRejectedAudio
                    }

                    pendingDoubtDecision = eval.heldDecision
                    pendingDoubtTranscript = eval.transcript
                    awaitingSecretCode = true
                    voiceCaptureEngine?.forceForwardForChallenge = true

                    val askSpeech = eval.dynamicSpeech.ifBlank {
                        "Aawaz par thoda doubt lag raha hai — aage badhne ke liye apna secret code bataiye."
                    }
                    updateNotificationText("Secret code pooch raha hoon...")
                    commandExecutor.speakDynamic(askSpeech)

                    mainHandler.removeCallbacks(secretChallengeTimeoutRunnable)
                    mainHandler.postDelayed(secretChallengeTimeoutRunnable, 16_000L)
                    releaseMicDispatchLock(380L)
                }
            }

            VoiceprintManager.MatchTier.REJECTED -> {
                commandExecutor.evaluateDoubtOrRejectedAudio(
                    wavBytes = captured.wavBytes,
                    voskHint = captured.voskHint,
                    tier = "REJECTED",
                    similarity = captured.verification.similarity,
                    acceptThreshold = captured.verification.acceptThreshold,
                    isMediaPlaying = captured.isMediaPlaying
                ) { eval ->
                    if (eval.isCommandAttempt) {
                        val refusal = eval.dynamicSpeech.ifBlank {
                            "Aapki voice Sir se match nahi kar rahi hai, main yeh command nahi chala sakta."
                        }
                        JarvisLogStore.log("VOICE_LOCK", "Blocked stranger command '${eval.transcript}' -> spoken refusal")
                        updateNotificationText("Voice match nahi hui — Blocked")
                        commandExecutor.speakDynamic(refusal)
                    }
                    releaseMicDispatchLock(if (eval.isCommandAttempt) 550L else 40L)
                }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        executingCommand = false
        pausedForEnrollment = false
        awaitingSecretCode = false
        pendingDoubtDecision = null
        pendingDoubtTranscript = ""
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: Exception) {
        }
        try {
            voiceCaptureEngine?.release()
        } catch (_: Exception) {
        }
        voiceCaptureEngine = null
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
