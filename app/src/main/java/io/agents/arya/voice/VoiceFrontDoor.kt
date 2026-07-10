// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.voice

import io.agents.arya.agent.PersianCommandCompiler

/**
 * Contracts for a future voice assistant path. No wake word, ASR model or TTS
 * dependency is bundled here; this keeps the current APK small and lets the UI
 * use system RecognizerIntent until a benchmarked local stack is selected.
 */
enum class VoiceTurnState { IDLE, LISTENING, PARTIAL, ROUTING, EXECUTING, SPEAKING, CANCELLED, FAILED }

data class VoicePartial(val text: String, val isFinal: Boolean)

interface VoicePartialListener {
    fun onPartial(partial: VoicePartial)
    fun onState(state: VoiceTurnState)
}

object VoiceEarlyRouter {
    /**
     * Safe partial pre-routing. It may produce a deterministic plan, but never
     * sends, calls, deletes or changes settings before final user speech and
     * normal task safety checks.
     */
    fun prewarmCandidate(partialText: String) = PersianCommandCompiler.compile(partialText)
}
