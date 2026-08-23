package io.agents.arya.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMarkdownTest {
    @Test
    fun rendersRoles() {
        val md = ChatMarkdown.render(
            "Chat",
            listOf(
                ChatMessage(ChatMessage.Role.USER, "hi"),
                ChatMessage(ChatMessage.Role.ASSISTANT, "hello"),
            ),
        )
        assertTrue(md.contains("# Chat"))
        assertTrue(md.contains("**User**"))
        assertTrue(md.contains("**Arya**"))
        assertTrue(md.contains("hello"))
    }
}
