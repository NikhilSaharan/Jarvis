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
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
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
        speakerVerified: Boolean = true
    ): ActionDecision = withContext(Dispatchers.IO) {
        if (wavBytes.size <= 44) {
            return@withContext ActionDecision(null, null, null, shouldIgnore = true)
        }
        if (apiKey.isBlank() || apiKey.startsWith("your_")) {
            return@withContext ActionDecision("Sir, Gemini API Key configure nahi hai.", null, null)
        }

        val prompt = buildAudioPrompt(voskHint, screenElementsSummary, isMediaPlaying, speakerVerified)
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
        JarvisLogStore.log("GEMINI_AUDIO", "Dispatching audio=${wavBytes.size / 1024}KB media=$isMediaPlaying verified=$speakerVerified hint='$voskHint'")
        executeCommandParts(parts, isAudioMode = true)
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
        val cleanHint = voskHint.replace("\"", "'").trim()
        val prompt = """
You are J.A.R.V.I.S., protecting Sir's phone via Voice Lock.
Biometric check status: tier=$tier (similarity=${"%.2f".format(similarity)} vs owner_threshold=${"%.2f".format(acceptThreshold)}), isMediaPlaying=$isMediaPlaying
Offline phonetic hint: "$cleanHint"
${if (livingMemory.isNotBlank()) "MEMORY:\n$livingMemory\n" else ""}
Live Screen Elements:
$screenElementsSummary

TASK: Listen closely to the attached Hinglish/Hindi/English audio clip and decide:
1. Is someone EXPLICITLY trying to give an imperative phone/app/media/settings command to Jarvis (or addressing "Jarvis"), OR is this just casual room conversation among friends / TV / movie / Reel dialogue / ambient noise?
   - If it is casual human-to-human talk ("bhai chai le aa", "kal milte hain", "Instagram pe maine woh dekha"), singing, background media, or unclear speech -> set `"is_command_attempt": false` and `"dynamic_speech": ""`.
2. If `"is_command_attempt": true`:
   - If `tier` is "DOUBT": Parse the requested command (`tool_name`, `tool_args`, `speech`) into `held_command` WITHOUT running it, and generate `"dynamic_speech"` in natural conversational Hinglish saying their voice sounds slightly uncertain/similar and asking for Sir's **secret code** before proceeding.
   - If `tier` is "REJECTED": Set `"held_command": null` and generate `"dynamic_speech"` in natural conversational Hinglish stating clearly that their voice does not match Sir's enrolled voice so the command cannot be executed.

TOOLS ALLOWED INSIDE `held_command` (when tier=DOUBT):
- media_control(action), volume_control(action, percent), answer_call(), end_call(), close_app(app_name), make_phone_call(recipient), whatsapp_call(recipient, call_type), send_whatsapp_message(recipient, message), instagram_send_message(recipient, message), instagram_read_last_message(sender), instagram_watch_story(person), instagram_follow_account(account), instagram_play_chat_reels(sender, count, from_me), instagram_play_reels(next), send_sms(recipient, message), play_youtube(query), open_app(app_name), universal_app_action(app_name, instruction), tap_tag(tag), type_tag(tag, text), scroll_screen(direction), schedule_action(...), sleep_routine(minutes), manage_vip(...), learn_preference(...), set_dnd(enabled), set_brightness(level), set_alarm(hour, minute, message), set_timer(seconds, message)

OUTPUT ONLY RAW JSON:
{
  "transcript": "<exact words heard>",
  "is_command_attempt": true|false,
  "dynamic_speech": "<1 short natural Hinglish sentence or empty string if false>",
  "held_command": { "reasoning": "...", "speech": null, "tool_name": "...", "tool_args": { ... } }
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

    private fun executeCommandParts(parts: JsonArray, isAudioMode: Boolean): ActionDecision {
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
                    val decision = parseDecisionObject(parsedDecision, isAudioMode)
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

    private fun parseDecisionObject(parsedDecision: JsonObject, isAudioMode: Boolean): ActionDecision {
        val shouldIgnore = parsedDecision.get("should_ignore")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asBoolean ?: false
        val transcript = parsedDecision.get("transcript")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotBlank() && it != "null" }
        val toolName = parsedDecision.get("tool_name")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
        val rawSpeech = parsedDecision.get("speech")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }
        val reasoning = parsedDecision.get("reasoning")?.takeIf { !it.isJsonNull && it.isJsonPrimitive }?.asString?.takeIf { it != "null" && it.isNotBlank() }

        if (shouldIgnore && toolName == null) {
            return ActionDecision(null, null, null, reasoning, transcript, shouldIgnore = true)
        }

        val asyncExecutionTools = setOf(
            "send_whatsapp_message", "whatsapp_call", "make_phone_call", "send_sms",
            "play_youtube", "open_app", "universal_app_action",
            "media_control", "volume_control",
            "instagram_send_message", "instagram_read_last_message", "instagram_watch_story",
            "instagram_follow_account", "instagram_play_chat_reels", "instagram_play_reels"
        )
        val speech = when {
            toolName != null && toolName in asyncExecutionTools -> null
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
        val memoryBlock = if (livingMemory.isNotBlank()) {
            "\nJARVIS LIVING MEMORY & USER CONTEXT:\n$livingMemory\n"
        } else ""

        return """
You are J.A.R.V.I.S., the ultra-intelligent, loyal AI butler companion and trusted friend to 'Sir'.
You possess full autonomous control over Sir's Android smartphone.
You converse naturally in casual everyday spoken Hinglish (simple, natural Hindi mixed with everyday English, exactly how real friends talk in India).
Keep all responses short (1-2 sentences), warm, and human.
Strictly avoid heavy, formal, bookish, or Sanskritized Hindi words (never use words like 'swabhavik', 'kripya', 'avasar', 'durlabh', 'monotony').

$memoryBlock
Sir's Input: "$cleanInput"
Live Screen Elements:
$screenElementsSummary

CORE OPERATING PRINCIPLES:
1. NATURAL HUMAN CONVERSATION FIRST (EMPATHY & FRIENDSHIP):
   - When Sir shares emotions, mood, boredom, tiredness, headache, pain, or casual thoughts:
   - Reply warmly, naturally, and conversationally like a caring friend (e.g. ask what happened or tell Sir to relax in 1-2 short sentences).
   - NEVER auto-trigger any tools, media, music, YouTube, DND, or apps unless Sir explicitly commands or requests an action on a device/app.
   - For general casual conversation, emotions, or general knowledge questions NOT asking about an app/message/contact: set `tool_name`: null and `tool_args`: null, and reply directly in `speech`.
   - IMPORTANT: If Sir asks what message a contact sent or asks to check/read/open messages, stories, reels, or calls inside an app (even when phrased as a question), ALWAYS invoke the corresponding tool!

2. CLEAN ACTION EXECUTION (NO ROBOTIC CLICHÉS OR STEP NARRATION):
   - Invoke an action tool ONLY when Sir explicitly gives a direct command or asks to check/perform an in-app action.
   - For app, calling, messaging, and media tools (universal_app_action, instagram_*, send_whatsapp_message, whatsapp_call, make_phone_call, send_sms, play_youtube):
     - Set `speech`: null. Never say "Bhej diya" or "Kar diya" before the tool finishes executing!
   - For direct single-action device settings tools (set_dnd, set_brightness, set_alarm, set_timer):
     - Provide a short, natural confirmation in `speech` in everyday Hinglish.
   - STRICTLY AVOID robotic English clichés (never say "Right away, Sir", "Opening X for you", "Playing X for you", "Executing X", "At your service").
   - NEVER narrate internal steps, screens, or UI buttons (do not say what screen was opened, what was typed, or what button was tapped). Let the tool execute cleanly.

3. HARDWARE & APPLICATION TOOLS:
   - media_control(action: "play_pause"|"pause"|"play"|"next"|"previous"|"stop"): Controls active Hotstar, YouTube, Spotify, or video playback silently without speaking over media.
   - volume_control(action: "up"|"down"|"mute"|"unmute"|"set", percent: int?): Changes device media volume silently without interrupting playback.
   - answer_call(): Answers, accepts, or picks up an incoming call (phone or WhatsApp).
   - end_call(): Cuts, ends, or disconnects an ongoing call, or declines/rejects an incoming call.
   - close_app(app_name: string?): Closes, stops, or kills a specific open app or media playing in PiP/background, or closes the current screen if none specified.
   - make_phone_call(recipient: string): Dials a normal phone call.
   - whatsapp_call(recipient: string, call_type: "voice"|"video"): Starts a WhatsApp audio/voice or video call.
   - send_whatsapp_message(recipient: string, message: string): Sends a WhatsApp message.
   - instagram_send_message(recipient: string, message: string): Sends a direct personal one-to-one DM message on Instagram to recipient.
   - instagram_read_last_message(sender: string): Opens sender's Instagram DM chat so it is marked Seen and reads aloud their latest incoming message (use even if phrased as a question asking what message someone sent).
   - instagram_watch_story(person: string): Opens Instagram homepage and slides the horizontal story tray to play person's active story without using Search.
   - instagram_follow_account(account: string): Searches Instagram accounts and follows the verified/official matching account (prioritizing genuine profiles over fanpages).
   - instagram_play_chat_reels(sender: string, count: int, from_me: boolean): Opens sender's Instagram DM chat and plays shared reels starting from the latest upward (count=1 for latest, count=N for last N requested reels, count=-1 for all shared reels; from_me=false by default for reels sent by sender, set from_me=true ONLY if Sir explicitly asks for reels sent by himself/herself).
   - instagram_play_reels(next: boolean): Opens Instagram's general Reels feed (only when NO friend sender is named) and scrolls next if next=true (use next=true whenever Sir asks for "agli", "next", "agla reel", "dusra" while watching Instagram!).
   - send_sms(recipient: string, message: string): Sends a normal SMS text message.
   - play_youtube(query: string): ONLY when Sir explicitly asks to play a song, music, or video on YouTube.
   - open_app(app_name: string): ONLY when Sir specifically asks to just launch or open an app without further actions.
   - universal_app_action(app_name: string, instruction: string): Use for other apps (notes, browsers, shopping, streaming, etc.) or any generic in-app multi-step UI task not covered by a dedicated tool above.
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
  "reasoning": "<1 concise sentence explaining why this tool and parameters were selected>",
  "speech": "<Natural, short reply in everyday Hinglish, or null if calling universal_app_action, media_control, volume_control, or instagram_*>",
  "tool_name": "<tool_name or null>",
  "tool_args": { ... or null }
}
""".trimIndent()
    }

    private fun buildAudioPrompt(
        voskHint: String,
        screenElementsSummary: String,
        isMediaPlaying: Boolean,
        speakerVerified: Boolean
    ): String {
        val livingMemory = memoryManager?.getLivingMemoryContext() ?: ""
        val memoryBlock = if (livingMemory.isNotBlank()) {
            "\nJARVIS LIVING MEMORY & USER CONTEXT:\n$livingMemory\n"
        } else ""
        val cleanHint = voskHint.replace("\"", "'").trim()

        return """
