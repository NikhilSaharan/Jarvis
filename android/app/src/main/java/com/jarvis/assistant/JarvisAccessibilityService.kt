package com.jarvis.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "J.A.R.V.I.S. Accessibility Service Connected!")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Real-time event receiver (0ms latency, runs in RAM)
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
     * Finds a clickable node matching text or content description.
     * If the node itself is not clickable, traverses up to find its clickable parent.
     */
    fun findClickableNode(target: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val lowerTarget = target.lowercase().trim()

        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null

            val text = node.text?.toString()?.lowercase() ?: ""
            val cd = node.contentDescription?.toString()?.lowercase() ?: ""

            if (text.contains(lowerTarget) || cd.contains(lowerTarget)) {
                // Find clickable ancestor if this node is not directly clickable
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
     * Finds the first editable text field (EditText) on screen.
     */
    fun findFirstInputField(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null

        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.isEditable || node.className?.contains("EditText") == true) {
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
     * Direct node action: Click (0ms native Android trigger, no touch coordinates needed).
     */
    fun clickNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!success) {
            // Fallback: try clicking parent
            return node.parent?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        }
        return true
    }

    /**
     * Direct node action: Set Text (types string directly into field in 1ms).
     */
    fun typeText(node: AccessibilityNodeInfo?, text: String): Boolean {
        if (node == null) return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
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
}
