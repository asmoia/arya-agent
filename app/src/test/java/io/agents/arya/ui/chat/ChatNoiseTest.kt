package io.agents.arya.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatNoiseTest {
    @Test
    fun stripsToolAndThink() {
        val raw = "hi <tool_call>{\"name\":\"x\"}</tool_call> there <think>secret</think> done"
        assertEquals("hi  there  done", ChatNoise.sanitizeAssistant(raw))
        assertTrue(ChatNoise.looksLikeRawToolJson("<tool_call>{}</tool_call>"))
        assertFalse(ChatNoise.looksLikeRawToolJson("hello"))
    }
}
