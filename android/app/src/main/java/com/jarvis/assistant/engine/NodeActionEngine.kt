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
    // LAYER 2: IN-MEMORY ACCESSIBILITY ACTIONS (5 Milliseconds Speed)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Places a WhatsApp Voice or Video Call in <1.0 second without coordinates.
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
            // Wait 600ms for chat screen to render
            delay(600)
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            // Auto-dismiss any privacy sheet if present
            val closeBtn = service.findClickableNode("Close") ?: service.findClickableNode("close")
            if (closeBtn != null) {
                service.clickNode(closeBtn)
                delay(200)
            }

            val targetDesc = if (isVideo) "Video call" else "Voice call"
            val callBtn = service.findClickableNode(targetDesc)
                ?: service.findClickableNode(if (isVideo) "video call" else "voice call")
                ?: service.findClickableNode(if (isVideo) "Video" else "Voice")
                ?: service.findClickableNode("Call")

            if (callBtn != null) {
                val clicked = service.clickNode(callBtn)
                if (clicked) {
                    delay(500)
                    // Auto-click confirmation dialog if WhatsApp shows "Start voice call? -> Call"
                    val confirmBtn = service.findClickableNode("Call") ?: service.findClickableNode("CALL")
                    if (confirmBtn != null && confirmBtn != callBtn) {
                        service.clickNode(confirmBtn)
                    }
                    val callTypeStr = if (isVideo) "video" else "voice"
                    callback(true, "WhatsApp par $displayName ko $callTypeStr call laga di hai Sir.")
                    return@launch
                }
            }

            callback(false, "WhatsApp par $targetDesc button nahi mil saka Sir.")
        }
    }

    /**
     * Sends a WhatsApp Text Message in <1.2 seconds.
     */
    fun sendWhatsAppMessage(recipient: String, message: String, callback: (Boolean, String) -> Unit) {
        val (number, displayName) = telephonyHelper.resolveContactNumber(recipient)
        if (number == null) {
            callback(false, "Sir, '$recipient' ka contact nahi mila.")
            return
        }

        val opened = systemController.openChatWhatsApp(number)
        if (!opened) {
            callback(false, "WhatsApp open nahi ho saka Sir.")
            return
        }

        mainScope.launch {
            delay(600)
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            // Step 1: Find message input field
            val inputField = service.findFirstInputField() ?: service.findClickableNode("Message")
            if (inputField != null) {
                service.typeText(inputField, message)
                delay(250)

                // Step 2: Find Send button
                val sendBtn = service.findClickableNode("Send")
                if (sendBtn != null) {
                    service.clickNode(sendBtn)
                    callback(true, "WhatsApp par $displayName ko message bhej diya hai Sir.")
                    return@launch
                }
            }

            callback(false, "WhatsApp me message type ya send nahi ho saka Sir.")
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
                callback(true, "Text enter kar diya hai Sir.")
                return
            }
        }
        callback(false, "Tag [${tag}] me text enter nahi ho saka Sir.")
    }
}
