package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Path
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telecom.TelecomManager
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.jarvis.assistant.debug.JarvisLogStore
import com.jarvis.assistant.engine.SystemController
import com.jarvis.assistant.memory.JarvisMemoryManager
import com.jarvis.assistant.voice.SpeakerHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.min

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

data class WhatsAppNotice(
    val sender: String,
    val message: String,
    val timestampMs: Long
)

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        var instance: JarvisAccessibilityService? = null
            private set

        val isServiceRunning: Boolean
            get() = instance != null

        private val recentWhatsAppNotices = ArrayDeque<WhatsAppNotice>()

        fun recordWhatsAppNotice(rawText: String) {
            val clean = rawText.trim()
            if (clean.isBlank()) return
            val parts = clean.split(Regex("\\s+-\\s+|:\\s+"), limit = 2)
            val sender = parts.getOrNull(0)?.trim().orEmpty()
            val msg = parts.getOrNull(1)?.trim().orEmpty()
            if (sender.isBlank() || msg.isBlank()) return
            val lower = clean.lowercase()
            if (lower.contains("checking for new messages") || lower.contains("backup in progress")) return
            synchronized(recentWhatsAppNotices) {
                recentWhatsAppNotices.removeAll { it.sender == sender && it.message == msg }
                recentWhatsAppNotices.addFirst(WhatsAppNotice(sender, msg, System.currentTimeMillis()))
                while (recentWhatsAppNotices.size > 15) recentWhatsAppNotices.removeLast()
            }
        }

        fun getRecentWhatsAppNotices(): List<WhatsAppNotice> {
            synchronized(recentWhatsAppNotices) {
                return recentWhatsAppNotices.toList()
            }
        }
    }

    private var lastIncomingCallAnnounceTime = 0L
    private var lastNotificationTime = 0L
    private var lastNotificationText = ""

    @Volatile
    private var cachedScreenElements: List<ScreenElement> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        JarvisLogStore.init(this)
        JarvisLogStore.log("ACC_SERVICE", "Accessibility Service Connected")
        Log.d(TAG, "J.A.R.V.I.S. Accessibility Service Connected!")
        com.jarvis.assistant.voice.AlwaysOnVoiceService.ensureRunningIfEnabled(this, "ACCESSIBILITY_CONNECTED")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        // 0. Instant Banking App Protection Shield (PhonePe, Paytm, Google Pay, etc.)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (com.jarvis.assistant.security.BankingProtectionManager.isBankingApp(pkg)) {
                com.jarvis.assistant.security.BankingProtectionManager.onPackageOpened(this, pkg)
                return
            }
        }

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
                recordWhatsAppNotice(fullText)
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
     * Dumps all visible, on-screen interactive elements into a crisp tagged list in <3ms.
     * Aggregates child labels into clickable rows, hides off-screen ViewPager/feed noise,
     * and caches the exact snapshot so tap_tag/type_tag always act on the inspected UI nodes.
     */
    private fun getDynamicScreenRect(): Rect {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                val bounds = wm?.maximumWindowMetrics?.bounds
                if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
                    return Rect(bounds)
                }
            } catch (_: Exception) {
            }
        }
        val dm = resources.displayMetrics
        return Rect(0, 0, dm.widthPixels.coerceAtLeast(1), dm.heightPixels.coerceAtLeast(1))
    }

    fun dumpScreenElements(): List<ScreenElement> {
        val root = rootInActiveWindow ?: return emptyList()
        val screenRect = getDynamicScreenRect()
        val screenArea = screenRect.width().toLong() * screenRect.height().toLong()
        val elements = mutableListOf<ScreenElement>()
        var counter = 1

        fun collectDescendantLabels(container: AccessibilityNodeInfo): String {
            val parts = mutableListOf<String>()
            fun walk(n: AccessibilityNodeInfo?, depth: Int) {
                if (n == null || depth > 6 || parts.size >= 4) return
                // Stop descending into separately clickable/editable child controls (e.g. Follow button inside a user row)
                if (depth > 0 && (n.isClickable || n.isEditable || n.isCheckable)) return

                val t = n.text?.toString()?.trim() ?: ""
                val c = n.contentDescription?.toString()?.trim() ?: ""
                val h = if (Build.VERSION.SDK_INT >= 26) n.hintText?.toString()?.trim() ?: "" else ""
                val chosen = when {
                    t.isNotBlank() -> t
                    c.isNotBlank() -> c
                    else -> h
                }
                if (chosen.isNotBlank() && parts.none { it.equals(chosen, ignoreCase = true) }) {
                    parts.add(chosen)
                }
                for (i in 0 until n.childCount) {
                    walk(n.getChild(i), depth + 1)
                }
            }
            walk(container, 0)
            return parts.joinToString(" | ").take(100)
        }

        fun traverse(node: AccessibilityNodeInfo?, insideClickableParent: Boolean) {
            if (node == null || elements.size >= 85) return

            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            val isOnScreen = bounds.width() > 4 && bounds.height() > 4 && Rect.intersects(bounds, screenRect)
            if (!isOnScreen || !node.isVisibleToUser) return

            val text = node.text?.toString()?.trim() ?: ""
            val cd = node.contentDescription?.toString()?.trim() ?: ""
            val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim() ?: "" else ""
            val cdOrHint = cd.ifBlank { hint }
            val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
            val resId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
            val isClickable = node.isClickable
            val isEditable = node.isEditable
            val isInteractive = isClickable || isEditable || node.isCheckable

            val nodeArea = bounds.width().toLong() * bounds.height().toLong()
            val isHugeWrapper = !isEditable && nodeArea > (screenArea * 0.72) && text.isBlank() && cdOrHint.isBlank()
            val isLargeClickableCard = isClickable &&
                bounds.width() >= (screenRect.width() * 0.24f).toInt() &&
                bounds.height() >= (screenRect.height() * 0.11f).toInt()
            val isLargeVisualMedia = bounds.width() >= (screenRect.width() * 0.22f).toInt() &&
                bounds.height() >= (screenRect.height() * 0.12f).toInt() &&
                (cls.contains("ImageView", ignoreCase = true) ||
                    cls.contains("TextureView", ignoreCase = true) ||
                    cls.contains("VideoView", ignoreCase = true) ||
                    cls.contains("SurfaceView", ignoreCase = true) ||
                    cls.contains("DraweeView", ignoreCase = true))

            var emittedThisNode = false
            if (isInteractive && !isHugeWrapper) {
                val aggregatedLabel = when {
                    text.isNotBlank() -> text
                    cdOrHint.isNotBlank() -> cdOrHint
                    isClickable -> collectDescendantLabels(node)
                    else -> ""
                }
                if (aggregatedLabel.isNotBlank() || resId.isNotBlank() || isEditable || isLargeClickableCard) {
                    elements.add(
                        ScreenElement(
                            tag = counter++,
                            text = if (text.isNotBlank()) text else aggregatedLabel,
                            contentDescription = cdOrHint,
                            className = cls,
                            isClickable = isClickable,
                            isEditable = isEditable,
                            bounds = bounds,
                            node = node
                        )
                    )
                    emittedThisNode = true
                }
            } else if (!insideClickableParent && !isHugeWrapper && (text.isNotBlank() || cdOrHint.isNotBlank() || isLargeVisualMedia)) {
                elements.add(
                    ScreenElement(
                        tag = counter++,
                        text = text,
                        contentDescription = cdOrHint,
                        className = cls,
                        isClickable = false,
                        isEditable = false,
                        bounds = bounds,
                        node = node
                    )
                )
            }

            val nextInsideClickable = insideClickableParent || (emittedThisNode && isClickable && !isHugeWrapper)
            for (i in 0 until node.childCount) {
                traverse(node.getChild(i), nextInsideClickable)
            }
        }

        traverse(root, false)
        cachedScreenElements = elements
        return elements
    }

    /**
     * Resolves a tagged element from the last inspected UI snapshot and verifies/refreshes it live.
     * Prevents mis-tapping shifted indices if the app UI animated while Gemini reasoned.
     */
    fun findTaggedElement(tag: Int): ScreenElement? {
        val snapshot = cachedScreenElements
        if (snapshot.isNotEmpty()) {
            val cached = snapshot.find { it.tag == tag } ?: return null
            if (!cached.node.refresh()) {
                return null
            }
            val freshBounds = Rect()
            cached.node.getBoundsInScreen(freshBounds)
            return cached.copy(
                bounds = freshBounds,
                isClickable = cached.node.isClickable,
                isEditable = cached.node.isEditable
            )
        }
        return dumpScreenElements().find { it.tag == tag }
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
        intervalMs: Long = 60,
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
     * Direct node action: Refresh node, search clickable ancestor up to 4 levels,
     * with visible-screen bounds gesture tap fallback.
     */
    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        node.refresh()

        var clickableTarget: AccessibilityNodeInfo? = node
        var depth = 0
        while (clickableTarget != null && !clickableTarget.isClickable && depth < 4) {
            clickableTarget = clickableTarget.parent
            depth++
        }

        val chosen = clickableTarget?.takeIf { it.isClickable } ?: node
        val bounds = Rect()
        chosen.getBoundsInScreen(bounds)
        val resId = chosen.viewIdResourceName?.substringAfterLast('/') ?: ""
        val cdStr = chosen.contentDescription?.toString() ?: ""
        val label = (chosen.text?.toString() ?: "").ifBlank { cdStr }

        if (clickableTarget != null && clickableTarget.isClickable) {
            if (clickableTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                JarvisLogStore.log("ACC_ACTION", "clickNode(ACTION_CLICK) depth=$depth cls=${chosen.className?.toString()?.substringAfterLast('.') ?: ""} label='${label.take(40)}' res='$resId' bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}]")
                return true
            }
        } else if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            JarvisLogStore.log("ACC_ACTION", "clickNode(SELF_ACTION_CLICK) label='${label.take(40)}' res='$resId'")
            return true
        }

        // Gesture Tap fallback ONLY inside visible screen bounds
        val screenRect = getDynamicScreenRect()
        if (bounds.width() > 0 && bounds.height() > 0 && Rect.intersects(bounds, screenRect)) {
            val cx = bounds.centerX().toFloat()
            val cy = bounds.centerY().toFloat()
            val clickPath = Path().apply {
                moveTo(cx, cy)
                lineTo(cx + 1f, cy + 1f)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
                .build()
            val dispatched = dispatchGesture(gesture, null, null)
            JarvisLogStore.log("ACC_ACTION", "clickNode(GESTURE) ($cx,$cy) label='${label.take(40)}' res='$resId' ok=$dispatched")
            return dispatched
        }
        return false
    }

    /**
     * Single clean OS-level touch tap at the node's live visible bounds center.
     * Triggers TouchListener / Compose / Meta Litho components reliably without double-tapping.
     */
    suspend fun tapNodeSingleTouch(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        node.refresh()

        var clickableTarget: AccessibilityNodeInfo? = node
        var depth = 0
        while (clickableTarget != null && !clickableTarget.isClickable && depth < 4) {
            clickableTarget = clickableTarget.parent
            depth++
        }
        val targetNode = if (clickableTarget != null && clickableTarget.isClickable) clickableTarget else node

        val bounds = Rect()
        targetNode.getBoundsInScreen(bounds)
        val screenRect = getDynamicScreenRect()
        val resId = targetNode.viewIdResourceName?.substringAfterLast('/') ?: ""
        val txt = node.text?.toString()?.take(35) ?: ""
        val cd = node.contentDescription?.toString()?.take(35) ?: ""

        if (bounds.width() > 0 && bounds.height() > 0 && Rect.intersects(bounds, screenRect)) {
            val tapped = tapCoordinatesAwait(bounds.centerX().toFloat(), bounds.centerY().toFloat(), 45L)
            JarvisLogStore.log("ACC_TAP", "tapSingleTouch (${bounds.centerX()},${bounds.centerY()}) depth=$depth txt='$txt' cd='$cd' res='$resId' bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] ok=$tapped")
            if (tapped) return true
        }
        val fallbackClick = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        JarvisLogStore.log("ACC_TAP", "tapSingleTouch fallback ACTION_CLICK txt='$txt' cd='$cd' res='$resId' ok=$fallbackClick")
        return fallbackClick
    }

    /**
     * Swipes horizontally inside a live container's bounds (e.g. horizontal Story tray).
     */
    suspend fun swipeHorizontalInRect(rect: Rect, toLeft: Boolean = true): Boolean {
        val screenRect = getDynamicScreenRect()
        if (rect.width() < (screenRect.width() * 0.10f).toInt() || rect.height() < (screenRect.height() * 0.01f).toInt()) return false
        val cy = rect.centerY().toFloat()
        val startX = if (toLeft) rect.right - rect.width() * 0.15f else rect.left + rect.width() * 0.15f
        val endX = if (toLeft) rect.left + rect.width() * 0.15f else rect.right - rect.width() * 0.15f
        val path = Path().apply {
            moveTo(startX, cy)
            lineTo(endX, cy)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 280L))
            .build()
        val ok = dispatchGestureAwait(gesture)
        JarvisLogStore.log("ACC_SWIPE", "swipeHorizontal toLeft=$toLeft ($startX -> $endX, y=$cy) rect=[${rect.left},${rect.top},${rect.right},${rect.bottom}] ok=$ok")
        return ok
    }

    /**
     * Direct node action: Set Text with Clipboard Fallback & Smart Multi-Line Handling.
     * Allows callers to override pressEnter (e.g. false for live-filtering DM search & chat inputs).
     */
    fun typeText(node: AccessibilityNodeInfo?, text: String, pressEnter: Boolean? = null): Boolean {
        if (node == null) return false
        node.refresh()

        // 1. Keep clipboard synced as reliable backup for custom rich editors
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("jarvis_input", text)
            clipboard?.setPrimaryClip(clip)
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard copy error: ${e.message}")
        }

        // 2. Ensure focus and try direct ACTION_SET_TEXT
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        val method = if (success) "SET_TEXT" else "PASTE"

        // 3. Fallback to ACTION_PASTE if SET_TEXT is not accepted (e.g. rich canvas or custom text editors)
        if (!success) {
            success = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }

        // 4. Only trigger IME ENTER when enabled and single-line
        val shouldEnter = pressEnter ?: (!text.contains("\n") && !node.isMultiLine)
        if (success && shouldEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } catch (e: Exception) {}
        }
        val resId = node.viewIdResourceName?.substringAfterLast('/') ?: ""
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        JarvisLogStore.log("ACC_TYPE", "typeText('$text') method=$method enter=$shouldEnter res='$resId' bounds=[${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}] ok=$success")
        return success
    }

    /**
     * Dumps all visible screen elements into a crisp numbered list with ID and state flags for Gemini inspection.
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

            val metaParts = mutableListOf<String>()
            if (resId.isNotBlank() && resId != displayLabel) metaParts.add("id: $resId")
            if (el.node.isSelected) metaParts.add("selected")
            if (el.node.isCheckable && el.node.isChecked) metaParts.add("checked")
            if (!el.node.isEnabled) metaParts.add("disabled")

            val metaSuffix = if (metaParts.isNotEmpty()) " (${metaParts.joinToString(", ")})" else ""
            sb.append("[${el.tag}] $type$metaSuffix: \"$displayLabel\"\n")
        }
        return sb.toString().trim()
    }

    /**
     * Swipes / scrolls the screen up or down via hardware gesture (for reels, feeds, web).
     */
    fun scroll(direction: String = "down"): Boolean {
        val screenRect = getDynamicScreenRect()
        val width = screenRect.width().toFloat()
        val height = screenRect.height().toFloat()

        val startX = width / 2f
        val endX = width / 2f
        val (startY, endY) = if (direction.lowercase() == "up") {
            Pair(height * 0.22f, height * 0.78f)
        } else {
            Pair(height * 0.78f, height * 0.22f)
        }

        val scrollPath = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(scrollPath, 0, 195))
            .build()
        val ok = dispatchGesture(gesture, null, null)
        JarvisLogStore.log("ACC_SCROLL", "scroll($direction) ($startX,$startY -> $endX,$endY) ok=$ok")
        return ok
    }

    /**
     * Crisp full-page fling scroll tailored for ViewPager / Reels / Shorts (awaits gesture completion).
     */
    suspend fun flingScroll(direction: String = "down"): Boolean {
        val screenRect = getDynamicScreenRect()
        val width = screenRect.width().toFloat()
        val height = screenRect.height().toFloat()

        val cx = width / 2f
        val (startY, endY) = if (direction.lowercase() == "up") {
            Pair(height * 0.22f, height * 0.78f)
        } else {
            Pair(height * 0.78f, height * 0.22f)
        }

        val flingPath = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(flingPath, 0L, 190L))
            .build()
        val ok = dispatchGestureAwait(gesture)
        JarvisLogStore.log("ACC_FLING", "flingScroll($direction) ($cx,$startY -> $cx,$endY) ok=$ok")
        return ok
    }

    /**
     * Precision zero-fling scroll that pauses briefly before lifting finger and awaits OS completion.
     * Prevents RecyclerView drift so the very next tap lands accurately on cards/rows without wasted fling-stop taps.
     */
    suspend fun scrollExact(
        direction: String = "down",
        upStartFraction: Float = 0.34f,
        upEndFraction: Float = 0.70f
    ): Boolean {
        val screenRect = getDynamicScreenRect()
        val width = screenRect.width().toFloat()
        val height = screenRect.height().toFloat()

        val cx = width / 2f
        val (startY, endY) = if (direction.lowercase() == "up") {
            Pair(height * upStartFraction, height * upEndFraction)
        } else {
            Pair(height * upEndFraction, height * upStartFraction)
        }

        val slidePath = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        val holdPath = Path().apply {
            moveTo(cx, endY)
            lineTo(cx, endY)
        }
        val ok = try {
            val stroke1 = GestureDescription.StrokeDescription(slidePath, 0L, 230L, true)
            val moved = dispatchGestureAwait(GestureDescription.Builder().addStroke(stroke1).build())
            if (moved) {
                val stroke2 = stroke1.continueStroke(holdPath, 0L, 65L, false)
                dispatchGestureAwait(GestureDescription.Builder().addStroke(stroke2).build())
            } else {
                val gentle = GestureDescription.StrokeDescription(slidePath, 0L, 380L)
                dispatchGestureAwait(GestureDescription.Builder().addStroke(gentle).build())
            }
        } catch (_: Exception) {
            val gentle = GestureDescription.StrokeDescription(slidePath, 0L, 380L)
            dispatchGestureAwait(GestureDescription.Builder().addStroke(gentle).build())
        }
        JarvisLogStore.log("ACC_SCROLL_EXACT", "scrollExact($direction) ($cx,$startY -> $cx,$endY) ok=$ok")
        return ok
    }

    /**
     * Global Hardware Back button press.
     */
    fun performBack(): Boolean {
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        JarvisLogStore.log("ACC_BACK", "performBack() ok=$ok")
        return ok
    }

    /**
     * Global Hardware Home button press.
     */
    fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    /**
     * Instant OS-level Screen Lock (Android 9+ / API 28+, 0 UI hops, ~10ms).
     */
    fun lockScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        JarvisLogStore.log("ACC_LOCK", "lockScreen() ok=$ok")
        return ok
    }

    /**
     * Answers an incoming phone or VoIP/WhatsApp call across all windows (full screen or heads-up banner).
     */
    fun answerCall(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isRingingPhone = am?.mode == AudioManager.MODE_RINGTONE
        val hasAnswerPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAnswerPerm && isRingingPhone) {
            try {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                @Suppress("DEPRECATION")
                telecomManager?.acceptRingingCall()
                JarvisLogStore.log("ACC_CALL", "telecomManager.acceptRingingCall() invoked")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "TelecomManager acceptRingingCall exception: ${e.message}")
            }
        }

        val answerKeywords = listOf("answer", "accept", "pick up", "receive", "tap to answer")
        val btn = findNodeAcrossWindows(resolveClickableParent = true) { node ->
            val pkg = node.packageName?.toString()?.lowercase() ?: ""
            val isCallPkg = pkg.contains("dialer") || pkg.contains("incall") || pkg.contains("telecom") ||
                pkg.contains("phone") || pkg.contains("whatsapp") || pkg.contains("systemui")
            if (!isCallPkg) return@findNodeAcrossWindows false
            val text = node.text?.toString()?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            answerKeywords.any { kw -> text.contains(kw) || cd.contains(kw) } ||
                resId.contains("answer") || resId.contains("accept")
        }
        if (btn != null) {
            val clicked = clickNode(btn) || tapElement(btn)
            JarvisLogStore.log("ACC_CALL", "UI answerCall node clicked=$clicked")
            if (clicked) return true
        }

        // Swipe-up handle fallback on lock-screen incoming call UI
        val swipeHandle = findNodeAcrossWindows(resolveClickableParent = false) { node ->
            val text = node.text?.toString()?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            text.contains("swipe up to answer") || cd.contains("swipe up to answer") || cd.contains("answer call")
        }
        if (swipeHandle != null) {
            val r = Rect()
            swipeHandle.getBoundsInScreen(r)
            if (r.width() > 0 && r.height() > 0) {
                val path = Path().apply {
                    moveTo(r.centerX().toFloat(), r.centerY().toFloat())
                    lineTo(r.centerX().toFloat(), (r.centerY() - 380f).coerceAtLeast(100f))
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 220))
                    .build()
                val swiped = dispatchGesture(gesture, null, null)
                if (swiped) return true
            }
        }

        // Last resort: try TelecomManager even if AudioManager mode didn't report RINGTONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && hasAnswerPerm) {
            try {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                @Suppress("DEPRECATION")
                telecomManager?.acceptRingingCall()
                return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * Ends an ongoing phone/WhatsApp call or rejects an incoming call across all windows.
     */
    fun endCall(): Boolean {
        val hasAnswerPerm = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ANSWER_PHONE_CALLS
        ) == PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasAnswerPerm) {
            try {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                @Suppress("DEPRECATION")
                if (telecomManager?.endCall() == true) {
                    JarvisLogStore.log("ACC_CALL", "telecomManager.endCall() ok=true")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "TelecomManager endCall exception: ${e.message}")
            }
        }

        val endKeywords = listOf("end call", "disconnect", "decline", "reject", "hang up", "dismiss")
        val btn = findNodeAcrossWindows(resolveClickableParent = true) { node ->
            val pkg = node.packageName?.toString()?.lowercase() ?: ""
            val isCallPkg = pkg.contains("dialer") || pkg.contains("incall") || pkg.contains("telecom") ||
                pkg.contains("phone") || pkg.contains("whatsapp") || pkg.contains("systemui")
            if (!isCallPkg) return@findNodeAcrossWindows false
            val text = node.text?.toString()?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            endKeywords.any { kw -> text.contains(kw) || cd.contains(kw) } ||
                resId.contains("decline") || resId.contains("reject") || resId.endsWith("end_call") || resId.endsWith("end")
        }
        if (btn != null) {
            val clicked = clickNode(btn) || tapElement(btn)
            JarvisLogStore.log("ACC_CALL", "UI endCall node clicked=$clicked")
            if (clicked) return true
        }
        return false
    }

    /**
     * Toggles Speakerphone button directly on the active Dialer / InCallUI / WhatsApp call screen.
     * Checks selected/checked/audio state first so enabling an already-on speaker does not turn it off.
     */
    fun setCallSpeakerUi(enabled: Boolean): Boolean? {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val btn = findNodeAcrossWindows(resolveClickableParent = true) { node ->
            val pkg = node.packageName?.toString()?.lowercase() ?: ""
            val isCallPkg = pkg.contains("dialer") || pkg.contains("incall") || pkg.contains("telecom") ||
                pkg.contains("phone") || pkg.contains("whatsapp")
            if (!isCallPkg) return@findNodeAcrossWindows false
            val txt = node.text?.toString()?.lowercase()?.trim() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase()?.trim() ?: ""
            val res = node.viewIdResourceName?.lowercase() ?: ""
            val isSpeakerLabel = txt == "speaker" || cd == "speaker" || cd.contains("speakerphone") ||
                cd.contains("speaker") || res.contains("speaker") || res.contains("audio_button")
            isSpeakerLabel && !cd.contains("more")
        } ?: return null

        @Suppress("DEPRECATION")
        val currentAudioSpeaker = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am?.communicationDevice?.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || am?.isSpeakerphoneOn == true
        } else {
            am?.isSpeakerphoneOn == true
        }
        val cdNow = btn.contentDescription?.toString()?.lowercase() ?: ""
        val uiSelected = btn.isSelected || btn.isChecked || cdNow.contains("speaker on") || currentAudioSpeaker
        if (uiSelected == enabled) {
            JarvisLogStore.log("ACC_CALL", "Speakerphone already enabled=$enabled")
            return true
        }
        val clicked = clickNode(btn) || tapElement(btn)
        JarvisLogStore.log("ACC_CALL", "Speakerphone UI toggled to enabled=$enabled ok=$clicked")
        return clicked
    }

    /**
     * Closes the currently active app / returns to home screen.
     */
    fun closeApp(): Boolean {
        return performHome()
    }

    // ═════════════════════════════════════════════════════════════════
    // MULTI-WINDOW TREE ENGINE & DYNAMIC AUTO-UNLOCK (ZERO STATIC COORDS)
    // ═════════════════════════════════════════════════════════════════

    /**
     * Collects AccessibilityNodeInfo roots from ALL active non-Jarvis windows (sorted top-most layer first).
     * Captures Keyguard, BiometricPrompt, OEM App Lock overlays, and system dialogs that rootInActiveWindow misses.
     */
    fun getAllNonJarvisWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        try {
            val allWindows = windows
            if (!allWindows.isNullOrEmpty()) {
                val sorted = allWindows.sortedByDescending { it.layer }
                for (w in sorted) {
                    val root = try { w.root } catch (e: Exception) { null } ?: continue
                    val pkg = root.packageName?.toString() ?: ""
                    if (pkg != packageName) {
                        roots.add(root)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error inspecting windows: ${e.message}")
        }

        val activeRoot = rootInActiveWindow
        if (activeRoot != null && activeRoot.packageName?.toString() != packageName) {
            val alreadyAdded = roots.any { it == activeRoot }
            if (!alreadyAdded) roots.add(activeRoot)
        }
        return roots
    }

    /**
     * Returns package names of all currently visible non-Jarvis windows.
     */
    fun getAllActiveWindowPackages(): List<String> {
        return getAllNonJarvisWindowRoots()
            .mapNotNull { it.packageName?.toString()?.takeIf { p -> p.isNotBlank() } }
            .distinct()
    }

    /**
     * Traverses ALL non-Jarvis windows to find a matching node.
     */
    fun findNodeAcrossWindows(
        resolveClickableParent: Boolean = true,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        for (root in getAllNonJarvisWindowRoots()) {
            fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (predicate(node)) {
                    if (resolveClickableParent) {
                        var current: AccessibilityNodeInfo? = node
                        while (current != null) {
                            if (current.isClickable) return current
                            current = current.parent
                        }
                    }
                    return node
                }
                for (i in 0 until node.childCount) {
                    val found = search(node.getChild(i))
                    if (found != null) return found
                }
                return null
            }
            val match = search(root)
            if (match != null) return match
        }
        return null
    }

    /**
     * Dumps all interactive/readable elements across ALL non-Jarvis windows.
     */
    fun dumpAllWindowsElements(): List<ScreenElement> {
        val elements = mutableListOf<ScreenElement>()
        var counter = 1

        for (root in getAllNonJarvisWindowRoots()) {
            fun traverse(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val text = node.text?.toString()?.trim() ?: ""
                val cd = node.contentDescription?.toString()?.trim() ?: ""
                val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
                val isClickable = node.isClickable
                val isEditable = node.isEditable

                val bounds = Rect()
                node.getBoundsInScreen(bounds)

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
        }
        return elements
    }

    fun dumpAllWindowsElementsSummary(): String {
        val elements = dumpAllWindowsElements()
        if (elements.isEmpty()) return "Screen is empty or secure window."
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
     * Executes a GestureDescription and suspends until Android confirms completion or cancellation.
     */
    suspend fun dispatchGestureAwait(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)

            if (!dispatched && cont.isActive) {
                cont.resume(false)
            }
        }
    }

    /**
     * Taps a node cleanly using accessibility click, falling back to live Rect center gesture ONLY if click returns false.
     * Prevents double-digit entry ("11" instead of "1") on PIN keypads!
     */
    suspend fun tapNodeDynamically(node: AccessibilityNodeInfo?, allowDualTap: Boolean = false): Boolean {
        if (node == null) return false
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
            (node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
        if (clicked && !allowDualTap) return true

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() > 0 && bounds.height() > 0) {
            val tapDone = tapCoordinatesAwait(bounds.centerX().toFloat(), bounds.centerY().toFloat(), 50L)
            return clicked || tapDone
        }
        return clicked
    }

    suspend fun tapCoordinatesAwait(x: Float, y: Float, durationMs: Long = 50L): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + 1f, y + 1f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        return dispatchGestureAwait(gesture)
    }

    /**
     * Wakes the screen via PowerManager and performs a fast dynamic vertical swipe-up to reveal lock prompt.
     */
    suspend fun wakeScreenAndSwipeUp(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        try {
            if (pm != null && !pm.isInteractive) {
                @Suppress("DEPRECATION")
                val wakeLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Jarvis:ScreenUnlockWakeLock"
                )
                wakeLock?.acquire(3000L)
                val waitStart = System.currentTimeMillis()
                while (!pm.isInteractive && System.currentTimeMillis() - waitStart < 450L) {
                    delay(25)
                }
                delay(75)
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock error: ${e.message}")
        }

        val screenRect = getDynamicScreenRect()
        val w = screenRect.width().toFloat()
        val h = screenRect.height().toFloat()

        val path = Path().apply {
            moveTo(w / 2f, h * 0.78f)
            lineTo(w / 2f, h * 0.22f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 175))
            .build()
        return dispatchGestureAwait(gesture)
    }

    /**
     * Checks whether ANY active window currently exposes a Pattern lock grid or PIN keypad / input.
     */
    fun hasVisibleUnlockPrompt(): Boolean {
        if (findDynamicPatternPoints() != null) return true
        if (collectDynamicDigitNodes().size >= 9) return true
        if (findPinInputField() != null) return true
        if (findKeypadContainerBounds() != null) return true
        return false
    }

    /**
     * Dynamically locates the 3x3 pattern dot coordinates from live Accessibility nodes across ALL windows.
     * ZERO static screen sizes! Works across OnePlus, Samsung, Xiaomi, Pixel, LockScreen & AppLock.
     */
    fun findDynamicPatternPoints(): List<Pair<Float, Float>>? {
        val screenRect = getDynamicScreenRect()
        val minScreenDim = minOf(screenRect.width(), screenRect.height()).coerceAtLeast(320)
        val dotMinSize = (minScreenDim * 0.015f).toInt().coerceAtLeast(8)
        val dotMaxSize = (minScreenDim * 0.35f).toInt()
        val boxMinSize = (minScreenDim * 0.16f).toInt()

        // Strategy 1: Parent pattern container exposing 9 child/virtual dot nodes
        val containerWith9Dots = findNodeAcrossWindows(resolveClickableParent = false) { node ->
            val cls = node.className?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""
            val looksLikePattern = cls.contains("pattern") || resId.contains("pattern") || cd.contains("pattern")
            if (!looksLikePattern || node.childCount < 9) return@findNodeAcrossWindows false

            var validChildren = 0
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                val r = Rect()
                c.getBoundsInScreen(r)
                if (r.width() in dotMinSize..dotMaxSize && r.height() in dotMinSize..dotMaxSize) validChildren++
            }
            validChildren == 9
        }

        if (containerWith9Dots != null) {
            val dotRects = mutableListOf<Rect>()
            for (i in 0 until containerWith9Dots.childCount) {
                val c = containerWith9Dots.getChild(i) ?: continue
                val r = Rect()
                c.getBoundsInScreen(r)
                if (r.width() in dotMinSize..dotMaxSize && r.height() in dotMinSize..dotMaxSize) dotRects.add(r)
            }
            if (dotRects.size == 9) {
                val sortedByY = dotRects.sortedBy { it.centerY() }
                val ordered = mutableListOf<Rect>()
                for (rowIdx in 0..2) {
                    val rowSlice = sortedByY.subList(rowIdx * 3, rowIdx * 3 + 3).sortedBy { it.centerX() }
                    ordered.addAll(rowSlice)
                }
                return ordered.map { Pair(it.centerX().toFloat(), it.centerY().toFloat()) }
            }
        }

        // Strategy 2: Live LockPatternView container node — collect all matching candidates and choose
        // the innermost roughly-square view (never accidentally matching an outer full-screen wrapper layout).
        val patternCandidates = mutableListOf<Pair<Int, Rect>>()
        for (root in getAllNonJarvisWindowRoots()) {
            fun scanPattern(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val cls = node.className?.toString()?.lowercase() ?: ""
                val resId = node.viewIdResourceName?.lowercase() ?: ""
                val cd = node.contentDescription?.toString()?.lowercase() ?: ""
                if (cls.contains("pattern") || resId.contains("pattern") || cd.contains("pattern")) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (r.width() >= boxMinSize && r.height() >= boxMinSize) {
                        val minDim = min(r.width(), r.height()).toFloat()
                        val maxDim = maxOf(r.width(), r.height()).toFloat()
                        val squareness = minDim / maxDim.coerceAtLeast(1f)
                        if (squareness >= 0.55f) {
                            var score = 0
                            if (cls.contains("lockpattern") || cls.contains("patternview")) score += 50
                            if (squareness >= 0.85f) score += 30
                            // Prefer tighter inner bounds over giant screen-wide wrappers
                            val compactness = (squareness * 20).toInt()
                            score += compactness
                            patternCandidates.add(Pair(score, r))
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    scanPattern(node.getChild(i))
                }
            }
            scanPattern(root)
        }

        val bestBox = patternCandidates
            .sortedWith(compareByDescending<Pair<Int, Rect>> { it.first }.thenBy { it.second.width().toLong() * it.second.height().toLong() })
            .firstOrNull()?.second

        if (bestBox != null) {
            val squareSize = minOf(bestBox.width(), bestBox.height()).toFloat()
            val left = bestBox.centerX() - squareSize / 2f
            val top = bestBox.centerY() - squareSize / 2f
            val cell = squareSize / 3f

            val pts = mutableListOf<Pair<Float, Float>>()
            for (row in 0..2) {
                for (col in 0..2) {
                    pts.add(Pair(left + cell * (col + 0.5f), top + cell * (row + 0.5f)))
                }
            }
            return pts
        }

        return null
    }

    /**
     * Draws an unlock pattern dynamically across live discovered 3x3 grid coordinates.
     * Never uses hardcoded screen positions — returns false if pattern grid isn't accessible.
     */
    suspend fun drawPatternDynamic(dots: List<Int>): Boolean {
        if (dots.size < 2) return false
        val gridPoints = findDynamicPatternPoints()
        if (gridPoints == null || gridPoints.size != 9) {
            Log.w(TAG, "Dynamic PatternView bounds not found in active windows.")
            return false
        }

        val strokePts = dots.mapNotNull { d ->
            val idx = d - 1
            if (idx in 0..8) gridPoints[idx] else null
        }
        if (strokePts.size < 2) return false

        val path = Path()
        val first = strokePts[0]
        path.moveTo(first.first, first.second)
        path.lineTo(first.first + 1f, first.second + 1f)

        for (i in 1 until strokePts.size) {
            val prev = strokePts[i - 1]
            val curr = strokePts[i]
            val midX = (prev.first + curr.first) / 2f
            val midY = (prev.second + curr.second) / 2f
            path.lineTo(midX, midY)
            path.lineTo(curr.first, curr.second)
        }

        val duration = (strokePts.size * 180L).coerceIn(550L, 1600L)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()

        return dispatchGestureAwait(gesture)
    }

    private fun collectDynamicDigitNodes(): Map<Char, AccessibilityNodeInfo> {
        val screenRect = getDynamicScreenRect()
        val minScreenDim = minOf(screenRect.width(), screenRect.height()).coerceAtLeast(320)
        val keyMinSize = (minScreenDim * 0.02f).toInt().coerceAtLeast(10)
        val keyMaxSize = (minScreenDim * 0.55f).toInt()
        val bestCandidates = mutableMapOf<Char, Pair<Int, AccessibilityNodeInfo>>()
        val wordToDigit = mapOf(
            "zero" to '0', "one" to '1', "two" to '2', "three" to '3', "four" to '4',
            "five" to '5', "six" to '6', "seven" to '7', "eight" to '8', "nine" to '9'
        )
        val resIdDigitRegex = Regex("(?i)(?:key|digit|num|btn|button|pin|pad)[_-]?([0-9])$")
        val keypadLabelRegex = Regex("^([0-9])(?:[\\s,\\n]+[A-Za-z+∞\\s]{1,12})?$")
        val ignoreIdWords = listOf("clock", "time", "date", "battery", "percent", "status", "alarm", "carrier", "sim")

        for (root in getAllNonJarvisWindowRoots()) {
            fun scan(node: AccessibilityNodeInfo?) {
                if (node == null) return
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (bounds.width() in keyMinSize..keyMaxSize && bounds.height() in keyMinSize..keyMaxSize) {
                    val resId = node.viewIdResourceName?.lowercase() ?: ""
                    if (ignoreIdWords.none { resId.contains(it) }) {
                        val rawText = node.text?.toString()?.trim() ?: ""
                        val rawCd = node.contentDescription?.toString()?.trim() ?: ""
                        val isClickableOrChild = node.isClickable || node.parent?.isClickable == true

                        var matchedDigit: Char? = null
                        var score = 0

                        val resMatch = resIdDigitRegex.find(resId.substringAfterLast('/'))
                        if (resMatch != null) {
                            matchedDigit = resMatch.groupValues[1][0]
                            score = 3
                        } else if (isClickableOrChild) {
                            val tMatch = keypadLabelRegex.find(rawText)
                            val cMatch = keypadLabelRegex.find(rawCd)
                            when {
                                rawText.length == 1 && rawText[0].isDigit() -> {
                                    matchedDigit = rawText[0]
                                    score = 3
                                }
                                rawCd.length == 1 && rawCd[0].isDigit() -> {
                                    matchedDigit = rawCd[0]
                                    score = 3
                                }
                                tMatch != null -> {
                                    matchedDigit = tMatch.groupValues[1][0]
                                    score = 2
                                }
                                cMatch != null -> {
                                    matchedDigit = cMatch.groupValues[1][0]
                                    score = 2
                                }
                                wordToDigit.containsKey(rawCd.lowercase()) -> {
                                    matchedDigit = wordToDigit[rawCd.lowercase()]
                                    score = 1
                                }
                            }
                        }

                        if (matchedDigit != null && score > 0) {
                            val parent = node.parent
                            val targetNode = if (!node.isClickable && parent?.isClickable == true) parent else node
                            val existing = bestCandidates[matchedDigit]
                            if (existing == null || score > existing.first) {
                                bestCandidates[matchedDigit] = Pair(score, targetNode)
                            }
                        }
                    }
                }

                for (i in 0 until node.childCount) {
                    scan(node.getChild(i))
                }
            }
            scan(root)
        }

        return bestCandidates.mapValues { it.value.second }
    }

    private fun findPinInputField(): AccessibilityNodeInfo? {
        return findNodeAcrossWindows(resolveClickableParent = false) { node ->
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() <= 0 || bounds.height() <= 0) return@findNodeAcrossWindows false

            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val isPwdInput = node.isPassword && node.isEditable
            val isExplicitPinEdit = node.isEditable && (
                resId.contains("pin") || resId.contains("password") || resId.contains("pwd") ||
                resId.contains("lock") || resId.contains("privacy")
            )
            isPwdInput || isExplicitPinEdit
        }
    }

    private fun findKeypadContainerBounds(): Rect? {
        val screenRect = getDynamicScreenRect()
        val keypadMinSize = (minOf(screenRect.width(), screenRect.height()) * 0.22f).toInt().coerceAtLeast(100)
        val keypadNode = findNodeAcrossWindows(resolveClickableParent = false) { node ->
            val cls = node.className?.toString()?.lowercase() ?: ""
            val resId = node.viewIdResourceName?.lowercase() ?: ""
            val isNumpad = resId.contains("keypad") || resId.contains("numpad") || resId.contains("pin_keyboard") ||
                resId.contains("security_keyboard") || resId.contains("pin_pad") || resId.contains("keyguard_pin") ||
                resId.contains("password_keyboard") || cls.contains("numpad") || cls.contains("numeric")
            if (!isNumpad) return@findNodeAcrossWindows false
            val r = Rect()
            node.getBoundsInScreen(r)
            r.width() >= keypadMinSize && r.height() >= keypadMinSize
        } ?: return null

        val box = Rect()
        keypadNode.getBoundsInScreen(box)
        return if (box.width() >= keypadMinSize && box.height() >= keypadMinSize) box else null
    }

    /**
     * Dynamically enters PIN / Password across ANY phone & lock screen with ZERO static screen coordinates.
     * Supports: (1) Live keypad digit nodes 0-9, (2) Editable password fields, (3) Live keypad container bounds.
     */
    suspend fun enterPinDynamic(pin: String, stillLockedCheck: () -> Boolean): Boolean {
        if (pin.isBlank()) return false

        // 1. Best for SystemUI & App Lock numeric keypads: discover individual digit nodes dynamically
        val digitNodes = collectDynamicDigitNodes()
        if (pin.all { digitNodes.containsKey(it) }) {
            Log.d(TAG, "Entering PIN via ${digitNodes.size} live digit nodes")
            for (ch in pin) {
                val node = digitNodes[ch] ?: return false
                tapNodeDynamically(node)
                delay(40)
            }
            val unlockWaitStart = System.currentTimeMillis()
            while (stillLockedCheck() && System.currentTimeMillis() - unlockWaitStart < 280L) {
                delay(40)
            }
            if (stillLockedCheck()) {
                val enterBtn = findNodeAcrossWindows(resolveClickableParent = true) { n ->
                    val res = n.viewIdResourceName?.lowercase() ?: ""
                    val txt = n.text?.toString()?.trim()?.lowercase() ?: ""
                    val cd = n.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                    res.contains("key_enter") || res.endsWith("enter") || res.endsWith("ok") ||
                        res.endsWith("done") || res.endsWith("confirm") ||
                        txt in listOf("ok", "enter", "done", "confirm", "✓") ||
                        cd in listOf("ok", "enter", "done", "confirm", "submit")
                }
                if (enterBtn != null) {
                    tapNodeDynamically(enterBtn)
                }
            }
            return true
        }

        // 2. Editable password/PIN input field (OnePlus Privacy Password dialog, alphanumeric lock, etc.)
        val inputField = findPinInputField()
        if (inputField != null) {
            Log.d(TAG, "Entering PIN via live editable password field")
            inputField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val typed = typeText(inputField, pin)
            if (typed) {
                val unlockWaitStart = System.currentTimeMillis()
                while (stillLockedCheck() && System.currentTimeMillis() - unlockWaitStart < 240L) {
                    delay(40)
                }
                if (stillLockedCheck()) {
                    val submitBtn = findNodeAcrossWindows(resolveClickableParent = true) { n ->
                        val res = n.viewIdResourceName?.lowercase() ?: ""
                        val txt = n.text?.toString()?.trim()?.lowercase() ?: ""
                        val cd = n.contentDescription?.toString()?.trim()?.lowercase() ?: ""
                        txt in listOf("ok", "enter", "done", "confirm", "unlock", "verify", "next") ||
                            cd in listOf("ok", "enter", "done", "confirm", "unlock", "verify") ||
                            res.contains("enter") || res.contains("confirm") || res.contains("ok")
                    }
                    if (submitBtn != null) {
                        tapNodeDynamically(submitBtn)
                    }
                }
                return true
            }
        }

        // 3. Fallback ONLY from live keypad container node Rect (never hardcoded screen sizes)
        val keypadBox = findKeypadContainerBounds()
        if (keypadBox != null && pin.all { it.isDigit() }) {
            Log.d(TAG, "Entering PIN via live keypad container bounds: $keypadBox")
            val colW = keypadBox.width() / 3f
            val rowH = keypadBox.height() / 4f
            fun digitCoordInBox(ch: Char): Pair<Float, Float> {
                return when (ch) {
                    '1' -> Pair(keypadBox.left + colW * 0.5f, keypadBox.top + rowH * 0.5f)
                    '2' -> Pair(keypadBox.left + colW * 1.5f, keypadBox.top + rowH * 0.5f)
                    '3' -> Pair(keypadBox.left + colW * 2.5f, keypadBox.top + rowH * 0.5f)
                    '4' -> Pair(keypadBox.left + colW * 0.5f, keypadBox.top + rowH * 1.5f)
                    '5' -> Pair(keypadBox.left + colW * 1.5f, keypadBox.top + rowH * 1.5f)
                    '6' -> Pair(keypadBox.left + colW * 2.5f, keypadBox.top + rowH * 1.5f)
                    '7' -> Pair(keypadBox.left + colW * 0.5f, keypadBox.top + rowH * 2.5f)
                    '8' -> Pair(keypadBox.left + colW * 1.5f, keypadBox.top + rowH * 2.5f)
                    '9' -> Pair(keypadBox.left + colW * 2.5f, keypadBox.top + rowH * 2.5f)
                    else -> Pair(keypadBox.left + colW * 1.5f, keypadBox.top + rowH * 3.5f) // '0'
                }
            }
            for (ch in pin) {
                val pt = digitCoordInBox(ch)
                tapCoordinatesAwait(pt.first, pt.second, 45L)
                delay(45)
            }
            val unlockWaitStart = System.currentTimeMillis()
            while (stillLockedCheck() && System.currentTimeMillis() - unlockWaitStart < 280L) {
                delay(40)
            }
            if (stillLockedCheck()) {
                tapCoordinatesAwait(keypadBox.left + colW * 2.5f, keypadBox.top + rowH * 3.5f, 45L)
            }
            return true
        }

        Log.w(TAG, "No dynamic PIN input field, digit nodes, or keypad container found in active windows.")
        return false
    }
}
