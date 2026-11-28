package com.jarvis.assistant.voice

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local, offline owner voiceprint storage and biometric verification engine.
 * Combines text-independent 48-dim CMVN Mel-Spectral formant embeddings (works across Hindi/Hinglish/English
 * even on 0.3s commands), voiced pitch (F0 Hz), and Vosk 128-dim x-vectors when available.
 */
object VoiceprintManager {

    enum class Sensitivity(val label: String, val baseThreshold: Float, val selfMargin: Float) {
        RELAXED("Relaxed", 0.49f, 0.20f),
        BALANCED("Balanced", 0.56f, 0.14f),
        STRICT("Strict", 0.64f, 0.08f)
    }

    enum class MatchTier {
        TRUSTED,
        DOUBT,
        REJECTED
    }

    data class EnrollmentSample(
        val spkVector: FloatArray,
        val spkFrames: Int,
        val pitchHz: Float?,
        val acousticVector: FloatArray? = null,
        val voicedFrames: Int = 0
    )

    data class AcousticFeatures(
        val vector: FloatArray,
        val voicedFrames: Int,
        val pitchHz: Float?
    )

    data class VerificationResult(
        val tier: MatchTier,
        val allowed: Boolean,
        val similarity: Float,
        val acceptThreshold: Float,
        val spkFrames: Int,
        val detectedPitchHz: Float?,
        val reason: String
    )

    private const val PREFS_NAME = "jarvis_voiceprint"
    private const val KEY_ENABLED = "voice_lock_enabled"
    private const val KEY_SENSITIVITY = "voice_lock_sensitivity"
    private const val KEY_CENTROID = "voice_lock_centroid"
    private const val KEY_SAMPLES = "voice_lock_samples"
    private const val KEY_MEDIAN_F0 = "voice_lock_median_f0"
    private const val KEY_MIN_F0 = "voice_lock_min_f0"
    private const val KEY_MAX_F0 = "voice_lock_max_f0"
    private const val KEY_SAMPLE_COUNT = "voice_lock_sample_count"
    private const val KEY_SELF_FLOOR = "voice_lock_self_floor"
    private const val KEY_SECRET_CODE = "voice_lock_secret_code"
    private const val KEY_ACOUSTIC_CENTROID = "voice_lock_acoustic_centroid"
    private const val KEY_ACOUSTIC_SAMPLES = "voice_lock_acoustic_samples"
    private const val KEY_ACOUSTIC_SELF_FLOOR = "voice_lock_acoustic_self_floor"

    private const val MEL_BANDS = 24
    private const val FFT_SIZE = 512

    private val gson = Gson()
    private val lock = Any()

    @Volatile
    private var cachedLoaded = false
    private var cachedEnabled = true
    private var cachedSensitivity = Sensitivity.BALANCED
    private var cachedCentroid: FloatArray? = null
    private var cachedSamples: List<FloatArray> = emptyList()
    private var cachedAcousticCentroid: FloatArray? = null
    private var cachedAcousticSamples: List<FloatArray> = emptyList()
    private var cachedAcousticSelfFloor: Float = 0f
    private var cachedMedianF0: Float = 0f
    private var cachedMinF0: Float = 0f
    private var cachedMaxF0: Float = 0f
    private var cachedSelfFloor: Float = 0f
    private var cachedSecretCode: String = ""

