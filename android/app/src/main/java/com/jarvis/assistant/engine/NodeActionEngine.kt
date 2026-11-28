package com.jarvis.assistant.engine

import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.JarvisAccessibilityService
import com.jarvis.assistant.security.AutoUnlockManager
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

        mainScope.launch {
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            // Unlock phone screen if locked before launching WhatsApp
            if (!AutoUnlockManager.ensureScreenUnlocked(systemController.appContext)) {
                callback(false, "Sir, phone screen unlock nahi ho saki.")
                return@launch
            }

            val opened = systemController.openChatWhatsApp(number)
            if (!opened) {
                callback(false, "WhatsApp open nahi ho saka Sir.")
                return@launch
            }

            // Fast event-driven wait (45ms ticks) handles App Lock, modal dismiss, and call button
            var retriedLaunch = false
            val waitStart = System.currentTimeMillis()
            var callBtn: AccessibilityNodeInfo? = null
            while (System.currentTimeMillis() - waitStart < 5200L) {
                val elapsed = System.currentTimeMillis() - waitStart
                if (AutoUnlockManager.isScreenLockedOrOff(systemController.appContext)) {
                    AutoUnlockManager.ensureScreenUnlocked(systemController.appContext)
                    continue
                }
                if (AutoUnlockManager.isAppLockScreen(service)) {
                    AutoUnlockManager.unlockAppLockIfPresent(systemController.appContext)
                    delay(60)
                    continue
                }
                val closeBtn = service.findClickableNode("Close") ?: service.findClickableNode("close")
                if (closeBtn != null) {
                    service.clickNode(closeBtn)
                    delay(60)
                    continue
                }

                callBtn = service.findNodeByPredicate { node ->
                    val resId = node.viewIdResourceName?.lowercase() ?: ""
                    val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                    val text = node.text?.toString()?.lowercase() ?: ""

                    if (isVideo) {
                        resId.contains("video_call") || cd.contains("video call") || cd == "video" || text.contains("video call")
                    } else {
                        val isVoice = resId.contains("voice_call") || cd.contains("voice call") || cd.contains("audio call") ||
                                resId.endsWith(":id/voice_call") || cd == "voice call" || text.contains("voice call")
                        val isGenericCall = (cd == "call" || text == "call" || resId.endsWith(":id/call"))
                        val isNotVideo = !resId.contains("video") && !cd.contains("video") && !text.contains("video")
                        (isVoice || isGenericCall) && isNotVideo
                    }
                }
                if (callBtn != null) break

                val activePkg = service.rootInActiveWindow?.packageName?.toString()?.lowercase().orEmpty()
                if (!retriedLaunch && elapsed > 1800L && !activePkg.contains("whatsapp")) {
                    retriedLaunch = true
                    systemController.openChatWhatsApp(number)
                }
                delay(45)
            }

            if (callBtn != null) {
                val clicked = service.tapElement(callBtn)
                if (clicked) {
                    val confirmBtn = service.pollForNode(timeoutMs = 900L, intervalMs = 45L) { n ->
                        val nId = n.viewIdResourceName?.lowercase() ?: ""
                        val nText = n.text?.toString()?.lowercase() ?: ""
                        val nCd = n.contentDescription?.toString()?.lowercase() ?: ""
                        n != callBtn && (nId == "android:id/button1" ||
                            (n.isClickable && (nText.contains("call") || nCd.contains("call") || nText.contains("start"))))
                    }
                    if (confirmBtn != null) {
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

        mainScope.launch {
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            // Unlock phone screen if locked before launching WhatsApp
            if (!AutoUnlockManager.ensureScreenUnlocked(systemController.appContext)) {
                callback(false, "Sir, phone screen unlock nahi ho saki.")
                return@launch
            }

            // Pre-fill text via native whatsapp:// URI so chat opens immediately with text ready
            val opened = systemController.openChatWhatsApp(number, prefillMessage = message)
            if (!opened) {
                callback(false, "WhatsApp open nahi ho saka Sir.")
                return@launch
            }

            // Unified 45ms dynamic loop: handles AppLock at ANY millisecond, retries dropped lockscreen intent,
            // auto-types only if prefill text is absent, and clicks genuine Send button immediately.
            var retriedLaunch = false
            var typedFallback = false
            val waitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - waitStart < 5600L) {
                val elapsed = System.currentTimeMillis() - waitStart
                if (AutoUnlockManager.isScreenLockedOrOff(systemController.appContext)) {
                    AutoUnlockManager.ensureScreenUnlocked(systemController.appContext)
                    continue
                }
                if (AutoUnlockManager.isAppLockScreen(service)) {
                    AutoUnlockManager.unlockAppLockIfPresent(systemController.appContext)
                    delay(60)
                    continue
                }

                val sendBtn = service.findNodeByPredicate { isWhatsAppSendButton(it) }
                if (sendBtn != null) {
                    // Single click ONLY: swaps with Voice/Mic button at same coordinates upon sending!
                    service.clickNode(sendBtn)
                    val sentStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - sentStart < 300L) {
                        if (service.findNodeByPredicate { isWhatsAppSendButton(it) } == null) break
                        delay(45)
                    }
                    val stillSending = service.findNodeByPredicate { isWhatsAppSendButton(it) }
                    if (stillSending != null) {
                        val b = Rect()
                        stillSending.getBoundsInScreen(b)
                        if (b.width() > 0 && b.height() > 0) {
                            service.tapCoordinatesAwait(b.centerX().toFloat(), b.centerY().toFloat(), 45L)
                        }
                    }
                    callback(true, "WhatsApp par $displayName ko message bhej diya hai Sir.")
                    return@launch
                }

                // If WhatsApp chat is open with empty input after 1200ms, inject message directly
                if (!typedFallback && elapsed > 1200L) {
                    val inputField = service.findNodeByPredicate { node ->
                        val resId = node.viewIdResourceName?.lowercase() ?: ""
                        node.isEditable && (resId.contains("entry") || (node.packageName?.toString()?.contains("whatsapp", true) == true))
                    }
                    if (inputField != null) {
                        val entryText = inputField.text?.toString()?.trim().orEmpty()
                        val isHintOnly = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && inputField.isShowingHintText
                        if (entryText.isEmpty() || isHintOnly || entryText.equals("Message", ignoreCase = true)) {
                            typedFallback = true
                            service.typeText(inputField, message)
                            delay(50)
                            continue
                        }
                    }
                }

                val activePkg = service.rootInActiveWindow?.packageName?.toString()?.lowercase().orEmpty()
                if (!retriedLaunch && elapsed > 1800L && !activePkg.contains("whatsapp")) {
                    retriedLaunch = true
                    systemController.openChatWhatsApp(number, prefillMessage = message)
                }

                delay(45)
            }

            callback(false, "WhatsApp me message send nahi ho saka Sir.")
        }
    }

    /**
     * Strictly matches WhatsApp's text Send button and rejects Voice Recording / Mic buttons
     * that occupy the identical screen position immediately after sending.
     */
    private fun isWhatsAppSendButton(node: AccessibilityNodeInfo): Boolean {
        val resId = node.viewIdResourceName?.lowercase() ?: ""
        val cd = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
        val text = node.text?.toString()?.lowercase()?.trim() ?: ""
        val cls = node.className?.toString()?.lowercase() ?: ""

        val isVoiceOrMic = resId.contains("voice") || resId.contains("mic") || resId.contains("audio") ||
                resId.contains("record") || resId.contains("ptt") ||
                cd.contains("voice") || cd.contains("mic") || cd.contains("audio") ||
                cd.contains("record") || cd.contains("वॉइस") || cd.contains("रिकॉर्ड")
        if (isVoiceOrMic) return false

        val isSendId = resId.endsWith(":id/send") || resId == "send"
        val isSendDesc = cd == "send" || cd == "भेजें" || (cd.contains("bhej") && node.isClickable)
        val isSendButtonText = text == "send" && cls.contains("button")

        return isSendId || isSendDesc || isSendButtonText
    }

    /**
     * Reads incoming WhatsApp messages fast without extra AI Vision/JSON hops:
     * Checks live WhatsApp chat rows (unread or latest) and enters a chat thread when needed
     * (either when a specific sender is requested or when unread message hides preview text).
     */
    fun readWhatsAppMessages(sender: String, callback: (Boolean, String) -> Unit) {
        val cleanSender = sender.trim()
        mainScope.launch {
            val service = JarvisAccessibilityService.instance
            if (service == null) {
                val recent = com.jarvis.assistant.JarvisAccessibilityService.getRecentWhatsAppNotices()
                if (recent.isNotEmpty()) {
                    val notice = if (cleanSender.isNotBlank()) {
                        recent.firstOrNull { it.sender.contains(cleanSender, ignoreCase = true) }
                    } else recent.firstOrNull()
                    if (notice != null) {
                        callback(true, "WhatsApp par ${notice.sender} ka message hai: ${notice.message}")
                        return@launch
                    }
                }
                callback(false, "Accessibility Service active nahi hai Sir.")
                return@launch
            }

            if (!AutoUnlockManager.ensureScreenUnlocked(systemController.appContext)) {
                callback(false, "Sir, phone screen unlock nahi ho saki.")
                return@launch
            }

            // If caller wants a specific person and has a saved phone number, jump directly to their WhatsApp chat!
            if (cleanSender.isNotBlank()) {
                val (number, displayName) = telephonyHelper.resolveContactNumber(cleanSender)
                if (!number.isNullOrBlank()) {
                    systemController.openChatWhatsApp(number)
                    val threadReady = waitForWhatsAppChatScreen(service, 4800L)
                    if (threadReady) {
                        delay(150)
                        val msg = extractLatestWhatsAppChatText(service)
                        val reply = if (!msg.isNullOrBlank()) {
                            "WhatsApp par $displayName ka last message hai: $msg"
                        } else {
                            "$displayName ki WhatsApp chat khol di hai Sir, koi recent text message nahi dikha."
                        }
                        callback(true, reply)
                        return@launch
                    }
                }
            }

            // Ensure WhatsApp Chats list is visible
            val openMsg = systemController.openApp("WhatsApp")
            if (openMsg.contains("nahi mila")) {
                callback(false, openMsg)
                return@launch
            }

            val waitStart = System.currentTimeMillis()
            while (System.currentTimeMillis() - waitStart < 4500L) {
                if (AutoUnlockManager.isAppLockScreen(service)) {
                    AutoUnlockManager.unlockAppLockIfPresent(systemController.appContext)
                    delay(80)
                    continue
                }
                val pkg = service.rootInActiveWindow?.packageName?.toString()?.lowercase().orEmpty()
                if (pkg.contains("whatsapp")) {
                    val hasChatsRows = service.dumpScreenElements().any {
                        val r = it.node.viewIdResourceName ?: ""
                        r.contains("contact_row_container", ignoreCase = true)
                    }
                    if (hasChatsRows) break
                    // If WhatsApp opened inside an old chat thread, navigate back to Chats inbox
                    val inChatThread = service.dumpScreenElements().any {
                        val r = it.node.viewIdResourceName ?: ""
                        r.endsWith(":id/entry") || r.contains("conversation_contact_name", ignoreCase = true)
                    }
                    if (inChatThread) {
                        service.performBack()
                        delay(280)
                        continue
                    }
                }
                delay(55)
            }

            val els = service.dumpScreenElements()
            val chatRows = els.filter {
                val r = it.node.viewIdResourceName ?: ""
                r.contains("contact_row_container", ignoreCase = true)
            }

            if (chatRows.isEmpty()) {
                val recent = com.jarvis.assistant.JarvisAccessibilityService.getRecentWhatsAppNotices().firstOrNull()
                if (recent != null) {
                    callback(true, "WhatsApp par ${recent.sender} ka message aaya tha: ${recent.message}")
                } else {
                    callback(false, "WhatsApp chats screen load nahi ho saki Sir.")
                }
                return@launch
            }

            fun rowText(el: com.jarvis.assistant.ScreenElement): String =
                el.text.ifBlank { el.contentDescription }.trim()

            // If specific sender named without saved phone number (group name or unsaved name):
            if (cleanSender.isNotBlank()) {
                val matchedRow = chatRows.firstOrNull {
                    rowText(it).contains(cleanSender, ignoreCase = true)
                }
                if (matchedRow != null) {
                    service.clickNode(matchedRow.node)
                    waitForWhatsAppChatScreen(service, 2200L)
                    delay(150)
                    val msg = extractLatestWhatsAppChatText(service)
                    val reply = if (!msg.isNullOrBlank()) {
                        "WhatsApp par $cleanSender chat mein last message hai: $msg"
                    } else {
                        "$cleanSender ki WhatsApp chat khol di hai Sir."
                    }
                    callback(true, reply)
                    return@launch
                }
            }

            // General "kiska message aaya hai" query: prefer unread rows first, else newest top chat row
            val unreadRows = chatRows.filter { rowText(it).contains("unread", ignoreCase = true) }
            val candidates = if (unreadRows.isNotEmpty()) unreadRows else listOf(chatRows.first())
            val summaries = mutableListOf<String>()

            for (row in candidates.take(2)) {
                val raw = rowText(row)
                val parts = raw.split("|").map { it.trim() }.filter { it.isNotBlank() }
                val chatName = parts.firstOrNull() ?: "Someone"
                val previewParts = parts.drop(1).filterNot {
                    it.matches(Regex("\\d{1,2}:\\d{2}\\s*(am|pm)?", RegexOption.IGNORE_CASE)) ||
                        it.matches(Regex("\\d+")) ||
                        it.contains("unread message", ignoreCase = true)
                }
                if (previewParts.isNotEmpty() && !raw.contains("unread message", ignoreCase = true)) {
                    summaries.add("$chatName ne bheja: ${previewParts.joinToString(" ")}")
                } else if (summaries.isEmpty()) {
                    // Open the unread chat row to read the actual hidden message content!
                    service.clickNode(row.node)
                    if (waitForWhatsAppChatScreen(service, 2000L)) {
                        delay(150)
                        val msg = extractLatestWhatsAppChatText(service)
                        if (!msg.isNullOrBlank()) {
                            summaries.add("$chatName ka unread message hai: $msg")
                        } else {
                            summaries.add("$chatName ka unread message aaya hai")
                        }
                    } else {
                        summaries.add("$chatName ka unread message aaya hai")
                    }
                    break
                } else {
                    summaries.add("$chatName ka unread message hai")
                }
            }

            val finalSpeech = if (summaries.isNotEmpty()) {
                "WhatsApp par ${summaries.joinToString("; ")} Sir."
            } else {
                "Abhi koi naya WhatsApp message nahi dikha Sir."
            }
            callback(true, finalSpeech)
        }
    }

    private suspend fun waitForWhatsAppChatScreen(service: JarvisAccessibilityService, timeoutMs: Long): Boolean {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            if (AutoUnlockManager.isAppLockScreen(service)) {
                AutoUnlockManager.unlockAppLockIfPresent(systemController.appContext)
                delay(70)
                continue
            }
            val inChat = service.dumpScreenElements().any {
                val r = it.node.viewIdResourceName ?: ""
                r.endsWith(":id/entry") || r.contains("message_text", ignoreCase = true) || r.contains("conversation_contact_name", ignoreCase = true)
            }
            if (inChat) return true
            delay(55)
        }
        return false
    }

    private fun extractLatestWhatsAppChatText(service: JarvisAccessibilityService): String? {
        val els = service.dumpScreenElements()
        val dm = service.resources.displayMetrics
        val minContentTop = (dm.heightPixels * 0.11f).toInt()
        val msgNodes = els.filter {
            val res = it.node.viewIdResourceName ?: ""
            val cls = it.className.lowercase()
            res.contains("message_text", ignoreCase = true) ||
                (cls.contains("textview") && !it.isClickable && it.bounds.top > minContentTop && it.text.isNotBlank() &&
                    !it.text.matches(Regex("\\d{1,2}:\\d{2}\\s*(am|pm)?", RegexOption.IGNORE_CASE)) &&
                    !it.text.equals("Today", ignoreCase = true) && !it.text.equals("Yesterday", ignoreCase = true) &&
                    !it.text.contains("end-to-end encrypted", ignoreCase = true))
        }
        return msgNodes.lastOrNull()?.text?.trim()?.takeIf { it.isNotBlank() }
    }

    /**
     * Fallback helper for YouTube search results page when direct videoId fetch was offline/blocked:
     * Automatically taps the first organic video card so playback starts hands-free.
     */
    suspend fun tapFirstYoutubeSearchResult() {
        val service = JarvisAccessibilityService.instance ?: return
        val dm = service.resources.displayMetrics
        val minTop = (dm.heightPixels * 0.10f).toInt()
        val minCardW = (dm.widthPixels * 0.30f).toInt()
        val minCardH = (dm.heightPixels * 0.06f).toInt()
        val waitStart = System.currentTimeMillis()
        while (System.currentTimeMillis() - waitStart < 2500L) {
            if (AutoUnlockManager.isAppLockScreen(service)) {
                AutoUnlockManager.unlockAppLockIfPresent(systemController.appContext)
                delay(80)
                continue
            }
            val pkg = service.rootInActiveWindow?.packageName?.toString()?.lowercase().orEmpty()
            if (pkg.contains("youtube")) {
                val els = service.dumpScreenElements()
                val alreadyPlaying = els.any {
                    val res = it.node.viewIdResourceName?.lowercase() ?: ""
                    res.contains("player_fragment") || res.contains("watch_player") || res.contains("player_control")
                }
                if (alreadyPlaying) return

                val videoCard = els.firstOrNull { el ->
                    val label = el.text.ifBlank { el.contentDescription }.lowercase()
                    el.isClickable && el.bounds.top > minTop && el.bounds.width() > minCardW && el.bounds.height() > minCardH &&
                        (label.contains("ago") || label.contains("views") || label.contains("minutes") || label.contains("seconds") || label.contains("play video")) &&
                        !label.contains("sponsored") && !label.contains("ad ")
                }
                if (videoCard != null) {
                    service.clickNode(videoCard.node)
                    return
                }
            }
            delay(85)
        }
    }

    /**
     * Universal Tap by Set-of-Marks tag number ([1], [2], [3]...).
     * Uses the live-refreshed cached snapshot element so shifting animations never cause wrong clicks.
     */
    fun tapByTag(tag: Int, callback: (Boolean, String) -> Unit) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            callback(false, "Accessibility Service active nahi hai Sir.")
            return
        }

        val targetElement = service.findTaggedElement(tag)
        if (targetElement != null) {
            val clicked = service.clickNode(targetElement.node)
            if (clicked) {
                callback(true, "Element [${tag}] click kar diya hai Sir.")
                return
            }
        }
        callback(false, "Tag [${tag}] par click nahi ho saka (element invalid ya screen badal gayi).")
    }

    /**
     * Universal Text Typing into tagged input field.
     * Verifies element is actually an active editable field before typing.
     */
    fun typeTextByTag(tag: Int, text: String, callback: (Boolean, String) -> Unit) {
        val service = JarvisAccessibilityService.instance
        if (service == null) {
            callback(false, "Accessibility Service active nahi hai Sir.")
            return
        }

        val targetElement = service.findTaggedElement(tag)
        if (targetElement != null) {
            val node = targetElement.node
            val canEdit = node.isEditable || node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }
            if (!canEdit) {
                callback(false, "Tag [${tag}] abhi editable input_field nahi hai — pehle is par tap karein.")
                return
            }
            val typed = service.typeText(node, text)
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