You are J.A.R.V.I.S., Sir's hands-free personal Android AI butler listening continuously in the background WITHOUT requiring a wake word ("Jarvis") before every command.
Listen directly to the attached Hinglish/Hindi/English audio clip and understand what Sir spoke.
Phonetic offline hint (may mishear Hindi words, always trust the real audio over hint): "$cleanHint"
Context: isMediaPlaying=$isMediaPlaying, speakerVoiceLockPassed=$speakerVerified

$memoryBlock
Live Screen Elements:
$screenElementsSummary

CRITICAL HANDS-FREE FILTER RULES (`should_ignore`):
1. Set `"should_ignore": true`, `"tool_name": null`, `"speech": null` IF the audio is:
   - Unclear noise, coughing, breathing, silence, music lyrics, singing, OR dialogue coming from Hotstar/Reels/TV speakers.
   - Casual human-to-human conversation (between Sir and friends/family) even if mentioning apps, calls, or people in past/narrative conversation ("kal maine Anuj ko msg kiya", "Instagram pe bahut reels aati hain", "uska phone aaya tha") WITHOUT issuing an imperative command to the assistant.
2. Set `"should_ignore": false` ONLY when Sir issues a clear imperative command/request to operate the phone, apps, calls, messages, media, or settings OR directly addresses "Jarvis".
3. Always infer the user's true intent dynamically from the live audio and the current Live Screen Elements (never assume fixed phrases):
   - If Sir asks to move to the next short/reel while Instagram is open -> call `instagram_play_reels(next=true)` with `"speech": null`.
   - If Sir asks to go back/up on the current feed -> call `scroll_screen(direction="up")` with `"speech": null`.
   - If Sir asks to pause/play/resume/skip active video or audio -> call `media_control` with `"speech": null`.
   - If Sir asks to adjust or mute volume -> call `volume_control` with `"speech": null`.