    private fun ensureLoaded(context: Context) {
        if (cachedLoaded) return
        synchronized(lock) {
            if (cachedLoaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            cachedEnabled = prefs.getBoolean(KEY_ENABLED, true)
            cachedSensitivity = try {
                Sensitivity.valueOf(prefs.getString(KEY_SENSITIVITY, Sensitivity.BALANCED.name) ?: Sensitivity.BALANCED.name)
            } catch (_: Exception) {
                Sensitivity.BALANCED
            }
            cachedAcousticSelfFloor = prefs.getFloat(KEY_ACOUSTIC_SELF_FLOOR, 0f)
            cachedMedianF0 = prefs.getFloat(KEY_MEDIAN_F0, 0f)
            cachedMinF0 = prefs.getFloat(KEY_MIN_F0, 0f)
            cachedMaxF0 = prefs.getFloat(KEY_MAX_F0, 0f)
            cachedSelfFloor = prefs.getFloat(KEY_SELF_FLOOR, 0f)
            cachedSecretCode = prefs.getString(KEY_SECRET_CODE, "")?.trim().orEmpty()

            val centroidJson = prefs.getString(KEY_CENTROID, null)
            cachedCentroid = if (!centroidJson.isNullOrBlank()) {
                try {
                    gson.fromJson(centroidJson, FloatArray::class.java)?.let { l2Normalize(it) }
                } catch (_: Exception) {
                    null
                }
            } else null

            val samplesJson = prefs.getString(KEY_SAMPLES, null)
            cachedSamples = if (!samplesJson.isNullOrBlank()) {
                try {
                    val type = object : TypeToken<List<FloatArray>>() {}.type
                    val parsed: List<FloatArray>? = gson.fromJson(samplesJson, type)
                    parsed?.map { l2Normalize(it) }.orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()

            val acCentroidJson = prefs.getString(KEY_ACOUSTIC_CENTROID, null)
            cachedAcousticCentroid = if (!acCentroidJson.isNullOrBlank()) {
                try {
                    gson.fromJson(acCentroidJson, FloatArray::class.java)?.let { l2Normalize(it) }
                } catch (_: Exception) {
                    null
                }
            } else null

            val acSamplesJson = prefs.getString(KEY_ACOUSTIC_SAMPLES, null)
            cachedAcousticSamples = if (!acSamplesJson.isNullOrBlank()) {
                try {
                    val type = object : TypeToken<List<FloatArray>>() {}.type
                    val parsed: List<FloatArray>? = gson.fromJson(acSamplesJson, type)
                    parsed?.map { l2Normalize(it) }.orEmpty()
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()

            cachedLoaded = true
        }
    }

    fun isEnrolled(context: Context): Boolean {
        ensureLoaded(context)
        val hasVosk = cachedCentroid != null && cachedCentroid!!.isNotEmpty()
        val hasAcoustic = cachedAcousticCentroid != null && cachedAcousticCentroid!!.isNotEmpty()
        return hasVosk || hasAcoustic
    }

    fun hasAcousticProfile(context: Context): Boolean {
        ensureLoaded(context)
        return cachedAcousticCentroid != null && cachedAcousticCentroid!!.isNotEmpty()
    }

    fun hasSecretCode(context: Context): Boolean {
        ensureLoaded(context)
        return cachedSecretCode.isNotBlank()
    }

    fun isFullyConfigured(context: Context): Boolean {
        return isEnrolled(context) && hasSecretCode(context)
    }

    fun getSecretCode(context: Context): String {
        ensureLoaded(context)
        return cachedSecretCode
    }

    fun matchesSecretCodeTextOffline(spokenText: String, expectedCode: String): Boolean {
        val a = spokenText.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val b = expectedCode.lowercase().replace(Regex("[^a-z0-9\\s]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (a.isEmpty() || b.isEmpty()) return false
        val joinedA = a.joinToString(" ")
        val joinedB = b.joinToString(" ")
        if (joinedA.contains(joinedB)) return true
        val hits = b.count { tok -> a.any { it == tok || (tok.length >= 4 && (it.contains(tok) || tok.contains(it))) } }
        return hits == b.size || (b.size >= 3 && hits >= b.size - 1)
    }

    fun isLockActive(context: Context): Boolean {
        ensureLoaded(context)
        return cachedEnabled && isEnrolled(context)
    }

    fun isLockEnabledPref(context: Context): Boolean {
        ensureLoaded(context)
        return cachedEnabled
    }

    fun setLockEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        synchronized(lock) {
            cachedEnabled = enabled
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply()
        }
    }

    fun getSensitivity(context: Context): Sensitivity {
        ensureLoaded(context)
        return cachedSensitivity
    }

    fun setSensitivity(context: Context, sensitivity: Sensitivity) {
        ensureLoaded(context)
        synchronized(lock) {
            cachedSensitivity = sensitivity
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_SENSITIVITY, sensitivity.name)
                .apply()
        }
    }

    fun getStatusSummary(context: Context): String {
        ensureLoaded(context)
        val count = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SAMPLE_COUNT, cachedSamples.size)
        val calPct = (cachedSelfFloor * 100f).toInt()
        val codeState = if (cachedSecretCode.isNotBlank()) "Secret Code SET" else "No Secret Code"
        return when {
            cachedCentroid == null -> "OFF (Voice Not Trained)"
            !cachedEnabled -> "PAUSED ($count samples • $codeState)"
            calPct > 0 -> "ACTIVE • ${cachedSensitivity.label} (Base: $calPct% • $codeState)"
            else -> "ACTIVE • ${cachedSensitivity.label} ($count samples • $codeState)"
        }
    }

    fun saveEnrollment(
        context: Context,
        samples: List<EnrollmentSample>,
        secretCode: String
    ): Boolean {
        val cleanSecret = secretCode.trim()
        if (samples.isEmpty() || cleanSecret.isBlank()) return false

        val voskValid = samples.filter { it.spkVector.isNotEmpty() }
        val voskDim = voskValid.firstOrNull()?.spkVector?.size ?: 0
        val normalizedVoskSamples = if (voskDim > 0) {
            voskValid.filter { it.spkVector.size == voskDim }.map { l2Normalize(it.spkVector) }
        } else emptyList()

        val voskCentroid = if (normalizedVoskSamples.isNotEmpty()) {
            val sum = FloatArray(voskDim)
            for (vec in normalizedVoskSamples) {
                for (i in 0 until voskDim) sum[i] += vec[i]
            }
            l2Normalize(sum)
        } else null

        val selfFloor = computeLeaveOneOutFloor(normalizedVoskSamples)

        val acousticValid = samples.mapNotNull { it.acousticVector }.filter { it.isNotEmpty() }
        val acDim = acousticValid.firstOrNull()?.size ?: 0
        val normalizedAcSamples = if (acDim > 0) {
            acousticValid.filter { it.size == acDim }.map { l2Normalize(it) }
        } else emptyList()

        val acCentroid = if (normalizedAcSamples.isNotEmpty()) {
            val sum = FloatArray(acDim)
            for (vec in normalizedAcSamples) {
                for (i in 0 until acDim) sum[i] += vec[i]
            }
            l2Normalize(sum)
        } else null

        val acSelfFloor = computeLeaveOneOutFloor(normalizedAcSamples)
        if (voskCentroid == null && acCentroid == null) return false

        val pitches = samples.mapNotNull { it.pitchHz }.filter { it in 60f..380f }.sorted()
        val medianPitch = if (pitches.isNotEmpty()) pitches[pitches.size / 2] else 0f
        val minPitch = if (pitches.isNotEmpty()) (pitches.first() * 0.72f).coerceAtLeast(55f) else 0f
        val maxPitch = if (pitches.isNotEmpty()) (pitches.last() * 1.35f).coerceAtMost(420f) else 0f

        synchronized(lock) {
            cachedLoaded = true
            cachedEnabled = true
            cachedCentroid = voskCentroid
            cachedSamples = normalizedVoskSamples
            cachedAcousticCentroid = acCentroid
            cachedAcousticSamples = normalizedAcSamples
            cachedAcousticSelfFloor = acSelfFloor
            cachedMedianF0 = medianPitch
            cachedMinF0 = minPitch
            cachedMaxF0 = maxPitch
            cachedSelfFloor = selfFloor
            cachedSecretCode = cleanSecret

            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, true)
                .putFloat(KEY_MEDIAN_F0, medianPitch)
                .putFloat(KEY_MIN_F0, minPitch)
                .putFloat(KEY_MAX_F0, maxPitch)
                .putFloat(KEY_SELF_FLOOR, selfFloor)
                .putFloat(KEY_ACOUSTIC_SELF_FLOOR, acSelfFloor)
                .putInt(KEY_SAMPLE_COUNT, maxOf(normalizedAcSamples.size, normalizedVoskSamples.size))
                .putString(KEY_CENTROID, voskCentroid?.let { gson.toJson(it) })
                .putString(KEY_SAMPLES, gson.toJson(normalizedVoskSamples))
                .putString(KEY_ACOUSTIC_CENTROID, acCentroid?.let { gson.toJson(it) })
                .putString(KEY_ACOUSTIC_SAMPLES, gson.toJson(normalizedAcSamples))
                .putString(KEY_SECRET_CODE, cleanSecret)
                .commit()
        }
        return true
    }

    private fun computeLeaveOneOutFloor(normalizedSamples: List<FloatArray>): Float {
        if (normalizedSamples.size < 2) return 0f
        val dim = normalizedSamples.first().size
        var minSelfSim = 1.0f
        for (idx in normalizedSamples.indices) {
            val otherSum = FloatArray(dim)
            for (j in normalizedSamples.indices) {
                if (j == idx) continue
                for (k in 0 until dim) otherSum[k] += normalizedSamples[j][k]
            }
            val otherCentroid = l2Normalize(otherSum)
            val sim = cosineSimilarity(normalizedSamples[idx], otherCentroid)
            if (sim < minSelfSim) minSelfSim = sim
        }
        return minSelfSim.coerceIn(0f, 0.94f)
    }

    /**
     * Auto-populates the language-independent 48-dim Acoustic Voiceprint whenever a user
     * upgrades from an older enrollment or completes Secret Code / trusted verification.
     */
    fun learnOwnerAcousticSample(context: Context, pcmBytes: ByteArray) {
        if (pcmBytes.size < 8_000) return
        ensureLoaded(context)
        if (!cachedEnabled || !isEnrolled(context)) return

        val feat = extractAcousticFeatures(pcmBytes)
        if (feat.voicedFrames < 10 || feat.vector.isEmpty()) return

        synchronized(lock) {
            if (cachedAcousticSamples.size >= 6) return
            val updatedSamples = (cachedAcousticSamples + l2Normalize(feat.vector)).takeLast(6)
            val dim = updatedSamples.first().size
            val sum = FloatArray(dim)
            for (v in updatedSamples) {
                if (v.size == dim) {
                    for (i in 0 until dim) sum[i] += v[i]
                }
            }
            val newCentroid = l2Normalize(sum)
            val newFloor = computeLeaveOneOutFloor(updatedSamples)
            cachedAcousticSamples = updatedSamples
            cachedAcousticCentroid = newCentroid
            cachedAcousticSelfFloor = newFloor

            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ACOUSTIC_CENTROID, gson.toJson(newCentroid))
                .putString(KEY_ACOUSTIC_SAMPLES, gson.toJson(updatedSamples))
                .putFloat(KEY_ACOUSTIC_SELF_FLOOR, newFloor)
                .apply()
        }
    }

    fun clearEnrollment(context: Context) {
        synchronized(lock) {
            cachedLoaded = true
            cachedCentroid = null
            cachedSamples = emptyList()
            cachedAcousticCentroid = null
            cachedAcousticSamples = emptyList()
            cachedAcousticSelfFloor = 0f
            cachedMedianF0 = 0f
            cachedMinF0 = 0f
            cachedMaxF0 = 0f
            cachedSelfFloor = 0f
            cachedSecretCode = ""
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CENTROID)
                .remove(KEY_SAMPLES)
                .remove(KEY_ACOUSTIC_CENTROID)
                .remove(KEY_ACOUSTIC_SAMPLES)
                .remove(KEY_ACOUSTIC_SELF_FLOOR)
                .remove(KEY_MEDIAN_F0)
                .remove(KEY_MIN_F0)
                .remove(KEY_MAX_F0)
                .remove(KEY_SELF_FLOOR)
                .remove(KEY_SAMPLE_COUNT)
                .remove(KEY_SECRET_CODE)
                .commit()
        }
    }

    fun verifySpeaker(
        context: Context,
        spkVector: FloatArray?,
        spkFrames: Int,
        pcmBytes: ByteArray,
        isMediaPlaying: Boolean
    ): VerificationResult {
        ensureLoaded(context)
        val voskCentroid = cachedCentroid
        val acCentroid = cachedAcousticCentroid
        if (!cachedEnabled || ((voskCentroid == null || voskCentroid.isEmpty()) && (acCentroid == null || acCentroid.isEmpty()))) {
            return VerificationResult(
                tier = MatchTier.TRUSTED,
                allowed = true,
                similarity = 1.0f,
                acceptThreshold = 0f,
                spkFrames = spkFrames,
                detectedPitchHz = null,
                reason = if (voskCentroid == null && acCentroid == null) "voice_lock_untrained" else "voice_lock_disabled"
            )
        }

        val acFeatures = extractAcousticFeatures(pcmBytes)
        val detectedPitch = acFeatures.pitchHz
        val pitchInOwnerBand = when {
            detectedPitch == null || cachedMedianF0 <= 0f -> true
            cachedMinF0 > 0f && cachedMaxF0 > cachedMinF0 -> detectedPitch in cachedMinF0..cachedMaxF0
            else -> (detectedPitch / cachedMedianF0) in 0.56f..1.72f
        }

        // 1. Text-independent 48-dim Mel-spectral biometric score (works on Hindi, Hinglish, and 0.3s clips!)
        var acousticScore: Float? = null
        if (acCentroid != null && acCentroid.isNotEmpty() && acFeatures.voicedFrames >= 6 && acFeatures.vector.size == acCentroid.size) {
            val acNorm = l2Normalize(acFeatures.vector)
            val acCentSim = cosineSimilarity(acNorm, acCentroid)
            val bestAcSample = cachedAcousticSamples.maxOfOrNull { cosineSimilarity(acNorm, it) } ?: acCentSim
            acousticScore = (0.50f * acCentSim) + (0.50f * bestAcSample)
        }

        // 2. Vosk 128-dim English x-vector score (auxiliary boost when available)
        var voskScore: Float? = null
        if (voskCentroid != null && voskCentroid.isNotEmpty() && spkVector != null && spkFrames >= 50 && spkVector.size == voskCentroid.size) {
            val vNorm = l2Normalize(spkVector)
            val vCentSim = cosineSimilarity(vNorm, voskCentroid)
            val topVSamples = cachedSamples.map { cosineSimilarity(vNorm, it) }.sortedDescending()
            val bestVSamples = when {
                topVSamples.size >= 2 -> (topVSamples[0] + topVSamples[1]) * 0.5f
                topVSamples.size == 1 -> topVSamples[0]
                else -> vCentSim
            }
            voskScore = (0.65f * vCentSim) + (0.35f * bestVSamples)
        }

        // If NEITHER acoustic nor Vosk produced a usable vector:
        if (acousticScore == null && voskScore == null) {
            // Backward compatibility: legacy enrollment without acousticCentroid yet when Vosk returned frames=0 on Hindi
            if (acCentroid == null && pcmBytes.size >= 8_000 && pitchInOwnerBand && cachedSecretCode.isNotBlank()) {
                return VerificationResult(
                    tier = MatchTier.DOUBT,
                    allowed = false,
                    similarity = 0f,
                    acceptThreshold = cachedSensitivity.baseThreshold,
                    spkFrames = spkFrames,
                    detectedPitchHz = detectedPitch,
                    reason = "legacy_hindi_doubt(vFrames=${acFeatures.voicedFrames})"
                )
            }
            return VerificationResult(
                tier = MatchTier.REJECTED,
                allowed = false,
                similarity = 0f,
                acceptThreshold = cachedSensitivity.baseThreshold,
                spkFrames = spkFrames,
                detectedPitchHz = detectedPitch,
                reason = "insufficient_voice(vFrames=${acFeatures.voicedFrames},spk=$spkFrames)"
            )
        }

        // Compute dynamic threshold suited for active feature source
        val acBase = when (cachedSensitivity) {
            Sensitivity.RELAXED -> 0.58f
            Sensitivity.BALANCED -> 0.65f
            Sensitivity.STRICT -> 0.74f
        }
        val calibratedAcFloor = if (cachedAcousticSelfFloor > 0f) {
            (cachedAcousticSelfFloor - (cachedSensitivity.selfMargin * 0.90f)).coerceIn(0.56f, 0.84f)
        } else 0f
        val acAcceptThr = maxOf(acBase, calibratedAcFloor)

        val voskCalibratedFloor = if (cachedSelfFloor > 0f) {
            (cachedSelfFloor - cachedSensitivity.selfMargin).coerceAtMost(0.70f)
        } else 0f
        val voskAcceptThr = maxOf(cachedSensitivity.baseThreshold, voskCalibratedFloor)

        val (effectiveScore, acceptThreshold, scoreTag) = when {
            acousticScore != null && voskScore != null -> {
                // Favor clear match without penalizing Hindi words where English Vosk scored low
                val blended = maxOf(acousticScore, (0.60f * acousticScore) + (0.40f * voskScore))
                val thr = if (acousticScore >= acAcceptThr) acAcceptThr else maxOf(acAcceptThr - 0.04f, 0.56f)
                Triple(blended, thr, "hybrid(ac=${"%.2f".format(acousticScore)},vk=${"%.2f".format(voskScore)})")
            }
            acousticScore != null -> {
                Triple(acousticScore, acAcceptThr, "acoustic(vFrames=${acFeatures.voicedFrames})")
            }
            else -> {
                Triple(voskScore!!, voskAcceptThr, "vosk(frames=$spkFrames)")
            }
        }
        val doubtThreshold = (acceptThreshold - 0.14f).coerceAtLeast(0.42f)

        if (!pitchInOwnerBand) {
            val extremeMismatch = cachedMedianF0 > 0f && detectedPitch != null &&
                ((detectedPitch / cachedMedianF0) < 0.45f || (detectedPitch / cachedMedianF0) > 2.05f)
            return if (!extremeMismatch && effectiveScore >= acceptThreshold && cachedSecretCode.isNotBlank()) {
                VerificationResult(
                    tier = MatchTier.DOUBT,
                    allowed = false,
                    similarity = effectiveScore,
                    acceptThreshold = acceptThreshold,
                    spkFrames = maxOf(spkFrames, acFeatures.voicedFrames),
                    detectedPitchHz = detectedPitch,
                    reason = "pitch_doubt(${detectedPitch?.toInt()}Hz, $scoreTag)"
                )
            } else {
                VerificationResult(
                    tier = MatchTier.REJECTED,
                    allowed = false,
                    similarity = effectiveScore,
                    acceptThreshold = acceptThreshold,
                    spkFrames = maxOf(spkFrames, acFeatures.voicedFrames),
                    detectedPitchHz = detectedPitch,
                    reason = "pitch_mismatch(${detectedPitch?.toInt()}Hz vs ${cachedMedianF0.toInt()}Hz, $scoreTag)"
                )
            }
        }

        return when {
            effectiveScore >= acceptThreshold -> VerificationResult(
                tier = MatchTier.TRUSTED,
                allowed = true,
                similarity = effectiveScore,
                acceptThreshold = acceptThreshold,
                spkFrames = maxOf(spkFrames, acFeatures.voicedFrames),
                detectedPitchHz = detectedPitch,
                reason = "trusted($scoreTag sim=${"%.2f".format(effectiveScore)}>=${"%.2f".format(acceptThreshold)})"
            )
            effectiveScore >= doubtThreshold && cachedSecretCode.isNotBlank() -> VerificationResult(
                tier = MatchTier.DOUBT,
                allowed = false,
                similarity = effectiveScore,
                acceptThreshold = acceptThreshold,
                spkFrames = maxOf(spkFrames, acFeatures.voicedFrames),
                detectedPitchHz = detectedPitch,
                reason = "doubt($scoreTag sim=${"%.2f".format(effectiveScore)} in ${"%.2f".format(doubtThreshold)}..${"%.2f".format(acceptThreshold)})"
            )
            else -> VerificationResult(
                tier = MatchTier.REJECTED,
                allowed = false,
                similarity = effectiveScore,
                acceptThreshold = acceptThreshold,
                spkFrames = maxOf(spkFrames, acFeatures.voicedFrames),
                detectedPitchHz = detectedPitch,
                reason = "rejected($scoreTag sim=${"%.2f".format(effectiveScore)}<thr=${"%.2f".format(doubtThreshold)})"
            )
        }
    }

    /**
     * Language-independent 48-dim CMVN Mel-Spectral speaker descriptor + median pitch (F0).
     * Runs in ~2ms directly on 16kHz PCM16 audio without needing English ASR recognition.
     */
    fun extractAcousticFeatures(pcmBytes: ByteArray): AcousticFeatures {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount < FFT_SIZE) return AcousticFeatures(FloatArray(0), 0, null)

        val samples = FloatArray(sampleCount)
        val shortBuf = ShortArray(sampleCount)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shortBuf)
        for (i in 0 until sampleCount) samples[i] = shortBuf[i].toFloat()

