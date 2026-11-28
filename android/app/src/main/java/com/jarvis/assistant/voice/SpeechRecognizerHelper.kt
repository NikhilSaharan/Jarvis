package com.jarvis.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class SpeechRecognizerHelper(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onError: (String) -> Unit,
    private val onErrorCode: ((Int) -> Unit)? = null,
    private val onReadyForSpeechCb: (() -> Unit)? = null
) : RecognitionListener {

    private var recognizer: SpeechRecognizer? = null

    @Volatile
    var isDestroyed: Boolean = false
        private set

    init {
        recreate()
    }

    fun recreate() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@SpeechRecognizerHelper)
            }
            isDestroyed = false
        }
    }

    fun startListening() {
        if (isDestroyed || recognizer == null) {
            recreate()
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        }
        recognizer?.startListening(intent)
    }

    fun stopListening() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
    }

    fun cancel() {
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    fun destroy() {
        isDestroyed = true
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    override fun onResults(results: Bundle?) {
        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        val text = matches?.firstOrNull() ?: ""
        if (text.isNotBlank()) {
            onResult(text)
        } else {
            onErrorCode?.invoke(SpeechRecognizer.ERROR_NO_MATCH)
            onError("Error code: ${SpeechRecognizer.ERROR_NO_MATCH}")
        }
    }

    override fun onError(error: Int) {
        Log.w("SpeechRecognizerHelper", "Recognition error code: $error")
        onErrorCode?.invoke(error)
        onError("Error code: $error")
    }

    override fun onReadyForSpeech(params: Bundle?) {
        onReadyForSpeechCb?.invoke()
    }

    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
