package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.gson.JsonParser
import com.jarvis.assistant.debug.JarvisLogStore
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Passive, 100% Zero-AudioFocus continuous speech capture & speaker-verification engine.
 *
 * Key properties:
 * 1. NEVER calls AudioManager.requestAudioFocus() and NEVER launches Android SpeechRecognizer,
 *    so Hotstar, Instagram Reels, YouTube, and music keep playing cleanly without pauses or muting.
 * 2. Requires NO mandatory "Jarvis" wake word — uses adaptive Voice Activity Detection (VAD)
 *    with a 384ms pre-roll ring buffer to capture spoken commands naturally.
 * 3. Runs offline Vosk + SpeakerModel (128-dim x-vectors) ON-DEVICE before triggering Gemini,
 *    ignoring strangers and movie/Reel dialogue when Voice Lock is trained.
 */
class PassiveVoiceCaptureEngine(
    context: Context,
    private val onUtteranceReady: (CapturedUtterance) -> Unit,
    private val onStatusUpdate: (State, String) -> Unit
) {

    enum class State {
        INITIALIZING,
        DOWNLOADING,
        LISTENING,
        PAUSED,
        WAITING_NETWORK,
        WAITING_PERMISSION,
        STOPPED
    }

    data class CapturedUtterance(
        val wavBytes: ByteArray,
        val voskHint: String,
        val verification: VoiceprintManager.VerificationResult,
        val durationMs: Long,
        val isMediaPlaying: Boolean
    )

    companion object {
        const val SAMPLE_RATE = 16000
        private const val CHUNK_BYTES = 1024 // 32ms @ 16kHz PCM16 mono
        private const val PRE_ROLL_FRAMES = 12 // ~384ms pre-roll before VAD onset
        private const val ONSET_FRAMES_REQUIRED = 4 // ~128ms continuous speech to start utterance
        private const val END_SILENCE_MS = 640L
        private const val MIN_UTTERANCE_MS = 420L
        private const val MAX_UTTERANCE_MS = 8500L
        private const val BYTES_PER_MS = 32 // 16000 * 2 / 1000

        private const val ASR_DIR_NAME = "vosk-model-en-in"
        private const val ASR_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip"
        private const val ASR_MARKER = ".ready_en_in_0_4"

        private const val SPK_DIR_NAME = "vosk-model-spk"
        private const val SPK_URL = "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip"
        private const val SPK_MARKER = ".ready_spk_0_4"

        private val sharedHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun areModelsDownloaded(context: Context): Boolean {
            val filesDir = context.applicationContext.filesDir
            val asrDir = File(filesDir, ASR_DIR_NAME)
            val spkDir = File(filesDir, SPK_DIR_NAME)
            return isAsrModelValid(asrDir) && isSpkModelValid(spkDir)
        }

        private fun isAsrModelValid(dir: File): Boolean {
            return dir.isDirectory &&
                File(dir, ASR_MARKER).exists() &&
                File(dir, "conf/model.conf").exists() &&
                File(dir, "am/final.mdl").exists()
        }

        private fun isSpkModelValid(dir: File): Boolean {
            return dir.isDirectory &&
                File(dir, SPK_MARKER).exists() &&
                File(dir, "final.ext.raw").exists()
        }

        fun ensureModelsDownloadedSync(
            context: Context,
            isCancelled: () -> Boolean,
            onProgress: (String) -> Unit,
            onActiveCallChange: (Call?) -> Unit = {}
        ): Pair<File, File>? {
            val appCtx = context.applicationContext
            // Clean up legacy en-us model directory from v1.3.1 if present to save phone storage
            try {
                val legacyDir = File(appCtx.filesDir, "vosk-model")
                if (legacyDir.exists()) legacyDir.deleteRecursively()
            } catch (_: Exception) {
            }

            val asrDir = File(appCtx.filesDir, ASR_DIR_NAME)
            if (!isAsrModelValid(asrDir)) {
                val ok = downloadAndInstallZip(
                    cacheDir = appCtx.cacheDir,
                    filesDir = appCtx.filesDir,
                    url = ASR_URL,
                    zipName = "vosk-en-in.zip",
                    targetDir = asrDir,
                    markerName = ASR_MARKER,
                    requiredFiles = listOf("conf/model.conf", "am/final.mdl"),
                    label = "Voice engine",
                    isCancelled = isCancelled,
                    onProgress = onProgress,
                    onActiveCallChange = onActiveCallChange
                )
                if (!ok) return null
            }

            val spkDir = File(appCtx.filesDir, SPK_DIR_NAME)
            if (!isSpkModelValid(spkDir)) {
                val ok = downloadAndInstallZip(
                    cacheDir = appCtx.cacheDir,
                    filesDir = appCtx.filesDir,
                    url = SPK_URL,
                    zipName = "vosk-spk.zip",
                    targetDir = spkDir,
                    markerName = SPK_MARKER,
                    requiredFiles = listOf("final.ext.raw"),
                    label = "Voice Lock model",
                    isCancelled = isCancelled,
                    onProgress = onProgress,
                    onActiveCallChange = onActiveCallChange
                )
                if (!ok) return null
            }

            return asrDir to spkDir
        }

        private fun downloadAndInstallZip(
            cacheDir: File,
            filesDir: File,
            url: String,
            zipName: String,
            targetDir: File,
            markerName: String,
            requiredFiles: List<String>,
            label: String,
            isCancelled: () -> Boolean,
            onProgress: (String) -> Unit,
            onActiveCallChange: (Call?) -> Unit
        ): Boolean {
            val zipFile = File(cacheDir, zipName)
            val stagingDir = File(filesDir, "${targetDir.name}-staging")
            return try {
                if (zipFile.exists()) zipFile.delete()
                if (stagingDir.exists()) stagingDir.deleteRecursively()
                stagingDir.mkdirs()

                onProgress("$label download ho raha hai (0%)...")
                JarvisLogStore.log("VOICE_ENGINE", "Downloading $label: $url")

                val request = Request.Builder().url(url).build()
                val call = sharedHttpClient.newCall(request)
                onActiveCallChange(call)

                call.execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty body")
                    val total = body.contentLength()
                    var downloaded = 0L
                    var lastPct = -1

                    body.byteStream().use { input ->
                        FileOutputStream(zipFile).use { output ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                if (isCancelled()) throw IOException("Cancelled")
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                downloaded += read
                                if (total > 0) {
                                    val pct = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                                    if (pct != lastPct && (pct % 15 == 0 || pct == 100)) {
                                        lastPct = pct
                                        onProgress("$label download ho raha hai ($pct%)...")
                                    }
                                }
                            }
                        }
                    }
                }
                onActiveCallChange(null)

                onProgress("$label extract ho raha hai...")
                unzipSafely(zipFile, stagingDir)

                val resolved = resolveExtractedRoot(stagingDir, requiredFiles)
                for (req in requiredFiles) {
                    if (!File(resolved, req).exists()) {
                        throw IOException("Missing extracted model file: $req")
                    }
                }

                if (targetDir.exists()) targetDir.deleteRecursively()
                if (!resolved.renameTo(targetDir)) {
                    resolved.copyRecursively(targetDir, overwrite = true)
                }
                File(targetDir, markerName).writeText("ready\n")
                JarvisLogStore.log("VOICE_ENGINE", "$label ready at ${targetDir.absolutePath}")
                true
            } catch (e: Exception) {
                JarvisLogStore.log("VOICE_ENGINE_WARN", "$label download error: ${e.message}")
                false
            } finally {
                onActiveCallChange(null)
                try {
                    if (zipFile.exists()) zipFile.delete()
                } catch (_: Exception) {
                }
                try {
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                } catch (_: Exception) {
                }
            }
        }

        private fun unzipSafely(zipFile: File, destDir: File) {
            val canonicalDest = destDir.canonicalPath
            ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val entry = zis.nextEntry ?: break
                    val outFile = File(destDir, entry.name)
                    val outCanonical = outFile.canonicalPath
                    if (!outCanonical.startsWith(canonicalDest + File.separator) && outCanonical != canonicalDest) {
                        throw SecurityException("Blocked unsafe zip entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { fos ->
                            while (true) {
                                val len = zis.read(buf)
                                if (len <= 0) break
                                fos.write(buf, 0, len)
                            }
                        }
                    }
                    zis.closeEntry()
                }
            }
        }

        private fun resolveExtractedRoot(stagingDir: File, requiredFiles: List<String>): File {
            if (requiredFiles.all { File(stagingDir, it).exists() }) return stagingDir
            val subDirs = stagingDir.listFiles()?.filter { it.isDirectory }.orEmpty()
            if (subDirs.size == 1) return subDirs[0]
            return stagingDir
        }

        fun createPassiveAudioRecord(bufferSize: Int): AudioRecord? {
            val sources = intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
            for (src in sources) {
                try {
                    val ar = AudioRecord(
                        src,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize
                    )
                    if (ar.state == AudioRecord.STATE_INITIALIZED) return ar
                    ar.release()
                } catch (_: Exception) {
                }
            }
            return null
        }

        fun parseVoskJson(jsonStr: String?): Triple<String, FloatArray?, Int> {
            if (jsonStr.isNullOrBlank()) return Triple("", null, 0)
            return try {
                val obj = JsonParser.parseString(jsonStr).asJsonObject
                val text = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
                val spkFrames = obj.get("spk_frames")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
                val spkArr = obj.get("spk")?.takeIf { it.isJsonArray }?.asJsonArray
                val spkVec = if (spkArr != null && spkArr.size() > 0) {
                    FloatArray(spkArr.size()) { i -> spkArr.get(i).asFloat }
                } else null
                Triple(text, spkVec, spkFrames)
            } catch (_: Exception) {
                Triple("", null, 0)
            }
        }

        data class EnrollmentRecording(
            val sample: VoiceprintManager.EnrollmentSample,
            val wavBytes: ByteArray,
            val voskText: String
        )

        /**
         * Records one natural enrollment phrase (~1s..5.5s) from the device mic and extracts
         * its 128-dim Vosk speaker x-vector, pitch (F0), WAV audio, and phonetic hint.
         */
        fun recordEnrollmentPhrase(
            context: Context,
            isCancelled: () -> Boolean,
            onProgress: (status: String, levelPct: Int) -> Unit,
            minSpkFrames: Int = 75,
            minSpokenMs: Long = 1400L
        ): Result<EnrollmentRecording> {
            val appCtx = context.applicationContext
            val dirs = ensureModelsDownloadedSync(
                context = appCtx,
                isCancelled = isCancelled,
                onProgress = { msg -> onProgress(msg, 0) }
            ) ?: return Result.failure(IOException("Offline voice models download nahi ho sake (internet check karein)."))

            val (asrDir, spkDir) = dirs
            var model: Model? = null
            var spkModel: SpeakerModel? = null
            var recognizer: Recognizer? = null
            var audioRecord: AudioRecord? = null
            var aec: AcousticEchoCanceler? = null
            var ns: NoiseSuppressor? = null

            return try {
                onProgress("Engine ready ho raha hai...", 0)
                model = Model(asrDir.absolutePath)
                spkModel = SpeakerModel(spkDir.absolutePath)
                recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).apply {
                    setSpeakerModel(spkModel)
                }

                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val audioBufSize = maxOf(minBuf * 2, 8192)
                var retryAttempt = 0
                while (audioRecord == null && retryAttempt < 4 && !isCancelled()) {
                    audioRecord = createPassiveAudioRecord(audioBufSize)
                    if (audioRecord == null) {
                        retryAttempt++
                        try { Thread.sleep(160L) } catch (_: InterruptedException) {}
                    }
                }
                val activeRecord = audioRecord ?: return Result.failure(
                    IOException("Microphone busy hai, thodi der baad try karein.")
                )

                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(activeRecord.audioSessionId)?.apply { enabled = true }
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(activeRecord.audioSessionId)?.apply { enabled = true }
                    }
                } catch (_: Exception) {
                }

                val chunk = ByteArray(CHUNK_BYTES)
                val preRoll = ArrayDeque<ByteArray>()
                val pcmOut = ByteArrayOutputStream()
                var startedSpeech = false
                var speechStartMs = 0L
                var lastVoiceMs = 0L
                val loopStartMs = System.currentTimeMillis()
                var noiseFloor = 380.0
                var onsetCount = 0

                activeRecord.startRecording()
                onProgress("Ab bolein (2-4 second)...", 0)

                while (!isCancelled()) {
                    val now = System.currentTimeMillis()
                    if (!startedSpeech && (now - loopStartMs) > 9000L) {
                        return Result.failure(IOException("Koi aawaz nahi sunai di — kripya mic ke paas bolein."))
                    }
                    if (startedSpeech && (now - speechStartMs) > 6500L) break

                    val read = activeRecord.read(chunk, 0, chunk.size)
                    if (read <= 0) break

                    val rms = VoiceprintManager.computePcmRms(chunk, read)
                    val levelPct = ((rms / 3500.0) * 100.0).toInt().coerceIn(0, 100)

                    if (!startedSpeech) {
                        noiseFloor = noiseFloor * 0.94 + rms * 0.06
                        val onsetThr = maxOf(520.0, noiseFloor * 2.1)
                        preRoll.addLast(chunk.copyOf(read))
                        while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

                        if (rms >= onsetThr) {
                            onsetCount++
                            if (onsetCount >= ONSET_FRAMES_REQUIRED) {
                                startedSpeech = true
                                speechStartMs = now
                                lastVoiceMs = now
                                for (pf in preRoll) {
                                    pcmOut.write(pf)
                                    recognizer.acceptWaveForm(pf, pf.size)
                                }
                                preRoll.clear()
                                onProgress("Sun raha hoon...", levelPct)
                            }
                        } else {
                            onsetCount = 0
                            onProgress("Ab bolein...", levelPct)
                        }
                    } else {
                        pcmOut.write(chunk, 0, read)
                        recognizer.acceptWaveForm(chunk, read)
                        val offsetThr = maxOf(340.0, noiseFloor * 1.45)
                        if (rms >= offsetThr) {
                            lastVoiceMs = now
                        }
                        onProgress("Recording voiceprint...", levelPct)
                        val spokenSoFar = now - speechStartMs
                        if (spokenSoFar >= minSpokenMs && (now - lastVoiceMs) >= 850L) {
                            break
                        }
                    }
                }

                if (isCancelled()) return Result.failure(IOException("Cancelled"))

                val (voskText, spkVec, spkFrames) = parseVoskJson(recognizer.finalResult)
                if (spkVec == null || spkVec.isEmpty() || spkFrames < minSpkFrames) {
                    return Result.failure(
                        IOException("Sample bahut chhota ya unclear tha (frames=$spkFrames). Thoda clear 2-3 sec bolein.")
                    )
                }

                val cleanPcm = VoiceprintManager.trimTrailingSilencePcm(pcmOut.toByteArray())
                val pitchHz = VoiceprintManager.extractPcmPitchHz(cleanPcm)
                val wav = VoiceprintManager.pcm16ToWav(cleanPcm, SAMPLE_RATE)
                Result.success(
                    EnrollmentRecording(
                        sample = VoiceprintManager.EnrollmentSample(spkVec, spkFrames, pitchHz),
                        wavBytes = wav,
                        voskText = voskText
                    )
                )
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                try {
                    if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord?.stop()
                } catch (_: Exception) {
                }
                try { aec?.release() } catch (_: Exception) {}
                try { ns?.release() } catch (_: Exception) {}
                try { audioRecord?.release() } catch (_: Exception) {}
                try { recognizer?.close() } catch (_: Exception) {}
                try { spkModel?.close() } catch (_: Exception) {}
                try { model?.close() } catch (_: Exception) {}
            }
        }
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val workerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "JarvisPassiveAudioWorker").apply { isDaemon = true }
    }

    @Volatile
    private var desiredListening = false

    @Volatile
    private var captureActive = false

    @Volatile
    private var pausedForExecution = false

    @Volatile
    var forceForwardForChallenge = false

    @Volatile
    private var released = false

    @Volatile
    private var workerScheduled = false

    @Volatile
    private var activeDownloadCall: Call? = null

    private var voskModel: Model? = null
    private var voskSpkModel: SpeakerModel? = null
    private var voskRecognizer: Recognizer? = null
    private var currentState: State = State.PAUSED
    private var lastAudioInitWarnAtMs = 0L
    private var lastRejectForwardMs = 0L

    fun startListening() {
        if (released) return
        pausedForExecution = false
        desiredListening = true
        scheduleWorkerPump()
    }

    fun pauseForProcessing(reason: String = "paused") {
        pausedForExecution = true
        desiredListening = false
        captureActive = false
        if (!released && currentState != State.STOPPED) {
            notifyState(State.PAUSED, reason)
        }
    }

    fun resumeListening() {
        if (released) return
        pausedForExecution = false
        desiredListening = true
        scheduleWorkerPump()
    }

    fun release() {
        released = true
        desiredListening = false
        captureActive = false
        try {
            activeDownloadCall?.cancel()
        } catch (_: Exception) {
        }
        activeDownloadCall = null
        notifyState(State.STOPPED, "Stopped")
        try {
            workerExecutor.execute { cleanupNativeResources() }
            workerExecutor.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun scheduleWorkerPump() {
        synchronized(this) {
            if (released || workerScheduled) return
            workerScheduled = true
        }
        try {
            workerExecutor.execute {
                try {
                    runWorkerPump()
                } finally {
                    synchronized(this@PassiveVoiceCaptureEngine) {
                        workerScheduled = false
                        if (desiredListening && !pausedForExecution && !released) {
                            mainHandler.postDelayed({
                                if (desiredListening && !pausedForExecution && !released) {
                                    scheduleWorkerPump()
                                }
                            }, 250L)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            synchronized(this) { workerScheduled = false }
        }
    }

    private fun runWorkerPump() {
        while (desiredListening && !pausedForExecution && !released) {
            val hasPerm = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasPerm) {
                notifyState(State.WAITING_PERMISSION, "Mic permission chahiye Sir")
                sleepQuietly(3000L)
                continue
            }

            if (!ensureNativeModelsLoaded()) {
                if (!desiredListening || released) break
                notifyState(State.WAITING_NETWORK, "Offline voice engine ke liye internet wait...")
                sleepQuietly(8000L)
                continue
            }

            if (!desiredListening || pausedForExecution || released) break

            val audioMode = try {
                audioManager.mode
            } catch (_: Exception) {
                AudioManager.MODE_NORMAL
            }
            if (audioMode == AudioManager.MODE_IN_CALL || audioMode == AudioManager.MODE_IN_COMMUNICATION) {
                notifyState(State.PAUSED, "Call chal rahi hai — mic paused")
                sleepQuietly(1200L)
                continue
            }

            if (SpeakerHelper.isAnySpeaking()) {
                sleepQuietly(220L)
                continue
            }

            runContinuousVadLoop()

            if (desiredListening && !pausedForExecution && !released) {
                sleepQuietly(1000L)
            }
        }
    }

    private fun ensureNativeModelsLoaded(): Boolean {
        if (voskModel != null && voskRecognizer != null) return true

        notifyState(State.INITIALIZING, "Offline Voice Lock model check ho raha hai...")
        val dirs = ensureModelsDownloadedSync(
            context = appContext,
            isCancelled = { !desiredListening || released },
            onProgress = { msg -> notifyState(State.DOWNLOADING, msg) },
            onActiveCallChange = { call -> activeDownloadCall = call }
        ) ?: return false

        val (asrDir, spkDir) = dirs
        return try {
            notifyState(State.INITIALIZING, "Voice + Speaker engine load ho raha hai...")
            val model = Model(asrDir.absolutePath)
            val spkModel = SpeakerModel(spkDir.absolutePath)
            val recognizer = Recognizer(model, SAMPLE_RATE.toFloat()).apply {
                setSpeakerModel(spkModel)
            }
            voskModel = model
            voskSpkModel = spkModel
            voskRecognizer = recognizer
            JarvisLogStore.log("VOICE_ENGINE", "Continuous Zero-AudioFocus Voice+Speaker engine loaded.")
            true
        } catch (e: Exception) {
            JarvisLogStore.log("VOICE_ENGINE_ERR", "Native model load failed: ${e.message}")
            cleanupNativeResources()
            false
        }
    }

    private fun runContinuousVadLoop() {
        val recognizer = voskRecognizer ?: return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return

        val audioBufSize = maxOf(minBuf * 2, 8192)
        val audioRecord = createPassiveAudioRecord(audioBufSize)
        if (audioRecord == null) {
            val now = System.currentTimeMillis()
            if (now - lastAudioInitWarnAtMs >= 30_000L) {
                lastAudioInitWarnAtMs = now
                JarvisLogStore.log("VOICE_ENGINE_WARN", "AudioRecord busy/awaiting unlock; retrying gently.")
            }
            return
        }

        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(audioRecord.audioSessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }

        val chunk = ByteArray(CHUNK_BYTES)
        val preRoll = ArrayDeque<ByteArray>()
        val utterancePcm = ByteArrayOutputStream()

        var inSpeech = false
        var onsetFrames = 0
        var lastVoiceMs = 0L
        var activeSpeechMs = 0L
        var peakRms = 0.0
        var noiseFloorRms = 360.0

        return try {
            recognizer.reset()
            audioRecord.startRecording()
            if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return
            }

            captureActive = true
            val lockLabel = if (VoiceprintManager.isLockActive(appContext)) "Sirf Aapki Awaaz (Voice Lock ON)" else "Direct Voice (0-Pause)"
            notifyState(State.LISTENING, "Hotstar/Reels ke saath sun raha hoon — $lockLabel")

            while (desiredListening && captureActive && !pausedForExecution && !released) {
                val mode = try {
                    audioManager.mode
                } catch (_: Exception) {
                    AudioManager.MODE_NORMAL
                }
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                    break
                }

                val readBytes = audioRecord.read(chunk, 0, chunk.size)
                if (readBytes < 0) break
                if (readBytes == 0) continue

                if (SpeakerHelper.isAnySpeaking()) {
                    if (inSpeech) {
                        inSpeech = false
                        onsetFrames = 0
                        activeSpeechMs = 0L
                        utterancePcm.reset()
                        recognizer.reset()
                    }
                    preRoll.clear()
                    continue
                }

                val now = System.currentTimeMillis()
                val rms = VoiceprintManager.computePcmRms(chunk, readBytes)

                if (!inSpeech) {
                    noiseFloorRms = (noiseFloorRms * 0.96 + rms * 0.04).coerceIn(180.0, 2600.0)
                    val onsetThreshold = maxOf(440.0, noiseFloorRms * 2.15)

                    preRoll.addLast(chunk.copyOf(readBytes))
                    while (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()

                    if (rms >= onsetThreshold) {
                        onsetFrames++
                        if (rms > peakRms) peakRms = rms
                        if (onsetFrames >= ONSET_FRAMES_REQUIRED) {
                            inSpeech = true
                            lastVoiceMs = now
                            activeSpeechMs = (onsetFrames * 32L)
                            utterancePcm.reset()
                            recognizer.reset()
                            for (pf in preRoll) {
                                utterancePcm.write(pf)
                                recognizer.acceptWaveForm(pf, pf.size)
                            }
                            preRoll.clear()
                        }
                    } else {
                        onsetFrames = 0
                        peakRms = 0.0
                    }
                    continue
                }

                // Inside active speech utterance
                utterancePcm.write(chunk, 0, readBytes)
                val voskFinal = recognizer.acceptWaveForm(chunk, readBytes)
                if (rms > peakRms) peakRms = rms

                val offsetThreshold = maxOf(320.0, noiseFloorRms * 1.5)
                if (rms >= offsetThreshold) {
                    lastVoiceMs = now
                    activeSpeechMs += (readBytes / BYTES_PER_MS).toLong()
                }

                val totalMs = (utterancePcm.size() / BYTES_PER_MS).toLong()
                val silenceMs = now - lastVoiceMs
                val endDetected = (totalMs >= (MIN_UTTERANCE_MS + 200L) && silenceMs >= END_SILENCE_MS) ||
                    (voskFinal && totalMs >= 850L && silenceMs >= 320L) ||
                    totalMs >= MAX_UTTERANCE_MS

                if (endDetected) {
                    val rawPcm = VoiceprintManager.trimTrailingSilencePcm(utterancePcm.toByteArray())
                    val finalResultJson = recognizer.finalResult
                    recognizer.reset()
                    utterancePcm.reset()
                    inSpeech = false
                    onsetFrames = 0
                    val capturedActiveMs = activeSpeechMs
                    activeSpeechMs = 0L

                    val (voskText, spkVec, spkFrames) = parseVoskJson(finalResultJson)
                    val capturedMs = (rawPcm.size / BYTES_PER_MS).toLong()
                    val isMediaPlaying = try {
                        audioManager.isMusicActive
                    } catch (_: Exception) {
                        false
                    }

                    val hadMeaningfulSignal = capturedMs >= MIN_UTTERANCE_MS &&
                        capturedActiveMs >= 260L &&
                        peakRms >= 550.0
                    peakRms = 0.0
                    if (!hadMeaningfulSignal) continue

                    val verification = VoiceprintManager.verifySpeaker(
                        context = appContext,
                        spkVector = spkVec,
                        spkFrames = spkFrames,
                        pcmBytes = rawPcm,
                        isMediaPlaying = isMediaPlaying
                    )

                    val nowDispatchMs = System.currentTimeMillis()
                    val shouldForward = when {
                        forceForwardForChallenge -> true
                        verification.tier == VoiceprintManager.MatchTier.TRUSTED -> true
                        verification.tier == VoiceprintManager.MatchTier.DOUBT -> {
                            !isMediaPlaying || capturedMs >= 700L
                        }
                        else -> {
                            !isMediaPlaying &&
                                capturedMs >= 750L &&
                                (nowDispatchMs - lastRejectForwardMs) >= 8000L &&
                                verification.spkFrames >= 45
                        }
                    }

                    if (!shouldForward) {
                        JarvisLogStore.log(
                            "VOICE_LOCK",
                            "Ignored (${verification.reason}) dur=${capturedMs}ms frames=${verification.spkFrames} pitch=${verification.detectedPitchHz?.toInt() ?: -1}Hz vosk='$voskText'"
                        )
                        continue
                    }

                    if (verification.tier == VoiceprintManager.MatchTier.REJECTED && !forceForwardForChallenge) {
                        lastRejectForwardMs = nowDispatchMs
                    }

                    val wavBytes = VoiceprintManager.pcm16ToWav(rawPcm, SAMPLE_RATE)
                    JarvisLogStore.log(
                        "VOICE_CAPTURE",
                        "Forward tier=${verification.tier} (${verification.reason}) sim=${"%.2f".format(verification.similarity)} dur=${capturedMs}ms wav=${wavBytes.size / 1024}KB media=$isMediaPlaying vosk='$voskText'"
                    )

                    // Pause passive dispatching while Gemini + tool execution run so next utterance stays clean
                    pausedForExecution = true
                    captureActive = false
                    desiredListening = false
                    val captured = CapturedUtterance(
                        wavBytes = wavBytes,
                        voskHint = voskText,
                        verification = verification,
                        durationMs = capturedMs,
                        isMediaPlaying = isMediaPlaying
                    )
                    mainHandler.post {
                        if (!released) {
                            onUtteranceReady(captured)
                        }
                    }
                    break
                }
            }
        } catch (e: Exception) {
            JarvisLogStore.log("VOICE_ENGINE_WARN", "Passive capture interrupted: ${e.message}")
        } finally {
            captureActive = false
            try {
                if (audioRecord.recordingState == AudioRecord.RECORDSTATE_RECORDING) audioRecord.stop()
            } catch (_: Exception) {
            }
            try { aec?.release() } catch (_: Exception) {}
            try { ns?.release() } catch (_: Exception) {}
            try { audioRecord.release() } catch (_: Exception) {}
        }
    }

    private fun cleanupNativeResources() {
        try { voskRecognizer?.close() } catch (_: Exception) {}
        voskRecognizer = null
        try { voskSpkModel?.close() } catch (_: Exception) {}
        voskSpkModel = null
        try { voskModel?.close() } catch (_: Exception) {}
        voskModel = null
    }

    private fun notifyState(state: State, message: String) {
        currentState = state
        mainHandler.post {
            if (!released) {
                onStatusUpdate(state, message)
            }
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
        }
    }
}
