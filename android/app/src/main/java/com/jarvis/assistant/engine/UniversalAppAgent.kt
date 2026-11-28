package com.jarvis.assistant.engine

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisAccessibilityService
import com.jarvis.assistant.brain.GeminiBrain
import com.jarvis.assistant.brain.SocialIntent
import com.jarvis.assistant.debug.JarvisLogStore
import com.jarvis.assistant.security.AutoUnlockManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UniversalAppAgent(
    private val context: Context,
    private val systemController: SystemController,
    private val nodeActionEngine: NodeActionEngine
) {
    private val mainScope = CoroutineScope(Dispatchers.Main)

    /**
     * Direct typed entrypoint for dedicated Instagram tools (skips 2nd AI intent-parsing call).
     */
    fun performInstagramTool(
        toolName: String,
        args: Map<String, Any>,
        geminiBrain: GeminiBrain?,
        callback: (Boolean, String) -> Unit
    ) {
        val (directIntent, fallbackInstruction) = when (toolName) {
            "instagram_send_message" -> {
                val rec = (args["recipient"] as? String)?.trim() ?: ""
                val msg = (args["message"] as? String)?.trim() ?: ""
                SocialIntent("dm", rec, msg, 1) to "Send direct Instagram message '$msg' to '$rec'"
            }
            "instagram_read_last_message" -> {
                val sender = (args["sender"] as? String)?.trim() ?: ""
                SocialIntent("read_dm", sender, "", 1) to "Open '$sender' chat on Instagram, mark seen, and read their last message"
            }
            "instagram_watch_story" -> {
                val person = (args["person"] as? String)?.trim() ?: ""
                SocialIntent("story", person, "", 1) to "Slide Instagram homepage story tray horizontally and watch '$person' story without searching"
            }
            "instagram_follow_account" -> {
                val account = (args["account"] as? String)?.trim() ?: ""
                SocialIntent("follow", account, "", 1) to "Search Instagram accounts for '$account' and follow the verified/official profile"
            }
            "instagram_play_chat_reels" -> {
                val sender = (args["sender"] as? String)?.trim() ?: ""
                val count = (args["count"] as? Number)?.toInt() ?: 1
                val fromMe = (args["from_me"] as? Boolean) == true
                val actionType = if (count < 0) "chat_media_all" else "chat_media"
                val ownerPhrase = if (fromMe) "sent by Sir" else "sent by '$sender'"
                val desc = when {
                    count < 0 -> "Open '$sender' chat on Instagram and play all shared reels $ownerPhrase from latest upward"
                    count > 1 -> "Open '$sender' chat on Instagram and play the last $count shared reels $ownerPhrase from latest upward"
                    else -> "Open '$sender' chat on Instagram and play the latest shared reel $ownerPhrase"
                }
                SocialIntent(actionType, sender, "", count, fromMe) to desc
            }
            "instagram_play_reels" -> {
                val next = (args["next"] as? Boolean) == true
                val instr = if (next) "scroll next reel" else "open reels"
                SocialIntent("reels", "", "", 1) to instr
            }
            else -> {
                SocialIntent("other", "", "", 1) to toolName
            }
        }
        executeInternal("Instagram", fallbackInstruction, directIntent, geminiBrain, callback)
    }

    /**
     * Autonomous Multi-Turn Observation-Action Loop for general apps and fallback UI tasks.
     */
    fun performAppAction(
        appName: String,
        instruction: String,
        geminiBrain: GeminiBrain,
        callback: (Boolean, String) -> Unit
    ) {
        executeInternal(appName, instruction, null, geminiBrain, callback)
    }

    private fun executeInternal(
        appName: String,
        instruction: String,
        directSocialIntent: SocialIntent?,
        geminiBrain: GeminiBrain?,
        callback: (Boolean, String) -> Unit
    ) {
        mainScope.launch {
            JarvisLogStore.log("AGENT_START", "app='$appName' directIntent=$directSocialIntent instruction='$instruction'")
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                JarvisLogStore.log("AGENT_ABORT", "Accessibility service null")
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            // 1. Check screen lock dynamically BEFORE launching app (no delay if already unlocked)
            val screenReady = AutoUnlockManager.ensureScreenUnlocked(context)
            if (!screenReady) {
                JarvisLogStore.log("AGENT_ABORT", "Screen unlock failed")
                callback(false, "Sir, phone screen unlock nahi ho saki.")
                return@launch
            }

            val lowerApp = appName.lowercase().trim()
            val isInstagram = lowerApp.contains("instagram") || lowerApp.contains("insta")

            // Only invoke extractSocialIntent when arriving via generic universal_app_action without a typed tool
            val socialIntentJob: Deferred<SocialIntent>? = if (isInstagram && directSocialIntent == null && geminiBrain != null) {
                async { geminiBrain.extractSocialIntent(instruction) }
            } else {
                null
            }

            // Skip redundant app launch Intent + 500ms wait when target app is already active on screen
            val currentForegroundPkg = service.rootInActiveWindow?.packageName?.toString().orEmpty()
            val alreadyInTargetApp = currentForegroundPkg.isNotBlank() &&
                ((isInstagram && currentForegroundPkg.contains("instagram", ignoreCase = true)) ||
                    (!isInstagram && systemController.doesPackageMatchAppName(currentForegroundPkg, appName))) &&
                service.dumpScreenElements().isNotEmpty()

            if (alreadyInTargetApp) {
                JarvisLogStore.log("APP_READY", "pkg='$currentForegroundPkg' waitMs=0 (already active)")
            } else {
                val openedMsg = systemController.openApp(appName)
                JarvisLogStore.log("APP_LAUNCH", "openApp('$appName') -> '$openedMsg'")
                if (openedMsg.contains("nahi mila")) {
                    socialIntentJob?.cancel()
                    callback(false, openedMsg)
                    return@launch
                }

                // Poll fast every 45ms until target app (or App Lock) renders
                delay(50)
                val launchStart = System.currentTimeMillis()
                var finalActivePkg = ""
                while (System.currentTimeMillis() - launchStart < 1500) {
                    if (AutoUnlockManager.isAppLockScreen(service)) break
                    finalActivePkg = service.rootInActiveWindow?.packageName?.toString()?.lowercase() ?: ""
                    val appReady = finalActivePkg.isNotBlank() && finalActivePkg != context.packageName.lowercase() &&
                        (!isInstagram || finalActivePkg.contains("instagram"))
                    if (appReady && service.dumpScreenElements().isNotEmpty()) break
                    delay(45)
                }
                JarvisLogStore.log("APP_READY", "pkg='$finalActivePkg' waitMs=${System.currentTimeMillis() - launchStart}")

                // 2. Auto-unlock App Lock if target app is protected (poll immediately instead of blind 650ms sleep)
                if (AutoUnlockManager.isAppLockScreen(service)) {
                    JarvisLogStore.log("APPLOCK", "Detected AppLock; attempting unlock")
                    AutoUnlockManager.unlockAppLockIfPresent(context, geminiBrain)
                    val unlockStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - unlockStart < 700) {
                        if (!AutoUnlockManager.isAppLockScreen(service) && service.dumpScreenElements().isNotEmpty()) break
                        delay(45)
                    }
                }
            }

            val actionHistory = mutableListOf<String>()

            // 3. Instant Live-Tree Fast-Path for Instagram — zero static coords, zero extra AI calls for direct tools!
            val resolvedSocialIntent = directSocialIntent ?: socialIntentJob?.await()
            if (isInstagram && resolvedSocialIntent != null && resolvedSocialIntent.actionType != "other") {
                val fastResult = InstagramTreeNavigator.execute(service, resolvedSocialIntent, instruction)
                JarvisLogStore.log(
                    "FAST_PATH",
                    "handled=${fastResult.handled} success=${fastResult.success} steps=${fastResult.stepsTaken} msg='${fastResult.message}'"
                )
                if (fastResult.handled) {
                    callback(fastResult.success, fastResult.message)
                    return@launch
                }
                actionHistory.addAll(fastResult.stepsTaken)
            }

            if (geminiBrain == null) {
                callback(false, "Sir, $appName par action complete nahi ho saka.")
                return@launch
            }

            val maxTurns = 10
            var turn = 0
            var finalMsg = ""
            var isCompleted = false
            var lastTapKey = ""
            var repeatedTapCount = 0

            while (turn < maxTurns && !isCompleted) {
                turn++
                if (turn > 1) {
                    delay(350)
                }

                if (AutoUnlockManager.isAppLockScreen(service)) {
                    AutoUnlockManager.unlockAppLockIfPresent(context, geminiBrain)
                    actionHistory.add("Auto-unlocked App Lock")
                    delay(700)
                    continue
                }

                val screenSummary = service.dumpScreenElementsSummary()
                if (screenSummary.isBlank() || screenSummary.contains("empty")) {
                    JarvisLogStore.log("FALLBACK_TURN_$turn", "Empty screen; waiting")
                    delay(700)
                    continue
                }
                JarvisLogStore.log("FALLBACK_SCREEN_$turn", screenSummary)

                val historyStr = if (actionHistory.isNotEmpty()) {
                    "\nActions taken so far:\n" + actionHistory.joinToString("\n") { "- $it" }
                } else ""

                val prompt = """
You are acting as an autonomous smartphone user inside $appName.
Live Screen UI Tree Elements:
$screenSummary
$historyStr

Sir's Overall Goal: "$instruction"

TREE-BASED NAVIGATION RULES (FOLLOW LIVE UI TREE STRUCTURE DIRECTLY):
1. ROUTE BY STRUCTURAL GOAL:
   - DIRECT MESSAGE / CHAT / DM: Open Messages Inbox tab -> tap top wide search pill -> pick matching personal chat row (with handle/avatar) -> type into message input_field -> tap Send.
   - READ LAST DM MESSAGE: Open Messages Inbox tab -> search and open matching personal chat thread -> read latest incoming message from the live tree and speak it clearly.
   - FRIEND OR USER REELS IN DM: Open Messages Inbox tab -> search and open friend's chat thread -> locate shared media/Reel cards in viewport (left side = friend, right side = user) -> play requested count cleanly waiting between reels.
   - WATCH STORY: Switch to Home tab -> locate horizontal Story tray at top -> slide horizontally and tap matching friend story ring.
   - FOLLOW / OPEN PROFILE: Switch to Search/Explore tab -> search account -> open verified/official profile matching target -> tap Follow (stop if already Following/Requested).
   - GENERIC REELS / GENERAL APPS: Use Reels tab when no friend specified, or search directly inside target app, type full text into input_field, and verify completion from the live UI tree before choosing "done".

2. LIVE ELEMENT INTERACTION:
   - Call "type_tag" ONLY on an 'input_field' (tap search pill first if shown as 'button').
   - Always act on the specific element matching the active goal; if an unexpected overlay appears or repeating an action leaves the screen unchanged, use "back".

3. CONTENT CREATION & NOTES (TABLES, LISTS, MEMOS):
   - Construct the COMPLETE, cleanly formatted content directly inside the "text" field of "type_tag" and inject it into the note's main 'input_field' in one clean action!
   - Once typed, tap Save/Done/Tick if needed, or choose "done".

4. VERIFY COMPLETION OR NOT FOUND:
   - Confirm from the latest Live Screen UI Tree Elements that the goal succeeded before choosing "done".
   - If genuinely not found, choose "done" with a short, honest spoken Hinglish reply.

Decide the best next single action:
- "tap_tag": Tap a numbered element [tag] from the Live Screen UI Tree.
- "type_tag": Type text into an 'input_field' [tag].
- "scroll_screen": Scroll active screen down or up.
- "back": Press hardware Back button (to dismiss a modal or exit an unintended post).
- "done": Goal completely achieved on screen, or target genuinely not found.

OUTPUT ONLY A RAW JSON OBJECT (no markdown, no backticks):
{
  "reasoning": "<1 concise sentence explaining why this element/action was chosen from the screen tree>",
  "action": "tap_tag" | "type_tag" | "scroll_screen" | "back" | "done",
  "tag": <number or null>,
  "text": "<text to type, or full generated content, or null>",
  "direction": "down" | "up" | null,
  "summary": "<When continuing: short step log. When done: a single, natural, clean sentence in spoken Hinglish confirming what happened.>"
}
""".trimIndent()

                val decision = geminiBrain.decideUIAction(prompt)
                val summary = decision.summary ?: decision.text ?: decision.action
                JarvisLogStore.log(
                    "FALLBACK_DECISION_$turn",
                    "action=${decision.action} tag=${decision.tag} dir=${decision.direction} reason='${decision.reasoning ?: ""}' summary='$summary'"
                )

                when (decision.action.lowercase().trim()) {
                    "done" -> {
                        isCompleted = true
                        val cleanSummary = (decision.summary ?: decision.text ?: "")
                            .replace(Regex("(?i)\\b(and )?goal complete(d)?( ho gaya hai)?\\b"), "")
                            .replace(Regex("(?i)^$appName:\\s*"), "")
                            .trim()
                        finalMsg = if (cleanSummary.isNotBlank() && cleanSummary.lowercase() != "done") {
                            cleanSummary
                        } else {
                            ""
                        }
                    }
                    "tap_tag" -> {
                        val targetTag = decision.tag ?: 1
                        val tapKey = "$targetTag|${screenSummary.hashCode()}"
                        if (tapKey == lastTapKey) {
                            repeatedTapCount++
                        } else {
                            lastTapKey = tapKey
                            repeatedTapCount = 0
                        }
                        if (repeatedTapCount >= 1) {
                            val backOk = service.performBack()
                            JarvisLogStore.log("FALLBACK_LOOP_BREAK", "Repeated tap on [$targetTag] with unchanged screen; pressed Back ok=$backOk")
                            actionHistory.add("Screen unchanged after repeating tap [$targetTag]; pressed Back")
                            lastTapKey = ""
                            repeatedTapCount = 0
                            delay(450)
                            continue
                        }
                        val pickedEl = service.findTaggedElement(targetTag)
                        JarvisLogStore.log("FALLBACK_PICKED_NODE", "tap_tag($targetTag) -> ${JarvisLogStore.describeElement(pickedEl)}")
                        var tapOk = false
                        var tapReply = ""
                        nodeActionEngine.tapByTag(targetTag) { ok, reply ->
                            tapOk = ok
                            tapReply = reply
                        }
                        JarvisLogStore.log("FALLBACK_EXEC", "tap_tag($targetTag) ok=$tapOk reply='$tapReply'")
                        if (tapOk) {
                            actionHistory.add("Tapped element [$targetTag] ($summary)")
                        } else {
                            actionHistory.add("FAILED to tap [$targetTag] ($tapReply) — inspect updated UI tree")
                        }
                        delay(450)
                    }
                    "type_tag" -> {
                        val targetTag = decision.tag ?: 1
                        val textToType = decision.text ?: instruction
                        val pickedEl = service.findTaggedElement(targetTag)
                        JarvisLogStore.log("FALLBACK_PICKED_NODE", "type_tag($targetTag) -> ${JarvisLogStore.describeElement(pickedEl)}")
                        var typeOk = false
                        var typeReply = ""
                        nodeActionEngine.typeTextByTag(targetTag, textToType) { ok, reply ->
                            typeOk = ok
                            typeReply = reply
                        }
                        JarvisLogStore.log("FALLBACK_EXEC", "type_tag($targetTag) ok=$typeOk reply='$typeReply'")
                        val preview = if (textToType.length > 40) textToType.take(40).replace("\n", " ") + "..." else textToType
                        if (typeOk) {
                            actionHistory.add("Typed '$preview' into element [$targetTag]")
                        } else {
                            actionHistory.add("FAILED to type '$preview' into [$targetTag] ($typeReply)")
                        }
                        delay(500)
                    }
                    "scroll_screen" -> {
                        val dir = decision.direction ?: "down"
                        val screenEls = service.dumpScreenElements()
                        val isFullPageVideoPager = screenEls.any {
                            val res = it.node.viewIdResourceName ?: ""
                            res.contains("clips_video_container", ignoreCase = true) ||
                                res.contains("clips_media_component", ignoreCase = true) ||
                                res.contains("scrubber", ignoreCase = true) ||
                                it.contentDescription.contains("Reel by ", ignoreCase = true) ||
                                it.text.contains("Reel by ", ignoreCase = true)
                        }
                        val ok = if (isFullPageVideoPager) {
                            service.flingScroll(dir)
                        } else {
                            service.scrollExact(dir)
                        }
                        JarvisLogStore.log("FALLBACK_EXEC", "scroll_screen($dir fling=$isFullPageVideoPager) ok=$ok")
                        actionHistory.add("Scrolled screen $dir")
                        if (isFullPageVideoPager && ok && instruction.lowercase().contains("reel")) {
                            isCompleted = true
                            finalMsg = ""
                        } else {
                            delay(180)
                        }
                    }
                    "back" -> {
                        val ok = service.performBack()
                        JarvisLogStore.log("FALLBACK_EXEC", "back ok=$ok")
                        actionHistory.add("Pressed back button")
                        delay(450)
                    }
                    "error" -> {
                        isCompleted = false
                        finalMsg = "Sir, AI server abhi busy hai (${decision.summary ?: "error"}), isliye action complete nahi ho paya — kripya thodi der mein dobara bolein."
                        break
                    }
                    else -> {
                        isCompleted = false
                        finalMsg = "Sir, screen step samajh nahi aaya — kripya dobara try karein."
                        break
                    }
                }
            }

            if (finalMsg.isBlank()) {
                finalMsg = if (isCompleted) {
                    geminiBrain.generateCompletionConfirmation(appName, instruction)
                } else {
                    "Sir, task pura nahi ho saka."
                }
            }

            callback(isCompleted, finalMsg)
        }
    }
}
