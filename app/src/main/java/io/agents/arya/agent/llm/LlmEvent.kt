package io.agents.arya.agent.llm

sealed interface LlmEvent {
    data class Text(val delta: String, val isReasoning: Boolean = false) : LlmEvent
    data class ToolCallStart(val name: String?) : LlmEvent
    data class ToolCallArgsDelta(val fragment: String) : LlmEvent
    data class ToolCall(val name: String, val argsJson: String) : LlmEvent
    data class Finished(val reason: String, val matchedStop: String? = null) : LlmEvent
    data class Error(val code: Int, val message: String) : LlmEvent
    data class Status(val message: String) : LlmEvent
}
