package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.brain.GeminiBrain
import com.jarvis.assistant.engine.NodeActionEngine
import com.jarvis.assistant.engine.SystemController
import com.jarvis.assistant.engine.TelephonyHelper
import com.jarvis.assistant.voice.SpeakerHelper
import com.jarvis.assistant.voice.SpeechRecognizerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FloatingBubbleService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null

    private lateinit var systemController: SystemController
    private lateinit var telephonyHelper: TelephonyHelper
    private lateinit var nodeActionEngine: NodeActionEngine
    private lateinit var speakerHelper: SpeakerHelper
    private lateinit var speechRecognizerHelper: SpeechRecognizerHelper
    private lateinit var geminiBrain: GeminiBrain

    private val serviceScope = CoroutineScope(Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        systemController = SystemController(this)
        telephonyHelper = TelephonyHelper(this)
        nodeActionEngine = NodeActionEngine(systemController, telephonyHelper)
        speakerHelper = SpeakerHelper(this)

        // API Key loaded securely from app preferences
        val prefs = getSharedPreferences("jarvis_config", Context.MODE_PRIVATE)
        val apiKey = prefs.getString("gemini_api_key", "") ?: ""
        geminiBrain = GeminiBrain(apiKey)

        speechRecognizerHelper = SpeechRecognizerHelper(
            this,
            onResult = { userText -> handleVoiceCommand(userText) },
            onError = { err -> speakerHelper.speak("Awaz samajh nahi aayi Sir.") }
        )

        setupFloatingView()
    }

    private fun setupFloatingView() {
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
            speakerHelper.speak("Ji Sir?")
            speechRecognizerHelper.startListening()
        }

        // Drag-and-drop movement listener
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
                        return false // allow click
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        try {
            windowManager.addView(floatingView, params)
        } catch (e: Exception) {
            Log.e("FloatingBubbleService", "Failed to add floating view: ${e.message}")
        }
    }

    private fun handleVoiceCommand(userText: String) {
        Log.d("FloatingBubble", "User command: '$userText'")

        serviceScope.launch {
            val decision = geminiBrain.processCommand(userText)
            if (decision.speech.isNotBlank()) {
                speakerHelper.speak(decision.speech)
            }

            val tool = decision.toolName ?: return@launch
            val args = decision.toolArgs ?: emptyMap()

            when (tool) {
                "set_dnd" -> {
                    val enabled = (args["enabled"] as? Boolean) ?: true
                    systemController.setDnd(enabled)
                }
                "set_brightness" -> {
                    val level = (args["level"] as? String) ?: "medium"
                    systemController.setBrightness(level)
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
                "make_phone_call" -> {
                    val num = (args["phone_number"] as? String) ?: ""
                    systemController.makePhoneCall(num)
                }
                "send_sms" -> {
                    val rec = (args["recipient"] as? String) ?: ""
                    val msg = (args["message"] as? String) ?: ""
                    systemController.sendSms(rec, msg)
                }
                "play_youtube" -> {
                    val q = (args["query"] as? String) ?: "trending songs"
                    systemController.playYoutube(q)
                }
                "open_app" -> {
                    val app = (args["app_name"] as? String) ?: ""
                    systemController.openApp(app)
                }
                "send_whatsapp_message" -> {
                    val rec = (args["recipient"] as? String) ?: ""
                    val msg = (args["message"] as? String) ?: ""
                    nodeActionEngine.sendWhatsAppMessage(rec, msg) { success, reply ->
                        if (success) speakerHelper.speak(reply)
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
                    val tag = (args["tag"] as? Number)?.toInt() ?: 1
                    nodeActionEngine.tapByTag(tag) { success, reply ->
                        Log.d("FloatingBubble", reply)
                    }
                }
            }
        }
    }

    private fun startForegroundServiceNotification() {
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
            .setContentText("Autonomous Assistant is running in background.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatingView != null) {
            windowManager.removeView(floatingView)
        }
        speechRecognizerHelper.destroy()
        speakerHelper.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
