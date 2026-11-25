package com.jarvis.assistant.engine

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisAccessibilityService
import com.jarvis.assistant.brain.GeminiBrain
import com.jarvis.assistant.security.AutoUnlockManager
import kotlinx.coroutines.CoroutineScope
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
     * Autonomous Multi-Turn Observation-Action Loop (Human-like smartphone navigation):
     * 1. Launches target app (Notes, Prime Video, Instagram, Amazon, etc.).
     * 2. Loops up to 6 turns (Observe -> Reason -> Act -> Repeat).
     * 3. Completely general & dynamic: Zero hardcoded app templates or button keywords.
     */
    fun performAppAction(
        appName: String,
        instruction: String,
        geminiBrain: GeminiBrain,
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

            // Parse social goal in parallel while launching app (0ms overhead)
            val socialIntentJob = if (isInstagram) {
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

            // Poll dynamically every 120ms until target app (or App Lock) renders (fast on warm launch!)
            delay(250)
            val launchStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - launchStart < 2000) {
                if (AutoUnlockManager.isAppLockScreen(service)) break
                val activePkg = service.rootInActiveWindow?.packageName?.toString()?.lowercase() ?: ""
                val appReady = activePkg.isNotBlank() && activePkg != context.packageName.lowercase() &&
                    (!isInstagram || activePkg.contains("instagram"))
                if (appReady && service.dumpScreenElements().isNotEmpty()) break
                delay(120)
            }

            // 2. Auto-unlock App Lock if target app is protected (taps Use Privacy Password / PIN / Pattern dynamically)
            if (AutoUnlockManager.isAppLockScreen(service)) {
                AutoUnlockManager.unlockAppLockIfPresent(context, geminiBrain)
                delay(700)
            }

            val actionHistory = mutableListOf<String>()

            // 3. Instant Live-Tree Fast-Path for Instagram (DMs, Stories, Follow, Reels) — zero static coords!
            if (socialIntentJob != null) {
                val socialIntent = socialIntentJob.await()
                if (socialIntent.actionType != "other") {
                    val fastResult = InstagramTreeNavigator.execute(service, socialIntent, instruction)
                    if (fastResult.handled) {
                        callback(fastResult.success, fastResult.message)
                        return@launch
                    }
                    actionHistory.addAll(fastResult.stepsTaken)
                }
            }

            val maxTurns = 10
            var turn = 0
            var finalMsg = ""
            var isCompleted = false

            while (turn < maxTurns && !isCompleted) {
                turn++
                if (turn > 1) {
                    delay(350) // Short settle time between turns (replaces 1.7s blind sleep)
                }

                // Auto-unlock App Lock if it appeared midway
                if (AutoUnlockManager.isAppLockScreen(service)) {
                    AutoUnlockManager.unlockAppLockIfPresent(context, geminiBrain)
                    actionHistory.add("Auto-unlocked App Lock")
                    delay(700)
                    continue
                }

                val screenSummary = service.dumpScreenElementsSummary()
                if (screenSummary.isBlank() || screenSummary.contains("empty")) {
                    delay(800)
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
   - DIRECT MESSAGE / CHAT / DM GOAL (e.g. "msg bhejo", "DM karo", "reply karo"):
     * Do NOT use the public Explore/Account Search tab first!
     * Look in the live tree for the Messages / Direct / Inbox / Chat button or icon and tap it FIRST.
     * Inside Messages, tap the chat Search bar/button (tap it first if it shows as 'button' instead of 'input_field'), type the recipient's name, tap the matching chat row, tap the message 'input_field', type the message, and tap Send.
   - STORY / STATUS WATCHING GOAL (e.g. "story dikhao", "story dekho"):
     * First check the live tree on the current screen for a visible story item matching the person's name (e.g. "<name>'s story") — if visible, tap it directly.
     * If not visible in the current story tray, go to Messages / Inbox (or normal Search) -> search the person -> open their chat/profile -> tap their story avatar/ring/View Story element in the tree. NEVER tap unrelated feed posts or ads.
   - FOLLOW / OPEN A NEW ACCOUNT OR PROFILE GOAL (e.g. "follow karo", "account kholo"):
     * Tap the normal Search / Explore tab or icon from the navigation bar (NOT Messages).
     * Tap the Search bar first if it is labeled 'button', wait for an 'input_field' to appear, then type the account/username.
     * Tap the matching account row in results (switch to an "Accounts" tab/filter if only posts grid appears), open their profile, and tap "Follow" (or "Follow Back"). If it already says "Following" or "Requested", do NOT tap again — choose "done".
   - REELS / SHORTS / VIDEO TAB GOAL (e.g. "reel chalao", "reels dekho", "next reel"):
     * First check the live tree to see if the Reels / Videos tab is already active (marked 'selected') or already showing a Reels player.
     * If NOT on the Reels tab yet, tap the "Reels" (or Video/Shorts) tab/button FIRST instead of scrolling Home photos. Once on Reels, scroll if asked or choose "done".
   - GENERAL MEDIA / MOVIES / SHOWS / ITEMS:
     * Prefer Search over endless scrolling; tap the matching item/show card and tap Play / Resume to start playback.

2. INPUT FIELDS & SCREEN NAVIGATION SAFETY:
   - ONLY call "type_tag" on an element labeled 'input_field'. If a search box or text box shows as 'button', call "tap_tag" FIRST so the editable 'input_field' opens on the next turn.
   - NEVER tap random feed posts, comments, or generic empty wrappers. If an unintended post/popup covers the screen and main navigation tabs/buttons are missing, use "back" to return.
   - Never repeat the exact same failed action consecutively — adapt directly from the updated UI tree.

3. CONTENT CREATION & NOTES (TABLES, LISTS, MEMOS):
   - When Sir's goal is to create or write structured content (e.g. a table, list, notes, checklist, memo):
   - Do NOT get trapped clicking proprietary formatting buttons, table grid wizards, or column add dialogs.
   - Construct the COMPLETE, cleanly formatted content (Markdown or aligned text table, bullet list, etc.) directly inside the "text" field of "type_tag" and inject it into the note's main 'input_field' in one clean action!
   - Once typed, tap a Save/Done/Tick button if needed, or choose "done".

4. VERIFY COMPLETION OR NOT FOUND:
   - Confirm from the latest Live Screen UI Tree Elements that the message sent, story opened, follow button changed to Following/Requested, or reel is active before choosing "done".
   - If the target contact, account, story, or show is genuinely not found after searching, choose "done" with an honest spoken Hinglish summary (e.g. "Anuj nahi mila Sir").

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
  "text": "<text to type, or full generated table/list/note content, or null>",
  "direction": "down" | "up" | null,
  "summary": "<When continuing: short step log. When done: a single, natural, clean sentence in spoken Hinglish to Sir confirming what happened, e.g. 'Instagram par Anuj ko hello bhej diya hai Sir' or 'Reels chala di hain Sir'. NEVER include robotic words like 'goal complete', 'execute', or app prefixes.>"
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
