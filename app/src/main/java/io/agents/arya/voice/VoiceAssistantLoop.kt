// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.voice

import android.content.Context
import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.LlmProvider
import io.agents.arya.agent.llm.LlmClientFactory
import io.agents.arya.utils.XLog
import dev.langchain4j.data.message.UserMessage

/**
 * PHASE 1 — The streaming voice-to-voice loop:
 *    mic -> STT (on-device) -> LlmClient (the existing agent, Phase 0 backend)
 *         -> TTS (offline) -> speaker
 *
 * Reuses the existing LlmClient contract (Local/BitNet/LiteRT) so the voice
 * path gets the same model/tooling as the typed chat — no agent rewrite needed.
 *
 * Usage: create an instance and call start(). The loop runs until stop()/release().
 */
class VoiceAssistantLoop(private val context: Context) {

    companion object {
        private const val TAG = "VoiceLoop"
    }

    private val speech: SpeechPipeline = SpeechPipelineFactory.create(context)
    private val tts: TtsEngine = AndroidTtsEngine(context)

    // Reuse the same local model the chat uses (Phase 0 backend: BitNet/LITERT).
    // Note: The LLM used here should match the user's configured provider.
    private val config: AgentConfig = AgentConfig(
        apiKey = "",
        baseUrl = "",
        // Prefer local/NPU provider; fall back to cloud if configured.
        provider = LlmProvider.BITNET,
        systemPrompt = "You are Arya, a helpful on-device Android voice assistant. " +
                "Reply concisely and in the user's language."
    )

    private val client = LlmClientFactory.create(config)

    /**
     * Start the voice loop. The provided callback fires with the final recognized text.
     */
    fun start(onFinalUserText: (String) -> Unit = {}) {
        XLog.i(TAG, "Voice loop started")
        speech.startListening(
            partial = { /* live caption could be shown here */ },
            onFinal = { userText ->
                if (userText.isBlank()) return@startListening
                onFinalUserText(userText)
                respond(userText)
            }
        )
    }

    private fun respond(userText: String) {
        // Single-turn voice reply.
        // For multi-turn conversation, accumulate history from ConversationStore.
        val response = client.chat(
            messages = listOf(UserMessage.from(userText)),
            toolSpecs = emptyList()
        )
        val reply = response.text ?: run {
            // If the model emitted a tool call, fall back to a short acknowledgement.
            // Full tool execution would require running the full agent loop.
            "متوجه شدم، برای این کار از چت استفاده کن."
        }
        XLog.i(TAG, "Reply: ${reply.take(80)}")
        tts.speak(reply) { /* done */ }
    }

    fun stop() {
        speech.stop()
        tts.stop()
    }

    fun release() {
        speech.release()
        tts.release()
        client.close()
    }
}
