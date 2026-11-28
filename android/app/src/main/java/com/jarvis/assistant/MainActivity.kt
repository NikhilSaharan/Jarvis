package com.jarvis.assistant

import android.Manifest
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.jarvis.assistant.debug.JarvisLogStore
import com.jarvis.assistant.engine.JarvisCommandExecutor
import com.jarvis.assistant.memory.JarvisMemoryManager
import com.jarvis.assistant.security.AutoUnlockManager
import com.jarvis.assistant.security.BankingProtectionManager
import com.jarvis.assistant.security.PatternLockPadView
import com.jarvis.assistant.update.AppUpdateManager
import com.jarvis.assistant.voice.AlwaysOnVoiceService
import com.jarvis.assistant.voice.PassiveVoiceCaptureEngine
import com.jarvis.assistant.voice.SpeakerHelper
import com.jarvis.assistant.voice.VoiceprintManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

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
    private lateinit var btnLockSetup: Button
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnVoiceLock: Button
    private lateinit var btnTalk: Button
    private lateinit var etCommandInput: EditText
    private lateinit var btnSendCommand: Button
    private lateinit var btnDownloadLogs: Button
    private lateinit var btnCopyLogs: Button
    private lateinit var btnClearLogs: Button
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private var pendingExportReady: Boolean = false
    private var pendingStartAlwaysOn: Boolean = false

    private lateinit var speakerHelper: SpeakerHelper
    private lateinit var memoryManager: JarvisMemoryManager
    private lateinit var commandExecutor: JarvisCommandExecutor

    private val httpClient = OkHttpClient()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updatePermissionStatus()
        if (pendingStartAlwaysOn &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            pendingStartAlwaysOn = false
            if (!VoiceprintManager.isFullyConfigured(this)) {
                showVoiceSetupPromptDialog(startMicAfter = true, onLaunch = false)
            } else {
                activatePermanentVoice()
            }
        }
    }

    private val createLogDocLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null && pendingExportReady) {
            try {
                var written = 0
                contentResolver.openOutputStream(uri)?.use { out ->
                    written = JarvisLogStore.writeFullLogsTo(out)
                }
                Toast.makeText(
                    this,
                    "Full logs saved ($written lines)!",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                pendingExportReady = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        JarvisLogStore.init(this)

        // Crash-proofing: Catch any uncaught exception and log it instead of dying
        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
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
        AlwaysOnVoiceService.ensureRunningIfEnabled(this, "MAIN_LAUNCH")
        updateAlwaysOnUiState()
        refreshLogsView()
        JarvisLogStore.setListener {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    refreshLogsView()
                }
            }
        }

        // Automatic In-App Update check on launch
        AppUpdateManager.checkForUpdates(this, isManual = false)

        // Show Voice Lock + Secret Code setup prompt right after opening app if not configured yet
        if (!VoiceprintManager.isFullyConfigured(this)) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isFinishing && !isDestroyed && !VoiceprintManager.isFullyConfigured(this)) {
                    showVoiceSetupPromptDialog(startMicAfter = false, onLaunch = true)
                }
            }, 450L)
        }
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvTitle)
        tvSystemStatusBadge = findViewById(R.id.tvSystemStatusBadge)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnDnd = findViewById(R.id.btnDnd)
        btnWriteSettings = findViewById(R.id.btnWriteSettings)
        btnApiKey = findViewById(R.id.btnApiKey)
        btnShield = findViewById(R.id.btnShield)
        btnLockSetup = findViewById(R.id.btnLockSetup)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnVoiceLock = findViewById(R.id.btnVoiceLock)
        btnTalk = findViewById(R.id.btnTalk)
        etCommandInput = findViewById(R.id.etCommandInput)
        btnSendCommand = findViewById(R.id.btnSendCommand)
        btnDownloadLogs = findViewById(R.id.btnDownloadLogs)
        btnCopyLogs = findViewById(R.id.btnCopyLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
    }

    private fun initServices() {
        speakerHelper = SpeakerHelper(this)
        memoryManager = JarvisMemoryManager(this)
        commandExecutor = JarvisCommandExecutor(this, speakerHelper, memoryManager)
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
                    commandExecutor.reloadApiKey(newKey)
                    appendLog("[Config]: API Key updated successfully.")
                    Toast.makeText(this, "API Key Saved!", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnShield.setOnClickListener {
            showBankingShieldDialog()
        }

        btnLockSetup.setOnClickListener {
            showAutoUnlockSetupDialog()
        }

        btnCheckUpdate.setOnClickListener {
            AppUpdateManager.checkForUpdates(this, isManual = true)
        }

        btnVoiceLock.setOnClickListener {
            showVoiceLockDialog()
        }

        // Permanent Always-On 24/7 Voice Listener Start / Stop Toggle
        btnTalk.setOnClickListener {
            if (AlwaysOnVoiceService.isEnabled(this)) {
                AlwaysOnVoiceService.stopPermanent(this)
                speakerHelper.speak("Mic listening band kar di hai Sir.")
                Toast.makeText(this, "24/7 Mic Stopped", Toast.LENGTH_SHORT).show()
                updateAlwaysOnUiState()
                updatePermissionStatus()
            } else {
                val hasMicPerm = ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasMicPerm) {
                    pendingStartAlwaysOn = true
                    requestAppPermissions()
                    Toast.makeText(this, "Mic permission allow karein Sir", Toast.LENGTH_SHORT).show()
                } else if (!VoiceprintManager.isFullyConfigured(this)) {
                    showVoiceSetupPromptDialog(startMicAfter = true, onLaunch = false)
                } else {
                    activatePermanentVoice()
                }
            }
        }

        // Text command input runner
        btnSendCommand.setOnClickListener {
            val cmd = etCommandInput.text.toString().trim()
            if (cmd.isNotEmpty()) {
                etCommandInput.setText("")
                commandExecutor.executeUserCommand(
                    userText = cmd,
                    sourceTag = "USER_UI",
                    onLogLine = { line -> sendRemoteLog("User (Text): \"$line\"") }
                )
            }
        }

        btnDownloadLogs.setOnClickListener {
            exportLogsToTxtFile()
        }

        btnCopyLogs.setOnClickListener {
            val fullText = JarvisLogStore.getFullLogText()
            if (fullText.isBlank()) {
                Toast.makeText(this, "Abhi koi logs nahi hain.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("jarvis_full_logs", fullText))
            Toast.makeText(
                this,
                "Copied all logs (${JarvisLogStore.lineCount()} lines, ${fullText.length} chars)!",
                Toast.LENGTH_SHORT
            ).show()
        }

        btnClearLogs.setOnClickListener {
            JarvisLogStore.clear()
            refreshLogsView()
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun activatePermanentVoice() {
        AlwaysOnVoiceService.startPermanent(this)
        val lockNote = if (VoiceprintManager.isLockActive(this)) {
            "Ab main bina 'Jarvis' bole bhi sirf aapki aawaz sununga, aur Hotstar ya Reels band nahi honge."
        } else {
            "Permanent mic shuru kar diya hai Sir. Hotstar aur Reels ke saath bhi sunta rahunga."
        }
        speakerHelper.speak(lockNote)
        Toast.makeText(
            this,
            "24/7 Mic ON! Zero-Pause mode active.",
            Toast.LENGTH_LONG
        ).show()
        requestIgnoreBatteryOptimizationsIfNeeded()
        updateAlwaysOnUiState()
        updatePermissionStatus()
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun exportLogsToTxtFile() {
        val lineTotal = JarvisLogStore.lineCount()
        if (lineTotal <= 0) {
            Toast.makeText(this, "Abhi koi logs nahi hain.", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "Jarvis_Logs_$stamp.txt"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Jarvis")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    var written = 0
                    contentResolver.openOutputStream(uri)?.use { out ->
                        written = JarvisLogStore.writeFullLogsTo(out)
                    }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    contentResolver.update(uri, values, null, null)
                    Toast.makeText(
                        this,
                        "Saved Downloads/Jarvis/$fileName ($written lines)!",
                        Toast.LENGTH_LONG
                    ).show()
                    return
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "MediaStore Downloads write failed: ${e.message}")
            }
        }

        pendingExportReady = true
        createLogDocLauncher.launch(fileName)
    }

    override fun onResume() {
        super.onResume()
        refreshLogsView()
        AlwaysOnVoiceService.onStatusChanged = {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    updateAlwaysOnUiState()
                    updatePermissionStatus()
                }
            }
        }
        AlwaysOnVoiceService.ensureRunningIfEnabled(this, "MAIN_RESUME")
        if (BankingProtectionManager.isShieldActive) {
            BankingProtectionManager.reattachAccessibility(this)
        }
        updateAlwaysOnUiState()
        updatePermissionStatus()
        val missedNotice = memoryManager.popPendingMissedCallNotice()
        if (!missedNotice.isNullOrBlank()) {
            appendLog("[VIP Alert]: $missedNotice")
            speakerHelper.speak(missedNotice)
        }
    }

    private fun updateAlwaysOnUiState() {
        val active = AlwaysOnVoiceService.isEnabled(this)
        if (active) {
            btnTalk.text = "⏹  MIC ON 24/7 — TAP TO STOP"
            btnTalk.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#D32F2F"))
            btnTalk.setTextColor(Color.WHITE)
        } else {
            btnTalk.text = "▶  START ALWAYS-ON MIC"
            btnTalk.backgroundTintList = ContextCompat.getColorStateList(this, R.color.jarvis_gold)
            btnTalk.setTextColor(ContextCompat.getColor(this, R.color.jarvis_dark_bg))
        }
    }

    private fun updatePermissionStatus() {
        val isAccRunning = JarvisAccessibilityService.isServiceRunning
        val isAlwaysOn = AlwaysOnVoiceService.isEnabled(this)
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

        updateVoiceLockButtonUi()

        when {
            isAlwaysOn && isAccRunning -> {
                tvSystemStatusBadge.text = "MIC 24/7 + ONLINE"
                tvSystemStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            }
            isAlwaysOn -> {
                tvSystemStatusBadge.text = "MIC 24/7 ON"
                tvSystemStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            }
            isAccRunning -> {
                tvSystemStatusBadge.text = "ONLINE"
                tvSystemStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.green_active))
            }
            else -> {
                tvSystemStatusBadge.text = "AWAITING ACCESS"
                tvSystemStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.jarvis_gold))
            }
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

    private fun showAutoUnlockSetupDialog() {
        val currentScreenType = AutoUnlockManager.getScreenLockType(this)
        val currentScreenVal = AutoUnlockManager.getScreenLockValue(this)
        val currentAppType = AutoUnlockManager.getAppLockType(this)
        val currentAppVal = AutoUnlockManager.getAppLockValue(this)

        val darkBg = Color.parseColor("#0A0E17")
        val cardBg = Color.parseColor("#141D2B")
        val cyan = Color.parseColor("#00E5FF")
        val secondaryText = Color.parseColor("#B0BEC5")
        val greenActive = Color.parseColor("#00E676")
        val gold = Color.parseColor("#FFD700")
        val cyanTint = ColorStateList.valueOf(cyan)

        fun createInputBoxDrawable(): GradientDrawable {
            return GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = 16f
                setStroke(2, Color.parseColor("#5500E5FF"))
            }
        }

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(darkBg)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(darkBg)
            setPadding(48, 36, 48, 24)
        }
        scrollView.addView(container)

        val tvHeader = TextView(this).apply {
            text = "🔐 Auto-Unlock & Passwords"
            setTypeface(null, Typeface.BOLD)
            setTextColor(cyan)
            textSize = 18f
            setPadding(0, 0, 0, 8)
        }
        container.addView(tvHeader)

        val tvInfo = TextView(this).apply {
            text = "J.A.R.V.I.S. zaroorat padne par hi lock check karke dynamic tareeqe se Phone Lock aur App Lock kholega. Bina lock ke direct execute hoga.\n"
            textSize = 12.5f
            setTextColor(secondaryText)
        }
        container.addView(tvInfo)

        // ─── SECTION 1: PHONE SCREEN LOCK ──────────────────────────────
        val tvScreenLabel = TextView(this).apply {
            text = "1. Phone Lock Screen:"
            setTypeface(null, Typeface.BOLD)
            setTextColor(cyan)
            textSize = 14.5f
            setPadding(0, 8, 0, 6)
        }
        container.addView(tvScreenLabel)

        val rgScreenType = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val rbScreenNone = RadioButton(this).apply {
            text = "None"
            id = View.generateViewId()
            setTextColor(Color.WHITE)
            buttonTintList = cyanTint
        }
        val rbScreenPin = RadioButton(this).apply {
            text = "PIN"
            id = View.generateViewId()
            setTextColor(Color.WHITE)
            buttonTintList = cyanTint
        }
        val rbScreenPattern = RadioButton(this).apply {
            text = "Pattern"
            id = View.generateViewId()
            setTextColor(Color.WHITE)
            buttonTintList = cyanTint
        }
        rgScreenType.addView(rbScreenNone)
        rgScreenType.addView(rbScreenPin)
        rgScreenType.addView(rbScreenPattern)
        container.addView(rgScreenType)

        // Screen PIN UI
        val screenPinLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val etScreenPin = EditText(this).apply {
            hint = "Screen PIN daalein (e.g. 1234)"
            background = createInputBoxDrawable()
            setPadding(28, 22, 28, 22)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8A99AD"))
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER
            if (currentScreenType == AutoUnlockManager.TYPE_PIN) setText(currentScreenVal)
        }
        screenPinLayout.addView(etScreenPin)

        val cbScreenAlpha = CheckBox(this).apply {
            text = "Alphanumeric Password (A-Z + Numbers)"
            setTextColor(secondaryText)
            textSize = 12f
            buttonTintList = cyanTint
        }
        cbScreenAlpha.setOnCheckedChangeListener { _, isChecked ->
            etScreenPin.inputType = if (isChecked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            etScreenPin.setSelection(etScreenPin.text.length)
        }
        if (currentScreenType == AutoUnlockManager.TYPE_PIN && currentScreenVal.any { !it.isDigit() }) {
            cbScreenAlpha.isChecked = true
        }
        screenPinLayout.addView(cbScreenAlpha)
        container.addView(screenPinLayout)

        // Screen Pattern UI (Interactive 3x3 Visual Pad)
        val screenPatternLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 12, 0, 12)
        }
        val tvScreenPatternStatus = TextView(this).apply {
            textSize = 12.5f
            setPadding(0, 0, 0, 8)
        }
        val screenPatternPad = PatternLockPadView(this)
        if (currentScreenType == AutoUnlockManager.TYPE_PATTERN) {
            val savedDots = AutoUnlockManager.parsePatternDots(currentScreenVal)
            screenPatternPad.setPattern(savedDots)
        }
        fun updateScreenPatternStatus(dots: List<Int>) {
            if (dots.size >= 2) {
                tvScreenPatternStatus.text = "✅ Screen Pattern: ${dots.joinToString(" → ")} (${dots.size} dots)"
                tvScreenPatternStatus.setTextColor(greenActive)
            } else {
                tvScreenPatternStatus.text = "Neeche 3×3 dots par ungli se pattern draw karein"
                tvScreenPatternStatus.setTextColor(gold)
            }
        }
        updateScreenPatternStatus(screenPatternPad.getPatternDots())
        screenPatternPad.onPatternChanged = { dots -> updateScreenPatternStatus(dots) }

        val btnClearScreenPattern = Button(this).apply {
            text = "Clear Pattern"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(cardBg)
            setOnClickListener { screenPatternPad.clearPattern() }
        }
        screenPatternLayout.addView(tvScreenPatternStatus)
        screenPatternLayout.addView(screenPatternPad)
        screenPatternLayout.addView(btnClearScreenPattern)
        container.addView(screenPatternLayout)

        fun refreshScreenVisibility() {
            screenPinLayout.visibility = if (rbScreenPin.isChecked) View.VISIBLE else View.GONE
            screenPatternLayout.visibility = if (rbScreenPattern.isChecked) View.VISIBLE else View.GONE
        }
        when (currentScreenType) {
            AutoUnlockManager.TYPE_PIN -> rbScreenPin.isChecked = true
            AutoUnlockManager.TYPE_PATTERN -> rbScreenPattern.isChecked = true
            else -> rbScreenNone.isChecked = true
        }
        refreshScreenVisibility()
        rgScreenType.setOnCheckedChangeListener { _, _ -> refreshScreenVisibility() }

        // ─── SECTION 2: APP LOCK (WHATSAPP, INSTA, NOTES) ──────────────
        val tvAppLabel = TextView(this).apply {
            text = "\n2. App Lock (WhatsApp, Insta, OnePlus Lock):"
            setTypeface(null, Typeface.BOLD)
            setTextColor(cyan)
            textSize = 14.5f
            setPadding(0, 8, 0, 6)
        }
        container.addView(tvAppLabel)

        val rgAppType = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val rbAppSame = RadioButton(this).apply {
            text = "Screen Lock wala hi use karein"
            id = View.generateViewId()
            setTextColor(Color.WHITE)
            buttonTintList = cyanTint
        }
        val rbAppPin = RadioButton(this).apply {
            text = "Alag App Lock PIN / Privacy Password"
            id = View.generateViewId()
            setTextColor(Color.WHITE)
            buttonTintList = cyanTint
        }
        val rbAppPattern = RadioButton(this).apply {
            text = "Alag App Lock Pattern"
            id = View.generateViewId()
            setTextColor(Color.WHITE)
            buttonTintList = cyanTint
        }
        val rbAppNone = RadioButton(this).apply {
            text = "No App Lock"
            id = View.generateViewId()
            setTextColor(Color.WHITE)
            buttonTintList = cyanTint
        }
        rgAppType.addView(rbAppSame)
        rgAppType.addView(rbAppPin)
        rgAppType.addView(rbAppPattern)
        rgAppType.addView(rbAppNone)
        container.addView(rgAppType)

        // App Lock PIN UI
        val appPinLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 12, 0, 12)
        }
        val etAppPin = EditText(this).apply {
            hint = "App Lock PIN / Privacy Password daalein"
            background = createInputBoxDrawable()
            setPadding(28, 22, 28, 22)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8A99AD"))
            textSize = 15f
            inputType = InputType.TYPE_CLASS_NUMBER
            if (currentAppType == AutoUnlockManager.TYPE_PIN) setText(currentAppVal)
        }
        appPinLayout.addView(etAppPin)

        val cbAppAlpha = CheckBox(this).apply {
            text = "Alphanumeric Privacy Password (A-Z + Numbers)"
            setTextColor(secondaryText)
            textSize = 12f
            buttonTintList = cyanTint
        }
        cbAppAlpha.setOnCheckedChangeListener { _, isChecked ->
            etAppPin.inputType = if (isChecked) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            } else {
                InputType.TYPE_CLASS_NUMBER
            }
            etAppPin.setSelection(etAppPin.text.length)
        }
        if (currentAppType == AutoUnlockManager.TYPE_PIN && currentAppVal.any { !it.isDigit() }) {
            cbAppAlpha.isChecked = true
        }
        appPinLayout.addView(cbAppAlpha)
        container.addView(appPinLayout)

        // App Lock Pattern UI (Separate Interactive 3x3 Visual Pad)
        val appPatternLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 12, 0, 12)
        }
        val tvAppPatternStatus = TextView(this).apply {
            textSize = 12.5f
            setPadding(0, 0, 0, 8)
        }
        val appPatternPad = PatternLockPadView(this)
        if (currentAppType == AutoUnlockManager.TYPE_PATTERN) {
            val savedAppDots = AutoUnlockManager.parsePatternDots(currentAppVal)
            appPatternPad.setPattern(savedAppDots)
        }
        fun updateAppPatternStatus(dots: List<Int>) {
            if (dots.size >= 2) {
                tvAppPatternStatus.text = "✅ App Lock Pattern: ${dots.joinToString(" → ")} (${dots.size} dots)"
                tvAppPatternStatus.setTextColor(greenActive)
            } else {
                tvAppPatternStatus.text = "Neeche 3×3 dots par App Lock pattern draw karein"
                tvAppPatternStatus.setTextColor(gold)
            }
        }
        updateAppPatternStatus(appPatternPad.getPatternDots())
        appPatternPad.onPatternChanged = { dots -> updateAppPatternStatus(dots) }

        val btnClearAppPattern = Button(this).apply {
            text = "Clear App Pattern"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(cardBg)
            setOnClickListener { appPatternPad.clearPattern() }
        }
        appPatternLayout.addView(tvAppPatternStatus)
        appPatternLayout.addView(appPatternPad)
        appPatternLayout.addView(btnClearAppPattern)
        container.addView(appPatternLayout)

        fun refreshAppVisibility() {
            appPinLayout.visibility = if (rbAppPin.isChecked) View.VISIBLE else View.GONE
            appPatternLayout.visibility = if (rbAppPattern.isChecked) View.VISIBLE else View.GONE
        }
        when (currentAppType) {
            AutoUnlockManager.TYPE_PIN -> rbAppPin.isChecked = true
            AutoUnlockManager.TYPE_PATTERN -> rbAppPattern.isChecked = true
            AutoUnlockManager.TYPE_NONE -> rbAppNone.isChecked = true
            else -> rbAppSame.isChecked = true
        }
        refreshAppVisibility()
        rgAppType.setOnCheckedChangeListener { _, _ -> refreshAppVisibility() }

        val dialog = AlertDialog.Builder(this)
            .setView(scrollView)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(darkBg))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(secondaryText)
            val saveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveBtn?.setTextColor(cyan)
            saveBtn?.setOnClickListener {
                val selectedScreenType = when {
                    rbScreenPin.isChecked -> AutoUnlockManager.TYPE_PIN
                    rbScreenPattern.isChecked -> AutoUnlockManager.TYPE_PATTERN
                    else -> AutoUnlockManager.TYPE_NONE
                }
                val screenVal = when (selectedScreenType) {
                    AutoUnlockManager.TYPE_PIN -> {
                        val pin = etScreenPin.text.toString().trim()
                        if (pin.isBlank()) {
                            Toast.makeText(this, "Kripya Screen PIN daalein!", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        pin
                    }
                    AutoUnlockManager.TYPE_PATTERN -> {
                        val dots = screenPatternPad.getPatternDots()
                        if (dots.size < 2) {
                            Toast.makeText(this, "Kripya Screen Pattern draw karein!", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        AutoUnlockManager.formatPatternDots(dots)
                    }
                    else -> ""
                }

                val selectedAppType = when {
                    rbAppPin.isChecked -> AutoUnlockManager.TYPE_PIN
                    rbAppPattern.isChecked -> AutoUnlockManager.TYPE_PATTERN
                    rbAppNone.isChecked -> AutoUnlockManager.TYPE_NONE
                    else -> AutoUnlockManager.TYPE_SAME_AS_SCREEN
                }
                val appVal = when (selectedAppType) {
                    AutoUnlockManager.TYPE_PIN -> {
                        val pin = etAppPin.text.toString().trim()
                        if (pin.isBlank()) {
                            Toast.makeText(this, "Kripya App Lock PIN daalein!", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        pin
                    }
                    AutoUnlockManager.TYPE_PATTERN -> {
                        val dots = appPatternPad.getPatternDots()
                        if (dots.size < 2) {
                            Toast.makeText(this, "Kripya App Lock Pattern draw karein!", Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        AutoUnlockManager.formatPatternDots(dots)
                    }
                    AutoUnlockManager.TYPE_SAME_AS_SCREEN -> screenVal
                    else -> ""
                }

                AutoUnlockManager.saveCredentials(this, selectedScreenType, screenVal, selectedAppType, appVal)
                Toast.makeText(this, "Lock setup safely save ho gaya hai Sir!", Toast.LENGTH_SHORT).show()
                appendLog("[Security]: Auto-Unlock configured (Screen=$selectedScreenType, App=$selectedAppType).")
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun updateVoiceLockButtonUi() {
        val enrolled = VoiceprintManager.isEnrolled(this)
        val active = VoiceprintManager.isLockActive(this)
        when {
            active -> {
                btnVoiceLock.text = "🛡️ MY VOICE LOCK: ON (${VoiceprintManager.getSensitivity(this).label} • Sirf Meri Awaaz)"
                btnVoiceLock.backgroundTintList = ContextCompat.getColorStateList(this, R.color.green_active)
                btnVoiceLock.setTextColor(ContextCompat.getColor(this, R.color.jarvis_dark_bg))
            }
            enrolled -> {
                btnVoiceLock.text = "⏸️ MY VOICE LOCK: PAUSED (Tap to Enable)"
                btnVoiceLock.backgroundTintList = ContextCompat.getColorStateList(this, R.color.jarvis_card_bg)
                btnVoiceLock.setTextColor(ContextCompat.getColor(this, R.color.jarvis_gold))
            }
            else -> {
                btnVoiceLock.text = "🎙️ MY VOICE LOCK: OFF (Tap to Train — Sirf Meri Awaaz)"
                btnVoiceLock.backgroundTintList = ContextCompat.getColorStateList(this, R.color.jarvis_card_bg)
                btnVoiceLock.setTextColor(ContextCompat.getColor(this, R.color.jarvis_cyan))
            }
        }
    }

    private fun showVoiceLockDialog() {
        val enrolled = VoiceprintManager.isEnrolled(this)
        val isEnabled = VoiceprintManager.isLockEnabledPref(this)
        val curSensitivity = VoiceprintManager.getSensitivity(this)

        val darkBg = Color.parseColor("#0A0E17")
        val cyan = Color.parseColor("#00E5FF")
        val secondary = Color.parseColor("#B0BEC5")
        val gold = Color.parseColor("#FFD700")
        val cyanTint = ColorStateList.valueOf(cyan)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(darkBg)
            setPadding(48, 36, 48, 24)
        }

        layout.addView(TextView(this).apply {
            text = "🎙️ Personal Voice Lock (Sirf Meri Awaaz)"
            setTypeface(null, Typeface.BOLD)
            setTextColor(cyan)
            textSize = 17f
        })

        layout.addView(TextView(this).apply {
            text = "Status: ${VoiceprintManager.getStatusSummary(this@MainActivity)}\n" +
                "Voice Lock ON hone par Jarvis offline aapki voiceprint match karega — Hotstar, Reels, aur doosre logon ki aawaz ignore hogi."
            setTextColor(secondary)
            textSize = 12.5f
            setPadding(0, 12, 0, 20)
        })

        val rbGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
        }
        val rbRelaxed = RadioButton(this).apply {
            text = "Relaxed (Chhote commands 'agli chalaao' easily chalenge)"
            setTextColor(Color.WHITE)
            buttonTintList = cyanTint
            id = View.generateViewId()
        }
        val rbBalanced = RadioButton(this).apply {
            text = "Balanced (Recommended — Best accuracy)"
            setTextColor(Color.WHITE)
            buttonTintList = cyanTint
            id = View.generateViewId()
        }
        val rbStrict = RadioButton(this).apply {
            text = "Strict (Maximum security — milti-julti aawaz bhi block)"
            setTextColor(Color.WHITE)
            buttonTintList = cyanTint
            id = View.generateViewId()
        }
        rbGroup.addView(rbRelaxed)
        rbGroup.addView(rbBalanced)
        rbGroup.addView(rbStrict)
        when (curSensitivity) {
            VoiceprintManager.Sensitivity.RELAXED -> rbRelaxed.isChecked = true
            VoiceprintManager.Sensitivity.BALANCED -> rbBalanced.isChecked = true
            VoiceprintManager.Sensitivity.STRICT -> rbStrict.isChecked = true
        }
        layout.addView(rbGroup)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }

        val dialog = AlertDialog.Builder(this)
            .setView(layout)
            .create()

        val btnTrain = Button(this).apply {
            text = if (enrolled) "🎙️ Re-Train (10s)" else "🎙️ Train My Voice"
            setBackgroundColor(Color.parseColor("#00E5FF"))
            setTextColor(darkBg)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = 10
            }
            setOnClickListener {
                dialog.dismiss()
                showVoiceEnrollmentWizard(startMicAfter = false)
            }
        }
        btnRow.addView(btnTrain)

        if (enrolled) {
            val btnToggle = Button(this).apply {
                text = if (isEnabled) "Pause Lock" else "Enable Lock"
                setBackgroundColor(gold)
                setTextColor(darkBg)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = 10
                }
                setOnClickListener {
                    VoiceprintManager.setLockEnabled(this@MainActivity, !isEnabled)
                    updateVoiceLockButtonUi()
                    dialog.dismiss()
                    Toast.makeText(
                        this@MainActivity,
                        if (!isEnabled) "Voice Lock ON (Sirf aapki aawaz)" else "Voice Lock paused",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            btnRow.addView(btnToggle)
        }

        val btnSaveClose = Button(this).apply {
            text = "Save"
            setBackgroundColor(Color.parseColor("#141D2B"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.8f)
            setOnClickListener {
                val chosen = when (rbGroup.checkedRadioButtonId) {
                    rbRelaxed.id -> VoiceprintManager.Sensitivity.RELAXED
                    rbStrict.id -> VoiceprintManager.Sensitivity.STRICT
                    else -> VoiceprintManager.Sensitivity.BALANCED
                }
                VoiceprintManager.setSensitivity(this@MainActivity, chosen)
                updateVoiceLockButtonUi()
                dialog.dismiss()
            }
        }
        btnRow.addView(btnSaveClose)
        layout.addView(btnRow)
        dialog.show()
    }

    private fun showVoiceSetupPromptDialog(startMicAfter: Boolean, onLaunch: Boolean) {
        val builder = AlertDialog.Builder(this)
            .setTitle("🎙️ Personal Voice Lock & Secret Code")
            .setMessage(
                "Sirf aapki hi aawaz par Jarvis chalane ke liye:\n" +
                "1. Apna ek Secret Code type karein.\n" +
                "2. Normal, Fast, Dheemi, Tez aawaz + Secret Code bolkar 5 quick samples save karein.\n\n" +
                "Iske baad kisi aur ki aawaz par Jarvis block karega, aur doubt hone par pehle Secret Code poochega!"
            )
            .setPositiveButton("🚀 Abhi Setup Karein") { _, _ ->
                showVoiceEnrollmentWizard(startMicAfter = startMicAfter)
            }
        if (onLaunch) {
            builder.setNegativeButton("Baad Me", null)
        } else {
            builder.setNeutralButton("Skip Karein") { _, _ -> activatePermanentVoice() }
            builder.setNegativeButton("Cancel", null)
        }
        builder.show()
    }

    private fun showVoiceEnrollmentWizard(startMicAfter: Boolean) {
        val hasMicPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMicPerm) {
            requestAppPermissions()
            Toast.makeText(this, "Pehle Mic permission allow karein Sir", Toast.LENGTH_SHORT).show()
            return
        }

        val styleTitles = listOf(
            "Sample 1/5 — Normal Voice (Samanya Gati):",
            "Sample 2/5 — Fast Speed (Tez Speed Me Bolein):",
            "Sample 3/5 — Low / Soft Voice (Dheemi Aawaz Me):",
            "Sample 4/5 — Loud / Clear Voice (Tez / Unchi Aawaz Me):",
            "Sample 5/5 — Speak Your Secret Code (Match Test):"
        )
        val stylePrompts = mutableListOf(
            "\"Jarvis aaj ka mausam kaisa hai aur abhi kitne baje hain\"",
            "\"Phone ki brightness aur volume thoda kam kar do\"",
            "\"YouTube ya Hotstar par koi video dhire se chalao\"",
            "\"Mera naam hamesha yaad rakhna sirf meri hi aawaz sunna\"",
            ""
        )

        val darkBg = Color.parseColor("#0A0E17")
        val cardBg = Color.parseColor("#141D2B")
        val cyan = Color.parseColor("#00E5FF")
        val gold = Color.parseColor("#FFD700")
        val green = Color.parseColor("#00E676")
        val secondary = Color.parseColor("#B0BEC5")

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(darkBg)
            setPadding(48, 36, 48, 32)
        }

        // ── STEP 0: Type Secret Code UI ──
        val secretStepLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        secretStepLayout.addView(TextView(this).apply {
            text = "🔐 Step 1: Apna Secret Code Type Karein"
            setTypeface(null, Typeface.BOLD)
            setTextColor(gold)
            textSize = 16f
        })
        secretStepLayout.addView(TextView(this).apply {
            text = "Koi bhi aasaan 1-3 word secret code type karein (jaise 'Alpha Nine' ya 'Mera Raaz 7').\n\n" +
                "Setup poora karne ke liye Sample 5 me yahi code bolkar match karna hoga, aur future me jab aawaz par doubt hoga tab Jarvis yahi code poochega."
            setTextColor(secondary)
            textSize = 12.5f
            setPadding(0, 12, 0, 18)
        })
        val etSecretCode = EditText(this).apply {
            hint = "Yahan secret code type karein..."
            setHintTextColor(Color.parseColor("#78909C"))
            setTextColor(Color.WHITE)
            setBackgroundColor(cardBg)
            setPadding(28, 24, 28, 24)
            setText(VoiceprintManager.getSecretCode(this@MainActivity))
            isSingleLine = true
        }
        secretStepLayout.addView(etSecretCode)
        val btnStartSamples = Button(this).apply {
            text = "Next: 5 Voice Samples Start Karein →"
            setBackgroundColor(cyan)
            setTextColor(darkBg)
            isAllCaps = false
        }
        secretStepLayout.addView(btnStartSamples, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 20 })

        // ── STEP 1..5: Voice Sample Recording UI ──
        val voiceStepLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val tvStep = TextView(this).apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(gold)
            textSize = 14f
        }
        val tvPhrase = TextView(this).apply {
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            textSize = 16.5f
            setBackgroundColor(cardBg)
            setPadding(28, 28, 28, 28)
        }
        val tvStatus = TextView(this).apply {
            setTextColor(cyan)
            textSize = 13f
            setPadding(0, 18, 0, 16)
        }
        val btnAction = Button(this).apply {
            setBackgroundColor(cyan)
            setTextColor(darkBg)
            isAllCaps = false
        }
        voiceStepLayout.addView(tvStep)
        voiceStepLayout.addView(tvPhrase, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })
        voiceStepLayout.addView(tvStatus)
        voiceStepLayout.addView(btnAction)

        val btnCancel = Button(this).apply {
            text = "Cancel"
            setBackgroundColor(cardBg)
            setTextColor(Color.WHITE)
            isAllCaps = false
        }

        rootLayout.addView(secretStepLayout)
        rootLayout.addView(voiceStepLayout)
        rootLayout.addView(btnCancel, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 12 })

        val samples = mutableListOf<VoiceprintManager.EnrollmentSample>()
        val cancelled = AtomicBoolean(false)
        var stepIdx = 0
        var typedSecretCode = ""

        AlwaysOnVoiceService.pauseForEnrollment(this)

        val dialog = AlertDialog.Builder(this)
            .setCancelable(false)
            .setView(rootLayout)
            .create()

        dialog.setOnDismissListener {
            cancelled.set(true)
            AlwaysOnVoiceService.resumeAfterEnrollment(this)
        }

        btnCancel.setOnClickListener {
            cancelled.set(true)
            dialog.dismiss()
        }

        fun updateStepUi(justSavedCount: Int = 0) {
            tvStep.text = styleTitles[stepIdx]
            tvPhrase.text = stylePrompts[stepIdx]
            if (justSavedCount > 0) {
                tvStatus.setTextColor(green)
                tvStatus.text = if (stepIdx == 4) {
                    "4 styles save ho gaye! Ab apna typed Secret Code bolkar verify karein."
                } else {
                    "Sample $justSavedCount save! Ab bataye hue style me agla sentence bolein."
                }
            } else {
                tvStatus.setTextColor(cyan)
                tvStatus.text = if (stepIdx == 0 && !PassiveVoiceCaptureEngine.areModelsDownloaded(this)) {
                    "First time offline Voice Lock model download hoga (~49 MB), phir Sample 1 record hoga."
                } else if (stepIdx == 4) {
                    "Button dabakar apna Secret Code saaf aawaz me bolein."
                } else {
                    "Button dabakar sentence bataye hue style me padhein (2-3 sec)."
                }
            }
            btnAction.text = if (stepIdx == 4) "🔐 Speak & Match Secret Code" else "🎙️ Record Sample ${stepIdx + 1}"
            btnAction.isEnabled = true
        }

        fun finishAndSaveAll() {
            val saved = VoiceprintManager.saveEnrollment(this@MainActivity, samples, typedSecretCode)
            updateVoiceLockButtonUi()
            dialog.dismiss()
            if (saved) {
                Toast.makeText(
                    this@MainActivity,
                    "🛡️ Voice Lock + Secret Code Active! Ab Jarvis sirf aapki aawaz sunega.",
                    Toast.LENGTH_LONG
                ).show()
                JarvisLogStore.log("VOICE_LOCK", "Owner enrolled ${samples.size} multi-style samples + Secret Code.")
                if (startMicAfter) {
                    activatePermanentVoice()
                } else {
                    speakerHelper.speak("Sabhi 5 voice styles aur secret code verify ho gaye hain Sir. Ab main sirf aapki aawaz sununga.")
                }
            }
        }

        fun startCurrentCapture() {
            btnAction.isEnabled = false
            btnAction.text = "Sun raha hoon..."
            tvStatus.setTextColor(cyan)
            tvStatus.text = "Taiyaar ho raha hai..."

            val isSecretStep = (stepIdx == 4)
            CoroutineScope(Dispatchers.IO).launch {
                val res = PassiveVoiceCaptureEngine.recordEnrollmentPhrase(
                    context = this@MainActivity,
                    isCancelled = { cancelled.get() || isFinishing || isDestroyed },
                    onProgress = { status, levelPct ->
                        runOnUiThread {
                            if (!cancelled.get()) {
                                tvStatus.text = if (levelPct > 0) "$status (Mic level: $levelPct%)" else status
                            }
                        }
                    },
                    minSpkFrames = if (isSecretStep) 45 else 75,
                    minSpokenMs = if (isSecretStep) 900L else 1400L
                )
                withContext(Dispatchers.Main) {
                    if (cancelled.get() || isFinishing || isDestroyed) return@withContext
                    res.onSuccess { recording ->
                        if (!isSecretStep) {
                            samples.add(recording.sample)
                            stepIdx++
                            updateStepUi(justSavedCount = stepIdx)
                        } else {
                            // Verify typed Secret Code == spoken Secret Code AND voice consistency against Samples 1..4
                            tvStatus.setTextColor(cyan)
                            tvStatus.text = "Typed code aur voice match check ho raha hai..."
                            commandExecutor.verifySecretCodeAudio(
                                wavBytes = recording.wavBytes,
                                expectedSecretCode = typedSecretCode,
                                voskHint = recording.voskText,
                                offlineFallbackMatch = VoiceprintManager::matchesSecretCodeTextOffline
                            ) { checkRes ->
                                if (cancelled.get() || isFinishing || isDestroyed) return@verifySecretCodeAudio
                                if (!checkRes.matched) {
                                    tvStatus.setTextColor(gold)
                                    tvStatus.text = "Bola gaya code ('${checkRes.heardText}') typed Secret Code ('$typedSecretCode') se match nahi hua! Dobara bolein."
                                    btnAction.text = "🔄 Retry Secret Code"
                                    btnAction.isEnabled = true
                                    return@verifySecretCodeAudio
                                }

                                // Ensure Sample 5 speaker vector came from the same voice as Samples 1..4
                                val spk5 = recording.sample.spkVector
                                val avgPrevSim = samples.map {
                                    VoiceprintManager.cosineSimilarity(spk5, it.spkVector)
                                }.maxOrNull() ?: 1.0f
                                if (samples.isNotEmpty() && avgPrevSim < 0.35f) {
                                    tvStatus.setTextColor(gold)
                                    tvStatus.text = "Secret code ki aawaz pehle samples se alag lag rahi hai! Khud apni aawaz me bolein."
                                    btnAction.text = "🔄 Retry Secret Code"
                                    btnAction.isEnabled = true
                                    return@verifySecretCodeAudio
                                }

                                samples.add(recording.sample)
                                finishAndSaveAll()
                            }
                        }
                    }.onFailure { err ->
                        tvStatus.setTextColor(gold)
                        tvStatus.text = "Dobara try karein: ${err.message}"
                        btnAction.text = if (isSecretStep) "🔄 Retry Secret Code" else "🔄 Retry Sample ${stepIdx + 1}"
                        btnAction.isEnabled = true
                    }
                }
            }
        }

        btnStartSamples.setOnClickListener {
            val entered = etSecretCode.text.toString().trim()
            if (entered.length < 4) {
                Toast.makeText(this, "Kam se kam 4 aksharon ka Secret Code type karein Sir.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            typedSecretCode = entered
            stylePrompts[4] = "Apna Secret Code abhi bolein:\n\"$typedSecretCode\""
            secretStepLayout.visibility = View.GONE
            voiceStepLayout.visibility = View.VISIBLE
            updateStepUi()
        }

        btnAction.setOnClickListener { startCurrentCapture() }
        dialog.show()
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

    fun executeToolFromScheduler(action: String, args: Map<String, Any>, summary: String = "") {
        commandExecutor.executeFromScheduler(action, args, summary)
    }

    private fun appendLog(message: String) {
        val time = timeFormat.format(Date())
        val logLine = "[$time] $message"
        val category = when {
            message.startsWith("User") -> "USER_UI"
            message.startsWith("Jarvis:") -> "JARVIS_SPEAK"
            message.startsWith("Executing Tool:") -> "TOOL_DISPATCH"
            else -> "CORE"
        }
        JarvisLogStore.log(category, message)
        sendRemoteLog(logLine)
    }

    private fun refreshLogsView() {
        runOnUiThread {
            if (!::tvLogs.isInitialized || !::scrollLogs.isInitialized) return@runOnUiThread
            val displayText = JarvisLogStore.getDisplayLogText()
            tvLogs.text = if (displayText.isNotEmpty()) {
                displayText
            } else {
                "[System]: J.A.R.V.I.S. Core initialized.\n[System]: Ready for 24/7 voice or text commands."
            }
            scrollLogs.post {
                scrollLogs.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
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
            } catch (_: Exception) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        JarvisLogStore.setListener(null)
        AlwaysOnVoiceService.onStatusChanged = null
        if (instance == this) instance = null
        speakerHelper.shutdown()
    }
}
