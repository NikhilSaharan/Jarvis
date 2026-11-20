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

class GeminiBrain(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun processCommand(userText: String, screenElementsSummary: String = ""): ActionDecision = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext ActionDecision("API key missing hai Sir.", null, null)
        }

        val prompt = buildPrompt(userText, screenElementsSummary)
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"

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

        try {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                Log.e("GeminiBrain", "HTTP Error ${response.code}: $body")
                return@withContext ActionDecision("Network error aaya Sir.", null, null)
            }

            val jsonResponse = gson.fromJson(body, JsonObject::class.java)
            val candidates = jsonResponse.getAsJsonArray("candidates")
            val firstCandidate = candidates?.get(0)?.asJsonObject
            val textContent = firstCandidate?.getAsJsonObject("content")
                ?.getAsJsonArray("parts")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

            val parsedDecision = gson.fromJson(textContent, JsonObject::class.java)
            val speech = parsedDecision.get("speech")?.asString ?: "Ji Sir."
            val toolName = parsedDecision.get("tool_name")?.asString
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
                }
            }

            ActionDecision(speech, toolName, if (argsMap.isEmpty()) null else argsMap)
        } catch (e: Exception) {
            Log.e("GeminiBrain", "Execution failed: ${e.message}")
            ActionDecision("Command process nahi ho saka Sir.", null, null)
        }
    }

    private fun buildPrompt(userText: String, screenElementsSummary: String): String {
        return """
You are an autonomous smartphone assistant. You understand Hindi, English, and Hinglish.
Analyze the user command and select the correct action.

USER COMMAND: "$userText"
SCREEN CONTEXT: "$screenElementsSummary"

SUPPORTED TOOLS:
- set_dnd(enabled: boolean) -> For "so raha hoon", "disturb mat karna", "silent karo"
- set_brightness(level: string) -> 'low', 'medium', 'high', 'max'
- set_alarm(hour: int, minute: int, message: string)
- set_timer(seconds: int, message: string)
- make_phone_call(phone_number: string)
- send_sms(recipient: string, message: string)
- send_whatsapp_message(recipient: string, message: string)
- whatsapp_call(recipient: string, call_type: "voice"|"video")
- play_youtube(query: string) -> For songs, music, bad mood
- open_app(app_name: string)
- tap_tag(tag: int)

RULES:
1. Output ONLY valid JSON:
{
  "speech": "<Short polite reply in Hinglish, 8-15 words max>",
  "tool_name": "<tool_name or null>",
  "tool_args": { ... }
}
2. For "so raha hoon" / "disturbance nahi chahiye", ALWAYS call set_dnd(enabled=true).
3. For "mood kharab hai" / songs / music, ALWAYS call play_youtube.
""".trimIndent()
    }
}
