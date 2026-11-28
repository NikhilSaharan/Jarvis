package com.jarvis.assistant.brain

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.jarvis.assistant.debug.JarvisLogStore
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
    val toolArgs: Map<String, Any>?,
    val reasoning: String? = null,
    val transcript: String? = null,
    val shouldIgnore: Boolean = false
)

data class UIActionDecision(
    val action: String,
    val tag: Int?,
    val text: String?,
    val direction: String?,
    val summary: String? = null,
    val reasoning: String? = null
)

data class SocialIntent(
    val actionType: String, // "dm", "read_dm", "chat_media", "chat_media_all", "story", "follow", "profile_info", "profile_post", "reels", "other"
    val target: String,
    val message: String,
    val count: Int = 1, // 1 = latest, N = last N shared items, -1 = all available
    val fromMe: Boolean = false // true ONLY when Sir explicitly requests reels sent by himself/herself
)

class GeminiBrain(
    private val apiKey: String,
    private val memoryManager: JarvisMemoryManager? = null
) {

    private val client = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(4, 5, TimeUnit.MINUTES))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun prewarm() {
        try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/")
                .head()
                .build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {
        }
    }

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
        val parts = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", prompt) })
        }
        JarvisLogStore.log("GEMINI_CMD", "Dispatching cmd='$trimmed' screenCtxLen=${screenElementsSummary.length}")
        executeCommandParts(parts, isAudioMode = false)
    }

    /**
     * Multimodal native Hinglish/English audio command understanding without Android SpeechRecognizer
     * (preserves Hotstar/Reels audio with zero AudioFocus steals and requires no wake word).
     */
    suspend fun processAudioCommand(
        wavBytes: ByteArray,
        voskHint: String = "",
        screenElementsSummary: String = "",
        isMediaPlaying: Boolean = false,
        speakerVerified: Boolean = true,
        isInActiveCall: Boolean = false
    ): ActionDecision = withContext(Dispatchers.IO) {
        if (wavBytes.size <= 44) {
            return@withContext ActionDecision(null, null, null, shouldIgnore = true)
        }
        if (apiKey.isBlank() || apiKey.startsWith("your_")) {
            return@withContext ActionDecision("Sir, Gemini API Key configure nahi hai.", null, null)
        }

        val prompt = buildAudioPrompt(voskHint, screenElementsSummary, isMediaPlaying, speakerVerified, isInActiveCall)
        val b64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val parts = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", prompt) })
            add(JsonObject().apply {
                add("inline_data", JsonObject().apply {
                    addProperty("mime_type", "audio/wav")
                    addProperty("data", b64Audio)
                })
            })
        }
        JarvisLogStore.log("GEMINI_AUDIO", "Dispatching audio=${wavBytes.size / 1024}KB media=$isMediaPlaying verified=$speakerVerified inCall=$isInActiveCall hint='$voskHint'")
        executeCommandParts(parts, isAudioMode = true, suppressSpeech = isInActiveCall)
    }

    /**
     * Evaluates an incoming utterance whose biometric Voice Lock score fell into DOUBT or REJECTED.
     * - Ignores background TV, Reels, or normal conversations between friends silently.
     * - If the speaker clearly attempted an assistant/phone command:
     *   - For DOUBT: returns both the parsed ActionDecision (held safely) + a dynamic Hinglish prompt asking for the Secret Code.
     *   - For REJECTED: returns ONLY a polite dynamic Hinglish refusal explaining the voice does not match Sir's voice.
     */
    data class UnauthorizedAudioEvaluation(
        val isCommandAttempt: Boolean,
        val transcript: String,
        val dynamicSpeech: String,
        val heldDecision: ActionDecision? = null
    )

    suspend fun evaluateDoubtOrRejectedAudio(
        wavBytes: ByteArray,
        voskHint: String,
        screenElementsSummary: String,
        tier: String, // "DOUBT" or "REJECTED"
        similarity: Float,
        acceptThreshold: Float,
        isMediaPlaying: Boolean
    ): UnauthorizedAudioEvaluation = withContext(Dispatchers.IO) {
        if (wavBytes.size <= 44 || apiKey.isBlank() || apiKey.startsWith("your_")) {
            return@withContext UnauthorizedAudioEvaluation(false, "", "")
        }

        val isDoubt = tier.equals("DOUBT", ignoreCase = true)
        val livingMemory = memoryManager?.getLivingMemoryContext() ?: ""
        val prompt = JarvisPrompts.buildVoiceGuardPrompt(
            tier = tier,
            similarity = similarity,
            acceptThreshold = acceptThreshold,
            isMediaPlaying = isMediaPlaying,
            screenSummary = screenElementsSummary,
            livingMemory = livingMemory,
            voskHint = voskHint,
            includeAudioHeader = true
        )

        val b64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val parts = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", prompt) })
            add(JsonObject().apply {
                add("inline_data", JsonObject().apply {
                    addProperty("mime_type", "audio/wav")
                    addProperty("data", b64Audio)
                })
            })
        }

        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val payload = JsonObject().apply {
                    add("contents", JsonArray().apply { add(JsonObject().apply { add("parts", parts) }) })
                    add("generation_config", JsonObject().apply {
                        addProperty("temperature", 0.2)
                        addProperty("response_mime_type", "application/json")
                    })
                }
                val req = Request.Builder().url(url).post(payload.toString().toRequestBody(jsonMediaType)).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val jsonRes = gson.fromJson(body, JsonObject::class.java)
                    val rawText = jsonRes.getAsJsonArray("candidates")?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")?.getAsJsonArray("parts")?.get(0)?.asJsonObject
                        ?.get("text")?.asString ?: ""
                    val obj = gson.fromJson(rawText, JsonObject::class.java)
                    val isAttempt = obj.get("is_command_attempt")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false
                    val transcript = obj.get("transcript")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
                    val dynSpeech = obj.get("dynamic_speech")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
                    val heldObj = obj.get("held_command")?.takeIf { isDoubt && !it.isJsonNull && it.isJsonObject }?.asJsonObject
                    val heldDecision = heldObj?.let { parseDecisionObject(it, isAudioMode = true) }
                    JarvisLogStore.log("VOICE_GUARD", "tier=$tier model=$model attempt=$isAttempt transcript='$transcript' speech='$dynSpeech'")
                    return@withContext UnauthorizedAudioEvaluation(isAttempt, transcript, dynSpeech, heldDecision)
                }
            } catch (e: Exception) {
                JarvisLogStore.log("VOICE_GUARD_ERR", "model=$model err=${e.message}")
            }
        }
        UnauthorizedAudioEvaluation(false, "", "")
    }

    /**
     * Verifies whether the speaker in wavBytes accurately spoke the configured Secret Code passphrase.
     */
    data class SecretCodeCheckResult(
        val matched: Boolean,
        val heardText: String,
        val dynamicReply: String
    )

    suspend fun verifySecretCodeAudio(
        wavBytes: ByteArray,
        expectedSecretCode: String,
        voskHint: String = "",
        offlineFallbackMatch: (String, String) -> Boolean
    ): SecretCodeCheckResult = withContext(Dispatchers.IO) {
        val cleanExpected = expectedSecretCode.trim()
        if (wavBytes.size <= 44 || cleanExpected.isBlank()) {
            return@withContext SecretCodeCheckResult(false, "", "")
        }
        if (apiKey.isBlank() || apiKey.startsWith("your_")) {
            val ok = offlineFallbackMatch(voskHint, cleanExpected)
            return@withContext SecretCodeCheckResult(ok, voskHint, "")
        }

        val cleanHint = voskHint.replace("\"", "'").trim()
        val prompt = """
You are verifying a spoken voice secret passphrase for J.A.R.V.I.S.
Expected Secret Code (written text): "$cleanExpected"
Offline rough phonetic hint: "$cleanHint"

Listen carefully to the attached audio clip.
Accept equivalent number words / spellings / slight Indian English or Hindi pronunciation variations of "$cleanExpected" (for example "9" == "nine" == "nau", case-insensitive), EVEN IF embedded in a short phrase like "mera secret code hai $cleanExpected".
Reject if a different code, unclear gibberish, or unrelated speech was spoken.
Also provide a very short, natural Hinglish `"dynamic_reply"` (1 sentence: warm confirmation if matched, polite refusal if failed).

OUTPUT ONLY RAW JSON:
{
  "heard_text": "<what the speaker actually said>",
  "matched": true|false,
  "dynamic_reply": "<1 short everyday Hinglish sentence>"
}
""".trimIndent()

        val b64Audio = Base64.encodeToString(wavBytes, Base64.NO_WRAP)
        val parts = JsonArray().apply {
            add(JsonObject().apply { addProperty("text", prompt) })
            add(JsonObject().apply {
                add("inline_data", JsonObject().apply {
                    addProperty("mime_type", "audio/wav")
                    addProperty("data", b64Audio)
                })
            })
        }

        for (model in models) {
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val payload = JsonObject().apply {
                    add("contents", JsonArray().apply { add(JsonObject().apply { add("parts", parts) }) })
                    add("generation_config", JsonObject().apply {
                        addProperty("temperature", 0.0)
                        addProperty("response_mime_type", "application/json")
                    })
                }
                val req = Request.Builder().url(url).post(payload.toString().toRequestBody(jsonMediaType)).build()
                val res = client.newCall(req).execute()
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val jsonRes = gson.fromJson(body, JsonObject::class.java)
                    val rawText = jsonRes.getAsJsonArray("candidates")?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")?.getAsJsonArray("parts")?.get(0)?.asJsonObject
                        ?.get("text")?.asString ?: ""
                    val obj = gson.fromJson(rawText, JsonObject::class.java)
                    val heard = obj.get("heard_text")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
                    val matched = obj.get("matched")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false
                    val reply = obj.get("dynamic_reply")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim().orEmpty()
                    JarvisLogStore.log("SECRET_CHECK", "model=$model matched=$matched heard='$heard'")
                    return@withContext SecretCodeCheckResult(matched, heard, reply)
                }
            } catch (e: Exception) {
                JarvisLogStore.log("SECRET_CHECK_ERR", "model=$model err=${e.message}")
            }
        }

        val fallbackOk = offlineFallbackMatch(voskHint, cleanExpected)
        SecretCodeCheckResult(fallbackOk, voskHint, "")
    }

    private fun executeCommandParts(parts: JsonArray, isAudioMode: Boolean, suppressSpeech: Boolean = false): ActionDecision {
        var lastError = ""
        for (model in models) {
            val t0 = System.currentTimeMillis()
            try {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
                val requestPayload = JsonObject().apply {
                    val contents = JsonArray().apply {
                        add(JsonObject().apply {
                            add("parts", parts)
                        })
                    }
                    add("contents", contents)

                    val genConfig = JsonObject().apply {
                        addProperty("temperature", if (isAudioMode) 0.15 else 0.3)
                        addProperty("response_mime_type", "application/json")
                    }
                    add("generation_config", genConfig)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(requestPayload.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = client.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - t0
                val body = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    val jsonResponse = gson.fromJson(body, JsonObject::class.java)
                    val candidates = jsonResponse.getAsJsonArray("candidates")
                    val firstCandidate = candidates?.get(0)?.asJsonObject
                    val textContent = firstCandidate?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")?.get(0)?.asJsonObject?.get("text")?.asString ?: ""

                    val parsedDecision = gson.fromJson(textContent, JsonObject::class.java)
                    val decision = parseDecisionObject(parsedDecision, isAudioMode, suppressSpeech)
                    JarvisLogStore.log(
                        if (isAudioMode) "GEMINI_AUDIO" else "GEMINI_CMD",
                        "model=$model latency=${elapsed}ms ignored=${decision.shouldIgnore} transcript='${decision.transcript ?: ""}' tool=${decision.toolName} args=${decision.toolArgs ?: emptyMap()} reason='${decision.reasoning ?: ""}' speech='${decision.speech ?: ""}'"
                    )
                    return decision
                } else {
                    lastError = "Gemini $model HTTP ${response.code}"
                    Log.w("GeminiBrain", "$lastError: $body")
                    JarvisLogStore.log("GEMINI_CMD_ERR", "model=$model latency=${elapsed}ms http=${response.code} body=${body.take(240)}")
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Network error"
                Log.w("GeminiBrain", "Gemini $model request failed: $lastError")
                JarvisLogStore.log("GEMINI_CMD_ERR", "model=$model exception=$lastError")
            }
        }

        return if (isAudioMode) {
            ActionDecision(null, null, null, reasonForAudioErr(lastError), shouldIgnore = true)
        } else {
            ActionDecision("Sir, Gemini AI se sampark nahi ho saka ($lastError). Kripya internet connection aur API key check karein.", null, null)
        }
    }

    private fun reasonForAudioErr(err: String): String = "Audio AI error: $err"

    private fun parseDecisionObject(
        parsedDecision: JsonObject,
        isAudioMode: Boolean,
        suppressSpeech: Boolean = false
    ): ActionDecision {
        val shouldIgnore = parsedDecision.get("should_ignore")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false
        val transcript = parsedDecision.get("transcript")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        val toolName = parsedDecision.get("tool_name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
        val rawSpeech = parsedDecision.get("speech")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
        val reasoning = parsedDecision.get("reasoning")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }

        if (shouldIgnore && toolName == null) {
            return ActionDecision(null, null, null, reasoning, transcript, shouldIgnore = true)
        }

        val speech = when {
            suppressSpeech -> null
            toolName != null && toolName in JarvisPrompts.ASYNC_SILENT_TOOLS -> null
            toolName == null && rawSpeech == null -> if (isAudioMode) null else "Ji Sir?"
            else -> rawSpeech
        }
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
        return ActionDecision(speech, toolName, if (argsMap.isEmpty()) null else argsMap, reasoning, transcript, shouldIgnore = false)
    }

    /**
     * Inspects the live accessibility screen tree with Gemini to determine next UI action.
     */
    suspend fun decideUIAction(prompt: String): UIActionDecision = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey.startsWith("your_")) {
            return@withContext UIActionDecision("error", null, null, null, "Gemini API Key missing")
        }

        var lastError = "AI service unavailable"
        for (attempt in 0..1) {
            var shouldRetryTransient = false
            for (model in models) {
                val t0 = System.currentTimeMillis()
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
                    val elapsed = System.currentTimeMillis() - t0
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
                        val reasoning = parsed.get("reasoning")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString
                        JarvisLogStore.log(
                            "GEMINI_UI",
                            "model=$model latency=${elapsed}ms raw=$textContent | action=$action tag=$tag text='${text ?: ""}' dir=$dir reason='${reasoning ?: ""}' summary='${summary ?: ""}'"
                        )
                        return@withContext UIActionDecision(action, tag, text, dir, summary, reasoning)
                    } else {
                        lastError = "HTTP ${response.code}"
                        if (response.code == 429 || response.code == 503) shouldRetryTransient = true
                        JarvisLogStore.log("GEMINI_UI_ERR", "model=$model latency=${elapsed}ms http=${response.code} body=${body.take(240)}")
                    }
                } catch (e: Exception) {
                    lastError = e.message ?: "Network error"
                    Log.w("GeminiBrain", "UI action decision error on $model: $lastError")
                    JarvisLogStore.log("GEMINI_UI_ERR", "model=$model exception=$lastError")
                }
            }
            if (!shouldRetryTransient || attempt >= 1) break
            kotlinx.coroutines.delay(550)
        }
        UIActionDecision("error", null, null, null, lastError)
    }

    /**
     * Parses a user command for social app navigation into structured typed intent.
     */
    suspend fun extractSocialIntent(instruction: String): SocialIntent = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey.startsWith("your_")) {
            return@withContext SocialIntent("other", "", "", 1)
        }

        val clean = instruction.replace("\"", "'").trim()
        val prompt = """
Extract the exact social app intent from Sir's command: "$clean"

Classify "actionType":
- "dm": sending a direct personal message/reply to a recipient.
- "read_dm": opening a specific sender's DM chat to mark it seen and read aloud their latest incoming message.
- "chat_media": opening or playing the latest reel/video (count=1), last N reels/videos (count=N), or all shared reels (count=-1) shared INSIDE a DM chat ("bheji hui", "sent by", "chat mein"). Set "fromMe": true ONLY if Sir explicitly asks for reels sent BY HIMSELF/HERSELF (otherwise false = friend's sent reels).
- "chat_media_all": playing all available reels/videos shared inside a specific person's DM chat (count=-1, respect "fromMe").
- "story": watching someone's story by sliding the homepage story tray horizontally.
- "follow": searching and following a public account/profile.
- "profile_info": checking an account's followers count, following count, post count, bio, or opening their profile info when Sir asks for stats/followers/profile details (NO DM).
- "profile_post": opening or playing the latest uploaded post or reel ON an account's Instagram profile grid (e.g. "latest post", "new post", "recent upload" — NOT shared in DM).
- "reels": opening or scrolling the general Reels feed when NO specific sender or profile target is named.
- "other": anything else.

OUTPUT ONLY RAW JSON:
{"actionType":"dm|read_dm|chat_media|chat_media_all|story|follow|profile_info|profile_post|reels|other","target":"<person or account name, or empty string>","message":"<exact message text if dm, else empty string>","count":<integer 1 for latest, N for last N requested reels, or -1 for all>,"fromMe":<true or false>}
""".trimIndent()

        val t0 = System.currentTimeMillis()
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
                        addProperty("temperature", 0.0)
                        addProperty("response_mime_type", "application/json")
                    }
                    add("generation_config", genConfig)
                }

                val request = Request.Builder()
                    .url(url)
                    .post(requestPayload.toString().toRequestBody(jsonMediaType))
                    .build()

                val response = client.newCall(request).execute()
                val elapsed = System.currentTimeMillis() - t0
                val body = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    val jsonResponse = gson.fromJson(body, JsonObject::class.java)
                    val textContent = jsonResponse.getAsJsonArray("candidates")?.get(0)?.asJsonObject
                        ?.getAsJsonObject("content")?.getAsJsonArray("parts")?.get(0)?.asJsonObject
                        ?.get("text")?.asString ?: ""
                    val parsed = gson.fromJson(textContent, JsonObject::class.java)
                    val actionType = parsed.get("actionType")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.lowercase()?.trim() ?: "other"
                    val target = parsed.get("target")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim() ?: ""
                    val msg = parsed.get("message")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim() ?: ""
                    val count = parsed.get("count")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asInt ?: 1
                    val fromMe = parsed.get("fromMe")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false
                    JarvisLogStore.log("GEMINI_SOCIAL", "model=$model latency=${elapsed}ms raw=$textContent -> action=$actionType target='$target' count=$count fromMe=$fromMe")
                    return@withContext SocialIntent(actionType, target, msg, count, fromMe)
                }
            } catch (e: Exception) {
                Log.w("GeminiBrain", "SocialIntent parse error on $model: ${e.message}")
                JarvisLogStore.log("GEMINI_SOCIAL_ERR", "model=$model err=${e.message}")
            }
        }
        SocialIntent("other", "", "", 1, false)
    }

    /**
     * Generates a dynamic 1-sentence completion confirmation in natural spoken Hinglish.
     * Ensures zero hardcoded or static fallback messages in Kotlin.
     */
    suspend fun generateCompletionConfirmation(appName: String, instruction: String): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey.startsWith("your_")) return@withContext ""
        val prompt = "Sir gave the command: '$instruction' in $appName. The task has just finished successfully. Respond as J.A.R.V.I.S. in 1 short, natural sentence in conversational everyday Hinglish confirming it is done (e.g. what was completed). Do NOT include robotic phrases like 'goal complete', 'execute', or app tags. Return only raw text without quotes."

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
                    val text = candidates?.get(0)?.asJsonObject?.getAsJsonObject("content")
                        ?.getAsJsonArray("parts")?.get(0)?.asJsonObject?.get("text")?.asString?.trim() ?: ""
                    if (text.isNotBlank()) return@withContext text
                }
            } catch (e: Exception) {
                Log.w("GeminiBrain", "Confirmation generation failed on $model: ${e.message}")
            }
        }
        ""
    }

    private fun buildPrompt(userText: String, screenElementsSummary: String): String {
        val cleanInput = userText.replace("\"", "'")
        val livingMemory = memoryManager?.getLivingMemoryContext() ?: ""
        return JarvisPrompts.buildTextCommandPrompt(
            cleanInput = cleanInput,
            livingMemory = livingMemory,
            screenSummary = screenElementsSummary
        )
    }

    private fun buildAudioPrompt(
        voskHint: String,
        screenElementsSummary: String,
        isMediaPlaying: Boolean,
        speakerVerified: Boolean,
        isInActiveCall: Boolean = false
    ): String {
        val livingMemory = memoryManager?.getLivingMemoryContext() ?: ""
        return JarvisPrompts.buildVoiceSystemPrompt(
            livingMemory = livingMemory,
            screenSummary = screenElementsSummary,
            isMediaPlaying = isMediaPlaying,
            speakerVerified = speakerVerified,
            isInActiveCall = isInActiveCall,
            voskHint = voskHint,
            includeAudioListenerHeader = true
        )
    }
}
