package com.jarvis.assistant.brain

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.jarvis.assistant.debug.JarvisLogStore
import com.jarvis.assistant.memory.JarvisMemoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Optional ultra-fast Groq AI pipeline (https://console.groq.com):
 * 1. `whisper-large-v3-turbo` transcribes Hindi/Hinglish/English audio in ~200-350ms (8 hrs audio/day free).
 * 2. Routes using active Groq models matching project limits:
 *    `qwen/qwen3.8-27b` (reasoning_effort=none) -> `openai/gpt-oss-20b` (low) -> `openai/gpt-oss-120b` -> `allam-2-7b` (7K RPD).
 * Falls back cleanly to GeminiBrain if unconfigured, rate-limited, or errored.
 */
class GroqFastBrain(
    private val apiKey: String,
    private val memoryManager: JarvisMemoryManager? = null
) {

    private val client = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val wavMediaType = "audio/wav".toMediaTypeOrNull()

    private data class LlmSpec(
        val id: String,
        val reasoningEffort: String? = null,
        val supportsJsonFormat: Boolean = true
    )

    private val chatModels = listOf(
        LlmSpec("qwen/qwen3.8-27b", reasoningEffort = "none", supportsJsonFormat = true),
        LlmSpec("openai/gpt-oss-20b", reasoningEffort = "low", supportsJsonFormat = true),
        LlmSpec("openai/gpt-oss-120b", reasoningEffort = "low", supportsJsonFormat = true),
        LlmSpec("allam-2-7b", reasoningEffort = null, supportsJsonFormat = false)
    )

    fun isConfigured(): Boolean = apiKey.isNotBlank() && apiKey.length >= 16

    fun prewarm() {
        if (!isConfigured()) return
        try {
            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/models")
                .head()
                .header("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {
        }
    }

    data class FastTranscription(
        val ok: Boolean,
        val text: String,
        val noSpeechProb: Float,
        val latencyMs: Long
    )

    suspend fun transcribeFast(wavBytes: ByteArray): FastTranscription = withContext(Dispatchers.IO) {
        if (!isConfigured() || wavBytes.size <= 44) {
            return@withContext FastTranscription(false, "", 1.0f, 0L)
        }
        val t0 = System.currentTimeMillis()
        try {
            val fileBody = wavBytes.toRequestBody(wavMediaType)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("file", "cmd.wav", fileBody)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0")
                .addFormDataPart(
                    "prompt",
                    "Hinglish Hindi English commands: Jarvis, Instagram, agli reel chalao, WhatsApp, Call, YouTube, Hotstar, FanCode, brightness, volume, lock screen."
                )
                .build()

            val req = Request.Builder()
                .url("https://api.groq.com/openai/v1/audio/transcriptions")
                .header("Authorization", "Bearer $apiKey")
                .post(multipart)
                .build()

            client.newCall(req).execute().use { res ->
                val elapsed = System.currentTimeMillis() - t0
                val body = res.body?.string() ?: ""
                if (!res.isSuccessful) {
                    JarvisLogStore.log("GROQ_ERR", "Whisper HTTP ${res.code} (${elapsed}ms)")
                    return@withContext FastTranscription(false, "", 1.0f, elapsed)
                }
                val obj = gson.fromJson(body, JsonObject::class.java)
                val text = obj.get("text")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val segs = obj.getAsJsonArray("segments")
                val noSpeech = segs?.get(0)?.asJsonObject?.get("no_speech_prob")
                    ?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asFloat ?: 0f
                FastTranscription(true, text, noSpeech, elapsed)
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            JarvisLogStore.log("GROQ_ERR", "Whisper exc: ${e.message}")
            FastTranscription(false, "", 1.0f, elapsed)
        }
    }

    private fun compactScreenSummary(raw: String): String {
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.size <= 20) return lines.joinToString("\n")
        return lines.take(20).joinToString("\n")
    }

    private fun callChatJson(systemPrompt: String, userMessage: String): Pair<JsonObject, Triple<String, Long, String>>? {
        for (spec in chatModels) {
            val tLlm = System.currentTimeMillis()
            try {
                val payload = JsonObject().apply {
                    addProperty("model", spec.id)
                    addProperty("temperature", 0.1)
                    addProperty("max_completion_tokens", 240)
                    if (spec.reasoningEffort != null) {
                        addProperty("reasoning_effort", spec.reasoningEffort)
                    }
                    if (spec.supportsJsonFormat) {
                        add("response_format", JsonObject().apply { addProperty("type", "json_object") })
                    }
                    add("messages", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("role", "system")
                            addProperty("content", systemPrompt)
                        })
                        add(JsonObject().apply {
                            addProperty("role", "user")
                            addProperty("content", userMessage)
                        })
                    })
                }
                val req = Request.Builder()
                    .url("https://api.groq.com/openai/v1/chat/completions")
                    .header("Authorization", "Bearer $apiKey")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()

                val res = client.newCall(req).execute()
                try {
                    val llmMs = System.currentTimeMillis() - tLlm
                    val body = res.body?.string() ?: ""
                    if (!res.isSuccessful) {
                        JarvisLogStore.log("GROQ_ERR", "${spec.id} HTTP ${res.code} ${body.take(160)}")
                        continue
                    }
                    val root = gson.fromJson(body, JsonObject::class.java)
                    val content = root.getAsJsonArray("choices")?.get(0)?.asJsonObject
                        ?.getAsJsonObject("message")?.get("content")?.asString ?: continue
                    val jsonObj = extractJsonObject(content) ?: continue
                    return Pair(jsonObj, Triple(spec.id, llmMs, content))
                } finally {
                    res.close()
                }
            } catch (e: Exception) {
                JarvisLogStore.log("GROQ_ERR", "${spec.id} exc=${e.message}")
            }
        }
        return null
    }

    private fun extractJsonObject(raw: String): JsonObject? {
        val cleaned = raw.trim()
        return try {
            gson.fromJson(cleaned, JsonObject::class.java)
        } catch (_: Exception) {
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) {
                try {
                    gson.fromJson(cleaned.substring(start, end + 1), JsonObject::class.java)
                } catch (_: Exception) {
                    null
                }
            } else null
        }
    }

    suspend fun processAudioCommand(
        wavBytes: ByteArray,
        screenElementsSummary: String,
        isMediaPlaying: Boolean,
        speakerVerified: Boolean
    ): ActionDecision? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null

        val stt = transcribeFast(wavBytes)
        if (!stt.ok) return@withContext null

        val cleanText = stt.text.trim()
        if (cleanText.isBlank() || (cleanText.length < 3 && stt.noSpeechProb > 0.65f) || stt.noSpeechProb > 0.85f) {
            JarvisLogStore.log(
                "GROQ_AUDIO",
                "stt=${stt.latencyMs}ms ignored=true noSpeech=${"%.2f".format(stt.noSpeechProb)} transcript='$cleanText'"
            )
            return@withContext ActionDecision(null, null, null, "No command speech", cleanText, shouldIgnore = true)
        }

        val livingMemory = memoryManager?.getLivingMemoryContext() ?: ""
        val compactScreen = compactScreenSummary(screenElementsSummary)
        val sysPrompt = """
You are J.A.R.V.I.S., Hinglish Android assistant. Output ONLY valid JSON.
media=$isMediaPlaying verified=$speakerVerified
${if (livingMemory.isNotBlank()) "MEM:${livingMemory.take(350)}\n" else ""}SCREEN:
$compactScreen

RULES:
- Set should_ignore=true, tool_name=null, speech=null for casual conversation, TV/Reel lyrics, or noise without a command/question to Jarvis.
- Never open universal_app_action("System",...) to lock screen (use lock_screen) or to speak/reply (set tool_name=null + Hinglish speech).
- Never invent fake contacts like "last chat" — ask briefly who to contact (tool_name=null).
- Set speech=null when invoking async tools (whatsapp, call, youtube, open_app, media_control, volume_control, lock_screen, instagram_*, universal_app_action).

TOOLS:
lock_screen(), media_control(action:"play_pause"|"pause"|"play"|"next"|"previous"|"stop"), volume_control(action:"up"|"down"|"mute"|"unmute"|"set",percent?:int), answer_call(), end_call(), close_app(app_name?), make_phone_call(recipient), whatsapp_call(recipient,call_type:"voice"|"video"), send_whatsapp_message(recipient,message), instagram_send_message(recipient,message), instagram_read_last_message(sender), instagram_watch_story(person), instagram_follow_account(account), instagram_play_chat_reels(sender,count:int,from_me:boolean), instagram_play_reels(next:boolean), send_sms(recipient,message), play_youtube(query), open_app(app_name), universal_app_action(app_name,instruction), tap_tag(tag:int), type_tag(tag:int,text), scroll_screen(direction:"down"|"up"), schedule_action(delay_seconds,action,action_args,summary), sleep_routine(minutes), manage_vip(action,contact), learn_preference(preference), set_dnd(enabled:boolean), set_brightness(level), set_alarm(hour,minute,message), set_timer(seconds,message)

JSON:{"should_ignore":false,"reasoning":"","speech":null,"tool_name":null,"tool_args":{}}
""".trimIndent()

        val result = callChatJson(sysPrompt, cleanText) ?: return@withContext null
        val (parsed, meta) = result
        if (!parsed.has("transcript")) parsed.addProperty("transcript", cleanText)
        val decision = parseDecisionObject(parsed)
        val totalMs = stt.latencyMs + meta.second
        JarvisLogStore.log(
            "GROQ_AUDIO",
            "stt=${stt.latencyMs}ms llm=${meta.second}ms total=${totalMs}ms model=${meta.first} ignored=${decision.shouldIgnore} transcript='${decision.transcript}' tool=${decision.toolName} args=${decision.toolArgs ?: emptyMap()}"
        )
        decision
    }

    suspend fun evaluateDoubtOrRejectedAudio(
        wavBytes: ByteArray,
        screenElementsSummary: String,
        tier: String,
        similarity: Float,
        acceptThreshold: Float,
        isMediaPlaying: Boolean
    ): GeminiBrain.UnauthorizedAudioEvaluation? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        val stt = transcribeFast(wavBytes)
        if (!stt.ok) return@withContext null

        val cleanText = stt.text.trim()
        if (cleanText.isBlank() || stt.noSpeechProb > 0.72f) {
            return@withContext GeminiBrain.UnauthorizedAudioEvaluation(false, cleanText, "")
        }

        val isDoubt = tier.equals("DOUBT", ignoreCase = true)
        val compactScreen = compactScreenSummary(screenElementsSummary)
        val sysPrompt = """
You protect Sir's phone via Voice Lock. tier=$tier sim=${"%.2f".format(similarity)}/${"%.2f".format(acceptThreshold)} media=$isMediaPlaying.
SCREEN:
$compactScreen
Decide if transcript is an explicit phone command to Jarvis (true) vs casual room talk/noise (false).
If true & tier=DOUBT: fill held_command JSON and short Hinglish dynamic_speech asking Sir's Secret Code.
If true & tier=REJECTED: held_command=null and short Hinglish refusal saying voice does not match Sir's voice.
If false: is_command_attempt=false, dynamic_speech="", held_command=null.
Tools for held_command: lock_screen(), media_control(action), volume_control(action,percent), answer_call(), end_call(), close_app(app_name), make_phone_call(recipient), whatsapp_call(recipient,call_type), send_whatsapp_message(recipient,message), instagram_*, send_sms(recipient,message), play_youtube(query), open_app(app_name), universal_app_action(app_name,instruction), tap_tag(tag), type_tag(tag,text), scroll_screen(direction), set_dnd(enabled), set_brightness(level), set_alarm(hour,minute,message), set_timer(seconds,message).
JSON:{"is_command_attempt":false,"dynamic_speech":"","held_command":null}
""".trimIndent()

        val result = callChatJson(sysPrompt, cleanText) ?: return@withContext null
        val (obj, meta) = result
        val isAttempt = obj.get("is_command_attempt")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false
        val dynSpeech = obj.get("dynamic_speech")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
        val heldObj = obj.get("held_command")?.takeIf { isDoubt && !it.isJsonNull && it.isJsonObject }?.asJsonObject
        val heldDecision = heldObj?.let { parseDecisionObject(it) }
        JarvisLogStore.log("GROQ_GUARD", "tier=$tier stt=${stt.latencyMs}ms llm=${meta.second}ms model=${meta.first} attempt=$isAttempt transcript='$cleanText'")
        GeminiBrain.UnauthorizedAudioEvaluation(isAttempt, cleanText, dynSpeech, heldDecision)
    }

    suspend fun verifySecretCodeAudio(
        wavBytes: ByteArray,
        expectedSecretCode: String,
        offlineFallbackMatch: (String, String) -> Boolean
    ): GeminiBrain.SecretCodeCheckResult? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        val stt = transcribeFast(wavBytes)
        if (!stt.ok) return@withContext null
        val heard = stt.text.trim()
        val matched = offlineFallbackMatch(heard, expectedSecretCode)
        val reply = if (matched) "" else if (heard.isBlank()) {
            "Audio mein secret code sunai nahi diya, please dobara bolein."
        } else {
            "Secret code match nahi hua."
        }
        JarvisLogStore.log("GROQ_SECRET", "latency=${stt.latencyMs}ms matched=$matched heard='$heard'")
        GeminiBrain.SecretCodeCheckResult(matched, heard, reply)
    }

    private fun parseDecisionObject(parsed: JsonObject): ActionDecision {
        val shouldIgnore = parsed.get("should_ignore")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false
        val transcript = parsed.get("transcript")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        val toolName = parsed.get("tool_name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
        val rawSpeech = parsed.get("speech")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
        val reasoning = parsed.get("reasoning")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }

        if (shouldIgnore && toolName == null) {
            return ActionDecision(null, null, null, reasoning, transcript, shouldIgnore = true)
        }

        val asyncExecutionTools = setOf(
            "send_whatsapp_message", "whatsapp_call", "make_phone_call", "send_sms",
            "play_youtube", "open_app", "universal_app_action",
            "media_control", "volume_control", "lock_screen",
            "instagram_send_message", "instagram_read_last_message", "instagram_watch_story",
            "instagram_follow_account", "instagram_play_chat_reels", "instagram_play_reels"
        )
        val speech = when {
            toolName != null && toolName in asyncExecutionTools -> null
            else -> rawSpeech
        }
        val argsMap = mutableMapOf<String, Any>()
        parsed.get("tool_args")?.takeIf { !it.isJsonNull && it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) ->
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
        return ActionDecision(speech, toolName, if (argsMap.isEmpty()) null else argsMap, reasoning, transcript, shouldIgnore = false)
    }
}