        val pitchHz = computePitchFromShorts(shortBuf)
        val hop = 256
        val hann = FloatArray(FFT_SIZE) { n ->
            (0.5 - 0.5 * cos((2.0 * PI * n) / (FFT_SIZE - 1))).toFloat()
        }
        val melFilters = buildMelFilterbank()

        val frameVectors = mutableListOf<FloatArray>()
        val re = FloatArray(FFT_SIZE)
        val im = FloatArray(FFT_SIZE)
        var offset = 0
        while (offset + FFT_SIZE <= sampleCount) {
            var sumSq = 0.0
            for (i in 0 until FFT_SIZE) {
                val s = samples[offset + i].toDouble()
                sumSq += s * s
            }
            val rms = sqrt(sumSq / FFT_SIZE)
            if (rms >= 210.0) {
                for (i in 0 until FFT_SIZE) {
                    val prev = if (offset + i > 0) samples[offset + i - 1] else samples[offset]
                    val preEmph = samples[offset + i] - 0.97f * prev
                    re[i] = preEmph * hann[i]
                    im[i] = 0f
                }
                fftRadix2(re, im)

                val logMel = FloatArray(MEL_BANDS)
                var bandMean = 0.0
                for (m in 0 until MEL_BANDS) {
                    val f = melFilters[m]
                    var energy = 0.0
                    for (k in 1..FFT_SIZE / 2) {
                        val magSq = re[k].toDouble() * re[k] + im[k].toDouble() * im[k]
                        energy += f[k] * magSq
                    }
                    val lv = ln(energy + 1e-6).toFloat()
                    logMel[m] = lv
                    bandMean += lv
                }
                val meanF = (bandMean / MEL_BANDS).toFloat()
                for (m in 0 until MEL_BANDS) logMel[m] -= meanF // CMVN cancels distance/loudness!
                frameVectors.add(logMel)
            }
            offset += hop
        }

