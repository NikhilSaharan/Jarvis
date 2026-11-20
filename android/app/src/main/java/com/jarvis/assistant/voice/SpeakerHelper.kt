package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class SpeakerHelper(context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var isReady = false

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("hi", "IN"))
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.UK)
            }
            tts?.setSpeechRate(1.0f)
            tts?.setPitch(1.0f)
            isReady = true
            Log.d("SpeakerHelper", "TextToSpeech engine ready.")
        }
    }

    fun speak(text: String) {
        if (!isReady || text.isBlank()) return
        Log.d("SpeakerHelper", "Speaking: '$text'")
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "UTTERANCE_ID")
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
    }
}
