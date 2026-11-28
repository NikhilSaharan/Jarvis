package com.jarvis.assistant.engine

import android.content.Context
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.JarvisAccessibilityService
import com.jarvis.assistant.brain.GeminiBrain
import com.jarvis.assistant.debug.JarvisLogStore
import com.jarvis.assistant.memory.JarvisMemoryManager
import com.jarvis.assistant.scheduler.ScheduledTaskManager
import com.jarvis.assistant.security.AutoUnlockManager
import com.jarvis.assistant.voice.SpeakerHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shared command execution engine used by MainActivity, AlwaysOnVoiceService (24/7 background mic),
 * and Scheduled tasks. Ensures identical Gemini routing, screen auto-unlock, tool dispatch,
 * TTS feedback, and complete persistent disk tracing whether the UI is open or killed.
 */
class JarvisCommandExecutor(
    context: Context,
    private val speakerHelper: SpeakerHelper,
    private val memoryManager: JarvisMemoryManager = JarvisMemoryManager(context.applicationContext)
) {

    private val appContext = context.applicationContext
    private val systemController = SystemController(appContext)
    private val telephonyHelper = TelephonyHelper(appContext)
    private val nodeActionEngine = NodeActionEngine(systemController, telephonyHelper)
    private val universalAppAgent = UniversalAppAgent(appContext, systemController, nodeActionEngine)
    private val scheduledTaskManager = ScheduledTaskManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    var geminiBrain: GeminiBrain = createBrain()
        private set

    private fun createBrain(): GeminiBrain {
        val defaultKey = BuildConfig.GEMINI_API_KEY
        val prefs = appContext.getSharedPreferences("jarvis_config", Context.MODE_PRIVATE)
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
        return GeminiBrain(apiKey, memoryManager)
    }

    fun reloadApiKey(customKey: String? = null) {
        val prefs = appContext.getSharedPreferences("jarvis_config", Context.MODE_PRIVATE)
        if (customKey != null) {
            prefs.edit().putString("gemini_api_key", customKey.trim()).apply()
        }
        geminiBrain = createBrain()
    }

    fun executeUserCommand(
        userText: String,
        sourceTag: String = "USER_UI",
        onLogLine: ((String) -> Unit)? = null,
        onFinished: (() -> Unit)? = null
    ) {
        val clean = userText.trim()
        if (clean.isEmpty()) {
            onFinished?.invoke()
            return
        }

        JarvisLogStore.log(sourceTag, "\"$clean\"")
        onLogLine?.invoke(clean)

        scope.launch {
            try {
                JarvisLogStore.log("CORE", "Brain: Analyzing with Gemini AI...")
                val screenContext = JarvisAccessibilityService.instance?.dumpScreenElementsSummary() ?: ""
                val decision = geminiBrain.processCommand(clean, screenContext)

                val tool = decision.toolName?.takeIf { it.isNotBlank() && it != "null" }
                val args = decision.toolArgs ?: emptyMap()
                val speech = decision.speech?.takeIf { it.isNotBlank() && it != "null" }
                val alreadySpoken = speech != null

                if (speech != null) {
                    JarvisLogStore.log("JARVIS_SPEAK", "Jarvis: \"$speech\"")
                    speakerHelper.speak(speech)
                }

                if (tool == null) {
                    JarvisLogStore.log("CORE", "[Result]: Done (Conversational)")
                    return@launch
                }

                executeSingleTool(tool, args, alreadySpoken)
            } catch (e: Exception) {
                JarvisLogStore.log("EXEC_ERROR", "Command failed cleanly: ${e.message}")
            } finally {
                onFinished?.invoke()
            }
        }
    }

    fun executeFromScheduler(action: String, args: Map<String, Any>, summary: String = "") {
        JarvisLogStore.log("SCHEDULER", "Executing scheduled task '$action' ($summary)")
        if (summary.isNotBlank()) {
            speakerHelper.speak("Sir, aapka scheduled task: $summary")
        }
        executeSingleTool(action, args, alreadySpoken = false)
    }

    fun executeSingleTool(tool: String, args: Map<String, Any>, alreadySpoken: Boolean = false) {
        JarvisLogStore.log("TOOL_DISPATCH", "Executing Tool: $tool($args)...")

        when (tool) {
            "schedule_action" -> {
                val delaySec = (args["delay_seconds"] as? Number)?.toInt() ?: 10
                val subAction = (args["action"] as? String) ?: (args["tool_name"] as? String) ?: ""
                @Suppress("UNCHECKED_CAST")
                val subArgs = (args["action_args"] as? Map<String, Any>) ?: (args["tool_args"] as? Map<String, Any>) ?: emptyMap()
                val summary = (args["summary"] as? String) ?: "Scheduled $subAction"
                val taskId = scheduledTaskManager.scheduleTask(delaySec, subAction, subArgs, summary)
                JarvisLogStore.log("SCHEDULER", "Task '$subAction' saved in AlarmManager for ${delaySec}s (ID: $taskId)")
                speakerHelper.speak("Sir, task schedule kar diya hai. Dusra kaam karne par bhi yeh apne samay par execute hoga.")
            }
            "manage_vip" -> {
                val action = (args["action"] as? String)?.lowercase() ?: "add"
                val contact = (args["contact"] as? String) ?: (args["name"] as? String) ?: ""
                if (action == "add" && contact.isNotBlank()) {
                    memoryManager.addVipContact(contact)
                    val reply = "Sir, $contact ko VIP priority list me add kar diya hai. Silent aur DND mode me bhi inka call bajega."
                    JarvisLogStore.log("VIP", reply)
                    speakerHelper.speak(reply)
                } else if (action == "remove" && contact.isNotBlank()) {
                    memoryManager.removeVipContact(contact)
                    val reply = "Sir, $contact ko VIP priority list se hata diya gaya hai."
                    JarvisLogStore.log("VIP", reply)
                    speakerHelper.speak(reply)
                } else {
                    val vips = memoryManager.getVipContacts()
                    val reply = if (vips.isEmpty()) "Sir, abhi koi VIP contact set nahi hai." else "Sir, aapke VIP contacts hain: ${vips.joinToString(", ")}."
                    JarvisLogStore.log("VIP", reply)
                    speakerHelper.speak(reply)
                }
            }
            "learn_preference" -> {
                val pref = (args["preference"] as? String) ?: ""
                if (pref.isNotBlank()) {
                    memoryManager.learnPreference(pref)
                    val reply = "Sir, maine aapki yeh pasand note kar li hai."
                    JarvisLogStore.log("MEMORY", reply)
                    speakerHelper.speak(reply)
                }
            }
            "universal_app_action" -> {
                val appName = (args["app_name"] as? String) ?: "Notes"
                val instruction = (args["instruction"] as? String) ?: (args["text"] as? String) ?: (args["action"] as? String) ?: ""
                JarvisLogStore.log("CORE", "[App Agent]: Opening $appName for AI execution...")
                universalAppAgent.performAppAction(appName, instruction, geminiBrain) { _, reply ->
                    JarvisLogStore.log("CORE", "[App Agent]: $reply")
                    if (!alreadySpoken && reply.isNotBlank()) {
                        speakerHelper.speak(reply)
                    }
                }
            }
            "sleep_routine" -> {
                val mins = (args["minutes"] as? Number)?.toInt() ?: 10
                val reply = systemController.sleepAndWakeUp(mins) { wakeupMsg ->
                    JarvisLogStore.log("SLEEP_WAKE", wakeupMsg)
                    speakerHelper.speak(wakeupMsg)
                }
                JarvisLogStore.log("SLEEP_BUTLER", reply)
                speakerHelper.speak(reply)
            }
            "set_dnd" -> {
                val enabled = (args["enabled"] as? Boolean) ?: true
                val reply = systemController.setDnd(enabled)
                JarvisLogStore.log("CORE", "[Result]: $reply")
                speakerHelper.speak(reply)
            }
            "set_brightness" -> {
                val level = (args["level"] as? String) ?: "medium"
                val reply = systemController.setBrightness(level)
                JarvisLogStore.log("CORE", "[Result]: $reply")
                speakerHelper.speak(reply)
            }
            "set_alarm" -> {
                val h = (args["hour"] as? Number)?.toInt() ?: 7
                val m = (args["minute"] as? Number)?.toInt() ?: 0
                val msg = (args["message"] as? String) ?: "Alarm"
                val reply = systemController.setAlarm(h, m, msg)
                JarvisLogStore.log("CORE", "[Result]: $reply")
                speakerHelper.speak(reply)
            }
            "set_timer" -> {
                val s = (args["seconds"] as? Number)?.toInt() ?: 60
                val msg = (args["message"] as? String) ?: "Timer"
                val reply = systemController.setTimer(s, msg)
                JarvisLogStore.log("CORE", "[Result]: $reply")
                speakerHelper.speak(reply)
            }
            "answer_call" -> {
                val reply = systemController.answerCall()
                JarvisLogStore.log("CALL", reply)
                if (!alreadySpoken) speakerHelper.speak(reply)
            }
            "end_call" -> {
                val reply = systemController.endCall()
                JarvisLogStore.log("CALL", reply)
                if (!alreadySpoken) speakerHelper.speak(reply)
            }
            "close_app" -> {
                val appName = (args["app_name"] as? String) ?: (args["name"] as? String) ?: ""
                val reply = systemController.closeApp(appName)
                JarvisLogStore.log("APP_CONTROL", reply)
                if (!alreadySpoken) speakerHelper.speak(reply)
            }
            "make_phone_call" -> {
                runAfterScreenUnlock {
                    val target = ((args["recipient"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: (args["phone_number"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                        ?: "").trim()
                    val (resolvedNumber, resolvedName) = telephonyHelper.resolveContactNumber(target)
                    if (resolvedNumber == null && target.replace(Regex("[^0-9+]"), "").length < 7) {
                        val reply = "Sir, '$target' ka phone number contacts me nahi mila."
                        JarvisLogStore.log("CALL", reply)
                        speakerHelper.speak(reply)
                    } else {
                        val numberToCall = resolvedNumber ?: target
                        val reply = systemController.makePhoneCall(numberToCall)
                        JarvisLogStore.log("CALL", "$reply ($resolvedName)")
                        speakerHelper.speak(reply)
                    }
                }
            }
            "send_sms" -> {
                runAfterScreenUnlock {
                    val rec = (args["recipient"] as? String) ?: ""
                    val msg = (args["message"] as? String) ?: ""
                    val (resolvedNumber, _) = telephonyHelper.resolveContactNumber(rec)
                    val numberToSend = resolvedNumber ?: rec
                    val reply = systemController.sendSms(numberToSend, msg)
                    JarvisLogStore.log("CORE", "[Result]: $reply")
                    speakerHelper.speak(reply)
                }
            }
            "play_youtube" -> {
                runAfterScreenUnlock {
                    val q = (args["query"] as? String) ?: "trending songs"
                    systemController.playYoutube(q) { reply ->
                        JarvisLogStore.log("YOUTUBE", reply)
                        speakerHelper.speak(reply)
                        scope.launch {
                            delay(900)
                            AutoUnlockManager.unlockAppLockIfPresent(appContext, geminiBrain)
                        }
                    }
                }
            }
            "open_app" -> {
                runAfterScreenUnlock(checkAppLockAfter = true) {
                    val app = (args["app_name"] as? String) ?: ""
                    val reply = systemController.openApp(app)
                    JarvisLogStore.log("APP_LAUNCHER", reply)
                    speakerHelper.speak(reply)
                }
            }
            "send_whatsapp_message" -> {
                runAfterScreenUnlock {
                    val rec = (args["recipient"] as? String) ?: ""
                    val msg = (args["message"] as? String) ?: ""
                    nodeActionEngine.sendWhatsAppMessage(rec, msg) { _, reply ->
                        JarvisLogStore.log("WHATSAPP", reply)
                        speakerHelper.speak(reply)
                    }
                }
            }
            "whatsapp_call" -> {
                runAfterScreenUnlock {
                    val rec = (args["recipient"] as? String) ?: ""
                    val isVideo = (args["call_type"] as? String)?.lowercase() == "video"
                    nodeActionEngine.callWhatsApp(rec, isVideo) { _, reply ->
                        JarvisLogStore.log("WHATSAPP_CALL", reply)
                        speakerHelper.speak(reply)
                    }
                }
            }
            "tap_tag" -> {
                runAfterScreenUnlock {
                    val tag = (args["tag"] as? Number)?.toInt() ?: 1
                    nodeActionEngine.tapByTag(tag) { _, reply ->
                        JarvisLogStore.log("UI_AGENT", reply)
                        speakerHelper.speak(reply)
                    }
                }
            }
            "type_tag" -> {
                runAfterScreenUnlock {
                    val tag = (args["tag"] as? Number)?.toInt() ?: 1
                    val text = (args["text"] as? String) ?: ""
                    nodeActionEngine.typeTextByTag(tag, text) { _, reply ->
                        JarvisLogStore.log("UI_AGENT", reply)
                        speakerHelper.speak(reply)
                    }
                }
            }
            "scroll_screen" -> {
                runAfterScreenUnlock {
                    val dir = (args["direction"] as? String) ?: "down"
                    nodeActionEngine.scrollScreen(dir) { _, reply ->
                        JarvisLogStore.log("SCROLL", reply)
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
                universalAppAgent.performInstagramTool(tool, args, geminiBrain) { _, reply ->
                    JarvisLogStore.log("CORE", "[Instagram]: $reply")
                    if (!alreadySpoken && reply.isNotBlank()) {
                        speakerHelper.speak(reply)
                    }
                }
            }
            else -> {
                JarvisLogStore.log("TOOL_WARN", "Unknown action '$tool'")
            }
        }
    }

    private fun runAfterScreenUnlock(
        checkAppLockAfter: Boolean = false,
        block: () -> Unit
    ) {
        if (!AutoUnlockManager.isScreenLockedOrOff(appContext)) {
            block()
            if (checkAppLockAfter) {
                scope.launch {
                    delay(900)
                    AutoUnlockManager.unlockAppLockIfPresent(appContext, geminiBrain)
                }
            }
            return
        }
        scope.launch {
            AutoUnlockManager.ensureScreenUnlocked(appContext)
            block()
            if (checkAppLockAfter) {
                delay(1000)
                AutoUnlockManager.unlockAppLockIfPresent(appContext, geminiBrain)
            }
        }
    }
}
