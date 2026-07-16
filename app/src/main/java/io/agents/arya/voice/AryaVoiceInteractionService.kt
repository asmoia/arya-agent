// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.voice

import android.service.voice.VoiceInteractionService
import io.agents.arya.utils.XLog

/**
 * PHASE 1 — Default Assistant integration (the "Siri-like" trigger).
 *
 * Registering Arya as the device's Digital Assistant means the user can launch
 * it by long-pressing Home / swiping from the corner / long-pressing Power
 * (Settings > Apps > Default apps > Digital assistant app), exactly like
 * ChatGPT / Perplexity / Home Assistant do. This is the closest an un-certified
 * third-party app can get to Siri's system trigger on non-rooted Android.
 *
 * NOTE: Hotword ("Hey Arya") still cannot be system-level (no low-power DSP
 * access); use WakeWordDetector for a software wake word (higher battery cost).
 * Make wake-word detection opt-in.
 */
class AryaVoiceInteractionService : VoiceInteractionService() {

    companion object {
        private const val TAG = "AryaVoiceSvc"
    }

    override fun onReady() {
        super.onReady()
        XLog.i(TAG, "Arya VoiceInteractionService ready — can be set as default assistant")
    }

    override fun onShutdown() {
        super.onShutdown()
        XLog.i(TAG, "Arya VoiceInteractionService shutdown")
    }

    // Pre-arm the mic hotword detector when the assistant service is live.
    // Actual detection runs in WakeWordDetector (software wake word, opt-in).
    override fun onLaunchVoiceAssistFromKeyguard() {
        super.onLaunchVoiceAssistFromKeyguard()
        XLog.i(TAG, "Voice assist launched from keyguard")
    }
}
