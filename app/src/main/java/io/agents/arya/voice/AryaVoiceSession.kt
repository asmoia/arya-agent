// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import androidx.core.content.ContextCompat
import io.agents.arya.utils.XLog

/**
 * PHASE 1 — The assistant session shown when Arya is invoked as the default
 * assistant. It starts the voice loop (STT -> LLM -> TTS) and surfaces a
 * lightweight UI overlay.
 */
class AryaVoiceSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "AryaVoiceSession"
        private const val REQUEST_MIC = 1001
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var loop: VoiceAssistantLoop? = null

    override fun onPrepare() {
        super.onPrepare()
        // Minimal UI surface; replace with a Compose overlay if desired.
        setUiEnabled(true)
    }

    override fun onHandleCallback(callback: Callback?) {
        super.onHandleCallback(callback)
    }

    override fun onShow(args: Bundle?, showCallback: ShowCallback?) {
        super.onShow(args, showCallback)
        XLog.i(TAG, "Voice session shown")
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MIC)
            return
        }
        startVoiceLoop()
    }

    override fun onHide() {
        super.onHide()
        loop?.stop()
        loop = null
        XLog.i(TAG, "Voice session hidden")
    }

    override fun onRequestPermissionResult(requestCode: Int, granted: Boolean) {
        if (requestCode == REQUEST_MIC && granted) {
            startVoiceLoop()
        } else {
            XLog.w(TAG, "Microphone permission denied — voice assistant cannot start")
        }
    }

    private fun startVoiceLoop() {
        loop = VoiceAssistantLoop(context)
        loop?.start { finalText ->
            XLog.i(TAG, "User said: $finalText")
            // The loop already drives STT->LLM->TTS; this callback is for logging/UI.
        }
    }
}
