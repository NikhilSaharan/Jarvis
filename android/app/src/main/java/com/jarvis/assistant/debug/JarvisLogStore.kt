package com.jarvis.assistant.debug

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ScreenElement
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Shared thread-safe execution trace store capturing A-to-Z runtime telemetry:
 * UI Accessibility tree dumps, picked nodes & scores, rejected candidates,
 * Gemini raw responses & reasoning, latency timings, and step outcomes.
 * Works identically across MainActivity, FloatingBubbleService, and background workers.
 */
object JarvisLogStore {

    private const val MAX_LINES = 1500
    private const val DISPLAY_TAIL_LINES = 400
    private const val LOG_FILE_NAME = "jarvis_trace.log"

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileExecutor = Executors.newSingleThreadExecutor()

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

    fun logScreenTree(stage: String, elements: List<ScreenElement>, limit: Int = 65) {
        val body = buildString {
            append("TREE_SNAPSHOT stage='$stage' count=${elements.size}")
            elements.take(limit).forEach { el ->
                val res = try {
                    el.node.viewIdResourceName?.substringAfterLast('/') ?: ""
                } catch (_: Exception) {
                    ""
                }
                val b = el.bounds
                val txt = el.text.replace("\n", " ").take(75)
                val cd = el.contentDescription.replace("\n", " ").take(75)
                val flags = buildList {
                    if (el.isClickable) add("C")
                    if (el.isEditable) add("E")
                    if (el.node.isSelected) add("S")
                }.joinToString(",")
                append("\n  [${el.tag}] ${el.className}")
                if (txt.isNotEmpty()) append(" txt='$txt'")
                if (cd.isNotEmpty() && cd != txt) append(" cd='$cd'")
                if (res.isNotEmpty()) append(" id='$res'")
                append(" [${b.left},${b.top},${b.right},${b.bottom}]")
                if (flags.isNotEmpty()) append(" [$flags]")
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
        return "tag=${el.tag} ${el.className} txt='${el.text.replace("\n", " ").take(55)}' cd='${el.contentDescription.replace("\n", " ").take(55)}' id='$res' [${b.left},${b.top},${b.right},${b.bottom}] click=${el.isClickable} edit=${el.isEditable}"
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
        val f = logFile
        if (f != null) {
            fileExecutor.execute {
                try {
                    f.writeText("")
                } catch (_: Exception) {
                }
            }
        }
        log("SYSTEM", "Console trace logs cleared.")
    }

    private fun persistLineAsync(entry: String) {
        val f = logFile ?: return
        fileExecutor.execute {
            try {
                if (f.exists() && f.length() > 400_000L) {
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
