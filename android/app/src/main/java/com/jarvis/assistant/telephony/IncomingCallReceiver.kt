package com.jarvis.assistant.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telephony.TelephonyManager
import android.util.Log
import com.jarvis.assistant.engine.SystemController
import com.jarvis.assistant.engine.TelephonyHelper
import com.jarvis.assistant.memory.JarvisMemoryManager
import com.jarvis.assistant.voice.SpeakerHelper

class IncomingCallReceiver : BroadcastReceiver() {

    companion object {
        private var lastState = TelephonyManager.EXTRA_STATE_IDLE
        private var isRinging = false
        private var savedCallerNumber: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: savedCallerNumber

        val memoryManager = JarvisMemoryManager(context)
        val telephonyHelper = TelephonyHelper(context)
        val speakerHelper = SpeakerHelper(context)
        val systemController = SystemController(context)
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                isRinging = true
                savedCallerNumber = incomingNumber
                val targetNumber = incomingNumber ?: ""

                val (resolvedNumber, resolvedName) = if (targetNumber.isNotBlank()) {
                    telephonyHelper.resolveContactNumber(targetNumber)
                } else Pair(null, "Unknown")

                val displayName = resolvedName.ifBlank { targetNumber }

                // Check if incoming caller is a VIP
                if (memoryManager.isVip(displayName) || memoryManager.isVip(targetNumber)) {
                    Log.d("IncomingCallReceiver", "VIP Call detected from: $displayName! Bypassing Silent & DND...")

                    // 1. Disable DND
                    try {
                        systemController.setDnd(false)
                    } catch (e: Exception) {}

                    // 2. Unmute / override Silent mode to Normal Ringing
                    try {
                        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                        }
                        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                        audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVol, AudioManager.FLAG_SHOW_UI)
                    } catch (e: Exception) {}

                    // 3. Butler voice announcement
                    speakerHelper.speak("Sir, $displayName ka zaroori VIP call aa raha hai.")
                }
            }

            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                isRinging = false
            }

            TelephonyManager.EXTRA_STATE_IDLE -> {
                // If the phone was ringing and went straight to IDLE, Sir missed the call
                if (isRinging && !savedCallerNumber.isNullOrBlank()) {
                    val missedNum = savedCallerNumber ?: ""
                    val (_, resolvedName) = telephonyHelper.resolveContactNumber(missedNum)
                    val displayName = resolvedName.ifBlank { missedNum }

                    if (memoryManager.isVip(displayName) || memoryManager.isVip(missedNum)) {
                        Log.d("IncomingCallReceiver", "Missed VIP call recorded: $displayName")
                        memoryManager.recordMissedVipCall(displayName)
                        speakerHelper.speak("Sir, $displayName ka zaroori call miss ho gaya hai.")
                    }
                }
                isRinging = false
                savedCallerNumber = null
            }
        }

        lastState = state
    }
}
