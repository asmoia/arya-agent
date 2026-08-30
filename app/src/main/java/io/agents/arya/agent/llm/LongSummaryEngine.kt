package io.agents.arya.agent.llm

/**
 * Chunked map-reduce summarizer for arbitrarily long content that must fit a
 * fixed model context window.
 *
 * A small on-device model has a hard window (Arya uses 2048 tokens). A full
 * Telegram transcript or a long document is far bigger than that, so we can not
 * just stuff it into one prompt. This engine instead:
 *
 *   1. **splits** the input messages into chunks that each fit the budget,
 *   2. **summarizes** each chunk (the "map" step),
 *   3. **merges** those chunk summaries in batches and re-summarizes until a
 *      single summary that fits comes out (the "reduce" step).
 *
 * The caller supplies a `summarize` function (the actual model call) so this
 * class stays free of any LLM/Android dependency and is trivially unit-testable.
 */
class LongSummaryEngine(
    private val estimateTokens: (String) -> Int,
    private val summarize: (String) -> String,
    private val contextTokens: Int = 2048,
    private val reserveTokens: Int = 128,
) {

    data class Result(
        val summary: String,
        val chunkCount: Int,
        val reduceRounds: Int,
        val truncatedInput: Boolean,
    )

    /** Tokens available for the prompt itself (leaves room for generation). */
    val promptBudget: Int
        get() = (contextTokens - reserveTokens).coerceAtLeast(256)

    /**
     * Split a flat list of message strings into consecutive chunks whose
     * estimated token count is <= promptBudget. A single over-sized message is
     * further split on sentence boundaries so it is never dropped.
     */
    fun splitChunks(parts: List<String>): List<List<String>> {
        if (parts.isEmpty()) return emptyList()
        val chunks = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        var currentTokens = 0

        fun flush() {
            if (current.isNotEmpty()) {
                chunks.add(current.toList())
                current.clear()
                currentTokens = 0
            }
        }

        for (part in parts) {
            val t = estimateTokens(part)
            if (t > promptBudget) {
                flush()
                for (piece in splitOversized(part)) {
                    val pt = estimateTokens(piece)
                    if (current.isNotEmpty() && currentTokens + pt > promptBudget) flush()
                    current.add(piece)
                    currentTokens += pt
                }
                continue
            }
            if (current.isNotEmpty() && currentTokens + t > promptBudget) flush()
            current.add(part)
            currentTokens += t
        }
        flush()
        return chunks
    }

    /**
     * Produce one summary that fits the budget from `parts`. Returns the final
     * summary plus bookkeeping (how many input chunks and reduce rounds it took).
     */
    fun summarizeAll(parts: List<String>): Result {
        val chunks = splitChunks(parts)
        if (chunks.isEmpty()) return Result("", 0, 0, truncatedInput = false)

        // Map: summarize each chunk once.
        var current = chunks.map { summarize(it.joinToString("\n")) }
        var reduceRounds = 0

        // Reduce: merge batch summaries until a single one remains.
        while (current.size > 1) {
            val next = mutableListOf<String>()
            val buf = StringBuilder()
            var bufTokens = 0
            for (s in current) {
                val t = estimateTokens(s)
                if (buf.isNotEmpty() && bufTokens + t > promptBudget) {
                    next.add(summarize(buf.toString()))
                    buf.setLength(0)
                    bufTokens = 0
                }
                buf.append(s).append('\n')
                bufTokens += t
            }
            if (buf.isNotEmpty()) next.add(summarize(buf.toString()))
            current = next
            reduceRounds++
        }
        return Result(current.first(), chunks.size, reduceRounds, truncatedInput = false)
    }

    /**
     * Return only the *first* batch of chunks that fits, so callers can process
     * a long transcript incrementally (one LLM turn at a time) instead of all at
     * once. Used by tools that let the agent page through a long chat.
     */
    fun firstBatch(parts: List<String>, batchLimit: Int = 1): List<String> {
        val chunks = splitChunks(parts)
        return chunks.take(batchLimit).map { it.joinToString("\n") }
    }

    private fun splitOversized(text: String): List<String> {
        if (text.isBlank()) return listOf(text)
        val sentences = text.split(Regex("(?<=[.!?؟۔])\\s+")).filter { it.isNotBlank() }
        if (sentences.size <= 1) {
            // No clean sentence boundaries — hard cut on the budget.
            return text.chunked(promptBudget.coerceAtLeast(256)).toList()
        }
        return sentences
    }
}
