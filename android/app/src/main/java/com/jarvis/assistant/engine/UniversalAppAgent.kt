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
You are acting as an autonomous smartphone user in $appName.
Screen UI Elements:
$screenSummary
$historyStr

Sir's Overall Goal: "$instruction"

SEARCH, NAVIGATION & EXECUTION RULES:
1. PREFER SEARCH OVER SCROLLING: When looking for a specific contact, person, chat, show, video, profile, or item, ALWAYS tap the Search bar or Search icon and type the name directly instead of scrolling endlessly through lists.
2. FINALIZE GOAL:
   - For chat/messaging: Open the person's chat, type the message into the text box, and tap the Send button.
   - For media/shows/video: Tap on the show/movie card or play/resume button to start playback.
   - For social/following: Tap on the target profile or Follow/Subscribe button.
3. CONTENT CREATION & NOTES (TABLES, LISTS, MEMOS):
   - When Sir's goal is to create or write content (e.g. a table, list, notes, checklist, memo):
   - DO NOT get trapped clicking proprietary formatting buttons, table grid wizards, or column add dialogs.
   - As an intelligent AI, CONSTRUCT the entire, beautifully structured content (using clean Markdown or aligned text table, bullet list, or formatted notes) directly inside the "text" field of "type_tag".
   - Tap the note/document editor field and type the complete generated content in ONE clean action!
   - Once content is typed, if there is a save/tick/done button tap it, or choose "done".
4. WHEN NOT FOUND: If the target contact, item, or show is not found after searching, choose "done" with an honest summary in natural Hinglish (e.g. "Anuj nahi mila Sir").

Decide the best next single action:
- "tap_tag": Tap a numbered element [tag] (e.g. search icon / search bar, create note button, contact/chat name, send button, play button).
- "type_tag": Type text into an input field [tag] (e.g. search query, chat message, or full generated note/table content).
- "scroll_screen": Scroll the active screen down or up (use only if search is unavailable).
- "back": Press the hardware back button (e.g. to dismiss a popup, exit an overlay, or return).
- "done": Goal is completely achieved on screen, or target was not found.

OUTPUT ONLY A RAW JSON OBJECT (no markdown, no backticks):
{
  "action": "tap_tag" | "type_tag" | "scroll_screen" | "back" | "done",
  "tag": <number or null>,
  "text": "<text to type, or full generated table/list/note content, or null>",
  "direction": "down" | "up" | null,
  "summary": "<When continuing: short step log. When done: a single, natural, clean sentence in spoken Hinglish to Sir confirming what happened, e.g. 'Notes me Prashant, Nikhil aur Akhil ka table bana diya hai Sir' or 'Anuj ko hello bhej diya hai Sir'. NEVER include robotic words like 'goal complete', 'execute', or app prefixes.>"
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
                        val preview = if (textToType.length > 40) textToType.take(40).replace("\n", " ") + "..." else textToType
                        actionHistory.add("Typed '$preview' into element [$targetTag]")
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
