package com.jarvis.assistant.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.engine.NodeActionEngine
import com.jarvis.assistant.engine.SystemController
import com.jarvis.assistant.engine.TelephonyHelper
import com.jarvis.assistant.engine.UniversalAppAgent
import com.jarvis.assistant.security.AutoUnlockManager
import com.jarvis.assistant.voice.SpeakerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getStringExtra("task_id") ?: return
        val action = intent.getStringExtra("action") ?: return
        val argsJson = intent.getStringExtra("action_args_json") ?: "{}"
        val summary = intent.getStringExtra("summary") ?: ""

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis:ScheduledTaskWakeLock")
        wakeLock.acquire(10_000L) // 10s max lock

        com.jarvis.assistant.debug.JarvisLogStore.init(context)
        com.jarvis.assistant.debug.JarvisLogStore.log("SCHEDULER_ALARM", "Executing scheduled task '$action' (ID: $taskId)...")
        Log.d("TaskAlarmReceiver", "Executing scheduled task '$action' (ID: $taskId)...")

        val gson = Gson()
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val args: Map<String, Any> = try {
            gson.fromJson(argsJson, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        // Clean up task from persistent queue
        val taskManager = ScheduledTaskManager(context)
        taskManager.removeTask(taskId)

        // Dispatch execution
        val activeActivity = MainActivity.instance
        if (activeActivity != null && !activeActivity.isDestroyed) {
            activeActivity.runOnUiThread {
                activeActivity.executeToolFromScheduler(action, args, summary)
            }
        } else {
            // Background headless execution
            val systemController = SystemController(context)
            val telephonyHelper = TelephonyHelper(context)
            val nodeActionEngine = NodeActionEngine(systemController, telephonyHelper)
            val speakerHelper = SpeakerHelper(context)

            CoroutineScope(Dispatchers.Main).launch {
                if (action in listOf("make_phone_call", "play_youtube", "open_app")) {
                    AutoUnlockManager.ensureScreenUnlocked(context)
                }
                when (action) {
                    "make_phone_call" -> {
                        val target = (args["recipient"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: (args["phone_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                            ?: ""
                        val (resolved, _) = telephonyHelper.resolveContactNumber(target)
                        val num = resolved ?: target
                        val res = systemController.makePhoneCall(num)
                        speakerHelper.speak("Sir, aapka scheduled task: $res")
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
                    "play_youtube" -> {
                        val q = (args["query"] as? String) ?: "trending songs"
                        systemController.playYoutube(q)
                        delay(1000)
                        AutoUnlockManager.unlockAppLockIfPresent(context)
                    }
                    "open_app" -> {
                        val app = (args["app_name"] as? String) ?: ""
                        systemController.openApp(app)
                        delay(1000)
                        AutoUnlockManager.unlockAppLockIfPresent(context)
                    }
                    "set_dnd" -> {
                        val enabled = (args["enabled"] as? Boolean) ?: true
                        systemController.setDnd(enabled)
                    }
                    "instagram_send_message",
                    "instagram_read_last_message",
                    "instagram_watch_story",
                    "instagram_follow_account",
                    "instagram_play_chat_reels",
                    "instagram_play_reels" -> {
                        UniversalAppAgent(context, systemController, nodeActionEngine)
                            .performInstagramTool(action, args, null) { _, reply ->
                                if (reply.isNotBlank()) speakerHelper.speak(reply)
                            }
                    }
                    else -> {
                        Log.d("TaskAlarmReceiver", "Background task executed: $action")
                    }
                }
            }
        }
    }
}
