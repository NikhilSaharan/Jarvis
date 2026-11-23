package com.jarvis.assistant.engine

import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisAccessibilityService
import com.jarvis.assistant.brain.GeminiBrain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        val openedMsg = systemController.openApp(appName)
        if (openedMsg.contains("nahi mila")) {
            callback(false, openedMsg)
            return
        }

        mainScope.launch {
            delay(1200) // Initial wait for app window to render
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            val actionHistory = mutableListOf<String>()
            val maxTurns = 6
            var turn = 0
            var finalMsg = "$appName me '$instruction' execute kar diya hai Sir."
            var isCompleted = false

            while (turn < maxTurns && !isCompleted) {
                turn++
                delay(1000) // Allow animations, screen transitions, or search loading to settle

                val screenSummary = service.dumpScreenElementsSummary()
                if (screenSummary.isBlank() || screenSummary.contains("empty")) {
                    delay(800)
                    continue
                }

                val historyStr = if (actionHistory.isNotEmpty()) {
                    "\nActions taken so far:\n" + actionHistory.joinToString("\n") { "- $it" }
                } else ""

                val prompt = """
You are acting as an autonomous smartphone user in $appName.
Screen UI Elements:
$screenSummary
$historyStr

Sir's Overall Goal: "$instruction"

Decide the best next single action:
- "tap_tag": Tap a numbered element [tag] (e.g. search icon, compose/new note (+), play button, movie card, reels tab, send, save, confirm).
- "type_tag": Type text into an input field [tag] (e.g. note body, table content, search query, message).
- "scroll_screen": Scroll the active screen down or up (e.g. to see next reel, scroll a feed, or find an off-screen item).
- "back": Press the hardware back button (e.g. to dismiss a popup, exit an overlay, or return).
- "done": Goal is completely achieved (e.g. note/table written, movie playback started, reels scrolled, action finalized).

OUTPUT ONLY A RAW JSON OBJECT (no markdown, no backticks):
{
  "action": "tap_tag" | "type_tag" | "scroll_screen" | "back" | "done",
  "tag": <number or null>,
  "text": "<text to type or null>",
  "direction": "down" | "up" | null,
  "summary": "<short description of this action in Hinglish, e.g. 'Search icon par tap kiya'>"
}
""".trimIndent()

                val decision = geminiBrain.decideUIAction(prompt)
                val summary = decision.summary ?: decision.text ?: decision.action

                when (decision.action.lowercase().trim()) {
                    "done" -> {
                        isCompleted = true
                        finalMsg = if (summary.isNotBlank() && summary != "done") {
                            "$appName: $summary"
                        } else {
                            "$appName me '$instruction' pura kar diya hai Sir."
                        }
                    }
                    "tap_tag" -> {
                        val targetTag = decision.tag ?: 1
                        nodeActionEngine.tapByTag(targetTag) { _, _ -> }
                        actionHistory.add("Tapped element [$targetTag] ($summary)")
                        delay(600)
                    }
                    "type_tag" -> {
                        val targetTag = decision.tag ?: 1
                        val textToType = decision.text ?: instruction
                        nodeActionEngine.typeTextByTag(targetTag, textToType) { _, _ -> }
                        actionHistory.add("Typed '$textToType' into element [$targetTag]")
                        delay(600)
                    }
                    "scroll_screen" -> {
                        val dir = decision.direction ?: "down"
                        service.scroll(dir)
                        actionHistory.add("Scrolled screen $dir")
                        delay(800)
                    }
                    "back" -> {
                        service.performBack()
                        actionHistory.add("Pressed back button")
                        delay(600)
                    }
                    else -> {
                        isCompleted = true
                    }
                }
            }

            callback(true, finalMsg)
        }
    }
}
