// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import io.agents.arya.utils.XLog
import java.util.Locale

/**
 * PHASE 1 — Text-to-speech for voice replies.
 *
 * Uses Android's built-in TextToSpeech with an OFFLINE voice
 * (user must install a Persian offline TTS voice in Settings > Languages > Text-to-speech).
 * When an offline voice is available, the assistant works fully without network.
 *
 * Upgrade path for higher quality: Piper / Kokoro (fully offline, higher quality).
 * Swap the implementation behind this interface when ready.
 */
interface TtsEngine {
    fun speak(text: String, onDone: () -> Unit)
    fun stop()
    fun release()
}

/**
 * Android's built-in TTS engine.
 */
class AndroidTtsEngine(private val context: Context) : TtsEngine {

    companion object {
        private const val TAG = "AryaTTS"
    }

    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Prefer an offline voice if available.
                val offline = tts?.voices?.firstOrNull { !it.isNetworkConnectionRequired }
                if (offline != null) {
                    XLog.i(TAG, "Using offline voice: ${offline.name}")
                }
                // Set Persian as preferred language, fall back gracefully.
                val faResult = tts?.setLanguage(Locale("fa", "IR"))
                if (faResult == TextToSpeech.LANG_MISSING_DATA || faResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    XLog.w(TAG, "Persian TTS not available, falling back to default")
                    tts?.setLanguage(Locale.getDefault())
                }
                tts?.setSpeechRate(1.05f)
                ready = true
                XLog.i(TAG, "TTS ready (offline voice: ${offline != null})")
            } else {
                XLog.w(TAG, "TTS init failed: $status")
            }
        }
    }

    override fun speak(text: String, onDone: () -> Unit) {
        if (!ready) {
            onDone()
            return
        }
        val params = Bundle()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(p0: String?) {}
            override fun onDone(p0: String?) {
                onDone()
            }

            override fun onError(p0: String?) {
                XLog.w(TAG, "TTS error")
                onDone()
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "arya_${System.currentTimeMillis()}")
    }

    override fun stop() {
        tts?.stop()
    }

    override fun release() {
        tts?.shutdown()
        tts = null
        ready = false
    }
}
