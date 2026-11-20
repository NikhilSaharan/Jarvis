package com.jarvis.assistant

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var tvDndStatus: TextView
    private lateinit var tvOverlayStatus: TextView

    private lateinit var btnAccessibility: Button
    private lateinit var btnDnd: Button
    private lateinit var btnOverlay: Button
    private lateinit var btnTalk: Button
    private lateinit var etApiKey: android.widget.EditText

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        updateStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        requestAppPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        tvDndStatus = findViewById(R.id.tvDndStatus)
        tvOverlayStatus = findViewById(R.id.tvOverlayStatus)

        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnDnd = findViewById(R.id.btnDnd)
        btnOverlay = findViewById(R.id.btnOverlay)
        btnTalk = findViewById(R.id.btnTalk)
        etApiKey = findViewById(R.id.etApiKey)

        val prefs = getSharedPreferences("jarvis_config", Context.MODE_PRIVATE)
        val savedKey = prefs.getString("gemini_api_key", "")
        if (!savedKey.isNullOrBlank()) {
            etApiKey.setText(savedKey)
        }
    }

    private fun setupListeners() {
        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Enable 'J.A.R.V.I.S.' in Installed Services", Toast.LENGTH_LONG).show()
        }

        btnDnd.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                startActivity(intent)
            }
        }

        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                }
            }
        }

        btnTalk.setOnClickListener {
            val keyInput = etApiKey.text.toString().trim()
            if (keyInput.isNotEmpty()) {
                val prefs = getSharedPreferences("jarvis_config", Context.MODE_PRIVATE)
                prefs.edit().putString("gemini_api_key", keyInput).apply()
            }

            // Start the floating bubble service
            val serviceIntent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Floating Arc Reactor active on screen!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        val isAccRunning = JarvisAccessibilityService.isServiceRunning
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val isDndGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            nm.isNotificationPolicyAccessGranted
        } else true

        val isOverlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true

        tvAccessibilityStatus.text = if (isAccRunning) "• Accessibility Service: ACTIVE (0ms in-memory)" else "• Accessibility Service: INACTIVE"
        tvAccessibilityStatus.setTextColor(if (isAccRunning) ContextCompat.getColor(this, R.color.green_active) else ContextCompat.getColor(this, R.color.text_secondary))

        tvDndStatus.text = if (isDndGranted) "• Do Not Disturb Policy: ACTIVE" else "• Do Not Disturb Policy: INACTIVE"
        tvDndStatus.setTextColor(if (isDndGranted) ContextCompat.getColor(this, R.color.green_active) else ContextCompat.getColor(this, R.color.text_secondary))

        tvOverlayStatus.text = if (isOverlayGranted) "• Floating Arc-Reactor: READY" else "• Floating Arc-Reactor: INACTIVE"
        tvOverlayStatus.setTextColor(if (isOverlayGranted) ContextCompat.getColor(this, R.color.green_active) else ContextCompat.getColor(this, R.color.text_secondary))

        if (isAccRunning && isDndGranted && isOverlayGranted) {
            tvStatus.text = "Status: ALL SYSTEMS ONLINE"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.jarvis_cyan))
            // Auto start floating bubble
            val serviceIntent = Intent(this, FloatingBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            tvStatus.text = "Status: Permissions Required"
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.white))
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
}
