package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamAssemblerTest {

    @Test
    fun testPlainTextSingleChunk() {
        val assembler = StreamAssembler()
        val events = assembler.feed("سلام دنیا! این یک متن نمونه است.")
        val finalEvents = assembler.finish()

        val allEvents = events + finalEvents
        val textEvents = allEvents.filterIsInstance<LlmEvent.Text>()
        val combinedText = textEvents.joinToString("") { it.delta }

        assertEquals("سلام دنیا! این یک متن نمونه است.", combinedText)
    }

    @Test
    fun testToolCallDetection() {
        val assembler = StreamAssembler()
        val stream = "در حال اجرا... <tool_call>{\"name\":\"open_app\",\"arguments\":{\"app_name\":\"WhatsApp\"}}</tool_call>"

        val events = assembler.feed(stream)
        val finalEvents = assembler.finish()
        val all = events + finalEvents

        val toolEvents = all.filterIsInstance<LlmEvent.ToolCall>()
        assertEquals(1, toolEvents.size)
        assertEquals("open_app", toolEvents[0].name)
    }

    @Test
    fun testChunkingPropertyIdenticalOutput() {
        val fullStream = "سلام! <tool_call>{\"name\":\"search\",\"arguments\":{\"query\":\"اخبار روز\"}}</tool_call> متشکرم."

        // 1. All at once
        val assembler1 = StreamAssembler()
        val res1 = assembler1.feed(fullStream) + assembler1.finish()

        // 2. 1-char chunks
        val assembler2 = StreamAssembler()
        val res2 = mutableListOf<LlmEvent>()
        for (ch in fullStream) {
            res2.addAll(assembler2.feed(ch.toString()))
        }
        res2.addAll(assembler2.finish())

        // 3. 3-char chunks
        val assembler3 = StreamAssembler()
        val res3 = mutableListOf<LlmEvent>()
        var idx = 0
        while (idx < fullStream.length) {
            val end = minOf(idx + 3, fullStream.length)
            res3.addAll(assembler3.feed(fullStream.substring(idx, end)))
            idx += 3
        }
        res3.addAll(assembler3.finish())

        val toolCalls1 = res1.filterIsInstance<LlmEvent.ToolCall>()
        val toolCalls2 = res2.filterIsInstance<LlmEvent.ToolCall>()
        val toolCalls3 = res3.filterIsInstance<LlmEvent.ToolCall>()

        assertEquals(toolCalls1.size, toolCalls2.size)
        assertEquals(toolCalls1.size, toolCalls3.size)
        if (toolCalls1.isNotEmpty()) {
            assertEquals(toolCalls1[0].name, toolCalls2[0].name)
            assertEquals(toolCalls1[0].name, toolCalls3[0].name)
        }
    }

    @Test
    fun testReasoningSplit() {
        val assembler = StreamAssembler()
        val stream = "<think>در حال بررسی درخواست کاربر...</think>پاسخ آماده است."

        val events = assembler.feed(stream) + assembler.finish()
        val reasoningTexts = events.filterIsInstance<LlmEvent.Text>().filter { it.isReasoning }
        val plainTexts = events.filterIsInstance<LlmEvent.Text>().filter { !it.isReasoning }

        assertTrue(reasoningTexts.isNotEmpty())
        assertEquals("در حال بررسی درخواست کاربر...", reasoningTexts.joinToString("") { it.delta })
        assertEquals("پاسخ آماده است.", plainTexts.joinToString("") { it.delta })
    }

    @Test
    fun stopStringSplitAcrossThreeDeltas() {
        val a = StreamAssembler(stopStrings = listOf("<|im_end|>"))
        val ev = a.feed("hi <|im") + a.feed("_en") + a.feed("d|> more") + a.finish()
        val text = ev.filterIsInstance<LlmEvent.Text>().joinToString("") { it.delta }
        assertTrue(text.contains("hi"))
        assertTrue(ev.any { it is LlmEvent.Finished })
    }

    @Test
    fun unclosedThinkWithNoVisibleAnswerIsSurfaced() {
        val assembler = StreamAssembler()
        val events = assembler.feed("<think>only a monologue") + assembler.finish()
        val visible = events.filterIsInstance<LlmEvent.Text>().filter { !it.isReasoning }
        assertTrue(visible.joinToString("") { it.delta }.contains("only a monologue"))
    }

    @Test
    fun persianZwnjSplitDoesNotCrash() {
        val a = StreamAssembler()
        val s = "می‌خواهم تلگرام را باز کنم"
        val ev = mutableListOf<LlmEvent>()
        for (ch in s) ev += a.feed(ch.toString())
        ev += a.finish()
        val text = ev.filterIsInstance<LlmEvent.Text>().filter { !it.isReasoning }.joinToString("") { it.delta }
        assertEquals(s, text)
    }
}
