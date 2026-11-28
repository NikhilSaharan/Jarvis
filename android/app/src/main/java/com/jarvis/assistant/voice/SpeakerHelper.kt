package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class SpeakerHelper(context: Context) : TextToSpeech.OnInitListener {

    companion object {
        @Volatile
        private var globalSpeakingSinceMs: Long = 0L

        @Volatile
        private var globalSpeaking: Boolean = false

        fun isAnySpeaking(): Boolean {
            if (globalSpeaking && globalSpeakingSinceMs > 0L &&
                (System.currentTimeMillis() - globalSpeakingSinceMs) > 15_000L
            ) {
                globalSpeaking = false
                globalSpeakingSinceMs = 0L
            }
            return globalSpeaking
        }
    }

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isReady = false
    private val utteranceCounter = AtomicInteger(0)

    @Volatile
    var isSpeaking: Boolean = false
        private set

    var onSpeakingStateChanged: ((Boolean) -> Unit)? = null

    fun isCurrentlySpeaking(): Boolean = isAnySpeaking()

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.UK)
            }
            tts?.setSpeechRate(1.0f)
            tts?.setPitch(1.0f)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    updateSpeaking(true)
                }

                override fun onDone(utteranceId: String?) {
                    updateSpeaking(false)
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    updateSpeaking(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    updateSpeaking(false)
                }
            })
            isReady = true
            Log.d("SpeakerHelper", "TextToSpeech engine ready.")
        }
    }

    private fun updateSpeaking(speaking: Boolean) {
        globalSpeaking = speaking
        globalSpeakingSinceMs = if (speaking) System.currentTimeMillis() else 0L
        if (isSpeaking != speaking) {
            isSpeaking = speaking
            try {
                onSpeakingStateChanged?.invoke(speaking)
            } catch (_: Exception) {
            }
        }
    }

    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        Log.d("SpeakerHelper", "Speaking: '$text'")
        val id = "JARVIS_UTT_${utteranceCounter.incrementAndGet()}"
        updateSpeaking(true)
        val res = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (res != TextToSpeech.SUCCESS) {
            updateSpeaking(false)
        }
    }

    fun stop() {
        tts?.stop()
        updateSpeaking(false)
    }

    fun shutdown() {
        updateSpeaking(false)
        onSpeakingStateChanged = null
        tts?.shutdown()
        tts = null
    }
}
