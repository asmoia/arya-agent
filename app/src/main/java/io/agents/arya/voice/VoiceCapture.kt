package io.agents.arya.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.agents.arya.R

/**
 * Shared SpeechRecognizer wrapper used by ComposeChatActivity and OverlayHostActivity.
 * Language is requested as fa-IR with a device-default fallback. No paid STT APIs.
 */
class VoiceCapture(
    private val activity: Activity,
    private val onListeningChanged: (Boolean) -> Unit,
    private val onPartial: (String) -> Unit,
    private val onFinal: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    val controller = VoiceInputController()

    fun ensureReady(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            onError(activity.getString(R.string.voice_input_unavailable))
            controller.onError("unavailable")
            return false
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQ_RECORD_AUDIO,
            )
            onError(activity.getString(R.string.voice_need_mic))
            return false
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity).apply {
                setRecognitionListener(Listener())
            }
        }
        return true
    }

    fun start() {
        if (!ensureReady()) return
        controller.start()
        onPartial("")
        onError("")
        onListeningChanged(true)
        val tag = java.util.Locale.getDefault().toLanguageTag()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            controller.onError(e.message ?: "start")
            onListeningChanged(false)
            onError(activity.getString(R.string.voice_input_error))
        }
    }

    fun stop() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        controller.stop()
        onListeningChanged(false)
    }

    fun destroy() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) {
        }
        recognizer = null
        controller.stop()
    }

    private inner class Listener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            controller.onReady()
            onListeningChanged(true)
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            controller.onError("sr-$error")
            onListeningChanged(false)
            onError(activity.getString(R.string.voice_no_speech))
        }

        override fun onResults(results: Bundle?) {
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            controller.onFinal(transcript)
            onListeningChanged(false)
            if (transcript.isNotBlank()) onFinal(transcript)
            else onError(activity.getString(R.string.voice_no_speech))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            controller.onPartial(text)
            onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        const val REQ_RECORD_AUDIO = 71
    }
}
