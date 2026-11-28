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
        val reasoningFormat: String? = null,
        val supportsJsonFormat: Boolean = true
    )

    private val chatModels = listOf(
        LlmSpec("qwen/qwen3.8-27b", reasoningEffort = "none", reasoningFormat = "hidden", supportsJsonFormat = true),
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
        val avgLogProb: Float,
        val compressionRatio: Float,
        val latencyMs: Long
    )

    suspend fun transcribeFast(wavBytes: ByteArray): FastTranscription = withContext(Dispatchers.IO) {
        if (!isConfigured() || wavBytes.size <= 44) {
            return@withContext FastTranscription(false, "", 1.0f, -2.0f, 0f, 0L)
        }
        val t0 = System.currentTimeMillis()
        try {
            val fileBody = wavBytes.toRequestBody(wavMediaType)
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", "whisper-large-v3-turbo")
                .addFormDataPart("language", "hi")
                .addFormDataPart("file", "cmd.wav", fileBody)
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("temperature", "0")
                .addFormDataPart("prompt", JarvisPrompts.WHISPER_VOCAB_PROMPT)
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
                    return@withContext FastTranscription(false, "", 1.0f, -2.0f, 0f, elapsed)
                }
                val obj = gson.fromJson(body, JsonObject::class.java)
                val text = obj.get("text")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val segs = obj.getAsJsonArray("segments")
                var worstNoSpeech = 0f
                var minAvgLogProb = 0f
                var maxCompRatio = 0f
                if (segs != null && segs.size() > 0) {
                    minAvgLogProb = Float.MAX_VALUE
                    for (i in 0 until segs.size()) {
                        val seg = segs.get(i)?.asJsonObject ?: continue
                        val ns = seg.get("no_speech_prob")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asFloat ?: 0f
                        val lp = seg.get("avg_logprob")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asFloat ?: -0.2f
                        val cr = seg.get("compression_ratio")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asFloat ?: 1.0f
                        if (ns > worstNoSpeech) worstNoSpeech = ns
                        if (lp < minAvgLogProb) minAvgLogProb = lp
                        if (cr > maxCompRatio) maxCompRatio = cr
                    }
                    if (minAvgLogProb == Float.MAX_VALUE) minAvgLogProb = 0f
                }
                FastTranscription(true, text, worstNoSpeech, minAvgLogProb, maxCompRatio, elapsed)
            }
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - t0
            JarvisLogStore.log("GROQ_ERR", "Whisper exc: ${e.message}")
            FastTranscription(false, "", 1.0f, -2.0f, 0f, elapsed)
        }
    }

    private fun hasInvalidSignalConfidence(
        cleanText: String,
        stt: FastTranscription
    ): Boolean {
        val lettersOnly = cleanText.replace(Regex("[\\s\\p{Punct}]+"), "")
        if (lettersOnly.length < 2) return true
        if (stt.noSpeechProb > 0.60f) return true
        if (stt.avgLogProb < -0.78f) return true
        if (stt.noSpeechProb > 0.35f && stt.avgLogProb < -0.52f) return true
        if (stt.compressionRatio > 2.20f) return true
        return false
    }

    private fun compactScreenSummary(raw: String): String {
        val lines = raw.lines().filter { it.isNotBlank() }
        if (lines.size <= 20) return lines.joinToString("\n")
        return lines.take(20).joinToString("\n")
    }

    private fun callChatJson(systemPrompt: String, userMessage: String): Pair<JsonObject, Triple<String, Long, String>>? {
        for (spec in chatModels) {
            val forcePlainRetry = if (spec.supportsJsonFormat) booleanArrayOf(true, false) else booleanArrayOf(false)
            for (useJsonFormat in forcePlainRetry) {
                val tLlm = System.currentTimeMillis()
                try {
                    val payload = JsonObject().apply {
                        addProperty("model", spec.id)
                        addProperty("temperature", 0.1)
                        addProperty("max_completion_tokens", 280)
                        if (spec.reasoningEffort != null) {
                            addProperty("reasoning_effort", spec.reasoningEffort)
                        }
                        if (spec.reasoningFormat != null) {
                            addProperty("reasoning_format", spec.reasoningFormat)
                        }
                        if (useJsonFormat) {
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
                            JarvisLogStore.log("GROQ_ERR", "${spec.id} json=$useJsonFormat HTTP ${res.code} ${body.take(160)}")
                            if (res.code == 400 && useJsonFormat) {
                                continue // immediately retry same fast model without strict json_object validation
                            }
                            break
                        }
                        val root = gson.fromJson(body, JsonObject::class.java)
                        val content = root.getAsJsonArray("choices")?.get(0)?.asJsonObject
                            ?.getAsJsonObject("message")?.get("content")?.asString
                        if (content.isNullOrBlank()) continue
                        val jsonObj = extractJsonObject(content) ?: continue
                        return Pair(jsonObj, Triple(spec.id, llmMs, content))
                    } finally {
                        res.close()
                    }
                } catch (e: Exception) {
                    JarvisLogStore.log("GROQ_ERR", "${spec.id} exc=${e.message}")
                    break
                }
            }
        }
        return null
    }

    private fun extractJsonObject(raw: String): JsonObject? {
        val cleaned = raw
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace("```json", "")
            .replace("```", "")
            .trim()
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

    suspend fun decideUIAction(prompt: String): UIActionDecision? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null
        val result = callChatJson(
            "You output ONLY a valid JSON object matching the schema requested by the user.",
            prompt
        ) ?: return@withContext null
        val (parsed, meta) = result
        val action = parsed.get("action")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString ?: "done"
        val tag = parsed.get("tag")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt
        val text = parsed.get("text")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        val dir = parsed.get("direction")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        val summary = parsed.get("summary")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        val reasoning = parsed.get("reasoning")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
        JarvisLogStore.log(
            "GROQ_UI",
            "model=${meta.first} latency=${meta.second}ms action=$action tag=$tag dir=$dir summary='${summary ?: ""}'"
        )
        UIActionDecision(action, tag, text, dir, summary, reasoning)
    }

    suspend fun generateCompletionConfirmation(appName: String, instruction: String): String = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext ""
        val sys = "Output JSON: {\"text\":\"<1 short natural Hinglish confirmation sentence>\"}. No robotic phrases."
        val user = "Sir's command '$instruction' in $appName just finished."
        val res = callChatJson(sys, user) ?: return@withContext ""
        res.first.get("text")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
    }

    suspend fun processAudioCommand(
        wavBytes: ByteArray,
        voskHint: String = "",
        screenElementsSummary: String,
        isMediaPlaying: Boolean,
        speakerVerified: Boolean,
        isInActiveCall: Boolean = false
    ): ActionDecision? = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext null

        val stt = transcribeFast(wavBytes)
        if (!stt.ok) return@withContext null

        val cleanText = stt.text.trim()
        if (cleanText.isBlank() || hasInvalidSignalConfidence(cleanText, stt)) {
            JarvisLogStore.log(
                "GROQ_AUDIO",
                "stt=${stt.latencyMs}ms ignored=true noSpeech=${"%.2f".format(stt.noSpeechProb)} logProb=${"%.2f".format(stt.avgLogProb)} cr=${"%.2f".format(stt.compressionRatio)} transcript='$cleanText'"
            )
            return@withContext ActionDecision(null, null, null, "Low-confidence/non-speech audio", cleanText, shouldIgnore = true)
        }

        val livingMemory = memoryManager?.getLivingMemoryContext() ?: ""
        val compactScreen = compactScreenSummary(screenElementsSummary)
        val sysPrompt = JarvisPrompts.buildVoiceSystemPrompt(
            livingMemory = livingMemory,
            screenSummary = compactScreen,
            isMediaPlaying = isMediaPlaying,
            speakerVerified = speakerVerified,
            isInActiveCall = isInActiveCall,
            voskHint = voskHint,
            includeAudioListenerHeader = false
        )

        val result = callChatJson(sysPrompt, cleanText) ?: return@withContext null
        val (parsed, meta) = result
        if (!parsed.has("transcript")) parsed.addProperty("transcript", cleanText)
        val decision = parseDecisionObject(parsed, suppressSpeech = isInActiveCall)
        val totalMs = stt.latencyMs + meta.second
        JarvisLogStore.log(
            "GROQ_AUDIO",
            "stt=${stt.latencyMs}ms llm=${meta.second}ms total=${totalMs}ms model=${meta.first} noSp=${"%.2f".format(stt.noSpeechProb)} lp=${"%.2f".format(stt.avgLogProb)} ignored=${decision.shouldIgnore} transcript='${decision.transcript}' tool=${decision.toolName} args=${decision.toolArgs ?: emptyMap()}"
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
        if (cleanText.isBlank() || hasInvalidSignalConfidence(cleanText, stt)) {
            return@withContext GeminiBrain.UnauthorizedAudioEvaluation(false, cleanText, "")
        }

        val isDoubt = tier.equals("DOUBT", ignoreCase = true)
        val livingMemory = memoryManager?.getLivingMemoryContext() ?: ""
        val compactScreen = compactScreenSummary(screenElementsSummary)
        val sysPrompt = JarvisPrompts.buildVoiceGuardPrompt(
            tier = tier,
            similarity = similarity,
            acceptThreshold = acceptThreshold,
            isMediaPlaying = isMediaPlaying,
            screenSummary = compactScreen,
            livingMemory = livingMemory,
            includeAudioHeader = false
        )

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

    private fun parseDecisionObject(parsed: JsonObject, suppressSpeech: Boolean = false): ActionDecision {
        val shouldIgnore = parsed.get("should_ignore")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false
        val transcript = parsed.get("transcript")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        val toolName = parsed.get("tool_name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
        val rawSpeech = parsed.get("speech")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
        val reasoning = parsed.get("reasoning")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }

        if (shouldIgnore && toolName == null) {
            return ActionDecision(null, null, null, reasoning, transcript, shouldIgnore = true)
        }

        val speech = when {
            suppressSpeech -> null
            toolName != null && toolName in JarvisPrompts.ASYNC_SILENT_TOOLS -> null
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
