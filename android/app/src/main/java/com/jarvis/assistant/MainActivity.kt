package com.jarvis.assistant

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.brain.GeminiBrain
import com.jarvis.assistant.engine.NodeActionEngine
import com.jarvis.assistant.engine.SystemController
import com.jarvis.assistant.engine.TelephonyHelper
import com.jarvis.assistant.engine.UniversalAppAgent
import com.jarvis.assistant.memory.JarvisMemoryManager
import com.jarvis.assistant.scheduler.ScheduledTaskManager
import com.jarvis.assistant.security.BankingProtectionManager
import com.jarvis.assistant.update.AppUpdateManager
import com.jarvis.assistant.voice.SpeakerHelper
import com.jarvis.assistant.voice.SpeechRecognizerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        var instance: MainActivity? = null
            private set
    }

    private lateinit var tvTitle: TextView
    private lateinit var tvSystemStatusBadge: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnDnd: Button
    private lateinit var btnWriteSettings: Button
    private lateinit var btnApiKey: Button
    private lateinit var btnShield: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnTalk: Button
    private lateinit var etCommandInput: EditText
    private lateinit var btnSendCommand: Button
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView

    private lateinit var systemController: SystemController
    private lateinit var telephonyHelper: TelephonyHelper
    private lateinit var nodeActionEngine: NodeActionEngine
    private lateinit var universalAppAgent: UniversalAppAgent
    private lateinit var speakerHelper: SpeakerHelper
    private var speechRecognizerHelper: SpeechRecognizerHelper? = null
    private lateinit var geminiBrain: GeminiBrain
    private lateinit var memoryManager: JarvisMemoryManager
    private lateinit var scheduledTaskManager: ScheduledTaskManager

    private val mainScope = CoroutineScope(Dispatchers.Main)
    private val httpClient = OkHttpClient()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        // Crash-proofing: Catch any uncaught exception and log it instead of dying
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("JarvisCrash", "Uncaught exception: ${throwable.message}", throwable)
            Handler(Looper.getMainLooper()).post {
                appendLog("[Crash Prevented]: ${throwable.message}")
            }
        }

        setContentView(R.layout.activity_main)

        initViews()
        initServices()
        setupListeners()
        requestAppPermissions()

        // Automatic In-App Update check on launch
        AppUpdateManager.checkForUpdates(this, isManual = false)
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvTitle)
        tvSystemStatusBadge = findViewById(R.id.tvSystemStatusBadge)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnDnd = findViewById(R.id.btnDnd)
        btnWriteSettings = findViewById(R.id.btnWriteSettings)
        btnApiKey = findViewById(R.id.btnApiKey)
        btnShield = findViewById(R.id.btnShield)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnTalk = findViewById(R.id.btnTalk)
        etCommandInput = findViewById(R.id.etCommandInput)
        btnSendCommand = findViewById(R.id.btnSendCommand)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
    }

    private fun initServices() {
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
                onResult = { userText ->
                    appendLog("User: \"$userText\"")
                    processUserCommand(userText)
                },
                onError = { err ->
                    appendLog("[Voice]: Mic stopped ($err)")
                }
            )
        } catch (e: Exception) {
            appendLog("[Voice]: Native speech recognizer notice: ${e.message}")
        }

        appendLog("J.A.R.V.I.S. Android Operating Core ready.")
    }

    private fun setupListeners() {
        btnAccessibility.setOnClickListener {
            showAccessibilityGuidanceDialog()
        }

        btnDnd.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                startActivity(intent)
            }
        }

        btnWriteSettings.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Write Settings permission granted", Toast.LENGTH_SHORT).show()
            }
        }

        btnApiKey.setOnClickListener {
            val prefs = getSharedPreferences("jarvis_config", Context.MODE_PRIVATE)
            val currentKey = prefs.getString("gemini_api_key", "") ?: ""
            val input = EditText(this).apply {
                setText(currentKey)
                hint = "Enter/paste Gemini API Key"
            }
            AlertDialog.Builder(this)
                .setTitle("Gemini API Key")
                .setMessage("Paste your new Gemini API key here:")
                .setView(input)
                .setPositiveButton("Save") { _, _ ->
                    val newKey = input.text.toString().trim()
                    prefs.edit().putString("gemini_api_key", newKey).apply()
                    val defaultKey = BuildConfig.GEMINI_API_KEY
                    geminiBrain = GeminiBrain(if (newKey.isNotBlank()) newKey else defaultKey, memoryManager)
                    appendLog("[Config]: API Key updated successfully.")
                    Toast.makeText(this, "API Key Saved!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnShield.setOnClickListener {
            showBankingShieldDialog()
        }

        btnCheckUpdate.setOnClickListener {
            AppUpdateManager.checkForUpdates(this, isManual = true)
        }

        // Voice button: Tap to talk
        btnTalk.setOnClickListener {
            speakerHelper.speak("Ji Sir?")
            appendLog("[Voice]: Listening for Sir's command...")
            try {
                speechRecognizerHelper?.startListening()
            } catch (e: Exception) {
                appendLog("[Voice Error]: ${e.message}")
            }
        }

        // Text command input runner
        btnSendCommand.setOnClickListener {
            val cmd = etCommandInput.text.toString().trim()
            if (cmd.isNotEmpty()) {
                etCommandInput.setText("")
                appendLog("User (Text): \"$cmd\"")
                processUserCommand(cmd)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (BankingProtectionManager.isShieldActive) {
            BankingProtectionManager.reattachAccessibility(this)
        }
        updatePermissionStatus()
        val missedNotice = memoryManager.popPendingMissedCallNotice()
        if (!missedNotice.isNullOrBlank()) {
            appendLog("[VIP Alert]: $missedNotice")
            speakerHelper.speak(missedNotice)
        }
    }

    private fun updatePermissionStatus() {
        val isAccRunning = JarvisAccessibilityService.isServiceRunning
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isDndGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.isNotificationPolicyAccessGranted
        } else true
        val isWriteSettingsGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(this)
        } else true
        val isShieldGranted = BankingProtectionManager.hasSecureSettingsPermission(this)

        btnAccessibility.text = if (isAccRunning) "Access: ON" else "Access"
        btnAccessibility.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (isAccRunning) R.color.green_active else R.color.jarvis_cyan
        )

        btnDnd.text = if (isDndGranted) "DND: ON" else "DND"
        btnDnd.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (isDndGranted) R.color.green_active else R.color.jarvis_card_bg
        )

        btnWriteSettings.text = if (isWriteSettingsGranted) "Settings: ON" else "Settings"
        btnWriteSettings.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (isWriteSettingsGranted) R.color.green_active else R.color.jarvis_card_bg
        )

        btnShield.text = if (isShieldGranted) "🛡️ Banking Shield: ON (UPI Protected)" else "🛡️ Banking Shield: SETUP (Click for PC command)"
        btnShield.backgroundTintList = ContextCompat.getColorStateList(
            this,
            if (isShieldGranted) R.color.green_active else R.color.jarvis_card_bg
        )
        btnShield.setTextColor(ContextCompat.getColor(this, if (isShieldGranted) R.color.jarvis_dark_bg else R.color.jarvis_gold))

        if (isAccRunning) {
            tvSystemStatusBadge.text = "ONLINE"
            tvSystemStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.green_active))
        } else {
            tvSystemStatusBadge.text = "AWAITING ACCESS"
            tvSystemStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.jarvis_gold))
        }
    }

    private fun showBankingShieldDialog() {
        val isGranted = BankingProtectionManager.hasSecureSettingsPermission(this)
        val hasUsage = BankingProtectionManager.hasUsageStatsPermission(this)
        val adbCmd = "adb shell pm grant com.jarvis.assistant android.permission.WRITE_SECURE_SETTINGS\nadb shell pm grant com.jarvis.assistant android.permission.PACKAGE_USAGE_STATS"

        val msg = if (isGranted) {
            "✅ Banking Shield is ACTIVE!\n\n" +
            "PhonePe, Paytm, Google Pay, BHIM wagera open hone par Jarvis automatically unregister ho jayega taaki koi 'Dangerous app' warning na aaye.\n\n" +
            "Payment complete hone ke baad Jarvis bina puche khud wapas connect ho jayega."
        } else {
            "🛡️ Automated Banking App Shield\n\n" +
            "PhonePe, Paytm, Google Pay ko 'Dangerous app' warning se bachane ke liye phone ko laptop se connect karke ye command run karein (Lifetime One-Time):\n\n" +
            "$adbCmd"
        }

        val builder = AlertDialog.Builder(this)
            .setTitle("Banking Protection Shield")
            .setMessage(msg)
            .setPositiveButton(if (isGranted) "Theek Hai" else "Copy Command") { _, _ ->
                if (!isGranted) {
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("ADB Command", adbCmd)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this, "ADB Command Copied!", Toast.LENGTH_SHORT).show()
                    appendLog("[Shield]: ADB command copied to clipboard.")
                }
            }

        if (!hasUsage) {
            builder.setNeutralButton("Usage Access") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (e: Exception) {}
            }
        }
        builder.setNegativeButton("Close", null).show()
    }

    private fun showAccessibilityGuidanceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Accessibility Permission Setup")
            .setMessage(
                "OnePlus / Android 13+ me agar Accessibility 'Greyed-out' ya 'Not working' dikhe:\n\n" +
                "1. Phone ki Home screen par J.A.R.V.I.S. icon ko 1 second daba kar rakhein -> 'App Info' (ⓘ) par jayein.\n" +
                "2. Upar right corner me 3 dots (⋮) par tap karein.\n" +
                "3. 'Allow restricted settings' par tap karein aur fingerprint lagayein.\n" +
                "4. Ab 'Settings Kholein' par click karke ON kar dein!"
            )
            .setPositiveButton("Settings Kholein") { _, _ ->
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun processUserCommand(userText: String) {
        mainScope.launch {
            appendLog("Brain: Analyzing with Gemini AI...")
            val screenContext = JarvisAccessibilityService.instance?.dumpScreenElementsSummary() ?: ""
            val decision = geminiBrain.processCommand(userText, screenContext)

            val tool = decision.toolName?.takeIf { it.isNotBlank() && it != "null" }
            val args = decision.toolArgs ?: emptyMap()
            val speech = decision.speech?.takeIf { it.isNotBlank() && it != "null" }
            val alreadySpoken = speech != null

            if (speech != null) {
                appendLog("Jarvis: \"$speech\"")
                speakerHelper.speak(speech)
            }

            if (tool == null) {
                appendLog("[Result]: Done (Conversational)")
                return@launch
            }

            executeSingleTool(tool, args, alreadySpoken)
        }
    }

    fun executeToolFromScheduler(action: String, args: Map<String, Any>, summary: String = "") {
        appendLog("[Scheduler Alarm]: Executing scheduled task '$action'...")
        if (summary.isNotBlank()) {
            speakerHelper.speak("Sir, aapka scheduled task: $summary")
        }
        executeSingleTool(action, args, false)
    }

    private fun executeSingleTool(tool: String, args: Map<String, Any>, alreadySpoken: Boolean = false) {
        appendLog("Executing Tool: $tool($args)...")

        when (tool) {
            "schedule_action" -> {
                val delaySec = (args["delay_seconds"] as? Number)?.toInt() ?: 10
                val subAction = (args["action"] as? String) ?: (args["tool_name"] as? String) ?: ""
                @Suppress("UNCHECKED_CAST")
                val subArgs = (args["action_args"] as? Map<String, Any>) ?: (args["tool_args"] as? Map<String, Any>) ?: emptyMap()
                val summary = (args["summary"] as? String) ?: "Scheduled $subAction"
                val taskId = scheduledTaskManager.scheduleTask(delaySec, subAction, subArgs, summary)
                appendLog("[Scheduler]: Task '$subAction' permanently saved in AlarmManager for $delaySec seconds (ID: $taskId). It will NEVER be dropped.")
                speakerHelper.speak("Sir, task schedule kar diya hai. Dusra kaam karne par bhi yeh apne samay par execute hoga.")
            }
            "manage_vip" -> {
                val action = (args["action"] as? String)?.lowercase() ?: "add"
                val contact = (args["contact"] as? String) ?: (args["name"] as? String) ?: ""
                if (action == "add" && contact.isNotBlank()) {
                    memoryManager.addVipContact(contact)
                    val reply = "Sir, $contact ko VIP priority list me add kar diya hai. Silent aur DND mode me bhi inka call bajega."
                    appendLog("[VIP]: $reply")
                    speakerHelper.speak(reply)
                } else if (action == "remove" && contact.isNotBlank()) {
                    memoryManager.removeVipContact(contact)
                    val reply = "Sir, $contact ko VIP priority list se hata diya gaya hai."
                    appendLog("[VIP]: $reply")
                    speakerHelper.speak(reply)
                } else {
                    val vips = memoryManager.getVipContacts()
                    val reply = if (vips.isEmpty()) "Sir, abhi koi VIP contact set nahi hai." else "Sir, aapke VIP contacts hain: ${vips.joinToString(", ")}."
                    appendLog("[VIP]: $reply")
                    speakerHelper.speak(reply)
                }
            }
            "learn_preference" -> {
                val pref = (args["preference"] as? String) ?: ""
                if (pref.isNotBlank()) {
                    memoryManager.learnPreference(pref)
                    val reply = "Sir, maine aapki yeh pasand note kar li hai."
                    appendLog("[Memory]: $reply")
                    speakerHelper.speak(reply)
                }
            }
            "universal_app_action" -> {
                val appName = (args["app_name"] as? String) ?: "Notes"
                val instruction = (args["instruction"] as? String) ?: (args["text"] as? String) ?: (args["action"] as? String) ?: ""
                appendLog("[App Agent]: Opening $appName for AI execution...")
                universalAppAgent.performAppAction(appName, instruction, geminiBrain) { success, reply ->
                    appendLog("[App Agent]: $reply")
                    if (!alreadySpoken && reply.isNotBlank()) {
                        speakerHelper.speak(reply)
                    }
                }
            }
            "sleep_routine" -> {
                val mins = (args["minutes"] as? Number)?.toInt() ?: 10
                val reply = systemController.sleepAndWakeUp(mins) { wakeupMsg ->
                    appendLog("[Sleep Wakeup]: $wakeupMsg")
                    speakerHelper.speak(wakeupMsg)
                }
                appendLog("[Sleep Butler]: $reply")
                speakerHelper.speak(reply)
            }
            "set_dnd" -> {
                val enabled = (args["enabled"] as? Boolean) ?: true
                val reply = systemController.setDnd(enabled)
                appendLog("[Result]: $reply")
                speakerHelper.speak(reply)
            }
            "set_brightness" -> {
                val level = (args["level"] as? String) ?: "medium"
                val reply = systemController.setBrightness(level)
                appendLog("[Result]: $reply")
                speakerHelper.speak(reply)
            }
            "set_alarm" -> {
                val h = (args["hour"] as? Number)?.toInt() ?: 7
                val m = (args["minute"] as? Number)?.toInt() ?: 0
                val msg = (args["message"] as? String) ?: "Alarm"
                val reply = systemController.setAlarm(h, m, msg)
                appendLog("[Result]: $reply")
                speakerHelper.speak(reply)
            }
            "set_timer" -> {
                val s = (args["seconds"] as? Number)?.toInt() ?: 60
                val msg = (args["message"] as? String) ?: "Timer"
                val reply = systemController.setTimer(s, msg)
                appendLog("[Result]: $reply")
                speakerHelper.speak(reply)
            }
            "answer_call" -> {
                val reply = systemController.answerCall()
                appendLog("[Call]: $reply")
                if (!alreadySpoken) speakerHelper.speak(reply)
            }
            "end_call" -> {
                val reply = systemController.endCall()
                appendLog("[Call]: $reply")
                if (!alreadySpoken) speakerHelper.speak(reply)
            }
            "close_app" -> {
                val appName = (args["app_name"] as? String) ?: (args["name"] as? String) ?: ""
                val reply = systemController.closeApp(appName)
                appendLog("[App Control]: $reply")
                if (!alreadySpoken) speakerHelper.speak(reply)
            }
            "make_phone_call" -> {
                val target = ((args["recipient"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: (args["phone_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "").trim()
                val (resolvedNumber, resolvedName) = telephonyHelper.resolveContactNumber(target)
                if (resolvedNumber == null && target.replace(Regex("[^0-9+]"), "").length < 7) {
                    val reply = "Sir, '$target' ka phone number contacts me nahi mila."
                    appendLog("[Call]: $reply")
                    speakerHelper.speak(reply)
                } else {
                    val numberToCall = resolvedNumber ?: target
                    val reply = systemController.makePhoneCall(numberToCall)
                    appendLog("[Call]: $reply ($resolvedName)")
                    speakerHelper.speak(reply)
                }
            }
            "send_sms" -> {
                val rec = (args["recipient"] as? String) ?: ""
                val msg = (args["message"] as? String) ?: ""
                val (resolvedNumber, _) = telephonyHelper.resolveContactNumber(rec)
                val numberToSend = resolvedNumber ?: rec
                val reply = systemController.sendSms(numberToSend, msg)
                appendLog("[Result]: $reply")
                speakerHelper.speak(reply)
            }
            "play_youtube" -> {
                val q = (args["query"] as? String) ?: "trending songs"
                systemController.playYoutube(q) { reply ->
                    appendLog("[YouTube]: $reply")
                    speakerHelper.speak(reply)
                }
            }
            "open_app" -> {
                val app = (args["app_name"] as? String) ?: ""
                val reply = systemController.openApp(app)
                appendLog("[App Launcher]: $reply")
                speakerHelper.speak(reply)
            }
            "send_whatsapp_message" -> {
                val rec = (args["recipient"] as? String) ?: ""
                val msg = (args["message"] as? String) ?: ""
                nodeActionEngine.sendWhatsAppMessage(rec, msg) { success, reply ->
                    appendLog("[WhatsApp]: $reply")
                    speakerHelper.speak(reply)
                }
            }
            "whatsapp_call" -> {
                val rec = (args["recipient"] as? String) ?: ""
                val isVideo = (args["call_type"] as? String)?.lowercase() == "video"
                nodeActionEngine.callWhatsApp(rec, isVideo) { success, reply ->
                    appendLog("[WhatsApp Call]: $reply")
                    speakerHelper.speak(reply)
                }
            }
            "tap_tag" -> {
                val tag = (args["tag"] as? Number)?.toInt() ?: 1
                nodeActionEngine.tapByTag(tag) { success, reply ->
                    appendLog("[UI Agent]: $reply")
                    speakerHelper.speak(reply)
                }
            }
            "type_tag" -> {
                val tag = (args["tag"] as? Number)?.toInt() ?: 1
                val text = (args["text"] as? String) ?: ""
                nodeActionEngine.typeTextByTag(tag, text) { success, reply ->
                    appendLog("[UI Agent]: $reply")
                    speakerHelper.speak(reply)
                }
            }
            "scroll_screen" -> {
                val dir = (args["direction"] as? String) ?: "down"
                nodeActionEngine.scrollScreen(dir) { success, reply ->
                    appendLog("[Scroll]: $reply")
                    speakerHelper.speak(reply)
                }
            }
            else -> {
                appendLog("[Tool Warning]: Unknown action '$tool'")
            }
        }
    }

    private fun appendLog(message: String) {
        val time = timeFormat.format(Date())
        val logLine = "[$time] $message"
        runOnUiThread {
            val current = tvLogs.text.toString()
            tvLogs.text = if (current.isEmpty()) logLine else "$current\n$logLine"
            scrollLogs.post {
                scrollLogs.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
        sendRemoteLog(logLine)
    }

    private fun sendRemoteLog(logMessage: String) {
        // Asynchronously post log to computer's local server (10.0.9.197:8000)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val mediaType = "text/plain; charset=utf-8".toMediaType()
                val req = Request.Builder()
                    .url("http://10.0.9.197:8000/log")
                    .post(logMessage.toRequestBody(mediaType))
                    .build()
                httpClient.newCall(req).execute().close()
            } catch (e: Exception) {
                // Silently ignore if laptop server is not listening
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) instance = null
        speechRecognizerHelper?.destroy()
        speakerHelper.shutdown()
    }
}
