package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseParserTest {
    @Test
    fun openaiContentAndToolCall() {
        val a = StreamAssembler()
        val acc = ToolDeltaAssembler()
        val text = SseParser.parseOpenAiDataLine(
            """{"choices":[{"delta":{"content":"hi "}}]}""",
            a,
            acc,
        )
        assertEquals("hi ", text.filterIsInstance<LlmEvent.Text>().joinToString("") { it.delta })
        val tools = SseParser.parseOpenAiDataLine(
            """{"choices":[{"delta":{"tool_calls":[{"function":{"name":"open_app","arguments":"{"}}]}}]}""",
            a,
            acc,
        )
        assertTrue(tools.any { it is LlmEvent.ToolCallStart && it.name == "open_app" })
        val more = SseParser.parseOpenAiDataLine(
            """{"choices":[{"delta":{"tool_calls":[{"function":{"arguments":"\"pkg\":\"tg\"}"}}]},"finish_reason":"tool_calls"}]}""",
            a,
            acc,
        )
        val done = more.filterIsInstance<LlmEvent.ToolCall>().single()
        assertEquals("open_app", done.name)
        assertTrue(done.argsJson.contains("pkg"))
    }

    @Test
    fun anthropicTextDelta() {
        val a = StreamAssembler()
        val ev = SseParser.parseAnthropicDataLine(
            """{"type":"content_block_delta","delta":{"type":"text_delta","text":"سلام"}}""",
            a,
        )
        assertEquals("سلام", ev.filterIsInstance<LlmEvent.Text>().joinToString("") { it.delta })
    }

    @Test
    fun redactApiKey() {
        val red = SseParser.redactSecrets("Authorization: Bearer sk-secret")
        assertTrue(red.contains("***"))
        assertTrue(!red.contains("sk-secret"))
    }
}
