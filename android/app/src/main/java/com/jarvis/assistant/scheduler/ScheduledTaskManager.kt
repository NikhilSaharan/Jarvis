package com.jarvis.assistant.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class ScheduledTaskItem(
    val id: String,
    val executeAtMillis: Long,
    val action: String,
    val actionArgsJson: String,
    val summary: String
)

class ScheduledTaskManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("jarvis_persistent_tasks", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun getPendingTasks(): List<ScheduledTaskItem> {
        val json = prefs.getString("tasks_queue", null) ?: return emptyList()
        val type = object : TypeToken<List<ScheduledTaskItem>>() {}.type
        return gson.fromJson(json, type) ?: emptyList()
    }

    private fun saveTasks(tasks: List<ScheduledTaskItem>) {
        prefs.edit().putString("tasks_queue", gson.toJson(tasks)).apply()
    }

    /**
     * Schedules a future task that persists across app closes, device reboots, and other commands.
     * NEVER drops even if another task is scheduled immediately after!
     */
    fun scheduleTask(
        delaySeconds: Int,
        action: String,
        actionArgs: Map<String, Any>,
        summary: String = ""
    ): String {
        val safeDelay = delaySeconds.coerceAtLeast(1)
        val triggerAtMillis = System.currentTimeMillis() + (safeDelay * 1000L)
        val taskId = UUID.randomUUID().toString()
        val argsJson = gson.toJson(actionArgs)

        val taskItem = ScheduledTaskItem(
            id = taskId,
            executeAtMillis = triggerAtMillis,
            action = action,
            actionArgsJson = argsJson,
            summary = summary
        )

        val currentTasks = getPendingTasks().toMutableList()
        currentTasks.add(taskItem)
        saveTasks(currentTasks)

        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            setAction("com.jarvis.assistant.EXECUTE_SCHEDULED_TASK")
            putExtra("task_id", taskId)
            putExtra("action", action)
            putExtra("action_args_json", argsJson)
            putExtra("summary", summary)
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val requestCode = taskId.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            Log.d("ScheduledTaskManager", "Task '$action' scheduled for $safeDelay seconds (ID: $taskId)")
        } catch (e: Exception) {
            Log.e("ScheduledTaskManager", "AlarmManager error: ${e.message}")
        }

        return taskId
    }

    fun removeTask(taskId: String) {
        val currentTasks = getPendingTasks().toMutableList()
        currentTasks.removeAll { it.id == taskId }
        saveTasks(currentTasks)
    }
}
