package com.jarvis.assistant.debug

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ScreenElement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared thread-safe execution trace store capturing A-to-Z runtime telemetry:
 * UI Accessibility tree dumps, picked nodes & scores, rejected candidates,
 * Gemini raw responses & reasoning, latency timings, and step outcomes.
 * Works identically across MainActivity, FloatingBubbleService, and background workers.
 */
object JarvisLogStore {

    private const val MAX_LINES = 1200
    private const val DISPLAY_TAIL_LINES = 350
    private const val LOG_FILE_NAME = "jarvis_trace.log"

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val ioScope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var listener: (() -> Unit)? = null

    fun init(context: Context) {
        val targetFile = File(context.applicationContext.filesDir, LOG_FILE_NAME)
        val shouldLoad = synchronized(lock) {
            if (logFile == null) logFile = targetFile
            lines.isEmpty() && targetFile.exists()
        }
        if (shouldLoad) {
            try {
                val saved = targetFile.readLines().takeLast(MAX_LINES)
                synchronized(lock) {
                    if (lines.isEmpty()) {
                        lines.addAll(saved)
                    }
                }
                notifyListener()
            } catch (_: Exception) {
            }
        }
    }

    fun setListener(cb: (() -> Unit)?) {
        listener = cb
    }

    fun log(category: String, message: String) {
        val stamp = synchronized(timeFmt) { timeFmt.format(Date()) }
        val sanitized = message.trimEnd()
        val formatted = "[$stamp] [$category] $sanitized"
        Log.d("JarvisTrace", formatted)

        synchronized(lock) {
            lines.addLast(formatted)
            while (lines.size > MAX_LINES) {
                lines.removeFirst()
            }
        }
        notifyListener()
        persistLineAsync(formatted)
    }

    fun logScreenTree(stage: String, elements: List<ScreenElement>, limit: Int = 55) {
        val header = "TREE_SNAPSHOT stage='$stage' count=${elements.size} (showing ${minOf(elements.size, limit)})"
        val body = buildString {
            append(header)
            elements.take(limit).forEach { el ->
                val res = try {
                    el.node.viewIdResourceName?.substringAfterLast('/') ?: ""
                } catch (_: Exception) {
                    ""
                }
                val b = el.bounds
                val txt = el.text.replace("\n", " ").take(65)
                val cd = el.contentDescription.replace("\n", " ").take(65)
                append("\n  [${el.tag}] cls=${el.className} txt='$txt' cd='$cd' res='$res' bounds=[${b.left},${b.top},${b.right},${b.bottom}] click=${el.isClickable} edit=${el.isEditable} sel=${el.node.isSelected}")
            }
        }
        log("UI_TREE", body)
    }

    fun describeElement(el: ScreenElement?): String {
        if (el == null) return "null"
        val res = try {
            el.node.viewIdResourceName?.substringAfterLast('/') ?: ""
        } catch (_: Exception) {
            ""
        }
        val b = el.bounds
        return "tag=${el.tag} cls=${el.className} txt='${el.text.replace("\n", " ").take(50)}' cd='${el.contentDescription.replace("\n", " ").take(50)}' res='$res' bounds=[${b.left},${b.top},${b.right},${b.bottom}] click=${el.isClickable} edit=${el.isEditable} sel=${el.node.isSelected}"
    }

    fun getFullLogText(): String = synchronized(lock) {
        lines.joinToString("\n")
    }

    fun getDisplayLogText(): String = synchronized(lock) {
        val start = (lines.size - DISPLAY_TAIL_LINES).coerceAtLeast(0)
        lines.drop(start).joinToString("\n")
    }

    fun lineCount(): Int = synchronized(lock) { lines.size }

    fun clear() {
        synchronized(lock) {
            lines.clear()
        }
        ioScope.launch {
            try {
                logFile?.writeText("")
            } catch (_: Exception) {
            }
        }
        log("SYSTEM", "Console trace logs cleared.")
    }

    private fun persistLineAsync(entry: String) {
        val f = logFile ?: return
        ioScope.launch {
            try {
                if (f.exists() && f.length() > 350_000L) {
                    val snapshot = synchronized(lock) { lines.joinToString("\n") }
                    f.writeText(if (snapshot.isNotEmpty()) "$snapshot\n" else "")
                } else {
                    f.appendText("$entry\n")
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun notifyListener() {
        try {
            listener?.invoke()
        } catch (_: Exception) {
        }
    }
}
