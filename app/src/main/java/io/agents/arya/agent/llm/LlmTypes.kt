package io.agents.arya.agent.llm

enum class Role {
    SYSTEM, USER, ASSISTANT, TOOL
}

data class ToolCallSpec(
    val id: String,
    val name: String,
    val argumentsJson: String
)

data class ChatMsg(
    val role: Role,
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallSpec> = emptyList()
)

data class ToolSpec(
    val name: String,
    val descriptionFa: String,
    val paramsJsonSchema: String
)
