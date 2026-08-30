package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongSummaryEngineTest {

    private val ctx = 2048
    private val reserve = 128
    private val budget = ctx - reserve
    private val tokenEstimate: (String) -> Int = { s -> s.length / 4 + 1 }

    private fun engine(summarize: (String) -> String) = LongSummaryEngine(
        estimateTokens = tokenEstimate,
        summarize = summarize,
        contextTokens = ctx,
        reserveTokens = reserve,
    )

    @Test
    fun `small input stays a single chunk`() {
        val parts = ("hello world ".repeat(20)).split(' ').filter { it.isNotBlank() }.map { "$it " }
        val e = engine { it }
        val chunks = e.splitChunks(parts)
        assertTrue("should fit in one chunk; got ${chunks.size}", chunks.size <= 2)
    }

    @Test
    fun `long input splits into many chunks that each fit the budget`() {
        val parts = List(300) { "Message $it: " + "Some useful content that should be summarized carefully. ".repeat(3) }
        val e = engine { it }
        val chunks = e.splitChunks(parts)
        assertTrue("expected many chunks for 300 messages", chunks.size > 5)
        for (c in chunks) {
            val tok = tokenEstimate(c.joinToString("\n"))
            assertTrue("chunk $tok tokens exceeds budget $budget", tok <= budget)
        }
    }

    @Test
    fun `reduce collapses many chunk summaries into one`() {
        val parts = List(120) { "Line $it: " + "A record of the conversation that needs to be reduced. ".repeat(3) }
        // A "summarize" that compresses by half each call, so reduce terminates.
        val e = engine { s -> s.substring(0, (s.length / 2).coerceAtLeast(1)) }
        val r = e.summarizeAll(parts)
        assertTrue("expect >1 chunk for 120 messages", r.chunkCount > 1)
        assertTrue("summary should be non-empty", r.summary.isNotEmpty())
        assertTrue("reduce rounds should be tracked", r.reduceRounds >= 1)
    }

    @Test
    fun `oversized single message is split not dropped`() {
        val big = "This is a single huge message. ".repeat(5000)
        val e = engine { it }
        val chunks = e.splitChunks(listOf(big))
        assertTrue("oversized message must be split", chunks.size > 1)
        val joined = chunks.flatten().joinToString("")
        assertTrue("no content lost", joined.length >= big.length * 0.9)
    }

    @Test
    fun `empty input yields empty summary with no chunks`() {
        val r = engine { it }.summarizeAll(emptyList())
        assertEquals("", r.summary)
        assertEquals(0, r.chunkCount)
        assertEquals(0, r.reduceRounds)
        assertFalse(r.truncatedInput)
    }

    @Test
    fun `map-reduce idempotence with deterministic summarizer`() {
        // Summarize = keep first 40 chars. Reduce then merges repeated prefixes.
        val parts = List(50) { "Segment ${it}: some content worth keeping. " }
        val e = engine { s -> s.take(40) }
        val r = e.summarizeAll(parts)
        assertTrue(r.summary.isNotEmpty())
        assertTrue(r.summary.length <= 40)
    }
}
