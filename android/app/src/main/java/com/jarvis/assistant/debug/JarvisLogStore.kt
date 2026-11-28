package com.jarvis.assistant.debug

import android.content.Context
import android.util.Log
import com.jarvis.assistant.ScreenElement
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Shared thread-safe execution trace store capturing A-to-Z runtime telemetry:
 * UI Accessibility tree dumps, picked nodes & scores, rejected candidates,
 * Gemini raw responses & reasoning, voice commands, and step outcomes.
 * Persists daily rolling logs across app closes, background runs, and device reboots (3-day retention).
 */
object JarvisLogStore {

    private const val MAX_MEMORY_LINES = 2500
    private const val DISPLAY_TAIL_LINES = 450
    private const val RETENTION_DAYS = 3
    private const val MAX_DAILY_FILE_BYTES = 8_000_000L
    private const val LOGS_DIR_NAME = "logs"
    private const val LEGACY_LOG_FILE_NAME = "jarvis_trace.log"

    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fileExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var logsDir: File? = null

    @Volatile
    private var listener: (() -> Unit)? = null

    fun init(context: Context) {
        val appCtx = context.applicationContext
        val dir = try {
            File(appCtx.filesDir, LOGS_DIR_NAME).apply { mkdirs() }
        } catch (_: Exception) {
            return
        }

        // One-time migration from legacy single jarvis_trace.log into today's daily log
        try {
            val legacy = File(appCtx.filesDir, LEGACY_LOG_FILE_NAME)
            val todayFile = File(dir, dailyFileName(Date()))
            if (legacy.exists() && legacy.length() > 0 && !todayFile.exists()) {
                legacy.copyTo(todayFile, overwrite = false)
                legacy.delete()
            }
        } catch (_: Exception) {
        }

        val shouldLoad = synchronized(lock) {
            if (logsDir == null) logsDir = dir
            lines.isEmpty()
        }
        if (shouldLoad) {
            try {
                pruneOldLogFiles(dir)
                val recentFiles = listSortedLogFiles(dir)
                val loaded = ArrayDeque<String>()
                for (f in recentFiles) {
                    f.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        for (ln in seq) {
                            loaded.addLast(ln)
                            while (loaded.size > MAX_MEMORY_LINES) {
                                loaded.removeFirst()
                            }
                        }
                    }
                }
                synchronized(lock) {
                    if (lines.isEmpty()) {
                        lines.addAll(loaded)
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
        val now = Date()
        val stamp = synchronized(timeFmt) { timeFmt.format(now) }
        val dayKey = synchronized(dayFmt) { dayFmt.format(now) }
        val sanitized = message.trimEnd()
        val formatted = "[$stamp] [$category] $sanitized"
        Log.d("JarvisTrace", formatted)

        synchronized(lock) {
            lines.addLast(formatted)
            while (lines.size > MAX_MEMORY_LINES) {
                lines.removeFirst()
            }
        }
        notifyListener()
        persistLineAsync(dayKey, formatted)
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

    fun getFullLogText(): String {
        val dir = logsDir
        if (dir != null && dir.exists()) {
            try {
                val files = listSortedLogFiles(dir)
                if (files.isNotEmpty()) {
                    val sb = StringBuilder()
                    for (f in files) {
                        if (sb.isNotEmpty()) sb.append('\n')
                        sb.append(f.readText(Charsets.UTF_8))
                    }
                    if (sb.isNotEmpty()) return sb.toString()
                }
            } catch (_: Exception) {
            }
        }
        return synchronized(lock) { lines.joinToString("\n") }
    }

    fun writeFullLogsTo(out: OutputStream): Int {
        var totalLines = 0
        val dir = logsDir
        if (dir != null && dir.exists()) {
            val files = listSortedLogFiles(dir)
            for (f in files) {
                try {
                    f.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        for (ln in seq) {
                            out.write(ln.toByteArray(Charsets.UTF_8))
                            out.write('\n'.code)
                            totalLines++
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }
        if (totalLines == 0) {
            val snapshot = synchronized(lock) { lines.toList() }
            for (ln in snapshot) {
                out.write(ln.toByteArray(Charsets.UTF_8))
                out.write('\n'.code)
                totalLines++
            }
        }
        out.flush()
        return totalLines
    }

    fun getDisplayLogText(): String = synchronized(lock) {
        val start = (lines.size - DISPLAY_TAIL_LINES).coerceAtLeast(0)
        lines.drop(start).joinToString("\n")
    }

    fun lineCount(): Int {
        val dir = logsDir
        if (dir != null && dir.exists()) {
            return try {
                var count = 0
                for (f in listSortedLogFiles(dir)) {
                    f.bufferedReader(Charsets.UTF_8).useLines { seq ->
                        count += seq.count()
                    }
                }
                count
            } catch (_: Exception) {
                synchronized(lock) { lines.size }
            }
        }
        return synchronized(lock) { lines.size }
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
        }
        val dir = logsDir
        if (dir != null) {
            fileExecutor.execute {
                try {
                    dir.listFiles()?.forEach { it.delete() }
                } catch (_: Exception) {
                }
            }
        }
        log("SYSTEM", "Console trace logs cleared.")
    }

    private fun dailyFileName(date: Date): String {
        val key = synchronized(dayFmt) { dayFmt.format(date) }
        return "jarvis_$key.log"
    }

    private fun listSortedLogFiles(dir: File): List<File> {
        return dir.listFiles { f -> f.isFile && f.name.startsWith("jarvis_") && f.name.endsWith(".log") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    private fun pruneOldLogFiles(dir: File) {
        val files = listSortedLogFiles(dir)
        if (files.size > RETENTION_DAYS) {
            files.dropLast(RETENTION_DAYS).forEach {
                try {
                    it.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun persistLineAsync(dayKey: String, entry: String) {
        val dir = logsDir ?: return
        fileExecutor.execute {
            try {
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, "jarvis_$dayKey.log")
                if (!target.exists()) {
                    pruneOldLogFiles(dir)
                } else if (target.length() > MAX_DAILY_FILE_BYTES) {
                    val kept = target.readLines(Charsets.UTF_8).takeLast(MAX_MEMORY_LINES)
                    target.writeText(if (kept.isNotEmpty()) "${kept.joinToString("\n")}\n" else "", Charsets.UTF_8)
                }
                target.appendText("$entry\n", Charsets.UTF_8)
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
