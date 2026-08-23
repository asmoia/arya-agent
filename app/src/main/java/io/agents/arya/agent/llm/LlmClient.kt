package io.agents.arya.agent.llm

import kotlinx.coroutines.flow.Flow

/**
 * Streaming-first LLM client. Local (AIDL) and cloud (OkHttp+SSE) share this.
 */
interface LlmClient {
    fun chatStream(messages: List<ChatMsg>, tools: List<ToolSpec> = emptyList()): Flow<LlmEvent>

    fun chatSync(messages: List<ChatMsg>, tools: List<ToolSpec> = emptyList()): LlmResponse

    fun close() {}
}
