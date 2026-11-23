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
            val maxTurns = 8
            var turn = 0
            var finalMsg = "Ho gaya hai Sir."
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

OPERATING PRINCIPLES:
1. CONTINUOUS EXECUTION TO GOAL:
   - Intermediate screens (such as search bars, search result lists, navigation menus, or category feeds) are NOT the final goal.
   - Actively interact with the relevant item, button, card, or content on screen to completely fulfill Sir's requested goal.
   - Return "done" ONLY when Sir's requested goal has actually been fulfilled on screen.
2. DYNAMIC CONFIRMATION:
   - When the goal is completed on screen, return "done" and provide a short, natural confirmation in casual Hinglish in `summary`.
   - Never recite technical instruction strings or app names.

Decide the best next single action:
- "tap_tag": Tap a numbered element [tag] (to select an item, button, card, or tab).
- "type_tag": Type text into an input field [tag] (to enter a search, note, or text).
- "scroll_screen": Scroll screen down or up (to reveal more items, browse, or scroll).
- "back": Press hardware back button (to dismiss overlay or popup).
- "done": Goal is completely achieved on screen.

OUTPUT ONLY A RAW JSON OBJECT (no markdown, no backticks):
{
  "action": "tap_tag" | "type_tag" | "scroll_screen" | "back" | "done",
  "tag": <number or null>,
  "text": "<text to type or null>",
  "direction": "down" | "up" | null,
  "summary": "<short description in casual Hinglish>"
}
""".trimIndent()

                val decision = geminiBrain.decideUIAction(prompt)
                val summary = decision.summary ?: decision.text ?: decision.action

                when (decision.action.lowercase().trim()) {
                    "done" -> {
                        isCompleted = true
                        finalMsg = if (summary.isNotBlank() && summary != "done") {
                            summary
                        } else {
                            "Ho gaya hai Sir."
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
