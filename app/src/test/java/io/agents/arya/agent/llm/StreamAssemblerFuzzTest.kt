package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StreamAssemblerFuzzTest {
    @Test
    fun randomChunksPreservePlainText() {
        val seed = 42
        val rnd = Random(seed)
        val input = "سلام دنیا! this is a test \u200c with ZWNJ and numbers 123."
        val a = StreamAssembler()
        val events = mutableListOf<LlmEvent>()
        var i = 0
        while (i < input.length) {
            val n = rnd.nextInt(1, 8)
            val end = minOf(i + n, input.length)
            events += a.feed(input.substring(i, end))
            i = end
        }
        events += a.finish()
        val text = events.filterIsInstance<LlmEvent.Text>().filter { !it.isReasoning }.joinToString("") { it.delta }
        assertEquals(input, text)
    }

    @Test
    fun randomOpenerInsertionsDoNotCrash() {
        val rnd = Random(7)
        val base = "hello <tool world"
        val a = StreamAssembler()
        var i = 0
        while (i < base.length) {
            a.feed(base.substring(i, minOf(i + rnd.nextInt(1, 4), base.length)))
            i += 3
        }
        val end = a.finish()
        assertTrue(end.isNotEmpty())
    }
}
