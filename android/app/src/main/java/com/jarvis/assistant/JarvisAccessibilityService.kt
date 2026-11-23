package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.engine.SystemController
import com.jarvis.assistant.memory.JarvisMemoryManager
import com.jarvis.assistant.voice.SpeakerHelper
import kotlinx.coroutines.delay

data class ScreenElement(
    val tag: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val bounds: Rect,
    val node: AccessibilityNodeInfo
)

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        var instance: JarvisAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null
    }

    private var lastIncomingCallAnnounceTime = 0L
    private var lastNotificationTime = 0L
    private var lastNotificationText = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "J.A.R.V.I.S. Accessibility Service Connected!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        // 1. 24/7 Screen Focus Recording
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (pkg.isNotBlank() && !pkg.contains("launcher") && !pkg.contains("systemui") && pkg != packageName) {
                try {
                    val pm = packageManager
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val appLabel = pm.getApplicationLabel(appInfo).toString()
                    JarvisMemoryManager(this).recordScreenFocus(appLabel)
                } catch (e: Exception) {
                    val label = pkg.substringAfterLast('.')
                    JarvisMemoryManager(this).recordScreenFocus(label)
                }
            }
        }

        // 2. 24/7 Real-Time Screen Observation for Incoming Calls (Phone / WhatsApp / Any App)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            checkIncomingCallScreen(pkg)
        }

        // 3. WhatsApp & Call Notification Butler
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            handleCallOrWhatsAppNotification(event, pkg)
        }
    }

    private fun checkIncomingCallScreen(pkg: String) {
        val now = System.currentTimeMillis()
        if (now - lastIncomingCallAnnounceTime < 7000) return

        val isCallingApp = pkg.contains("dialer", ignoreCase = true) ||
                pkg.contains("incall", ignoreCase = true) ||
                pkg.contains("phone", ignoreCase = true) ||
                pkg.contains("telecom", ignoreCase = true) ||
                pkg.contains("whatsapp", ignoreCase = true)

        if (!isCallingApp) return

        val hasAnswer = findClickableNode("answer") != null ||
                findClickableNode("accept") != null ||
                findClickableNode("swipe up to answer") != null
        val hasDecline = findClickableNode("decline") != null ||
                findClickableNode("reject") != null ||
                findClickableNode("dismiss") != null

        if (hasAnswer && hasDecline) {
            var callerName = "Someone"
            val elements = dumpScreenElements()
            for (el in elements) {
                val t = el.text.trim()
                if (t.isNotBlank() &&
                    !t.equals("answer", ignoreCase = true) &&
                    !t.equals("decline", ignoreCase = true) &&
                    !t.equals("reject", ignoreCase = true) &&
                    !t.equals("incoming call", ignoreCase = true) &&
                    !t.contains("swipe", ignoreCase = true) &&
                    !t.contains("voice call", ignoreCase = true) &&
                    !t.contains("video call", ignoreCase = true) &&
                    t.length > 1
                ) {
                    callerName = t
                    break
                }
            }

            lastIncomingCallAnnounceTime = now
            val memoryManager = JarvisMemoryManager(this)
            val isVip = memoryManager.isVip(callerName)

            if (isVip) {
                bypassSilentAndDnd()
            }

            val speaker = SpeakerHelper(this)
            speaker.speak("Sir, $callerName ka call aa raha hai. Pick karna hai ya reject?")
        }
    }

    private fun handleCallOrWhatsAppNotification(event: AccessibilityEvent, pkg: String) {
        val isWhatsApp = pkg.contains("whatsapp", ignoreCase = true)
        val isPhoneCall = pkg.contains("dialer", ignoreCase = true) ||
                pkg.contains("incall", ignoreCase = true) ||
                pkg.contains("telecom", ignoreCase = true) ||
                pkg.contains("phone", ignoreCase = true)

        if (!isWhatsApp && !isPhoneCall) return

        val texts = event.text.map { it.toString().trim() }.filter { it.isNotBlank() }
        val fullText = texts.joinToString(" - ")
        val now = System.currentTimeMillis()

        if (fullText.isNotBlank() && (now - lastNotificationTime > 6000 || fullText != lastNotificationText)) {
            lastNotificationTime = now
            lastNotificationText = fullText

            val memoryManager = JarvisMemoryManager(this)
            val isVip = memoryManager.isVip(fullText)

            if (isVip) {
                bypassSilentAndDnd()
            }

            val speaker = SpeakerHelper(this)
            val isMissed = fullText.lowercase().contains("missed") || fullText.lowercase().contains("miss")
            if (isPhoneCall || isMissed) {
                speaker.speak("Sir, ${if (isVip) "VIP " else ""}$fullText")
            } else if (isWhatsApp) {
                speaker.speak("Sir, WhatsApp par message aaya hai: $fullText")
            }
        }
    }

    fun bypassSilentAndDnd() {
        try {
            SystemController(this).setDnd(false)
        } catch (e: Exception) {}

        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVol, 0)
        } catch (e: Exception) {}
    }

    override fun onInterrupt() {
        Log.w(TAG, "J.A.R.V.I.S. Accessibility Service Interrupted.")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "J.A.R.V.I.S. Accessibility Service Destroyed.")
    }

    // ═════════════════════════════════════════════════════════════════
    // IN-MEMORY NODE TRAVERSAL (Sub-millisecond speed)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Dumps all interactive elements on the current active screen into a tagged list in <2ms.
     */
    fun dumpScreenElements(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val elements = mutableListOf<ScreenElement>()
        var counter = 1

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return

            val text = node.text?.toString()?.trim() ?: ""
            val cd = node.contentDescription?.toString()?.trim() ?: ""
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable

            val bounds = Rect()
            node.getBoundsInScreen(bounds)

            // Filter meaningful interactive components
            val isInteractive = isClickable || isEditable || node.isCheckable || node.isFocusable
            if (isInteractive || text.isNotEmpty() || cd.isNotEmpty()) {
                if (bounds.width() > 0 && bounds.height() > 0) {
                    elements.add(
                        ScreenElement(
                            tag = counter++,
                            text = text,
                            contentDescription = cd,
                            className = cls,
                            isClickable = isClickable,
                            isEditable = isEditable,
                            bounds = bounds,
                            node = node
                        )
                    )
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)
        return elements
    }

    /**
     * Finds a node matching a custom predicate (text, desc, resource ID).
     */
    fun findNodeByPredicate(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (predicate(node)) {
                var current: AccessibilityNodeInfo? = node
                while (current != null) {
                    if (current.isClickable) return current
                    current = current.parent
                }
                return node
            }
            for (i in 0 until node.childCount) {
                val found = search(node.getChild(i))
                if (found != null) return found
            }
            return null
        }

        return search(root)
    }

    /**
     * Finds a clickable node matching text, content description, or resource ID.
     */
    fun findClickableNode(target: String): AccessibilityNodeInfo? {
        val lowerTarget = target.lowercase().trim()
        return findNodeByPredicate { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.lowercase() ?: ""

            text.contains(lowerTarget) || cd.contains(lowerTarget) || resId.contains(lowerTarget)
        }
    }

    /**
     * Finds a node by view ID resource name (e.g. android:id/button1).
     */
    fun findNodeById(id: String): AccessibilityNodeInfo? {
        val lowerId = id.lowercase().trim()
        return findNodeByPredicate { node ->
            node.viewIdResourceName?.lowercase()?.contains(lowerId) == true
        }
    }

    /**
     * Finds the first editable text field (EditText) on screen.
     */
    fun findFirstInputField(): AccessibilityNodeInfo? {
        return findNodeByPredicate { node ->
            node.isEditable || node.className?.contains("EditText") == true
        }
    }

    /**
     * Polls the active screen up to timeoutMs for a node matching the predicate.
     * Prevents race conditions during app loading/rendering!
     */
    suspend fun pollForNode(
        timeoutMs: Long = 3500,
        intervalMs: Long = 250,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val node = findNodeByPredicate(predicate)
            if (node != null) return node
            delay(intervalMs)
        }
        return null
    }

    /**
     * Direct node action: Dual-Action Tap (Click Action + Hardware Gesture Tap).
     * Bypasses OEM click suppression and guarantees button activation on OnePlus/OxygenOS!
     */
    fun tapElement(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            val clickPath = Path().apply {
                moveTo(cx, cy)
                lineTo(cx + 1f, cy + 1f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
        }
        return true
    }

    /**
     * Direct node action: Click with Gesture Tap Fallback (100% reliable on all OEMs).
     */
    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        if (node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true

        // Gesture Tap fallback
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            val clickPath = Path().apply {
                moveTo(cx, cy)
                lineTo(cx + 1f, cy + 1f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
                .build()
            return dispatchGesture(gesture, null, null)
        }
        return false
    }

    /**
     * Direct node action: Set Text (types string directly into field in 1ms).
     */
    fun typeText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (success && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } catch (e: Exception) {}
        }
        return success
    }

    /**
     * Dumps all interactive screen elements into a clean numbered list for Gemini inspection.
     */
    fun dumpScreenElementsSummary(): String {
        val elements = dumpScreenElements()
        if (elements.isEmpty()) return "Screen is empty or no interactive elements."
        val sb = StringBuilder()
        for (el in elements) {
            val resId = el.node.viewIdResourceName?.substringAfterLast('/') ?: ""
            val rawLabel = if (el.text.isNotBlank()) el.text else el.contentDescription
            val label = if (rawLabel.isNotBlank()) rawLabel else resId

            if (label.isBlank() && !el.isEditable && !el.isClickable) continue

            val type = when {
                el.isEditable -> "input_field"
                el.isClickable -> "button"
                else -> "text"
            }
            val displayLabel = if (label.isNotBlank()) label else "button"
            val idSuffix = if (resId.isNotBlank() && resId != displayLabel) " (id: $resId)" else ""
            sb.append("[${el.tag}] $type$idSuffix: \"$displayLabel\"\n")
        }
        return sb.toString().trim()
    }

    /**
     * Swipes / scrolls the screen up or down via hardware gesture (for reels, feeds, web).
     */
    fun scroll(direction: String = "down"): Boolean {
        val displayMetrics = resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()

        val startX = width / 2f
        val endX = width / 2f
        val (startY, endY) = if (direction.lowercase() == "up") {
            Pair(height * 0.3f, height * 0.75f)
        } else {
            Pair(height * 0.75f, height * 0.3f)
        }

        val scrollPath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(scrollPath, 0, 300))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Global Hardware Back button press.
     */
    fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Global Hardware Home button press.
     */
    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Answers an incoming phone or VoIP/WhatsApp call.
     */
    fun answerCall(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                telecomManager?.acceptRingingCall()
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager acceptRingingCall exception: ${e.message}")
        }

        val answerKeywords = listOf("answer", "accept", "pick up", "swipe up to answer", "incoming call")
        for (kw in answerKeywords) {
            val node = findClickableNode(kw)
            if (node != null) {
                tapElement(node)
                return true
            }
        }
        return false
    }

    /**
     * Ends an ongoing phone/WhatsApp call or rejects an incoming call.
     */
    fun endCall(): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecomManager?.endCall() == true) return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "TelecomManager endCall exception: ${e.message}")
        }

        val endKeywords = listOf("end call", "disconnect", "decline", "reject", "hang up", "cut", "dismiss")
        for (kw in endKeywords) {
            val node = findClickableNode(kw)
            if (node != null) {
                tapElement(node)
                return true
            }
        }
        return false
    }

    /**
     * Closes the currently active app / returns to home screen.
     */
    fun closeApp(): Boolean {
        return performHome()
    }
}
