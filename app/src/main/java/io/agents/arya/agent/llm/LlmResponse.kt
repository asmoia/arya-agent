package io.agents.arya.agent.llm

data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0
)

data class InferenceTelemetry(
    val promptEvalMs: Double = 0.0,
    val promptTokens: Int = 0,
    val genMs: Double = 0.0,
    val genTokens: Int = 0,
    val genTokPerSec: Double = 0.0,
    val finishReason: String = "stop"
)

data class LlmResponse(
    val text: String?,
    val toolCalls: List<ToolCallSpec> = emptyList(),
    val tokenUsage: TokenUsage? = null,
    val modelName: String? = null,
    val telemetry: InferenceTelemetry? = null,
) {
    fun hasToolExecutionRequests(): Boolean = toolCalls.isNotEmpty()
    val toolExecutionRequests: List<ToolCallSpec> get() = toolCalls
}
