package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.brain.GeminiBrain
import com.jarvis.assistant.debug.JarvisLogStore
import com.jarvis.assistant.engine.NodeActionEngine
import com.jarvis.assistant.engine.SystemController
import com.jarvis.assistant.engine.TelephonyHelper
import com.jarvis.assistant.engine.UniversalAppAgent
import com.jarvis.assistant.memory.JarvisMemoryManager
import com.jarvis.assistant.scheduler.ScheduledTaskManager
import com.jarvis.assistant.security.AutoUnlockManager
import com.jarvis.assistant.security.BankingProtectionManager
import com.jarvis.assistant.voice.SpeakerHelper
import com.jarvis.assistant.voice.SpeechRecognizerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

    private lateinit var systemController: SystemController
    private lateinit var telephonyHelper: TelephonyHelper
    private lateinit var nodeActionEngine: NodeActionEngine
    private lateinit var universalAppAgent: UniversalAppAgent
    private lateinit var speakerHelper: SpeakerHelper
    private var speechRecognizerHelper: SpeechRecognizerHelper? = null
    private lateinit var geminiBrain: GeminiBrain
    private lateinit var memoryManager: JarvisMemoryManager
    private lateinit var scheduledTaskManager: ScheduledTaskManager

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        try {
            JarvisLogStore.init(this)
            startForegroundServiceNotification()

            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            systemController = SystemController(this)
            telephonyHelper = TelephonyHelper(this)
            nodeActionEngine = NodeActionEngine(systemController, telephonyHelper)
            universalAppAgent = UniversalAppAgent(this, systemController, nodeActionEngine)
            speakerHelper = SpeakerHelper(this)
            memoryManager = JarvisMemoryManager(this)
            scheduledTaskManager = ScheduledTaskManager(this)

            // User API key configuration from BuildConfig (injected via ENV/Secrets) or SharedPreferences
            val defaultKey = BuildConfig.GEMINI_API_KEY
            val prefs = getSharedPreferences("jarvis_config", Context.MODE_PRIVATE)
            val storedKey = prefs.getString("gemini_api_key", "")
            val apiKey = if (storedKey.isNullOrBlank() || storedKey.startsWith("AIzaSy")) {
                if (defaultKey.isNotBlank()) {
                    prefs.edit().putString("gemini_api_key", defaultKey).apply()
                    defaultKey
                } else {
                    ""
                }
            } else {
                storedKey
            }
            geminiBrain = GeminiBrain(apiKey, memoryManager)

            try {
                speechRecognizerHelper = SpeechRecognizerHelper(
                    this,
                    onResult = { userText -> handleVoiceCommand(userText) },
                    onError = { err -> speakerHelper.speak("Awaz samajh nahi aayi Sir.") }
                )
            } catch (e: Exception) {
                Log.w("FloatingBubble", "SpeechRecognizer notice: ${e.message}")
            }

            setupFloatingView()
            startBankingShieldWatcher()
        } catch (e: Exception) {
            Log.e("FloatingBubble", "Critical error in onCreate: ${e.message}")
        }
    }

    private fun startBankingShieldWatcher() {
        serviceScope.launch(Dispatchers.IO) {
            while (true) {
                delay(1500)
                try {
                    if (BankingProtectionManager.isShieldActive) {
                        val fgPkg = BankingProtectionManager.getForegroundPackage(this@FloatingBubbleService)
                        // If user moved away from banking app to launcher, home, or another app
                        if (fgPkg != null && !BankingProtectionManager.isBankingApp(fgPkg)) {
                            BankingProtectionManager.reattachAccessibility(this@FloatingBubbleService)
                        }
                    }
                } catch (e: Exception) {
                    Log.w("FloatingBubble", "Banking shield watcher notice: ${e.message}")
                }
            }
        }
    }

    private fun setupFloatingView() {
        try {
            floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null)

            val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 20
                y = 300
            }

            val ivArc = floatingView?.findViewById<ImageView>(R.id.ivFloatingArc)

            ivArc?.setOnClickListener {
                if (BankingProtectionManager.isShieldActive) {
                    BankingProtectionManager.reattachAccessibility(this)
                    speakerHelper.speak("Jarvis reconnected Sir.")
                    return@setOnClickListener
                }
                speakerHelper.speak("Ji Sir?")
                speechRecognizerHelper?.startListening()
            }

            ivArc?.setOnTouchListener(object : View.OnTouchListener {
                private var initialX = 0
                private var initialY = 0
                private var initialTouchX = 0f
                private var initialTouchY = 0f

                override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                    if (event == null) return false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialX = params.x
                            initialY = params.y
                            initialTouchX = event.rawX
                            initialTouchY = event.rawY
                            return false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            params.x = initialX + (event.rawX - initialTouchX).toInt()
                            params.y = initialY + (event.rawY - initialTouchY).toInt()
                            try {
                                windowManager.updateViewLayout(floatingView, params)
                            } catch (e: Exception) {}
                            return true
                        }
                    }
                    return false
                }
            })

            windowManager.addView(floatingView, params)
        } catch (e: Exception) {
            Log.e("FloatingBubble", "Floating view error: ${e.message}")
        }
    }

    private fun handleVoiceCommand(userText: String) {
        serviceScope.launch {
            JarvisLogStore.log("USER_BUBBLE", "Voice command: \"$userText\"")
            val screenContext = JarvisAccessibilityService.instance?.dumpScreenElementsSummary() ?: ""
            val decision = geminiBrain.processCommand(userText, screenContext)
            val speech = decision.speech?.takeIf { it.isNotBlank() && it != "null" }
            val alreadySpoken = speech != null

            if (speech != null) {
                JarvisLogStore.log("JARVIS_SPEAK", speech)
                speakerHelper.speak(speech)
            }

            val tool = decision.toolName?.takeIf { it.isNotBlank() && it != "null" } ?: return@launch
            val args = decision.toolArgs ?: emptyMap()
            JarvisLogStore.log("TOOL_DISPATCH", "From FloatingBubble -> tool=$tool args=$args")
            executeTool(tool, args, alreadySpoken)
        }
    }

    private fun executeTool(tool: String, args: Map<String, Any>, alreadySpoken: Boolean = false) {
        when (tool) {
            "schedule_action" -> {
                val delaySec = (args["delay_seconds"] as? Number)?.toInt() ?: 10
                val subAction = (args["action"] as? String) ?: (args["tool_name"] as? String) ?: ""
                @Suppress("UNCHECKED_CAST")
                val subArgs = (args["action_args"] as? Map<String, Any>) ?: (args["tool_args"] as? Map<String, Any>) ?: emptyMap()
                val summary = (args["summary"] as? String) ?: "Scheduled $subAction"
                scheduledTaskManager.scheduleTask(delaySec, subAction, subArgs, summary)
                speakerHelper.speak("Sir, task schedule kar diya hai. Dusra kaam karne par bhi yeh apne samay par execute hoga.")
            }
            "manage_vip" -> {
                val action = (args["action"] as? String)?.lowercase() ?: "add"
                val contact = (args["contact"] as? String) ?: (args["name"] as? String) ?: ""
                if (action == "add" && contact.isNotBlank()) {
                    memoryManager.addVipContact(contact)
                    speakerHelper.speak("Sir, $contact ko VIP priority list me add kar diya hai. Silent aur DND mode me bhi inka call bajega.")
                } else if (action == "remove" && contact.isNotBlank()) {
                    memoryManager.removeVipContact(contact)
                    speakerHelper.speak("Sir, $contact ko VIP list se hata diya gaya hai.")
                } else {
                    val vips = memoryManager.getVipContacts()
                    val reply = if (vips.isEmpty()) "Sir, abhi koi VIP contact set nahi hai." else "Sir, aapke VIP contacts hain: ${vips.joinToString(", ")}."
                    speakerHelper.speak(reply)
                }
            }
            "learn_preference" -> {
                val pref = (args["preference"] as? String) ?: ""
                if (pref.isNotBlank()) {
                    memoryManager.learnPreference(pref)
                    speakerHelper.speak("Sir, maine aapki yeh pasand note kar li hai.")
                }
            }
            "universal_app_action" -> {
                val appName = (args["app_name"] as? String) ?: "Notes"
                val instruction = (args["instruction"] as? String) ?: (args["text"] as? String) ?: (args["action"] as? String) ?: ""
                universalAppAgent.performAppAction(appName, instruction, geminiBrain) { success, reply ->
                    JarvisLogStore.log("APP_AGENT_DONE", "app=$appName ok=$success reply='$reply'")
                    if (!alreadySpoken && reply.isNotBlank()) {
                        speakerHelper.speak(reply)
                    }
                }
            }
            "sleep_routine" -> {
                val mins = (args["minutes"] as? Number)?.toInt() ?: 10
                val reply = systemController.sleepAndWakeUp(mins) { msg ->
                    speakerHelper.speak(msg)
                }
                speakerHelper.speak(reply)
            }
            "set_dnd" -> {
                val enabled = (args["enabled"] as? Boolean) ?: true
                systemController.setDnd(enabled)
            }
            "set_brightness" -> {
                val level = args["level"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "medium"
                val reply = systemController.setBrightness(level)
                if (!alreadySpoken || reply.contains("permission", ignoreCase = true)) {
                    speakerHelper.speak(reply)
                }
            }
            "toggle_flashlight" -> {
                val raw = args["enabled"]
                val enabled: Boolean? = when (raw) {
                    is Boolean -> raw
                    is String -> when (raw.lowercase().trim()) {
                        "true", "on", "enable", "start" -> true
                        "false", "off", "disable", "stop" -> false
                        else -> null
                    }
                    else -> null
                }
                val reply = systemController.setFlashlight(enabled)
                if (!alreadySpoken) speakerHelper.speak(reply)
            }
            "toggle_speakerphone" -> {
                val raw = args["enabled"]
                val enabled = when (raw) {
                    is Boolean -> raw
                    is String -> raw.lowercase().trim() !in listOf("false", "off", "disable")
                    else -> true
                }
                systemController.setSpeakerphone(enabled)
            }
            "set_alarm" -> {
                val h = (args["hour"] as? Number)?.toInt() ?: 7
                val m = (args["minute"] as? Number)?.toInt() ?: 0
                val msg = (args["message"] as? String) ?: "Alarm"
                systemController.setAlarm(h, m, msg)
            }
            "set_timer" -> {
                val s = (args["seconds"] as? Number)?.toInt() ?: 60
                val msg = (args["message"] as? String) ?: "Timer"
                systemController.setTimer(s, msg)
            }
            "answer_call" -> {
                val reply = systemController.answerCall()
                if (!alreadySpoken) speakerHelper.speak(reply)
            }
            "end_call" -> {
                val reply = systemController.endCall()
                if (!alreadySpoken) speakerHelper.speak(reply)
            }
            "close_app" -> {
                val appName = (args["app_name"] as? String) ?: (args["name"] as? String) ?: ""
                val reply = systemController.closeApp(appName)
                if (!alreadySpoken) speakerHelper.speak(reply)
            }
            "make_phone_call" -> {
                runAfterScreenUnlock {
                    val target = ((args["recipient"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: (args["phone_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "").trim()
                    val (resolvedNumber, _) = telephonyHelper.resolveContactNumber(target)
                    if (resolvedNumber == null && target.replace(Regex("[^0-9+]"), "").length < 7) {
                        speakerHelper.speak("Sir, '$target' ka phone number contacts me nahi mila.")
                    } else {
                        val numberToCall = resolvedNumber ?: target
                        val reply = systemController.makePhoneCall(numberToCall)
                        speakerHelper.speak(reply)
                    }
                }
            }
            "send_sms" -> {
                runAfterScreenUnlock {
                    val rec = (args["recipient"] as? String) ?: ""
                    val msg = (args["message"] as? String) ?: ""
                    systemController.sendSms(rec, msg)
                }
            }
            "play_youtube" -> {
                runAfterScreenUnlock {
                    val q = (args["query"] as? String) ?: "trending songs"
                    systemController.playYoutube(q) { _, directPlayed ->
                        serviceScope.launch {
                            delay(900)
                            AutoUnlockManager.unlockAppLockIfPresent(this@FloatingBubbleService, geminiBrain)
                            if (!directPlayed) {
                                nodeActionEngine.tapFirstYoutubeSearchResult()
                            }
                        }
                    }
                }
            }
            "open_app" -> {
                runAfterScreenUnlock(checkAppLockAfter = true) {
                    val app = (args["app_name"] as? String) ?: ""
                    systemController.openApp(app)
                }
            }
            "send_whatsapp_message" -> {
                val rec = (args["recipient"] as? String) ?: ""
                val msg = (args["message"] as? String) ?: ""
                nodeActionEngine.sendWhatsAppMessage(rec, msg) { success, reply ->
                    if (success) speakerHelper.speak(reply)
                }
            }
            "whatsapp_read_messages" -> {
                val sender = (args["sender"] as? String) ?: (args["recipient"] as? String) ?: ""
                nodeActionEngine.readWhatsAppMessages(sender) { _, reply ->
                    if (!alreadySpoken && reply.isNotBlank()) speakerHelper.speak(reply)
                }
            }
            "whatsapp_call" -> {
                val rec = (args["recipient"] as? String) ?: ""
                val isVideo = (args["call_type"] as? String)?.lowercase() == "video"
                nodeActionEngine.callWhatsApp(rec, isVideo) { success, reply ->
                    if (success) speakerHelper.speak(reply)
                }
            }
            "tap_tag" -> {
                runAfterScreenUnlock {
                    val tag = (args["tag"] as? Number)?.toInt() ?: 1
                    nodeActionEngine.tapByTag(tag) { _, reply ->
                        speakerHelper.speak(reply)
                    }
                }
            }
            "type_tag" -> {
                runAfterScreenUnlock {
                    val tag = (args["tag"] as? Number)?.toInt() ?: 1
                    val text = (args["text"] as? String) ?: ""
                    nodeActionEngine.typeTextByTag(tag, text) { _, reply ->
                        speakerHelper.speak(reply)
                    }
                }
            }
            "scroll_screen" -> {
                runAfterScreenUnlock {
                    val dir = (args["direction"] as? String) ?: "down"
                    nodeActionEngine.scrollScreen(dir) { _, reply ->
                        speakerHelper.speak(reply)
                    }
                }
            }
            "instagram_send_message",
            "instagram_read_last_message",
            "instagram_watch_story",
            "instagram_follow_account",
            "instagram_play_chat_reels",
            "instagram_play_reels" -> {
                universalAppAgent.performInstagramTool(tool, args, geminiBrain) { success, reply ->
                    JarvisLogStore.log("INSTAGRAM_DONE", "tool=$tool ok=$success reply='$reply'")
                    if (!alreadySpoken && reply.isNotBlank()) {
                        speakerHelper.speak(reply)
                    }
                }
            }
        }
    }

    private fun runAfterScreenUnlock(
        checkAppLockAfter: Boolean = false,
        block: () -> Unit
    ) {
        if (!AutoUnlockManager.isScreenLockedOrOff(this)) {
            block()
            if (checkAppLockAfter) {
                serviceScope.launch {
                    delay(900)
                    AutoUnlockManager.unlockAppLockIfPresent(this@FloatingBubbleService, geminiBrain)
                }
            }
            return
        }
        serviceScope.launch {
            AutoUnlockManager.ensureScreenUnlocked(this@FloatingBubbleService)
            block()
            if (checkAppLockAfter) {
                delay(1000)
                AutoUnlockManager.unlockAppLockIfPresent(this@FloatingBubbleService, geminiBrain)
            }
        }
    }

    private fun startForegroundServiceNotification() {
        try {
            val channelId = "jarvis_service_channel"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "J.A.R.V.I.S. Background Service",
                    NotificationManager.IMPORTANCE_LOW
                )
                val nm = getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }

            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("J.A.R.V.I.S. Active")
                .setContentText("Autonomous Assistant is running.")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1001, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1001, notification)
            }
        } catch (e: Exception) {
            Log.w("FloatingBubble", "startForeground exception: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (floatingView != null) {
                windowManager.removeView(floatingView)
            }
            speechRecognizerHelper?.destroy()
            speakerHelper.shutdown()
        } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
