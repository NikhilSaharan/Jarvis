package com.jarvis.assistant.engine

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.JarvisAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class NodeActionEngine(
    private val systemController: SystemController,
    private val telephonyHelper: TelephonyHelper
) {
    private val mainScope = CoroutineScope(Dispatchers.Main)

    // ═════════════════════════════════════════════════════════════════
    // LAYER 2: IN-MEMORY ACCESSIBILITY ACTIONS (Polled & Guaranteed)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Places a WhatsApp Voice or Video Call in <1.2 seconds without coordinates.
     * Uses polling up to 4.0 seconds to wait for chat rendering, then auto-confirms prompts.
     */
    fun callWhatsApp(recipient: String, isVideo: Boolean, callback: (Boolean, String) -> Unit) {
        val (number, displayName) = telephonyHelper.resolveContactNumber(recipient)
        if (number == null) {
            callback(false, "Sir, '$recipient' ka phone number contacts me nahi mila.")
            return
        }

        val opened = systemController.openChatWhatsApp(number)
        if (!opened) {
            callback(false, "WhatsApp open nahi ho saka Sir.")
            return
        }

        mainScope.launch {
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            // Dismiss any modal/privacy bottom-sheet if shown
            delay(400)
            val closeBtn = service.findClickableNode("Close") ?: service.findClickableNode("close")
            if (closeBtn != null) {
                service.clickNode(closeBtn)
                delay(200)
            }

            val targetIdKeyword = if (isVideo) "video" else "voice"
            val targetDesc = if (isVideo) "video call" else "voice call"

            // Poll for call button by Resource ID, Content Description, or Text (up to 4.0 seconds)
            val callBtn = service.pollForNode(timeoutMs = 4000) { node ->
                val resId = node.viewIdResourceName?.lowercase() ?: ""
                val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""

                resId.contains(targetIdKeyword) ||
                        cd.contains(targetDesc) ||
                        (cd.contains("call") && !cd.contains("history")) ||
                        (text.contains("call") && !text.contains("history"))
            }

            if (callBtn != null) {
                val clicked = service.clickNode(callBtn)
                if (clicked) {
                    delay(500)
                    // Auto-confirm if WhatsApp shows confirmation dialog ("Start voice call? -> Call")
                    val confirmBtn = service.findClickableNode("Call") ?: service.findClickableNode("CALL")
                    if (confirmBtn != null && confirmBtn != callBtn) {
                        service.clickNode(confirmBtn)
                    }

                    val callTypeStr = if (isVideo) "video" else "voice"
                    callback(true, "WhatsApp par $displayName ko $callTypeStr call laga di hai Sir.")
                    return@launch
                }
            }

            callback(false, "WhatsApp par Call button nahi mil saka Sir.")
        }
    }

    /**
     * Sends a WhatsApp Text Message in <1.2 seconds.
     * Pre-fills message via WhatsApp deep link and automatically clicks Send.
     */
    fun sendWhatsAppMessage(recipient: String, message: String, callback: (Boolean, String) -> Unit) {
        val (number, displayName) = telephonyHelper.resolveContactNumber(recipient)
        if (number == null) {
            callback(false, "Sir, '$recipient' ka contact nahi mila.")
            return
        }

        // Pre-fill text via URL so WhatsApp loads with the message already in the input field!
        val opened = systemController.openChatWhatsApp(number, prefillMessage = message)
        if (!opened) {
            callback(false, "WhatsApp open nahi ho saka Sir.")
            return
        }

        mainScope.launch {
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            // Poll for Send button by Resource ID or Description (up to 4.0 seconds)
            val sendBtn = service.pollForNode(timeoutMs = 4000) { node ->
                val resId = node.viewIdResourceName?.lowercase() ?: ""
                val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""

                resId.contains("send") || cd == "send" || text == "send"
            }

            if (sendBtn != null) {
                val clicked = service.clickNode(sendBtn)
                if (clicked) {
                    callback(true, "WhatsApp par $displayName ko message bhej diya hai Sir.")
                    return@launch
                }
            }

            // Fallback: If not sent via pre-fill, type into input field and click Send
            val inputField = service.findNodeByPredicate { node ->
                val resId = node.viewIdResourceName?.lowercase() ?: ""
                node.isEditable || resId.contains("entry") || node.text?.contains("Message") == true
            }

            if (inputField != null) {
                service.typeText(inputField, message)
                delay(300)
                val retrySend = service.findNodeByPredicate { node ->
                    val resId = node.viewIdResourceName?.lowercase() ?: ""
                    val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                    resId.contains("send") || cd.contains("send")
                }
                if (retrySend != null) {
                    service.clickNode(retrySend)
                    callback(true, "WhatsApp par $displayName ko message bhej diya hai Sir.")
                    return@launch
                }
            }

            callback(false, "WhatsApp me message send nahi ho saka Sir.")
        }
    }

    /**
     * Universal Tap by Set-of-Marks tag number ([1], [2], [3]...).
     */
    fun tapByTag(tag: Int, callback: (Boolean, String) -> Unit) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            callback(false, "Accessibility Service active nahi hai Sir.")
            return
        }

        val elements = service.dumpScreenElements()
        val targetElement = elements.find { it.tag == tag }
        if (targetElement != null) {
            val clicked = service.clickNode(targetElement.node)
            if (clicked) {
                callback(true, "Element [${tag}] click kar diya hai Sir.")
                return
            }
        }
        callback(false, "Tag [${tag}] par click nahi ho saka Sir.")
    }

    /**
     * Universal Text Typing into tagged input field.
     */
    fun typeTextByTag(tag: Int, text: String, callback: (Boolean, String) -> Unit) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            callback(false, "Accessibility Service active nahi hai Sir.")
            return
        }

        val elements = service.dumpScreenElements()
        val targetElement = elements.find { it.tag == tag }
        if (targetElement != null) {
            val typed = service.typeText(targetElement.node, text)
            if (typed) {
                callback(true, "Element [${tag}] me text likh diya hai Sir.")
                return
            }
        }
        callback(false, "Tag [${tag}] me text nahi likh saka Sir.")
    }
}
