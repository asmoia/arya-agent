// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import androidx.core.content.ContextCompat
import io.agents.arya.utils.XLog

/**
 * PHASE 1 — The assistant session shown when Arya is invoked as the default
 * assistant. It starts the voice loop (STT -> LLM -> TTS) and surfaces a
 * lightweight UI overlay.
 *
 * Only real public VoiceInteractionSession APIs are used here. The previous
 * version overrode methods that do not exist in the SDK (onPrepare,
 * onHandleCallback(Callback), onShow(Bundle, ShowCallback),
 * onRequestPermissionResult) and called session.requestPermissions(), which
 * also does not exist — a VoiceInteractionSession cannot request runtime
 * permissions itself.
 */
class AryaVoiceSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "AryaVoiceSession"
    }

    private var loop: VoiceAssistantLoop? = null

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        XLog.i(TAG, "Voice session shown")
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // The session cannot show a permission dialog. The user must grant
            // RECORD_AUDIO inside the Arya app first; close the session instead
            // of starting a dead mic loop.
            XLog.w(TAG, "RECORD_AUDIO not granted — closing session (grant mic in Arya app first)")
            hide()
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

    override fun onDestroy() {
        loop?.release()
        loop = null
        super.onDestroy()
    }

    private fun startVoiceLoop() {
        loop = VoiceAssistantLoop(context)
        loop?.start { finalText ->
            XLog.i(TAG, "User said: $finalText")
            // The loop already drives STT->LLM->TTS; this callback is for logging/UI.
        }
    }
}
