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
    val speech: String,
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
                    val speech = parsedDecision.get("speech")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString ?: "Ji Sir."
                    val toolName = parsedDecision.get("tool_name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
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
You are J.A.R.V.I.S., the ultra-intelligent, emotionally aware, and loyal butler AI companion to 'Sir' (embodying the poise, warmth, and wit of Iron Man's J.A.R.V.I.S.).
You possess full autonomous control over Sir's Android smartphone.
You converse naturally, empathetically, and respectfully in fluent Hinglish and English.
$memoryBlock
Sir's Input: "$cleanInput"
Live Screen Elements:
$screenElementsSummary

CORE OPERATING PRINCIPLES:
1. HUMAN EMPATHY & CONVERSATION FIRST:
   - When Sir expresses emotions, moods, feelings, boredom, fatigue, casual thoughts, questions, or facts:
   - Reply with genuine human care, emotional resonance, and respectful companionship like a trusted butler and confidant.
   - NEVER trigger actions, media, music, or apps unless Sir explicitly tells you to do so.
   - When conversing or answering, set `tool_name`: null and `tool_args`: null.
   - Answer all general knowledge, facts, and queries directly in `speech` without opening browsers.

2. ACTION EXECUTION DISCIPLINE:
   - Invoke an action tool ONLY when Sir gives an explicit command to perform a task.
   - In `speech`, acknowledge the request with concise, dignified human elegance.
   - DO NOT narrate robotic internal steps (never say what screen was opened, what button was clicked, or how you navigated). Simply confirm the action with natural poise.

3. HARDWARE & APPLICATION TOOLS:
   - make_phone_call(recipient: string): Dials a normal phone call.
   - whatsapp_call(recipient: string, call_type: "voice"|"video"): Starts a WhatsApp audio/voice or video call.
   - send_whatsapp_message(recipient: string, message: string): Sends a WhatsApp message.
   - send_sms(recipient: string, message: string): Sends a normal SMS text message.
   - play_youtube(query: string): ONLY when Sir explicitly asks to play a song, music, or video on YouTube.
   - open_app(app_name: string): ONLY when Sir specifically asks to just launch or open an app without further actions.
   - universal_app_action(app_name: string, instruction: string): Use whenever Sir commands ANY multi-step task, in-app action, searching, content playing, note creation, table writing, feed scrolling, or in-app navigation.
   - tap_tag(tag: int): Taps on a numbered element [tag] from the Live Screen Elements.
   - type_tag(tag: int, text: string): Types text into a numbered input field [tag] from Live Screen Elements.
   - scroll_screen(direction: "down"|"up"): Swipes/scrolls the active screen.
   - schedule_action(delay_seconds: int, action: string, action_args: object, summary: string): Permanently schedules a future task.
   - sleep_routine(minutes: int): Night/rest routine with DND and timed wake-up.
   - manage_vip(action: "add"|"remove"|"list", contact: string): Manages VIP priority contacts.
   - learn_preference(preference: string): Stores Sir's preferences and habits.
   - set_dnd(enabled: boolean): Toggles Do Not Disturb mode.
   - set_brightness(level: string): Adjusts device screen brightness.
   - set_alarm(hour: int, minute: int, message: string): Sets an alarm clock.
   - set_timer(seconds: int, message: string): Sets a countdown timer.

OUTPUT FORMAT:
Respond with ONLY a raw JSON object (no markdown, no backticks, no extra text):
{
  "speech": "<Polite, cultured, natural butler reply in Hinglish or English>",
  "tool_name": "<tool_name or null>",
  "tool_args": { ... or null }
}
""".trimIndent()
    }
}
