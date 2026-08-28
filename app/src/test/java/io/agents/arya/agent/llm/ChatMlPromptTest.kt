package io.agents.arya.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMlPromptTest {
    @Test
    fun nonThinkingPrefillsOfficialEmptyThinkBlock() {
        val prompt = ChatMlPrompt.build(
            listOf(ChatMsg(Role.USER, "سلام")),
            emptyList(),
            enableThinking = false,
        )
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertFalse(prompt.contains("/no_think"))
        assertTrue(prompt.contains("<|im_start|>user\nسلام<|im_end|>"))
    }

    @Test
    fun thinkingModeDoesNotPrefillEmptyThink() {
        val prompt = ChatMlPrompt.build(
            listOf(ChatMsg(Role.USER, "hi")),
            emptyList(),
            enableThinking = true,
        )
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
        assertFalse(prompt.contains("<think>"))
        assertFalse(prompt.contains("/no_think"))
    }
}
