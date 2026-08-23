package io.agents.arya.voice

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
    @Volatile
    private var discardNextResult = false
    /** Optional RecognizerIntent launcher for EMUI when SpeechRecognizer returns ERROR_CLIENT. */
    var launchFallbackIntent: ((Intent) -> Unit)? = null

    fun ensureReady(): Boolean {
        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            onError(activity.getString(R.string.voice_input_unavailable))
            controller.onError("unavailable")
            return false
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Activity owns ActivityResultContracts.RequestPermission — do not request here.
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
        discardNextResult = false
        controller.start()
        onPartial("")
        onError("")
        onListeningChanged(true)
        val intent = buildListenIntent()
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

    /** Drop the current utterance without delivering onFinal (short-press cancel). */
    fun cancel() {
        discardNextResult = true
        try {
            recognizer?.cancel()
        } catch (_: Exception) {
        }
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
        controller.stop()
        onListeningChanged(false)
        onPartial("")
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
            if (discardNextResult) {
                discardNextResult = false
                controller.stop()
                onListeningChanged(false)
                return
            }
            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_SERVER) {
                val fallback = launchFallbackIntent
                if (fallback != null) {
                    controller.stop()
                    onListeningChanged(false)
                    fallback(buildListenIntent())
                    return
                }
            }
            controller.onError("sr-$error")
            onListeningChanged(false)
            onError(activity.getString(R.string.voice_no_speech))
        }

        override fun onResults(results: Bundle?) {
            if (discardNextResult) {
                discardNextResult = false
                controller.stop()
                onListeningChanged(false)
                return
            }
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

    fun buildListenIntent(): Intent {
        val tag = java.util.Locale.getDefault().toLanguageTag()
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
    }

    companion object {
        const val REQ_RECORD_AUDIO = 71

        fun hasRecordAudio(activity: Activity): Boolean {
            return ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }

        fun openAppSettings(activity: Activity) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        }
    }
}
