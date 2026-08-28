package io.agents.arya.agent.llm

import org.json.JSONObject

enum class ToolFormat {
    QWEN_CHATML,
    STRUCTURED_JSON
}

class StreamAssembler(
    val format: ToolFormat = ToolFormat.QWEN_CHATML,
    val stopStrings: List<String> = listOf("<|im_end|>", "</tool_call>")
) {
    private val buffer = StringBuilder()
    private var inToolCall = false
    private var inThinking = false
    private var emittedVisible = false
    private val toolCallBuffer = StringBuilder()

    private val toolCallOpener = "<tool_call>"
    private val toolCallCloser = "</tool_call>"
    private val thinkOpener = "<think>"
    private val thinkCloser = "</think>"

    fun feed(delta: String): List<LlmEvent> {
        buffer.append(delta)
        val events = mutableListOf<LlmEvent>()

        while (buffer.isNotEmpty()) {
            if (!inToolCall && !inThinking) {
                // Check if buffer contains <tool_call>
                val toolIdx = buffer.indexOf(toolCallOpener)
                val thinkIdx = buffer.indexOf(thinkOpener)

                if (toolIdx >= 0 && (thinkIdx < 0 || toolIdx < thinkIdx)) {
                    if (toolIdx > 0) {
                        events.add(LlmEvent.Text(buffer.substring(0, toolIdx)))
                        emittedVisible = true
                    }
                    events.add(LlmEvent.ToolCallStart(null))
                    buffer.delete(0, toolIdx + toolCallOpener.length)
                    inToolCall = true
                    toolCallBuffer.clear()
                    continue
                }

                if (thinkIdx >= 0) {
                    if (thinkIdx > 0) {
                        events.add(LlmEvent.Text(buffer.substring(0, thinkIdx)))
                        emittedVisible = true
                    }
                    buffer.delete(0, thinkIdx + thinkOpener.length)
                    inThinking = true
                    continue
                }

                // Check for potential partial openers or stop strings
                val maxOpenerLen = maxOf(toolCallOpener.length, thinkOpener.length)
                val holdbackLen = getHoldbackLength(buffer.toString())

                if (holdbackLen > 0) {
                    val safeLen = buffer.length - holdbackLen
                    if (safeLen > 0) {
                        events.add(LlmEvent.Text(buffer.substring(0, safeLen)))
                        emittedVisible = true
                        buffer.delete(0, safeLen)
                    }
                    break
                } else {
                    events.add(LlmEvent.Text(buffer.toString()))
                    emittedVisible = true
                    buffer.clear()
                }
            } else if (inThinking) {
                val closeIdx = buffer.indexOf(thinkCloser)
                if (closeIdx >= 0) {
                    if (closeIdx > 0) {
                        events.add(LlmEvent.Text(buffer.substring(0, closeIdx), isReasoning = true))
                    }
                    buffer.delete(0, closeIdx + thinkCloser.length)
                    inThinking = false
                    continue
                } else {
                    val holdback = getPartialMatchLength(buffer.toString(), thinkCloser)
                    val safeLen = buffer.length - holdback
                    if (safeLen > 0) {
                        events.add(LlmEvent.Text(buffer.substring(0, safeLen), isReasoning = true))
                        buffer.delete(0, safeLen)
                    }
                    break
                }
            } else if (inToolCall) {
                val closeIdx = buffer.indexOf(toolCallCloser)
                if (closeIdx >= 0) {
                    toolCallBuffer.append(buffer.substring(0, closeIdx))
                    buffer.delete(0, closeIdx + toolCallCloser.length)
                    inToolCall = false

                    val rawToolJson = toolCallBuffer.toString().trim()
                    val parsed = parseToolCallJson(rawToolJson)
                    if (parsed != null) {
                        events.add(LlmEvent.ToolCall(parsed.first, parsed.second))
                    } else {
                        events.add(LlmEvent.Text(rawToolJson))
                    }
                    toolCallBuffer.clear()
                    continue
                } else {
                    val holdback = getPartialMatchLength(buffer.toString(), toolCallCloser)
                    val safeLen = buffer.length - holdback
                    if (safeLen > 0) {
                        val fragment = buffer.substring(0, safeLen)
                        toolCallBuffer.append(fragment)
                        events.add(LlmEvent.ToolCallArgsDelta(fragment))
                        buffer.delete(0, safeLen)
                    }
                    break
                }
            }
        }

        return events
    }

    fun finish(): List<LlmEvent> {
        val events = mutableListOf<LlmEvent>()
        if (inToolCall) {
            val raw = toolCallBuffer.append(buffer).toString().trim()
            val parsed = parseToolCallJson(raw)
            if (parsed != null) {
                events.add(LlmEvent.ToolCall(parsed.first, parsed.second))
            } else {
                events.add(LlmEvent.Finished("truncated_tool_call"))
            }
        } else if (buffer.isNotEmpty()) {
            events.add(LlmEvent.Text(buffer.toString(), isReasoning = inThinking))
        }
        events.add(LlmEvent.Finished("stop"))
        return events
    }

    private fun getHoldbackLength(text: String): Int {
        var maxHoldback = 0
        val candidates = stopStrings + listOf(toolCallOpener, thinkOpener)
        for (cand in candidates) {
            val p = getPartialMatchLength(text, cand)
            if (p > maxHoldback) maxHoldback = p
        }
        return maxHoldback
    }

    private fun getPartialMatchLength(text: String, target: String): Int {
        val maxCheck = minOf(text.length, target.length - 1)
        for (len in maxCheck downTo 1) {
            if (target.startsWith(text.takeLast(len))) {
                return len
            }
        }
        return 0
    }

    private fun parseToolCallJson(rawJson: String): Pair<String, String>? {
        return try {
            val json = JSONObject(rawJson)
            val name = json.optString("name", json.optString("name", ""))
            val args = json.opt("arguments")
            val argsJson = when (args) {
                is JSONObject -> args.toString()
                is String -> args
                else -> "{}"
            }
            if (name.isNotEmpty()) Pair(name, argsJson) else null
        } catch (_: Exception) {
            // Attempt simple regex parse if JSON is slightly malformed
            val nameMatch = Regex(""""name"\s*:\s*"(\w+)"""").find(rawJson)
            if (nameMatch != null) {
                val name = nameMatch.groupValues[1]
                Pair(name, rawJson)
            } else null
        }
    }
}
