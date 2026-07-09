// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Embedded Hermes core — conversation history compression.

package io.agents.arya.agent.hermes.core

import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import io.agents.arya.utils.XLog

/**
 * Compresses long chat histories so the agent can stay within context limits.
 * Ported conceptually from Hermes context_compressor / conversation_compression.
 */
object HermesContextCompressor {

    private const val TAG = "HermesCompressor"

    /** Soft threshold: start compressing tool results. */
    const val SOFT_CHARS = 24_000

    /** Hard threshold: drop oldest non-system turns into a summary stub. */
    const val HARD_CHARS = 48_000

    /** Keep this many most-recent messages intact at the hard stage. */
    const val KEEP_RECENT = 12

    fun estimateChars(messages: List<ChatMessage>): Int =
        messages.sumOf { msgChars(it) }

    fun compress(messages: MutableList<ChatMessage>) {
        val before = estimateChars(messages)
        if (before < SOFT_CHARS) return

        // Stage 1: shrink large observation tool results
        for (i in messages.indices) {
            val m = messages[i]
            if (m is ToolExecutionResultMessage) {
                val name = m.toolName() ?: ""
                val text = m.text() ?: ""
                if (name == "get_screen_info" && text.length > 400) {
                    messages[i] = ToolExecutionResultMessage.from(
                        m.id(),
                        name,
                        "[screen_info compressed: ${text.length} chars — call get_screen_info again if needed]"
                    )
                } else if (text.length > 1200) {
                    messages[i] = ToolExecutionResultMessage.from(
                        m.id(),
                        name,
                        text.take(400) + "…[truncated ${text.length - 400} chars]"
                    )
                }
            }
        }

        val mid = estimateChars(messages)
        if (mid < HARD_CHARS) {
            if (mid < before) XLog.d(TAG, "soft-compress $before → $mid chars")
            return
        }

        // Stage 2: collapse oldest turns into a single summary user message
        if (messages.size <= KEEP_RECENT + 1) return

        val system = messages.firstOrNull { it is SystemMessage }
        val recent = messages.takeLast(KEEP_RECENT).toMutableList()
        val old = messages.drop(if (system != null) 1 else 0).dropLast(KEEP_RECENT)

        val summary = buildString {
            appendLine("[Hermes context compression]")
            appendLine("Earlier turns were summarized to free context. Key fragments:")
            var used = 0
            for (m in old) {
                val line = when (m) {
                    is UserMessage -> "User: ${m.singleText().take(180)}"
                    is AiMessage -> {
                        val tools = m.toolExecutionRequests()?.joinToString { it.name() ?: "?" } ?: ""
                        "AI: ${(m.text() ?: "").take(120)}" + if (tools.isNotEmpty()) " tools=[$tools]" else ""
                    }
                    is ToolExecutionResultMessage -> "Tool(${m.toolName()}): ${(m.text() ?: "").take(80)}"
                    else -> null
                } ?: continue
                if (used + line.length > 2500) break
                appendLine("- $line")
                used += line.length
            }
        }

        messages.clear()
        if (system != null) messages.add(system)
        messages.add(UserMessage.from(summary))
        messages.addAll(recent)
        XLog.i(TAG, "hard-compress $before → ${estimateChars(messages)} chars (kept $KEEP_RECENT recent)")
    }

    private fun msgChars(m: ChatMessage): Int = when (m) {
        is SystemMessage -> m.text()?.length ?: 0
        is UserMessage -> m.singleText().length
        is AiMessage -> (m.text()?.length ?: 0) +
            (m.toolExecutionRequests()?.sumOf { (it.arguments()?.length ?: 0) + (it.name()?.length ?: 0) } ?: 0)
        is ToolExecutionResultMessage -> (m.text()?.length ?: 0) + (m.toolName()?.length ?: 0)
        else -> 0
    }
}
