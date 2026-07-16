// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import io.agents.arya.utils.XLog
import java.util.Locale

/**
 * PHASE 1 — Speech-to-text abstraction.
 *
 * Currently uses Android's built-in SpeechRecognizer (which routes to Google's
 * cloud ASR on most devices).
 *
 * TODO (Phase 1 Enhancement): Replace with true on-device STT via sherpa-onnx
 * (Whisper + Silero VAD) for fully offline, private voice input.
 * Add to build.gradle.kts:
 *   implementation("com.k2fsa.sherpa.onnx:sherpa-onnx-android:latest.release")
 * and bundle whisper + Vad model assets.
 */
interface SpeechPipeline {
    /** Start streaming recognition. partial = live hypothesis, onFinal = final text. */
    fun startListening(partial: (String) -> Unit, onFinal: (String) -> Unit)
    fun stop()
    fun release()
}

/**
 * Android's built-in SpeechRecognizer (fallback STT).
 * NOTE: on most devices this routes to Google's cloud ASR, not truly on-device.
 * For production voice privacy, integrate sherpa-onnx (Whisper) instead.
 */
class AndroidSpeechClient(private val context: Context) : SpeechPipeline {

    companion object {
        private const val TAG = "AndroidSTT"
    }

    private var recognizer: SpeechRecognizer? = null
    private var partialCallback: ((String) -> Unit)? = null
    private var finalCallback: ((String) -> Unit)? = null

    override fun startListening(partial: (String) -> Unit, onFinal: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            XLog.w(TAG, "SpeechRecognizer unavailable on this device")
            return
        }

        partialCallback = partial
        finalCallback = onFinal

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).also { rec ->
            rec.setRecognitionListener(object : RecognitionListener {
                override fun onPartialResults(p0: Bundle?) {
                    p0?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.let { partialCallback?.invoke(it) }
                }

                override fun onResults(p0: Bundle?) {
                    p0?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.let { finalCallback?.invoke(it) }
                }

                override fun onError(e: Int) {
                    XLog.w(TAG, "onError $e")
                    // Restart listening on recoverable errors
                    if (e in listOf(
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        )
                    ) {
                        // Ignore — the next utterance will trigger onResults again
                    }
                }

                override fun onReadyForSpeech(p0: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(p0: Float) {}
                override fun onBufferReceived(p0: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(p0: Int, p1: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        recognizer?.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun release() {
        recognizer?.destroy()
        recognizer = null
        partialCallback = null
        finalCallback = null
    }
}

/**
 * Factory: creates the speech pipeline.
 * Currently returns [AndroidSpeechClient]. Upgrade to sherpa-onnx for true on-device STT.
 */
object SpeechPipelineFactory {
    fun create(context: Context): SpeechPipeline {
        return AndroidSpeechClient(context)
    }
}
