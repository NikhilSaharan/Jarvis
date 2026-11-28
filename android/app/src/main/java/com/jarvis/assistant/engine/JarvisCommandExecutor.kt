package com.jarvis.assistant.engine

import android.content.Context
import android.media.AudioManager
import com.jarvis.assistant.BuildConfig
import com.jarvis.assistant.JarvisAccessibilityService
import com.jarvis.assistant.brain.GeminiBrain
import com.jarvis.assistant.brain.GroqFastBrain
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
import kotlinx.coroutines.withContext

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
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val systemController = SystemController(appContext)
    private val telephonyHelper = TelephonyHelper(appContext)
    private val nodeActionEngine = NodeActionEngine(systemController, telephonyHelper)
    private val universalAppAgent = UniversalAppAgent(appContext, systemController, nodeActionEngine)
    private val scheduledTaskManager = ScheduledTaskManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var geminiBrain: GeminiBrain = createBrain()
        private set

    @Volatile
    var groqBrain: GroqFastBrain = createGroqBrain()
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

    private fun createGroqBrain(): GroqFastBrain {
        val defaultGroq = BuildConfig.GROQ_API_KEY
        val prefs = appContext.getSharedPreferences("jarvis_config", Context.MODE_PRIVATE)
        val storedKey = prefs.getString("groq_api_key", "")?.trim().orEmpty()
        val effective = if (storedKey.isBlank() && defaultGroq.isNotBlank()) {
            prefs.edit().putString("groq_api_key", defaultGroq).apply()
            defaultGroq
        } else {
            storedKey
        }
        return GroqFastBrain(effective, memoryManager)
    }

    fun reloadApiKey(customKey: String? = null, customGroqKey: String? = null) {
        val prefs = appContext.getSharedPreferences("jarvis_config", Context.MODE_PRIVATE)
        val ed = prefs.edit()
        if (customKey != null) {
            ed.putString("gemini_api_key", customKey.trim())
        }
        if (customGroqKey != null) {
            ed.putString("groq_api_key", customGroqKey.trim())
        }
        ed.apply()
        geminiBrain = createBrain()
        groqBrain = createGroqBrain()
        prewarmConnections()
    }

    fun prewarmConnections() {
        ioScope.launch {
            geminiBrain.prewarm()
            groqBrain.prewarm()
        }
    }

    private fun isInActiveAudioCall(): Boolean {
        return try {
            val m = audioManager.mode
            m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {
            false
        }
    }

    private fun speakSafely(text: String) {
        if (text.isBlank()) return
        if (isInActiveAudioCall()) {
            JarvisLogStore.log("JARVIS_MUTED_INCALL", "Suppressed TTS over call: $text")
            return
        }
        speakerHelper.speak(text)
    }

    private fun buildScreenContextForBrain(): String {
        val acc = JarvisAccessibilityService.instance
        val audioMode = try {
            audioManager.mode
        } catch (_: Exception) {
            AudioManager.MODE_NORMAL
        }
        val isRinging = audioMode == AudioManager.MODE_RINGTONE
        val inCall = audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION
        val raw = if (isRinging || inCall) {
            acc?.dumpAllWindowsElementsSummary() ?: acc?.dumpScreenElementsSummary() ?: ""
        } else {
            acc?.dumpScreenElementsSummary() ?: ""
        }
        val prefixes = mutableListOf<String>()
        if (isRinging) {
            prefixes.add("[DEVICE STATE: INCOMING CALL RINGING NOW! If Sir says pick/uthao/accept -> answer_call(). If Sir says reject/cut/kaat do -> end_call(). NEVER explain caller name meaning!]")
        } else if (inCall) {
            prefixes.add("[DEVICE STATE: ACTIVE CALL IN PROGRESS! ONLY run explicit commands (toggle_speakerphone, end_call, volume_control, toggle_flashlight, lock_screen); ignore caller talk and set speech=null.]")
        }
        if (AutoUnlockManager.isScreenLockedOrOff(appContext)) {
            prefixes.add("[DEVICE STATE: Screen is currently LOCKED / OFF. Hardware tools (lock_screen/media/volume/call) and auto-unlockable tools (WhatsApp, calls, apps) are ready.]")
        }
        return if (prefixes.isNotEmpty()) prefixes.joinToString("\n") + "\n" + raw else raw
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
                val screenContext = buildScreenContextForBrain()
                val decision = geminiBrain.processCommand(clean, screenContext)
                dispatchDecision(decision)
            } catch (e: Exception) {
                JarvisLogStore.log("EXEC_ERROR", "Command failed cleanly: ${e.message}")
            } finally {
                onFinished?.invoke()
            }
        }
    }

    fun executeAudioCommand(
        wavBytes: ByteArray,
        voskHint: String,
        isMediaPlaying: Boolean,
        speakerVerified: Boolean,
        isInActiveCall: Boolean = false,
        sourceTag: String = "USER_VOICE",
        onFinished: ((ignored: Boolean, transcript: String) -> Unit)? = null
    ) {
        ioScope.launch {
            var ignored = false
            var transcript = ""
            try {
                val screenContext = buildScreenContextForBrain()
                val decision = (if (groqBrain.isConfigured()) {
                    groqBrain.processAudioCommand(
                        wavBytes = wavBytes,
                        voskHint = voskHint,
                        screenElementsSummary = screenContext,
                        isMediaPlaying = isMediaPlaying,
                        speakerVerified = speakerVerified,
                        isInActiveCall = isInActiveCall
                    )
                } else null) ?: geminiBrain.processAudioCommand(
                    wavBytes = wavBytes,
                    voskHint = voskHint,
                    screenElementsSummary = screenContext,
                    isMediaPlaying = isMediaPlaying,
                    speakerVerified = speakerVerified,
                    isInActiveCall = isInActiveCall
                )
                transcript = decision.transcript?.trim().orEmpty()
                if (decision.shouldIgnore) {
                    ignored = true
                    return@launch
                }
                if (transcript.isNotBlank()) {
                    JarvisLogStore.log(sourceTag, "\"$transcript\"")
                }
                withContext(Dispatchers.Main) {
                    dispatchDecision(decision)
                }
            } catch (e: Exception) {
                JarvisLogStore.log("EXEC_ERROR", "Audio command failed cleanly: ${e.message}")
            } finally {
                onFinished?.invoke(ignored, transcript)
            }
        }
    }

    fun evaluateDoubtOrRejectedAudio(
        wavBytes: ByteArray,
        voskHint: String,
        tier: String,
        similarity: Float,
        acceptThreshold: Float,
        isMediaPlaying: Boolean,
        onResult: (com.jarvis.assistant.brain.GeminiBrain.UnauthorizedAudioEvaluation) -> Unit
    ) {
        ioScope.launch {
            val res = try {
                val screenContext = buildScreenContextForBrain()
                (if (groqBrain.isConfigured()) {
                    groqBrain.evaluateDoubtOrRejectedAudio(
                        wavBytes = wavBytes,
                        screenElementsSummary = screenContext,
                        tier = tier,
                        similarity = similarity,
                        acceptThreshold = acceptThreshold,
                        isMediaPlaying = isMediaPlaying
                    )
                } else null) ?: geminiBrain.evaluateDoubtOrRejectedAudio(
                    wavBytes = wavBytes,
                    voskHint = voskHint,
                    screenElementsSummary = screenContext,
                    tier = tier,
                    similarity = similarity,
                    acceptThreshold = acceptThreshold,
                    isMediaPlaying = isMediaPlaying
                )
            } catch (e: Exception) {
                JarvisLogStore.log("VOICE_GUARD_ERR", "Eval error: ${e.message}")
                com.jarvis.assistant.brain.GeminiBrain.UnauthorizedAudioEvaluation(false, "", "")
            }
            withContext(Dispatchers.Main) {
                onResult(res)
            }
        }
    }

    fun verifySecretCodeAudio(
        wavBytes: ByteArray,
        expectedSecretCode: String,
        voskHint: String,
        offlineFallbackMatch: (String, String) -> Boolean,
        onResult: (com.jarvis.assistant.brain.GeminiBrain.SecretCodeCheckResult) -> Unit
    ) {
        scope.launch {
            val res = try {
                (if (groqBrain.isConfigured()) {
                    groqBrain.verifySecretCodeAudio(
                        wavBytes = wavBytes,
                        expectedSecretCode = expectedSecretCode,
                        offlineFallbackMatch = offlineFallbackMatch
                    )
                } else null) ?: geminiBrain.verifySecretCodeAudio(
                    wavBytes = wavBytes,
                    expectedSecretCode = expectedSecretCode,
                    voskHint = voskHint,
                    offlineFallbackMatch = offlineFallbackMatch
                )
            } catch (e: Exception) {
                JarvisLogStore.log("SECRET_CHECK_ERR", "Check failed: ${e.message}")
                com.jarvis.assistant.brain.GeminiBrain.SecretCodeCheckResult(false, voskHint, "")
            }
            onResult(res)
        }
    }

    fun executePreParsedDecision(
        decision: com.jarvis.assistant.brain.ActionDecision,
        transcript: String = "",
        sourceTag: String = "USER_VOICE",
        onFinished: (() -> Unit)? = null
    ) {
        scope.launch {
            try {
                if (transcript.isNotBlank()) {
                    JarvisLogStore.log(sourceTag, "\"$transcript\"")
                }
                dispatchDecision(decision)
            } catch (e: Exception) {
                JarvisLogStore.log("EXEC_ERROR", "Held command failed cleanly: ${e.message}")
            } finally {
                onFinished?.invoke()
            }
        }
    }

    fun speakDynamic(text: String) {
        if (text.isBlank()) return
        JarvisLogStore.log("JARVIS_SPEAK", "Jarvis: \"$text\"")
        speakSafely(text)
    }

    private fun dispatchDecision(decision: com.jarvis.assistant.brain.ActionDecision) {
        val tool = decision.toolName?.takeIf { it.isNotBlank() && it != "null" }
        val args = decision.toolArgs ?: emptyMap()
        val speech = decision.speech?.takeIf { it.isNotBlank() && it != "null" }
        val alreadySpoken = speech != null && !isInActiveAudioCall()

        if (speech != null) {
            JarvisLogStore.log("JARVIS_SPEAK", "Jarvis: \"$speech\"")
            speakSafely(speech)
        }

        if (tool == null) {
            if (alreadySpoken) {
                JarvisLogStore.log("CORE", "[Result]: Done (Conversational)")
            }
            return
        }

        executeSingleTool(tool, args, alreadySpoken)
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
                if (!alreadySpoken) {
                    val unitText = if (delaySec >= 60 && delaySec % 60 == 0) "${delaySec / 60} minute" else "$delaySec seconds"
                    speakSafely("Theek hai Sir, $unitText baad $summary kar dunga.")
                }
            }
            "manage_vip" -> {
                val action = (args["action"] as? String)?.lowercase() ?: "add"
                val contact = (args["contact"] as? String) ?: (args["name"] as? String) ?: ""
                if (action == "add" && contact.isNotBlank()) {
                    memoryManager.addVipContact(contact)
                    val reply = "Sir, $contact ko VIP priority list me add kar diya hai. Silent aur DND mode me bhi inka call bajega."
                    JarvisLogStore.log("VIP", reply)
                    speakSafely(reply)
                } else if (action == "remove" && contact.isNotBlank()) {
                    memoryManager.removeVipContact(contact)
                    val reply = "Sir, $contact ko VIP priority list se hata diya gaya hai."
                    JarvisLogStore.log("VIP", reply)
                    speakSafely(reply)
                } else {
                    val vips = memoryManager.getVipContacts()
                    val reply = if (vips.isEmpty()) "Sir, abhi koi VIP contact set nahi hai." else "Sir, aapke VIP contacts hain: ${vips.joinToString(", ")}."
                    JarvisLogStore.log("VIP", reply)
                    speakSafely(reply)
                }
            }
            "learn_preference" -> {
                val pref = (args["preference"] as? String) ?: ""
                if (pref.isNotBlank()) {
                    memoryManager.learnPreference(pref)
                    val reply = "Sir, maine aapki yeh pasand note kar li hai."
                    JarvisLogStore.log("MEMORY", reply)
                    speakSafely(reply)
                }
            }
            "universal_app_action" -> {
                val appName = (args["app_name"] as? String) ?: "Notes"
                val instruction = (args["instruction"] as? String) ?: (args["text"] as? String) ?: (args["action"] as? String) ?: ""
                JarvisLogStore.log("CORE", "[App Agent]: Opening $appName for AI execution...")
                universalAppAgent.performAppAction(appName, instruction, geminiBrain, groqBrain) { _, reply ->
                    JarvisLogStore.log("CORE", "[App Agent]: $reply")
                    if (!alreadySpoken && reply.isNotBlank()) {
                        speakSafely(reply)
                    }
                }
            }
            "sleep_routine" -> {
                val mins = (args["minutes"] as? Number)?.toInt() ?: 10
                val reply = systemController.sleepAndWakeUp(mins) { wakeupMsg ->
                    JarvisLogStore.log("SLEEP_WAKE", wakeupMsg)
                    speakSafely(wakeupMsg)
                }
                JarvisLogStore.log("SLEEP_BUTLER", reply)
                speakSafely(reply)
            }
            "set_dnd" -> {
                val enabled = (args["enabled"] as? Boolean) ?: true
                val reply = systemController.setDnd(enabled)
                JarvisLogStore.log("CORE", "[Result]: $reply")
                if (!alreadySpoken) speakSafely(reply)
            }
            "set_brightness" -> {
                val level = args["level"]?.toString()?.trim()?.takeIf { it.isNotBlank() } ?: "medium"
                val reply = systemController.setBrightness(level)
                JarvisLogStore.log("CORE", "[Result]: $reply")
                if (!alreadySpoken || reply.contains("permission", ignoreCase = true)) {
                    speakSafely(reply)
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
                JarvisLogStore.log("FLASHLIGHT", reply)
                if (!alreadySpoken) speakSafely(reply)
            }
            "toggle_speakerphone" -> {
                val raw = args["enabled"]
                val enabled = when (raw) {
                    is Boolean -> raw
                    is String -> raw.lowercase().trim() !in listOf("false", "off", "disable")
                    else -> true
                }
                val reply = systemController.setSpeakerphone(enabled)
                JarvisLogStore.log("SPEAKERPHONE", reply)
                if (!alreadySpoken && !isInActiveAudioCall()) speakSafely(reply)
            }
            "set_alarm" -> {
                val h = (args["hour"] as? Number)?.toInt() ?: 7
                val m = (args["minute"] as? Number)?.toInt() ?: 0
                val msg = (args["message"] as? String) ?: "Alarm"
                val reply = systemController.setAlarm(h, m, msg)
                JarvisLogStore.log("CORE", "[Result]: $reply")
                if (!alreadySpoken) speakSafely(reply)
            }
            "set_timer" -> {
                val s = (args["seconds"] as? Number)?.toInt() ?: 60
                val msg = (args["message"] as? String) ?: "Timer"
                val reply = systemController.setTimer(s, msg)
                JarvisLogStore.log("CORE", "[Result]: $reply")
                if (!alreadySpoken) speakSafely(reply)
            }
            "answer_call" -> {
                val reply = systemController.answerCall()
                JarvisLogStore.log("CALL", reply)
                if (!alreadySpoken) speakSafely(reply)
            }
            "end_call" -> {
                val reply = systemController.endCall()
                JarvisLogStore.log("CALL", reply)
                if (!alreadySpoken) speakSafely(reply)
            }
            "close_app" -> {
                val appName = (args["app_name"] as? String) ?: (args["name"] as? String) ?: ""
                val reply = systemController.closeApp(appName)
                JarvisLogStore.log("APP_CONTROL", reply)
                if (!alreadySpoken) speakSafely(reply)
            }
            "lock_screen" -> {
                val reply = systemController.lockScreen()
                JarvisLogStore.log("LOCK_SCREEN", if (reply.isNotBlank()) reply else "Locked screen cleanly.")
                if (!alreadySpoken && reply.isNotBlank()) speakSafely(reply)
            }
            "media_control" -> {
                val action = (args["action"] as? String) ?: "play_pause"
                val reply = systemController.controlMedia(action)
                JarvisLogStore.log("MEDIA_CONTROL", "Action='$action' ${if (reply.isNotBlank()) "msg='$reply'" else "(silent)"}")
                if (!alreadySpoken && reply.isNotBlank()) speakSafely(reply)
            }
            "volume_control" -> {
                val action = (args["action"] as? String) ?: "down"
                val percent = (args["percent"] as? Number)?.toInt()
                val reply = systemController.controlVolume(action, percent)
                JarvisLogStore.log("VOLUME_CONTROL", "Action='$action' pct=$percent ${if (reply.isNotBlank()) "msg='$reply'" else "(silent)"}")
                if (!alreadySpoken && reply.isNotBlank()) speakSafely(reply)
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
                        speakSafely(reply)
                    } else {
                        val numberToCall = resolvedNumber ?: target
                        val reply = systemController.makePhoneCall(numberToCall)
                        JarvisLogStore.log("CALL", "$reply ($resolvedName)")
                        speakSafely(reply)
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
                    speakSafely(reply)
                }
            }
            "play_youtube" -> {
                runAfterScreenUnlock {
                    val q = (args["query"] as? String) ?: "trending songs"
                    systemController.playYoutube(q) { reply, directPlayed ->
                        JarvisLogStore.log("YOUTUBE", "$reply (direct=$directPlayed)")
                        speakSafely(reply)
                        scope.launch {
                            delay(900)
                            AutoUnlockManager.unlockAppLockIfPresent(appContext, geminiBrain)
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
                    val reply = systemController.openApp(app)
                    JarvisLogStore.log("APP_LAUNCHER", reply)
                    speakSafely(reply)
                }
            }
            "send_whatsapp_message" -> {
                val rec = (args["recipient"] as? String) ?: ""
                val msg = (args["message"] as? String) ?: ""
                nodeActionEngine.sendWhatsAppMessage(rec, msg) { _, reply ->
                    JarvisLogStore.log("WHATSAPP", reply)
                    speakSafely(reply)
                }
            }
            "whatsapp_read_messages" -> {
                val sender = (args["sender"] as? String) ?: (args["recipient"] as? String) ?: ""
                nodeActionEngine.readWhatsAppMessages(sender) { _, reply ->
                    JarvisLogStore.log("WHATSAPP", reply)
                    if (!alreadySpoken && reply.isNotBlank()) {
                        speakSafely(reply)
                    }
                }
            }
            "whatsapp_call" -> {
                val rec = (args["recipient"] as? String) ?: ""
                val isVideo = (args["call_type"] as? String)?.lowercase() == "video"
                nodeActionEngine.callWhatsApp(rec, isVideo) { _, reply ->
                    JarvisLogStore.log("WHATSAPP_CALL", reply)
                    speakSafely(reply)
                }
            }
            "tap_tag" -> {
                runAfterScreenUnlock {
                    val tag = (args["tag"] as? Number)?.toInt() ?: 1
                    nodeActionEngine.tapByTag(tag) { _, reply ->
                        JarvisLogStore.log("UI_AGENT", reply)
                        speakSafely(reply)
                    }
                }
            }
            "type_tag" -> {
                runAfterScreenUnlock {
                    val tag = (args["tag"] as? Number)?.toInt() ?: 1
                    val text = (args["text"] as? String) ?: ""
                    nodeActionEngine.typeTextByTag(tag, text) { _, reply ->
                        JarvisLogStore.log("UI_AGENT", reply)
                        speakSafely(reply)
                    }
                }
            }
            "scroll_screen" -> {
                runAfterScreenUnlock {
                    val dir = (args["direction"] as? String) ?: "down"
                    nodeActionEngine.scrollScreen(dir) { _, reply ->
                        JarvisLogStore.log("SCROLL", reply)
                        speakSafely(reply)
                    }
                }
            }
            "instagram_send_message",
            "instagram_read_last_message",
            "instagram_watch_story",
            "instagram_follow_account",
            "instagram_play_chat_reels",
            "instagram_play_reels" -> {
                universalAppAgent.performInstagramTool(tool, args, geminiBrain, groqBrain) { _, reply ->
                    JarvisLogStore.log("CORE", "[Instagram]: $reply")
                    if (!alreadySpoken && reply.isNotBlank()) {
                        speakSafely(reply)
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
