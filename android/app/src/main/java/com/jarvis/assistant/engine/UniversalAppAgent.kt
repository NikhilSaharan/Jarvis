package com.jarvis.assistant.engine

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisAccessibilityService
import com.jarvis.assistant.brain.GeminiBrain
import com.jarvis.assistant.brain.SocialIntent
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
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            // 1. Check screen lock dynamically BEFORE launching app (no delay if already unlocked)
            val screenReady = AutoUnlockManager.ensureScreenUnlocked(context)
            if (!screenReady) {
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

            val openedMsg = systemController.openApp(appName)
            if (openedMsg.contains("nahi mila")) {
                socialIntentJob?.cancel()
                callback(false, openedMsg)
                return@launch
            }

            // Poll fast every 70ms until target app (or App Lock) renders
            delay(120)
            val launchStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - launchStart < 1800) {
                if (AutoUnlockManager.isAppLockScreen(service)) break
                val activePkg = service.rootInActiveWindow?.packageName?.toString()?.lowercase() ?: ""
                val appReady = activePkg.isNotBlank() && activePkg != context.packageName.lowercase() &&
                    (!isInstagram || activePkg.contains("instagram"))
                if (appReady && service.dumpScreenElements().isNotEmpty()) break
                delay(70)
            }

            // 2. Auto-unlock App Lock if target app is protected
            if (AutoUnlockManager.isAppLockScreen(service)) {
                AutoUnlockManager.unlockAppLockIfPresent(context, geminiBrain)
                delay(650)
            }

            val actionHistory = mutableListOf<String>()

            // 3. Instant Live-Tree Fast-Path for Instagram — zero static coords, zero extra AI calls for direct tools!
            val resolvedSocialIntent = directSocialIntent ?: socialIntentJob?.await()
            if (isInstagram && resolvedSocialIntent != null && resolvedSocialIntent.actionType != "other") {
                val fastResult = InstagramTreeNavigator.execute(service, resolvedSocialIntent, instruction)
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
                    delay(700)
                    continue
                }

                val historyStr = if (actionHistory.isNotEmpty()) {
                    "\nActions taken so far:\n" + actionHistory.joinToString("\n") { "- $it" }
                } else ""

                val prompt = """
You are acting as an autonomous smartphone user inside $appName.
Live Screen UI Tree Elements:
$screenSummary
$historyStr

Sir's Overall Goal: "$instruction"

TREE-BASED NAVIGATION & EXECUTION RULES (FOLLOW LIVE UI TREE STRICTLY — ZERO RANDOM CLICKS):
1. ROUTE TO THE RIGHT APP SECTION BASED ON SIR'S INTENT:
   - DIRECT MESSAGE / CHAT / DM GOAL:
     * Do NOT use public Explore Search first! Tap the Messages / Direct / Inbox button first.
     * STRICTLY NEVER tap the top-right 'New message' compose/pencil icon in Messages Inbox — ALWAYS use the wide 'Search or ask Meta AI' / 'Search' bar below the username header (scroll up if scrolled out of view).
     * Inside Search results, tap the matching one-to-one personal chat row (SKIP group chats with multiple member names unless explicitly targeting a group, and skip Meta AI suggestion rows), tap message 'input_field', type message, and tap Send.
   - READ LAST DM MESSAGE GOAL:
     * Go to Messages / Inbox -> tap matching friend row directly if visible on Inbox OR use 'Search or ask Meta AI' (NEVER top-right 'New message') -> open their one-to-one chat thread so it marks Seen -> read the latest incoming message text from the tree and speak it clearly in "reply".
   - FRIEND-SENT OR USER-SENT REEL / VIDEO / POST IN DM:
     * Do NOT open the generic Reels bottom tab!
     * Go to Messages / Inbox -> use 'Search or ask Meta AI' (or tap friend directly if visible) -> open their one-to-one chat thread -> scroll up if needed and play shared reel(s) from the requested sender (left-side bubbles = friend sent; right-side / 'You sent' bubbles = user sent ONLY if explicitly requested). Wait until each reel finishes before returning to chat!
   - STORY WATCHING GOAL:
     * Stay strictly on the Home feed Story Tray — NEVER open Search (neither Explore nor DM Search) for stories!
     * Scroll/swipe the horizontal Story tray cleanly until <person>'s story bubble appears and tap it. If absent in the Home Story tray, stop cleanly and inform Sir.
   - FOLLOW / OPEN A PUBLIC ACCOUNT OR PROFILE GOAL:
     * Tap the normal Search / Explore tab -> type <account> -> check Accounts tab if needed -> prioritize the Verified / official primary account match and strictly avoid fanpages, parody, or backup accounts -> open profile and tap "Follow" (stop if already "Following" or "Requested").
   - GENERIC REELS / VIDEO TAB GOAL (NO friend sender):
     * Tap the Reels / Video tab if not already active; scroll if asked or choose "done".
   - GENERAL MEDIA / MOVIES / SHOWS / ITEMS:
     * Prefer Search over endless scrolling; tap the matching item/show card and tap Play / Resume to start playback.

2. INPUT FIELDS & SCREEN NAVIGATION SAFETY:
   - ONLY call "type_tag" on an element labeled 'input_field'. If a search box or text box shows as 'button', call "tap_tag" FIRST so the editable 'input_field' opens on the next turn.
   - NEVER tap random feed posts, comments, or generic empty wrappers. If an unintended post/popup covers the screen and main navigation tabs/buttons are missing, use "back" to return.
   - Never repeat the exact same failed action consecutively — adapt directly from the updated UI tree.

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
  "action": "tap_tag" | "type_tag" | "scroll_screen" | "back" | "done",
  "tag": <number or null>,
  "text": "<text to type, or full generated content, or null>",
  "direction": "down" | "up" | null,
  "summary": "<When continuing: short step log. When done: a single, natural, clean sentence in spoken Hinglish confirming what happened.>"
}
""".trimIndent()

                val decision = geminiBrain.decideUIAction(prompt)
                val summary = decision.summary ?: decision.text ?: decision.action

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
                        var tapOk = false
                        var tapReply = ""
                        nodeActionEngine.tapByTag(targetTag) { ok, reply ->
                            tapOk = ok
                            tapReply = reply
                        }
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
                        var typeOk = false
                        var typeReply = ""
                        nodeActionEngine.typeTextByTag(targetTag, textToType) { ok, reply ->
                            typeOk = ok
                            typeReply = reply
                        }
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
                        service.scroll(dir)
                        actionHistory.add("Scrolled screen $dir")
                        delay(550)
                    }
                    "back" -> {
                        service.performBack()
                        actionHistory.add("Pressed back button")
                        delay(450)
                    }
                    else -> {
                        isCompleted = true
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
