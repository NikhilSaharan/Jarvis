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
     * Autonomous Vision/Tree-Based In-App Agent:
     * 1. Launches target app.
     * 2. Dumps live accessibility UI elements tree.
     * 3. Uses Gemini to inspect the screen elements and decide which tag to tap/type.
     * 4. Executes the action dynamically without ANY hardcoded button keywords!
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
            // Wait for app window to render
            delay(1200)
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            // Step 1: Dump live screen elements
            val screenSummary = service.dumpScreenElementsSummary()
            if (screenSummary.isBlank() || screenSummary.contains("empty")) {
                callback(true, "$appName open kar diya hai Sir.")
                return@launch
            }

            // Step 2: Let Gemini inspect the live screen tree and pick the element tag
            val prompt = """
You are acting as an autonomous smartphone user in $appName.
Screen UI Elements:
$screenSummary

Sir's Goal: "$instruction"

Decide what to do:
- If a button needs to be tapped (e.g. to create, add, compose, open menu, search, or select an item), choose its tag.
- If text needs to be typed into an input field, specify the input field tag and the text.
- If screen needs to be scrolled, set action to scroll_screen.

OUTPUT ONLY JSON:
{
  "action": "tap_tag" | "type_tag" | "scroll_screen" | "done",
  "tag": <number or null>,
  "text": "<text to type or null>",
  "direction": "down" | "up" | null
}
""".trimIndent()

            val decision = geminiBrain.decideUIAction(prompt)
            when (decision.action) {
                "tap_tag" -> {
                    val targetTag = decision.tag ?: 1
                    nodeActionEngine.tapByTag(targetTag) { tapped, msg ->
                        if (tapped && !decision.text.isNullOrBlank()) {
                            // If text is to be typed on the resulting screen (e.g. new note body/title)
                            mainScope.launch {
                                delay(800)
                                val nextSummary = service.dumpScreenElementsSummary()
                                val nextPrompt = """
Screen in $appName now shows:
$nextSummary

Task: Type "${decision.text}" into the appropriate note, body, or text field.
OUTPUT ONLY JSON:
{
  "action": "type_tag",
  "tag": <number of the input field>
}
""".trimIndent()
                                val nextDecision = geminiBrain.decideUIAction(nextPrompt)
                                val inputTag = nextDecision.tag ?: 1
                                nodeActionEngine.typeTextByTag(inputTag, decision.text) { typed, _ ->
                                    callback(typed, "$appName me '${decision.text}' add kar diya hai Sir.")
                                }
                            }
                        } else {
                            callback(tapped, msg)
                        }
                    }
                }
                "type_tag" -> {
                    val targetTag = decision.tag ?: 1
                    nodeActionEngine.typeTextByTag(targetTag, decision.text ?: instruction) { typed, _ ->
                        callback(typed, "$appName me text type kar diya hai Sir.")
                    }
                }
                "scroll_screen" -> {
                    val dir = decision.direction ?: "down"
                    val scrolled = service.scroll(dir)
                    callback(scrolled, if (scrolled) "Screen scroll kar di hai Sir." else "Scroll nahi ho saka.")
                }
                else -> {
                    callback(true, "$appName me task execute kar diya hai Sir.")
                }
            }
        }
    }
}
