package com.jarvis.assistant.engine

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.util.Log
import com.jarvis.assistant.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UniversalAppAgent(
    private val context: Context,
    private val systemController: SystemController
) {
    private val mainScope = CoroutineScope(Dispatchers.Main)

    /**
     * Autonomous Multi-Step In-App UI Agent:
     * Opens target app, finds Add/New/Compose/Search button, clicks it, types text, and saves!
     */
    fun performAppAction(
        appName: String,
        action: String,
        textToEnter: String = "",
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

            // 1. Look for Add / New / Compose / Edit / Floating Action Button / Search
            val isSearch = action.contains("search") || action.contains("find") || action.contains("dhundo")
            val actionBtn = service.pollForNode(timeoutMs = 3500) { node ->
                val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""
                val resId = node.viewIdResourceName?.lowercase() ?: ""

                if (isSearch) {
                    desc.contains("search") || text.contains("search") || resId.contains("search")
                } else {
                    desc.contains("add") || desc.contains("new") || desc.contains("create") ||
                            desc.contains("compose") || desc.contains("note") || desc == "+" ||
                            text.contains("add") || text.contains("new") || text.contains("create") ||
                            text.contains("compose") || text.contains("note") || text == "+" ||
                            resId.contains("add") || resId.contains("fab") || resId.contains("create") ||
                            resId.contains("new") || resId.contains("compose")
                }
            }

            if (actionBtn != null) {
                service.tapElement(actionBtn)
                delay(700)
            }

            // 2. Type text if provided
            if (textToEnter.isNotBlank()) {
                val inputField = service.pollForNode(timeoutMs = 3500) { node ->
                    node.isEditable || node.className?.contains("EditText") == true
                }

                if (inputField != null) {
                    service.typeText(inputField, textToEnter)
                    delay(500)

                    // 3. Look for Done / Save / Check button
                    val saveBtn = service.findNodeByPredicate { node ->
                        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
                        val text = node.text?.toString()?.lowercase() ?: ""
                        val resId = node.viewIdResourceName?.lowercase() ?: ""

                        desc.contains("save") || desc.contains("done") || desc.contains("check") || desc.contains("tick") ||
                                text.contains("save") || text.contains("done") ||
                                resId.contains("save") || resId.contains("done") || resId.contains("check")
                    }
                    if (saveBtn != null) {
                        service.tapElement(saveBtn)
                    } else {
                        // Many Android note apps (Keep, OnePlus Notes) save automatically when pressing BACK
                        delay(300)
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }

                    callback(true, "$appName me '$textToEnter' save kar diya hai Sir.")
                    return@launch
                }
            }

            callback(true, "$appName open karke action execute kar diya hai Sir.")
        }
    }
}
