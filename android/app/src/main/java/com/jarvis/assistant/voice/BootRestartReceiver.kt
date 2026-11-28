package com.jarvis.assistant.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.debug.JarvisLogStore

/**
 * Restores Always-On 24/7 voice listening automatically after device reboot,
 * user unlock, app OTA update (MY_PACKAGE_REPLACED), or OEM task-kill watchdog alarm.
 */
class BootRestartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!AlwaysOnVoiceService.isEnabled(context)) return
        JarvisLogStore.init(context)
        if (!AlwaysOnVoiceService.isRunning) {
            JarvisLogStore.log("BOOT_RECEIVER", "Received '$action'; restoring permanent mic.")
        }
        AlwaysOnVoiceService.ensureRunningIfEnabled(context, action)
    }
}
