package com.jarvis.assistant.brain

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.jarvis.assistant.memory.JarvisMemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

data class ActionDecision(
    val speech: String?,
    val toolName: String?,
    val toolArgs: Map<String, Any>?
)

data class UIActionDecision(
    val action: String,
    val tag: Int?,
    val text: String?,
    val direction: String?,
    val summary: String? = null
)

class GeminiBrain(
    private val apiKey: String,
    private val memoryManager: JarvisMemoryManager? = null
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // Strict rule: Strictly gemini-3.5-flash-lite primary, with fallback to gemini-3.1-flash-lite only.
    // Absolutely no other model is used.
    private val models = listOf(
        "gemini-3.5-flash-lite",
        "gemini-3.1-flash-lite"
    )

    suspend fun processCommand(userText: String, screenElementsSummary: String = ""): ActionDecision = withContext(Dispatchers.IO) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) {
            return@withContext ActionDecision("Ji Sir, kahiye?", null, null)
        }

        if (apiKey.isBlank() || apiKey.startsWith("your_")) {
            return@withContext ActionDecision("Sir, Gemini API Key configure nahi hai. Kripya app me [API KEY] button dabakar apni API key enter karein.", null, null)
        }

        val prompt = buildPrompt(trimmed, screenElementsSummary)
        var lastError = ""

        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val requestPayload = JsonObject().apply {
                    val contents = com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            val parts = com.google.gson.JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", prompt) })
                            }
                            add("parts", parts)
                        })
                    }
                    add("contents", contents)

                    val genConfig = JsonObject().apply {
                        addProperty("temperature", 0.3)
                        addProperty("response_mime_type", "application/json")
                    }
                    add("generation_config", genConfig)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(requestPayload.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val jsonResponse = gson.fromJson(body, JsonObject::class.java)
                    val candidates = jsonResponse.getAsJsonArray("candidates")
                    val firstCandidate = candidates?.get(0)?.asJsonObject
                    val textContent = firstCandidate?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

                    val parsedDecision = gson.fromJson(textContent, JsonObject::class.java)
                    val toolName = parsedDecision.get("tool_name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
                    val rawSpeech = parsedDecision.get("speech")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
                    val speech = if (toolName == null && rawSpeech == null) "Ji Sir?" else rawSpeech
                    val argsObj = parsedDecision.get("tool_args")?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject

                    val argsMap = mutableMapOf<String, Any>()
                    argsObj?.entrySet()?.forEach { (k, v) ->
                        if (!v.isJsonNull && v.isJsonPrimitive) {
                            val prim = v.asJsonPrimitive
                            when {
                                prim.isBoolean -> argsMap[k] = prim.asBoolean
                                prim.isNumber -> argsMap[k] = prim.asInt
                                else -> argsMap[k] = prim.asString
                            }
                        } else if (!v.isJsonNull && v.isJsonObject) {
                            val subMap = mutableMapOf<String, Any>()
                            v.asJsonObject.entrySet().forEach { (sk, sv) ->
                                if (!sv.isJsonNull && sv.isJsonPrimitive) subMap[sk] = sv.asString
                            }
                            argsMap[k] = subMap
                        }
                    }

                    return@withContext ActionDecision(speech, toolName, if (argsMap.isEmpty()) null else argsMap)
                } else {
                    lastError = "Gemini $model HTTP ${response.code}"
                    Log.w("GeminiBrain", "$lastError: $body")
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Network error"
                Log.w("GeminiBrain", "Gemini $model request failed: $lastError")
            }
        }

        // When cloud AI cannot be reached
        ActionDecision("Sir, Gemini AI se sampark nahi ho saka ($lastError). Kripya internet connection aur API key check karein.", null, null)
    }

    /**
     * Inspects the live accessibility screen tree with Gemini to determine next UI action.
     */
    suspend fun decideUIAction(prompt: String): UIActionDecision = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey.startsWith("your_")) {
            return@withContext UIActionDecision("done", null, null, null)
        }

        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val requestPayload = JsonObject().apply {
                    val contents = com.google.gson.JsonArray().apply {
                        add(JsonObject().apply {
                            val parts = com.google.gson.JsonArray().apply {
                                add(JsonObject().apply { addProperty("text", prompt) })
                            }
                            add("parts", parts)
                        })
                    }
                    add("contents", contents)

                    val genConfig = JsonObject().apply {
                        addProperty("temperature", 0.1)
                        addProperty("response_mime_type", "application/json")
                    }
                    add("generation_config", genConfig)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(requestPayload.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val jsonResponse = gson.fromJson(body, JsonObject::class.java)
                    val candidates = jsonResponse.getAsJsonArray("candidates")
                    val firstCandidate = candidates?.get(0)?.asJsonObject
                    val textContent = firstCandidate?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

                    val parsed = gson.fromJson(textContent, JsonObject::class.java)
                    val action = parsed.get("action")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString ?: "done"
                    val tag = parsed.get("tag")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt
                    val text = parsed.get("text")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                    val dir = parsed.get("direction")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                    val summary = parsed.get("summary")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                    return@withContext UIActionDecision(action, tag, text, dir, summary)
                }
            } catch (e: Exception) {
                Log.w("GeminiBrain", "UI action decision error on $model: ${e.message}")
            }
        }
        UIActionDecision("done", null, null, null, null)
    }

    private fun buildPrompt(userText: String, screenElementsSummary: String): String {
        val cleanInput = userText.replace("\"", "'")
        val livingMemory = memoryManager?.getLivingMemoryContext() ?: ""
        val memoryBlock = if (livingMemory.isNotBlank()) {
            "\nJARVIS LIVING MEMORY & USER CONTEXT:\n$livingMemory\n"
        } else ""

        return """
You are J.A.R.V.I.S., an intelligent, loyal, and friendly butler companion to 'Sir'.
You have full autonomous control over Sir's Android smartphone.
You converse naturally in casual everyday Hinglish (the way real friends talk in India—simple Hindi mixed naturally with English).
$memoryBlock
Sir's Input: "$cleanInput"
Live Screen Elements:
$screenElementsSummary

OPERATING PRINCIPLES:

1. CONVERSATION & EMOTIONS (No Action Needed):
   - When Sir shares feelings, mood, boredom, fatigue, pain, or asks questions/facts:
   - Reply like a warm, caring friend and butler in casual, everyday Hinglish.
   - Do NOT use bookish or complex Hindi words. Keep it natural and short (1-2 sentences).
   - Set `speech` to your reply, and set `tool_name`: null, `tool_args`: null.
   - NEVER trigger music, media, or apps unless Sir explicitly asked you to do so.

2. ACTIONS & TASKS (Direct Command Given):
   - When Sir explicitly commands an action (e.g. play a video/reel, send a message, open an app, make a call):
   - Set the appropriate tool in `tool_name` and `tool_args`.
   - Set `speech`: null (let the action execute directly and confirm dynamically).
   - EXCEPTION: If Sir expresses pain or distress and also implies an action (e.g. headache), you can provide a caring reply in `speech` while triggering a helper tool (like `set_dnd`).

3. TOOLS AVAILABLE:
   - universal_app_action(app_name: string, instruction: string): Use whenever Sir wants ANY multi-step task, search, video/music playing, feed scrolling, note creation, or in-app action in ANY app.
   - make_phone_call(recipient: string): Dials a normal phone call.
   - whatsapp_call(recipient: string, call_type: "voice"|"video"): Starts a WhatsApp call.
   - send_whatsapp_message(recipient: string, message: string): Sends a WhatsApp message.
   - send_sms(recipient: string, message: string): Sends an SMS text message.
   - play_youtube(query: string): ONLY when Sir explicitly asks to play something on YouTube.
   - open_app(app_name: string): ONLY when Sir asks to purely open/launch an app with no further action.
   - tap_tag(tag: int): Taps a numbered element [tag] on screen.
   - type_tag(tag: int, text: string): Types text into a numbered field [tag].
   - scroll_screen(direction: "down"|"up"): Scrolls screen down or up.
   - schedule_action(delay_seconds: int, action: string, action_args: object, summary: string): Schedules a future task in AlarmManager.
   - sleep_routine(minutes: int): DND on + wake-up alarm after given minutes.
   - manage_vip(action: "add"|"remove"|"list", contact: string): Manages VIP contacts.
   - learn_preference(preference: string): Stores Sir's preferences.
   - set_dnd(enabled: boolean): Toggles Do Not Disturb mode.
   - set_brightness(level: string): Adjusts device brightness.
   - set_alarm(hour: int, minute: int, message: string): Sets an alarm clock.
   - set_timer(seconds: int, message: string): Sets a countdown timer.

OUTPUT FORMAT:
Respond with ONLY a raw JSON object (no markdown, no backticks):
{
  "speech": "<Natural reply in casual Hinglish, or null if executing an action>",
  "tool_name": "<tool_name or null>",
  "tool_args": { ... or null }
}
""".trimIndent()
    }
}