        if (frameVectors.isEmpty()) return AcousticFeatures(FloatArray(0), 0, pitchHz)

        val nFrames = frameVectors.size.toFloat()
        val meanVec = FloatArray(MEL_BANDS)
        for (fv in frameVectors) {
            for (m in 0 until MEL_BANDS) meanVec[m] += fv[m]
        }
        for (m in 0 until MEL_BANDS) meanVec[m] /= nFrames

        val stdVec = FloatArray(MEL_BANDS)
        for (fv in frameVectors) {
            for (m in 0 until MEL_BANDS) {
                val d = fv[m] - meanVec[m]
                stdVec[m] += d * d
            }
        }
        for (m in 0 until MEL_BANDS) stdVec[m] = sqrt(stdVec[m] / nFrames)

        val combined = FloatArray(MEL_BANDS * 2)
        for (m in 0 until MEL_BANDS) {
            combined[m] = meanVec[m]
            combined[MEL_BANDS + m] = stdVec[m] * 0.55f
        }
        return AcousticFeatures(l2Normalize(combined), frameVectors.size, pitchHz)
    }

    private fun buildMelFilterbank(): Array<FloatArray> {
        fun hzToMel(hz: Double) = 2595.0 * ln(1.0 + hz / 700.0) / ln(10.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)
        val lowMel = hzToMel(80.0)
        val highMel = hzToMel(7600.0)
        val nBins = FFT_SIZE / 2 + 1
        val bins = IntArray(MEL_BANDS + 2) { idx ->
            val mel = lowMel + idx * (highMel - lowMel) / (MEL_BANDS + 1)
            val hz = melToHz(mel)
            (((FFT_SIZE + 1) * hz) / 16000.0).toInt().coerceIn(0, nBins - 1)
        }
        return Array(MEL_BANDS) { m ->
            val filt = FloatArray(nBins)
            val left = bins[m]
            val center = bins[m + 1]
            val right = bins[m + 2]
            for (k in left until center) {
                filt[k] = if (center > left) (k - left).toFloat() / (center - left) else 1f
            }
            for (k in center..right) {
                filt[k] = if (right > center) (right - k).toFloat() / (right - center) else 1f
            }
            filt
        }
    }

    private fun fftRadix2(re: FloatArray, im: FloatArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while ((j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wLenRe = cos(ang).toFloat()
            val wLenIm = sin(ang).toFloat()
            var i = 0
            while (i < n) {
                var wRe = 1f
                var wIm = 0f
                val half = len shr 1
                for (k in 0 until half) {
                    val uRe = re[i + k]
                    val uIm = im[i + k]
                    val vRe = re[i + k + half] * wRe - im[i + k + half] * wIm
                    val vIm = re[i + k + half] * wIm + im[i + k + half] * wRe
                    re[i + k] = uRe + vRe
                    im[i + k] = uIm + vIm
                    re[i + k + half] = uRe - vRe
                    im[i + k + half] = uIm - vIm
                    val nextWRe = wRe * wLenRe - wIm * wLenIm
                    wIm = wRe * wLenIm + wIm * wLenRe
                    wRe = nextWRe
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Extracts median fundamental vocal pitch (F0 Hz) from 16kHz 16-bit mono PCM bytes.
     */
    fun extractPcmPitchHz(pcmBytes: ByteArray): Float? {
        val sampleCount = pcmBytes.size / 2
        if (sampleCount < 640) return null
        val samples = ShortArray(sampleCount)
        ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return computePitchFromShorts(samples)
    }

    private fun computePitchFromShorts(samples: ShortArray): Float? {
        val sampleCount = samples.size
        if (sampleCount < 640) return null

        val frameLen = 640 // 40ms @ 16kHz
        val step = 320     // 20ms hop
        val minLag = 45    // ~355Hz upper vocal limit
        val maxLag = 228   // ~70Hz lower vocal limit
        val frameF0s = mutableListOf<Float>()

        var offset = 0
        while (offset + frameLen <= sampleCount) {
            var energy = 0.0
            for (i in 0 until frameLen) {
                val v = samples[offset + i].toDouble()
                energy += v * v
            }
            val rms = sqrt(energy / frameLen)
            if (rms >= 420.0) {
                var bestCorr = 0.0
                var bestLag = -1
                for (tau in minLag..maxLag) {
                    var cross = 0.0
                    var normA = 0.0
                    var normB = 0.0
                    val limit = frameLen - tau
                    for (i in 0 until limit) {
                        val a = samples[offset + i].toDouble()
                        val b = samples[offset + i + tau].toDouble()
                        cross += a * b
                        normA += a * a
                        normB += b * b
                    }
                    val denom = sqrt(normA * normB)
                    if (denom > 1.0) {
                        val corr = cross / denom
                        if (corr > bestCorr) {
                            bestCorr = corr
                            bestLag = tau
                        }
                    }
                }
                if (bestCorr >= 0.50 && bestLag > 0) {
                    val f0 = 16000.0f / bestLag.toFloat()
                    if (f0 in 65f..360f) {
                        frameF0s.add(f0)
                    }
                }
            }
            offset += step
        }

        if (frameF0s.size < 3) return null
        frameF0s.sort()
        return frameF0s[frameF0s.size / 2]
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val va = a[i].toDouble()
            val vb = b[i].toDouble()
            dot += va * vb
            normA += va * va
            normB += vb * vb
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-8) (dot / denom).toFloat() else 0f
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSq = 0.0
        for (v in vec) sumSq += v * v
        val norm = sqrt(sumSq)
        if (norm < 1e-8) return vec.copyOf()
        return FloatArray(vec.size) { i -> (vec[i] / norm).toFloat() }
    }

    fun computePcmRms(pcmBytes: ByteArray, length: Int = pcmBytes.size): Double {
        val count = length / 2
        if (count <= 0) return 0.0
        var sumSq = 0.0
        var idx = 0
        while (idx + 1 < length) {
            val lo = pcmBytes[idx].toInt() and 0xFF
            val hi = pcmBytes[idx + 1].toInt()
            val sample = ((hi shl 8) or lo).toShort().toDouble()
            sumSq += sample * sample
            idx += 2
        }
        return sqrt(sumSq / count)
    }

    fun trimSilenceEdgesPcm(pcmBytes: ByteArray): ByteArray {
        if (pcmBytes.size <= 3200) return pcmBytes
        val frameBytes = 640 // 20ms @ 16kHz mono PCM16
        var startPos = 0
        while (startPos + frameBytes < pcmBytes.size - 3200) {
            if (computeSliceRms(pcmBytes, startPos, startPos + frameBytes) >= 240.0) break
            startPos += frameBytes / 2
        }
        var endPos = pcmBytes.size
        while (endPos > startPos + 3200) {
            val s = (endPos - frameBytes).coerceAtLeast(startPos)
            if (computeSliceRms(pcmBytes, s, endPos) >= 240.0) break
            endPos -= frameBytes / 2
        }
        val paddedStart = ((startPos - 1024).coerceAtLeast(0)) and -2 // 32ms lead cushion
        val paddedEnd = ((endPos + 1024).coerceAtMost(pcmBytes.size)) and -2 // 32ms tail cushion
        if (paddedEnd <= paddedStart) return pcmBytes
        return pcmBytes.copyOfRange(paddedStart, paddedEnd)
    }

    private fun computeSliceRms(buf: ByteArray, start: Int, end: Int): Double {
        val count = ((end - start) / 2).coerceAtLeast(1)
        var sumSq = 0.0
        var i = start
        while (i + 1 < end) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            val s = ((hi shl 8) or lo).toShort().toDouble()
            sumSq += s * s
            i += 2
        }
        return sqrt(sumSq / count)
    }

    fun trimTrailingSilencePcm(pcmBytes: ByteArray): ByteArray {
        return trimSilenceEdgesPcm(pcmBytes)
    }

    fun pcm16ToWav(pcmBytes: ByteArray, sampleRate: Int = 16000): ByteArray {
        val totalAudioLen = pcmBytes.size
        val totalDataLen = totalAudioLen + 36
        val byteRate = sampleRate * 2 // 16-bit mono
        val wav = ByteArray(44 + totalAudioLen)

        wav[0] = 'R'.code.toByte(); wav[1] = 'I'.code.toByte()
        wav[2] = 'F'.code.toByte(); wav[3] = 'F'.code.toByte()
        writeIntLE(wav, 4, totalDataLen)
        wav[8] = 'W'.code.toByte(); wav[9] = 'A'.code.toByte()
        wav[10] = 'V'.code.toByte(); wav[11] = 'E'.code.toByte()
        wav[12] = 'f'.code.toByte(); wav[13] = 'm'.code.toByte()
        wav[14] = 't'.code.toByte(); wav[15] = ' '.code.toByte()
        writeIntLE(wav, 16, 16) // PCM chunk size
        writeShortLE(wav, 20, 1) // AudioFormat = 1 (PCM)
        writeShortLE(wav, 22, 1) // NumChannels = 1
        writeIntLE(wav, 24, sampleRate)
        writeIntLE(wav, 28, byteRate)
        writeShortLE(wav, 32, 2) // BlockAlign
        writeShortLE(wav, 34, 16) // BitsPerSample
        wav[36] = 'd'.code.toByte(); wav[37] = 'a'.code.toByte()
        wav[38] = 't'.code.toByte(); wav[39] = 'a'.code.toByte()
        writeIntLE(wav, 40, totalAudioLen)
        System.arraycopy(pcmBytes, 0, wav, 44, totalAudioLen)
        return wav
    }

    private fun writeIntLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun writeShortLE(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
