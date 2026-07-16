// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.llm

import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.ChatMessage
import io.agents.arya.agent.AgentConfig

/**
 * PHASE 0 FOUNDATION — NPU/GPU inference backend via Google AI Edge LiteRT-LM
 * (provider = LITERT).
 *
 * The previous version of this client called a MediaPipe-style API
 * (`com.google.ai.edge.litertlm.LlmInference` / `LlmInferenceOptions` /
 * `generateResponse(...)`) that does NOT exist in the published
 * `com.google.ai.edge.litertlm:litertlm-android` artifact — verified against
 * 0.10.0, where the SDK exposes `Engine` / `EngineConfig` / `Conversation` /
 * `ConversationConfig` / `SamplerConfig` instead. That is why the merged
 * phases branch failed to compile.
 *
 * A complete, battle-tested LiteRT-LM integration already exists in
 * [LocalLlmClient] + [LocalModelRuntime]: backend auto-selection
 * (GPU/NPU/CPU per device), single-conversation leasing, and GPU→CPU
 * fallback. Rather than duplicating ~600 lines of that logic (and shipping
 * an unverifiable second path), LITERT delegates to the same proven runtime.
 *
 * IMPORTANT (read before shipping):
 *  - config.baseUrl must point to a `.litertlm` / LiteRT-compatible model
 *    (e.g. Gemma E2B/E4B), NOT a plain GGUF. GGUF will NOT load in LiteRT-LM.
 *  - LiteRT auto-selects NPU > GPU > CPU per device. True NPU needs a model
 *    compiled for that SoC's accelerator.
 */
class LitertLlmClient(config: AgentConfig) : LlmClient {

    private val delegate = LocalLlmClient(config)

    override fun chat(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>
    ): LlmResponse = delegate.chat(messages, toolSpecs)

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse = delegate.chatStreaming(messages, toolSpecs, listener)

    override fun close() = delegate.close()
}
