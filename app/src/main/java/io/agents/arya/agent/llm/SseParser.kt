package io.agents.arya.agent.llm

import org.json.JSONObject

/**
 * Pure SSE adapters so CloudLlmClient and JVM tests share one path (S6).
 */
object SseParser {

    fun parseOpenAiDataLine(data: String, assembler: StreamAssembler, tools: ToolDeltaAssembler? = null): List<LlmEvent> {
        if (data == "[DONE]") return emptyList()
        return try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices") ?: return emptyList()
            if (choices.length() == 0) return emptyList()
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta") ?: return finishIfStop(choice, tools)
            val out = mutableListOf<LlmEvent>()
            val content = delta.optString("content", "")
            if (content.isNotEmpty()) out += assembler.feed(content)
            val toolCalls = delta.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val tc = toolCalls.getJSONObject(0)
                val func = tc.optJSONObject("function")
                if (func != null) {
                    val name = func.optString("name", "")
                    val args = func.optString("arguments", "")
                    if (name.isNotEmpty()) {
                        tools?.onName(name)
                        out += LlmEvent.ToolCallStart(name)
                    }
                    if (args.isNotEmpty()) {
                        tools?.onArgs(args)
                        out += LlmEvent.ToolCallArgsDelta(args)
                    }
                }
            }
            out += finishIfStop(choice, tools)
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun parseAnthropicDataLine(data: String, assembler: StreamAssembler, tools: ToolDeltaAssembler? = null): List<LlmEvent> {
        return try {
            val json = JSONObject(data)
            when (json.optString("type")) {
                "content_block_delta" -> {
                    val delta = json.optJSONObject("delta") ?: return emptyList()
                    when (delta.optString("type")) {
                        "text_delta" -> {
                            val text = delta.optString("text", "")
                            if (text.isNotEmpty()) assembler.feed(text) else emptyList()
                        }
                        "input_json_delta" -> {
                            val partial = delta.optString("partial_json", "")
                            if (partial.isNotEmpty()) {
                                tools?.onArgs(partial)
                                listOf(LlmEvent.ToolCallArgsDelta(partial))
                            } else emptyList()
                        }
                        else -> emptyList()
                    }
                }
                "content_block_start" -> {
                    val block = json.optJSONObject("content_block")
                    val name = block?.optString("name").orEmpty()
                    if (name.isNotEmpty()) {
                        tools?.onName(name)
                        listOf(LlmEvent.ToolCallStart(name))
                    } else emptyList()
                }
                "message_stop", "content_block_stop" -> tools?.finish()?.let { listOf(it) } ?: emptyList()
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun redactSecrets(line: String): String {
        var out = line
        out = out.replace(Regex("(?i)bearer\\s+\\S+"), "Bearer ***")
        out = out.replace(Regex("(?i)sk-[A-Za-z0-9]+"), "sk-***")
        out = out.replace(Regex("(?i)(api[_-]?key|x-api-key)\\s*[:=]\\s*\\S+"), "$1=***")
        return out
    }

    private fun finishIfStop(choice: JSONObject, tools: ToolDeltaAssembler?): List<LlmEvent> {
        val reason = choice.optString("finish_reason", "")
        if (reason == "tool_calls" || reason == "stop") {
            val done = tools?.finish()
            return if (done != null) listOf(done) else emptyList()
        }
        return emptyList()
    }
}

/** Accumulates streamed function-call name/args into one [LlmEvent.ToolCall]. */
class ToolDeltaAssembler {
    private var name: String? = null
    private val args = StringBuilder()

    fun onName(n: String) {
        if (n.isNotEmpty()) name = n
    }

    fun onArgs(fragment: String) {
        if (fragment.isNotEmpty()) args.append(fragment)
    }

    fun finish(): LlmEvent.ToolCall? {
        val n = name ?: return null
        val json = args.toString().ifBlank { "{}" }
        name = null
        args.clear()
        return LlmEvent.ToolCall(n, json)
    }

    fun pendingName(): String? = name
}
