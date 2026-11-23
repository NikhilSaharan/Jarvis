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
            var finalMsg = ""
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
You are an autonomous smartphone agent navigating $appName based purely on the Live Screen UI Elements tree.
Screen UI Elements:
$screenSummary
$historyStr

Sir's Goal: "$instruction"

TREE-BASED NAVIGATION & EXECUTION RULES:
1. SCREEN & SECTION NAVIGATION:
   - Inspect the current Screen UI Elements tree carefully.
   - If the controls or items needed for the goal are not present on the current screen, look at the visible navigation elements (tabs, navigation buttons, search icons, back button) in the tree to navigate to the relevant section of the app.
2. MULTI-STEP ACTIONS & COMPLETION:
   - Typing into a search or text input is only an intermediate step. NEVER choose "done" immediately after typing.
   - After typing, inspect the resulting elements in the next turn, tap the matching target item from the list/tree to open it, and then tap the final required action button (e.g. follow, send, play, add).
   - Choose "done" ONLY when the final action has actually been executed on screen.
3. SCROLLING:
   - Use "scroll_screen" with direction "down" to reveal next content/items/reels.
   - Use "scroll_screen" with direction "up" to reveal previous content/items/reels.
4. TARGET NOT FOUND:
   - If after searching or inspecting the tree the target does not exist, choose "done" with an honest, natural sentence (e.g. target nahi mila Sir).

Decide the best next single action:
- "tap_tag": Tap a numbered element [tag] from the Screen UI Elements tree.
- "type_tag": Type text into a numbered input field [tag] from the Screen UI Elements tree.
- "scroll_screen": Scroll the active screen ("down" for next content, "up" for previous content).
- "back": Press the hardware back button.
- "done": Goal is fully completed on screen, or target was not found.

OUTPUT ONLY A RAW JSON OBJECT (no markdown, no backticks):
{
  "action": "tap_tag" | "type_tag" | "scroll_screen" | "back" | "done",
  "tag": <number or null>,
  "text": "<text to type or null>",
  "direction": "down" | "up" | null,
  "summary": "<When continuing: brief step log. When done: a single, natural sentence in conversational spoken Hinglish to Sir confirming what was done. NEVER use robotic words like 'goal complete' or 'execute'.>"
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
