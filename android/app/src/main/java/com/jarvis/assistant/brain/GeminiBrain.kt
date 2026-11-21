package com.jarvis.assistant.brain

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
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
    val direction: String?
)

class GeminiBrain(
    private val apiKey: String,
    private val memoryManager: com.jarvis.assistant.memory.JarvisMemoryManager? = null
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
                    val speech = parsedDecision.get("speech")?.asString ?: "Ji Sir."
                    val toolName = parsedDecision.get("tool_name")?.asString?.takeIf { it != "null" && it.isNotBlank() }
                    val argsObj = parsedDecision.getAsJsonObject("tool_args")

                    val argsMap = mutableMapOf<String, Any>()
                    argsObj?.entrySet()?.forEach { (k, v) ->
                        if (v.isJsonPrimitive) {
                            val prim = v.asJsonPrimitive
                            when {
                                prim.isBoolean -> argsMap[k] = prim.asBoolean
                                prim.isNumber -> argsMap[k] = prim.asInt
                                else -> argsMap[k] = prim.asString
                            }
                        } else if (v.isJsonObject) {
                            val subMap = mutableMapOf<String, Any>()
                            v.asJsonObject.entrySet().forEach { (sk, sv) ->
                                if (sv.isJsonPrimitive) subMap[sk] = sv.asString
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
                    val action = parsed.get("action")?.asString ?: "done"
                    val tag = parsed.get("tag")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt
                    val text = parsed.get("text")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                    val dir = parsed.get("direction")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                    return@withContext UIActionDecision(action, tag, text, dir)
                }
            } catch (e: Exception) {
                Log.w("GeminiBrain", "UI action decision error on $model: ${e.message}")
            }
        }
        UIActionDecision("done", null, null, null)
    }

    private fun buildPrompt(userText: String, screenElementsSummary: String): String {
        val cleanInput = userText.replace("\"", "'")
        val livingMemory = memoryManager?.getLivingMemoryContext() ?: ""
        val memoryBlock = if (livingMemory.isNotBlank()) {
            "\nJARVIS LIVING MEMORY & USER CONTEXT:\n$livingMemory\n"
        } else ""

        return """
You are J.A.R.V.I.S., the ultra-intelligent, respectful British butler AI assistant to 'Sir'.
You have full autonomous control over Sir's Android phone.
You deeply understand Hindi, Hinglish, and English naturally.
$memoryBlock
Sir's Input: "$cleanInput"
Live Screen Elements:
$screenElementsSummary

CORE OPERATING INSTRUCTIONS:
1. GENERAL KNOWLEDGE, CHIT-CHAT, QUESTIONS, MATH, NEWS, ADVICE:
   - When Sir asks any question, asks for news, math problems, facts, definitions, or conversation:
   - Answer Sir directly, accurately, and knowledgeably in `speech`.
   - Set `tool_name`: null and `tool_args`: null.
   - NEVER open a browser, Chrome, or any app just to answer a question or report news!

2. HARDWARE & APPLICATION TOOLS (Only call when Sir explicitly requests an action):
   - make_phone_call(recipient: string): Dials a normal phone call to a contact name or number.
   - whatsapp_call(recipient: string, call_type: "voice"|"video"): Starts a WhatsApp audio/voice or video call.
   - send_whatsapp_message(recipient: string, message: string): Sends a WhatsApp message.
   - send_sms(recipient: string, message: string): Sends a normal SMS text message.
   - play_youtube(query: string): Searches and autoplays music, songs, or videos on YouTube. Choose an intelligent search query matching Sir's exact mood or request.
   - open_app(app_name: string): Opens any installed application on Sir's phone.
   - universal_app_action(app_name: string, instruction: string): Opens any app and autonomously executes Sir's instruction using live UI tree inspection.
   - tap_tag(tag: int): Taps on a numbered element [tag] from the Live Screen Elements.
   - type_tag(tag: int, text: string): Types text into a numbered input field [tag] from Live Screen Elements.
   - scroll_screen(direction: "down"|"up"): Swipes/scrolls the active screen (for reels, feeds, long lists).
   - schedule_action(delay_seconds: int, action: string, action_args: object, summary: string): When Sir wants ANY action performed in the future (e.g. after X seconds, minutes, hours). Stores permanently in AlarmManager so other commands will NOT cancel it.
   - sleep_routine(minutes: int): When Sir is going to sleep or resting; sets DND ON and schedules wake-up alarm after given minutes.
   - manage_vip(action: "add"|"remove"|"list", contact: string): Adds/removes VIP contacts whose calls bypass Silent and DND.
   - learn_preference(preference: string): Permanently remembers Sir's habits, rules, or preferences.
   - set_dnd(enabled: boolean): Enables or disables Do Not Disturb mode.
   - set_brightness(level: string): Sets brightness ("low", "medium", "high", "max").
   - set_alarm(hour: int, minute: int, message: string): Sets a hardware alarm clock.
   - set_timer(seconds: int, message: string): Sets a countdown timer (only for timers/reminders, not for delayed actions).

OUTPUT FORMAT:
Respond with ONLY a raw JSON object (no markdown, no backticks, no extra text):
{
  "speech": "<Polite, concise butler reply in natural Hinglish or English>",
  "tool_name": "<tool_name or null>",
  "tool_args": { ... or null }
}
""".trimIndent()
    }
}
