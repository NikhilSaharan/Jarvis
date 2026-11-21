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
            delay(500)
            val closeBtn = service.findClickableNode("Close") ?: service.findClickableNode("close")
            if (closeBtn != null) {
                service.clickNode(closeBtn)
                delay(200)
            }

            // In WhatsApp chat toolbar:
            // Video Call button: id has "video_call", cd is "Video call"
            // Voice/Audio Call button: id has "voice_call", cd is "Voice call"
            val callBtn = service.pollForNode(timeoutMs = 4500) { node ->
                val resId = node.viewIdResourceName?.lowercase() ?: ""
                val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""

                if (isVideo) {
                    // MUST be a video call button
                    resId.contains("video_call") || cd.contains("video call") || cd == "video" || text.contains("video call")
                } else {
                    // MUST be an audio/voice call button, and strictly NOT video!
                    val isVoice = resId.contains("voice_call") || cd.contains("voice call") || cd.contains("audio call") ||
                            resId.endsWith(":id/voice_call") || cd == "voice call" || text.contains("voice call")
                    val isGenericCall = (cd == "call" || text == "call" || resId.endsWith(":id/call"))
                    val isNotVideo = !resId.contains("video") && !cd.contains("video") && !text.contains("video")
                    (isVoice || isGenericCall) && isNotVideo
                }
            }

            if (callBtn != null) {
                val clicked = service.tapElement(callBtn)
                if (clicked) {
                    delay(700)
                    // Auto-confirm if WhatsApp shows confirmation dialog:
                    // Dialog can say "Start voice call? -> Call" or "Start video call? -> Video call"
                    val confirmBtn = service.findNodeById("android:id/button1")
                        ?: service.findClickableNode(if (isVideo) "Video call" else "Call")
                        ?: service.findClickableNode("CALL")
                        ?: service.findClickableNode("Call")
                        ?: service.findNodeByPredicate { n ->
                            val nText = n.text?.toString()?.lowercase() ?: ""
                            val nCd = n.contentDescription?.toString()?.lowercase() ?: ""
                            n.isClickable && n != callBtn && (nText.contains("call") || nCd.contains("call") || nText.contains("start"))
                        }

                    if (confirmBtn != null && confirmBtn != callBtn) {
                        service.tapElement(confirmBtn)
                    }

                    val callTypeStr = if (isVideo) "video" else "audio"
                    callback(true, "WhatsApp par $displayName ko $callTypeStr call laga di hai Sir.")
                    return@launch
                }
            }

            val callTypeStr = if (isVideo) "video" else "audio"
            callback(false, "WhatsApp par $callTypeStr call button nahi mil saka Sir.")
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

            delay(800)
            // Poll for Send button by Resource ID or Description (up to 5.0 seconds)
            val sendBtn = service.pollForNode(timeoutMs = 5000) { node ->
                val resId = node.viewIdResourceName?.lowercase() ?: ""
                val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                val text = node.text?.toString()?.lowercase() ?: ""

                resId.contains("send") || cd == "send" || cd == "भेजें" || cd.contains("bhej") || text == "send"
            }

            if (sendBtn != null) {
                service.tapElement(sendBtn)
                delay(300)
                service.tapElement(sendBtn)
                callback(true, "WhatsApp par $displayName ko message bhej diya hai Sir.")
                return@launch
            }

            // Fallback: If not sent via pre-fill, type into input field and click Send
            val inputField = service.pollForNode(timeoutMs = 2500) { node ->
                val resId = node.viewIdResourceName?.lowercase() ?: ""
                val text = node.text?.toString() ?: ""
                node.isEditable || resId.contains("entry") || text.contains("Message", ignoreCase = true)
            }

            if (inputField != null) {
                service.typeText(inputField, message)
                delay(400)
                val retrySend = service.pollForNode(timeoutMs = 2500) { node ->
                    val resId = node.viewIdResourceName?.lowercase() ?: ""
                    val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                    resId.contains("send") || cd.contains("send") || cd.contains("भेजें")
                }
                if (retrySend != null) {
                    service.tapElement(retrySend)
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

    /**
     * Swipes / scrolls the active screen up or down.
     */
    fun scrollScreen(direction: String = "down", callback: (Boolean, String) -> Unit) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            callback(false, "Accessibility Service active nahi hai Sir.")
            return
        }
        val scrolled = service.scroll(direction)
        callback(scrolled, if (scrolled) "Screen scroll kar di hai Sir." else "Scroll execute nahi ho saka.")
    }
}
