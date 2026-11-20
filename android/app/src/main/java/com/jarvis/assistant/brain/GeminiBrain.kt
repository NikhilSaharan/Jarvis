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
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    // STRICT PRIMARY MODEL: gemini-3.5-flash-lite
    private val models = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

    suspend fun processCommand(userText: String, screenElementsSummary: String = ""): ActionDecision = withContext(Dispatchers.IO) {
        val trimmed = userText.trim()
        if (trimmed.isEmpty()) {
            return@withContext ActionDecision("Ji Sir, kahiye?", null, null)
        }

        // STEP 1: Attempt Cloud AI Intelligence (Gemini) if API key is provided
        if (apiKey.isNotBlank() && !apiKey.startsWith("your_")) {
            val prompt = buildPrompt(trimmed, screenElementsSummary)
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

                        return@withContext ActionDecision(speech, toolName, if (argsMap.isEmpty()) null else argsMap)
                    } else {
                        Log.w("GeminiBrain", "Gemini $model HTTP ${response.code}: $body")
                    }
                } catch (e: Exception) {
                    Log.w("GeminiBrain", "Gemini $model request failed: ${e.message}")
                }
            }
        }

        // STEP 2: Zero-Latency Local Rule Engine (100% Offline Resilience)
        val localDecision = parseLocalIntent(trimmed)
        if (localDecision != null) {
            return@withContext localDecision
        }

        // STEP 3: Polite Butler Conversational Default
        ActionDecision("Ji Sir, main aapke aadesh ke intezaar me hoon.", null, null)
    }

    /**
     * Local Butler Engine: Parses voice & text commands directly on-device in 0.001s
     * Handles Hindi, Hinglish, English, and slang without requiring internet.
     */
    fun parseLocalIntent(userText: String): ActionDecision? {
        val lower = userText.lowercase().trim()

        // 1. WhatsApp Voice / Video Call
        if (lower.contains("whatsapp") && (lower.contains("call") || lower.contains("kol") || lower.contains("video"))) {
            val isVideo = lower.contains("video")
            val contact = extractTargetName(userText, listOf("whatsapp", "video", "voice", "normal", "call", "kol", "ko", "par", "pe", "lagao", "karo", "to", "kijiye", "bhi"))
            val target = if (contact.isNotBlank()) contact else "contact"
            val speech = if (isVideo) "Ji Sir, $target ko WhatsApp video call laga raha hoon." else "Ji Sir, $target ko WhatsApp call laga raha hoon."
            return ActionDecision(speech, "whatsapp_call", mapOf("recipient" to target, "call_type" to if (isVideo) "video" else "voice"))
        }

        // 2. WhatsApp Message
        if (lower.contains("whatsapp") && (lower.contains("message") || lower.contains("msg") || lower.contains("bhejo") || lower.contains("send"))) {
            val contact = extractTargetName(userText, listOf("whatsapp", "message", "msg", "bhejo", "send", "ko", "par", "pe", "likho", "karo", "to"))
            val target = if (contact.isNotBlank()) contact else "contact"
            return ActionDecision("Ji Sir, $target ko WhatsApp message bhej raha hoon.", "send_whatsapp_message", mapOf("recipient" to target, "message" to "Hello"))
        }

        // 3. Normal SIM Phone Call
        if (lower.contains("call") || lower.contains("phone") || lower.contains("dial") || lower.contains("milao")) {
            val contact = extractTargetName(userText, listOf("normal", "sim", "phone", "call", "kol", "dial", "lagao", "karo", "ko", "par", "pe", "milao", "to", "kijiye"))
            val target = if (contact.isNotBlank()) contact else "contact"
            return ActionDecision("Ji Sir, $target ko call mila raha hoon.", "make_phone_call", mapOf("recipient" to target, "phone_number" to target))
        }

        // 4. Do Not Disturb (DND) / Sleep / Silent
        if (lower.contains("disturb") || lower.contains("so raha") || lower.contains("dnd") || lower.contains("silent") || lower.contains("sone ja") || lower.contains("tention mat")) {
            return ActionDecision("Ji Sir, Do Not Disturb mode activate kar diya hai. Aaram se soiye.", "set_dnd", mapOf("enabled" to true))
        }
        if (lower.contains("wake up") || lower.contains("uth gaya") || lower.contains("dnd off") || lower.contains("general")) {
            return ActionDecision("Ji Sir, Do Not Disturb mode deactivate kar diya hai.", "set_dnd", mapOf("enabled" to false))
        }

        // 5. Mood Kharab / Songs / Music / YouTube
        if (lower.contains("mood kharab") || lower.contains("gana") || lower.contains("gaana") || lower.contains("song") || lower.contains("music") || lower.contains("youtube") || lower.contains("bajao") || lower.contains("chalao")) {
            val query = if (lower.contains("mood kharab")) {
                "relaxing peaceful hindi songs"
            } else {
                val clean = extractTargetName(userText, listOf("youtube", "pe", "par", "gaana", "gana", "song", "songs", "music", "play", "chalao", "bajao", "sunao", "lagao", "karo", "kuch"))
                if (clean.isNotBlank()) clean else "trending hindi songs"
            }
            return ActionDecision("Ji Sir, aapka mood behtar karne ke liye gaana chala raha hoon.", "play_youtube", mapOf("query" to query))
        }

        // 6. Hardware Alarm
        if (lower.contains("alarm")) {
            val (hour, minute) = parseAlarmTime(lower)
            return ActionDecision("Ji Sir, $hour:$minute ka alarm laga diya hai.", "set_alarm", mapOf("hour" to hour, "minute" to minute, "message" to "Alarm"))
        }

        // 7. System Countdown Timer
        if (lower.contains("timer")) {
            val seconds = parseTimerSeconds(lower)
            return ActionDecision("Ji Sir, $seconds seconds ka timer laga diya hai.", "set_timer", mapOf("seconds" to seconds, "message" to "Timer"))
        }

        // 8. Screen Brightness
        if (lower.contains("brightness") || lower.contains("roshni")) {
            val level = when {
                lower.contains("kam") || lower.contains("low") || lower.contains("dim") -> "low"
                lower.contains("full") || lower.contains("zyada") || lower.contains("high") || lower.contains("max") -> "high"
                else -> "medium"
            }
            return ActionDecision("Ji Sir, brightness $level kar di hai.", "set_brightness", mapOf("level" to level))
        }

        // 9. Open Application
        if (lower.startsWith("open ") || lower.contains("kholo")) {
            val app = extractTargetName(userText, listOf("open", "kholo", "app", "application"))
            if (app.isNotBlank()) {
                return ActionDecision("Ji Sir, $app open kar raha hoon.", "open_app", mapOf("app_name" to app))
            }
        }

        // 10. Butler Greeting
        if (lower.contains("hello") || lower.contains("hi") || lower.contains("kaise ho") || lower.contains("jarvis")) {
            return ActionDecision("Ji Sir, main bilkul taiyar hoon. Hukum kijiye.", null, null)
        }

        return null
    }

    private fun extractTargetName(input: String, stopWords: List<String>): String {
        val stopSet = stopWords.map { it.lowercase() }.toSet()
        val tokens = input.split(Regex("\\s+"))
        val filtered = tokens.filter { token ->
            val clean = token.lowercase().replace(Regex("[^a-zA-Z0-9]"), "")
            clean.isNotEmpty() && !stopSet.contains(clean)
        }
        return filtered.joinToString(" ")
    }

    private fun parseAlarmTime(text: String): Pair<Int, Int> {
        val digitMatch = Regex("(\\d{1,2})(:(\\d{2}))?").find(text)
        if (digitMatch != null) {
            val hour = digitMatch.groupValues[1].toIntOrNull() ?: 7
            val min = digitMatch.groupValues[3].toIntOrNull() ?: 0
            val isPm = text.contains("pm") || text.contains("shaam") || text.contains("raat")
            val finalHour = if (isPm && hour < 12) hour + 12 else hour
            return Pair(finalHour, min)
        }
        return Pair(7, 0)
    }

    private fun parseTimerSeconds(text: String): Int {
        val numMatch = Regex("(\\d+)").find(text)
        val num = numMatch?.groupValues?.get(1)?.toIntOrNull() ?: 60
        return if (text.contains("minute") || text.contains("min")) num * 60 else num
    }

    private fun buildPrompt(userText: String, screenElementsSummary: String): String {
        return """
You are J.A.R.V.I.S., an autonomous Android butler assistant for 'Sir'.
Analyze Sir's command and choose the most appropriate action.

USER COMMAND: "$userText"
SCREEN CONTEXT: "$screenElementsSummary"

SUPPORTED TOOLS:
- set_dnd(enabled: boolean) -> For "so raha hoon", "disturb mat karna", "silent"
- set_brightness(level: string) -> 'low', 'medium', 'high', 'max'
- set_alarm(hour: int, minute: int, message: string)
- set_timer(seconds: int, message: string)
- make_phone_call(recipient: string, phone_number: string)
- send_sms(recipient: string, message: string)
- send_whatsapp_message(recipient: string, message: string)
- whatsapp_call(recipient: string, call_type: "voice"|"video")
- play_youtube(query: string) -> For "mood kharab hai", songs, music
- open_app(app_name: string)
- tap_tag(tag: int)

RULES:
1. Output ONLY valid JSON:
{
  "speech": "<Polite butler reply in Hinglish, 8-15 words max>",
  "tool_name": "<tool_name or null>",
  "tool_args": { ... }
}
2. For "mood kharab hai" / songs, ALWAYS call play_youtube.
3. For "so raha hoon" / "disturb mat karna", ALWAYS call set_dnd(enabled=true).
""".trimIndent()
    }
}