TOOLS:
- media_control(action: "play_pause"|"pause"|"play"|"next"|"previous"|"stop")
- volume_control(action: "up"|"down"|"mute"|"unmute"|"set", percent: int?)
- answer_call()
- end_call()
- close_app(app_name: string?)
- make_phone_call(recipient: string)
- whatsapp_call(recipient: string, call_type: "voice"|"video")
- send_whatsapp_message(recipient: string, message: string)
- instagram_send_message(recipient: string, message: string)
- instagram_read_last_message(sender: string)
- instagram_watch_story(person: string)
- instagram_follow_account(account: string)
- instagram_play_chat_reels(sender: string, count: int, from_me: boolean)
- instagram_play_reels(next: boolean)
- send_sms(recipient: string, message: string)
- play_youtube(query: string)
- open_app(app_name: string)
- universal_app_action(app_name: string, instruction: string)
- tap_tag(tag: int)
- type_tag(tag: int, text: string)
- scroll_screen(direction: "down"|"up")
- schedule_action(delay_seconds: int, action: string, action_args: object, summary: string)
- sleep_routine(minutes: int)
- manage_vip(action: "add"|"remove"|"list", contact: string)
- learn_preference(preference: string)
- set_dnd(enabled: boolean)
- set_brightness(level: string)
- set_alarm(hour: int, minute: int, message: string)
- set_timer(seconds: int, message: string)

OUTPUT ONLY RAW JSON (no markdown, no backticks):
{
  "transcript": "<exact Hinglish/English transcription of what was heard, or empty string if noise>",
  "should_ignore": true|false,
  "reasoning": "<1 short sentence>",
  "speech": "<short everyday Hinglish reply ONLY when needed, else null>",
  "tool_name": "<tool_name or null>",
  "tool_args": { ... or null }
}
""".trimIndent()
    }
}
