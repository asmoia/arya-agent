package io.agents.arya.ui.chat

object ChatMarkdown {
    fun render(title: String, messages: List<ChatMessage>): String = buildString {
        appendLine("# $title")
        appendLine()
        for (m in messages) {
            val who = when (m.role) {
                ChatMessage.Role.USER -> "User"
                ChatMessage.Role.ASSISTANT -> "Arya"
                ChatMessage.Role.SYSTEM -> "System"
                ChatMessage.Role.TOOL_GROUP -> "Tool"
            }
            appendLine("**$who**")
            appendLine()
            appendLine(m.content.trim())
            appendLine()
        }
    }
}
