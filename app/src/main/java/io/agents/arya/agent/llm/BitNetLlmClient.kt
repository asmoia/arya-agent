// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.agent.tool.ToolSpecification
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import com.google.gson.Gson
import io.agents.arya.agent.AgentConfig
import io.agents.arya.utils.XLog

/**
 * LlmClient implementation using llama.cpp / bitnet.cpp via [BitNetNative] JNI.
 *
 * Supports any GGUF model including BitNet i2_s when the native library is
 * built with bitnet.cpp kernels.  Falls back gracefully when the .so is absent.
 *
 * Architecture differences from [LocalLlmClient] (LiteRT-LM):
 * - Stateless prompt assembly: we rebuild the full prompt each call (like
 *   OpenAI/Anthropic clients) rather than relying on a stateful Conversation.
 * - Tool calls are parsed from the raw model output using regex patterns.
 * - Model handle is cached across calls within the same task; released on [close].
 */
class BitNetLlmClient(private val config: AgentConfig) : LlmClient {

    private val GSON = Gson()
    private var modelHandle: Long = 0L
    private var modelPath: String = ""

    // ---- Model lifecycle ----

    private fun ensureModel(): Long {
        if (modelHandle > 0) return modelHandle
        val path = config.baseUrl
        if (path.isBlank()) throw IllegalStateException("BitNet model path is empty")
        val handle = BitNetNative.loadModel(path, nCtx = 2048, nThreads = 0)
        if (handle <= 0) throw IllegalStateException("Failed to load BitNet model: $path")
        modelHandle = handle
        modelPath = path
        XLog.i(TAG, "Model loaded: $path handle=$handle")
        return handle
    }

    // ---- LlmClient ----

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        val handle = ensureModel()
        val prompt = buildPrompt(messages, toolSpecs)
        XLog.d(TAG, "chat: prompt length=${prompt.length} tools=${toolSpecs.size}")

        val rawOutput = BitNetNative.completion(
            handle = handle,
            prompt = prompt,
            maxTokens = 512,
            temperature = config.temperature.coerceIn(0.0, 1.0).toFloat(),
            topP = 0.95f,
            topK = 20,
            repeatPenalty = 1.1f,
            stopSequences = GSON.toJson(STOP_SEQUENCES),
        ) ?: throw IllegalStateException("BitNet completion returned null")

        XLog.d(TAG, "chat: output length=${rawOutput.length}")
        return parseResponse(rawOutput)
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
    ): LlmResponse {
        // BitNet native streaming is not yet wired through JNI.
        // Fall back to blocking completion + emit all at once.
        val response = chat(messages, toolSpecs)
        val text = response.text
        if (text != null) {
            listener.onToken(text)
        }
        return response
    }

    override fun close() {
        if (modelHandle > 0) {
            XLog.i(TAG, "close: freeing model handle=$modelHandle")
            BitNetNative.freeModel(modelHandle)
            modelHandle = 0L
        }
    }

    // ---- Prompt formatting ----

    /**
     * Build a ChatML-style prompt from the message list.
     *
     * ChatML is widely understood by small models and works with Qwen, BitNet,
     * Phi, and many others. If the model has a different chat template, this
     * method can be extended.
     */
    private fun buildPrompt(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): String {
        val sb = StringBuilder()

        // System message
        val systemMsg = messages.filterIsInstance<SystemMessage>().firstOrNull()?.text()
            ?: config.systemPrompt.ifEmpty { DEFAULT_BITNET_SYSTEM_PROMPT }
        sb.append("<|im_start|>system\n")
        sb.append(systemMsg)

        // Append tool declarations if present
        if (toolSpecs.isNotEmpty()) {
            sb.append("\n\n## Available Tools\n")
            for (spec in toolSpecs) {
                sb.append("- **${spec.name()}**: ${spec.description() ?: ""}\n")
                val params = spec.parameters()
                if (params != null) {
                    val props = params.properties()
                    if (props != null && props.isNotEmpty()) {
                        sb.append("  Parameters: ")
                        sb.append(props.entries.joinToString(", ") { (name, schema) ->
                            "${name}: ${schema.description() ?: "string"}"
                        })
                        sb.append("\n")
                    }
                }
            }
            sb.append("\nTo call a tool, output a JSON block: {\"name\": \"tool_name\", \"arguments\": {...}}\n")
        }

        sb.append("<|im_end|>\n")

        // Chat history
        for (msg in messages) {
            when (msg) {
                is SystemMessage -> { /* already handled */ }
                is UserMessage -> {
                    sb.append("<|im_start|>user\n${msg.singleText()}<|im_end|>\n")
                }
                is AiMessage -> {
                    val text = msg.text()
                    if (!text.isNullOrBlank()) {
                        sb.append("<|im_start|>assistant\n$text<|im_end|>\n")
                    }
                    msg.toolExecutionRequests()?.firstOrNull()?.let { req ->
                        sb.append("<|im_start|>assistant\n")
                        sb.append("{\"name\": \"${req.name()}\", \"arguments\": ${req.arguments()}}")
                        sb.append("<|im_end|>\n")
                    }
                }
                is ToolExecutionResultMessage -> {
                    sb.append("<|im_start|>tool\n[Tool ${msg.toolName()} result]: ${msg.text()}<|im_end|>\n")
                }
            }
        }

        // Start assistant turn
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    // ---- Response parsing ----

    private fun parseResponse(rawOutput: String): LlmResponse {
        val trimmed = rawOutput.trim()

        // Try to extract a tool call from the output
        val toolCall = extractToolCall(trimmed)
        if (toolCall != null) {
            return LlmResponse(
                text = null,
                toolExecutionRequests = listOf(toolCall),
            )
        }

        // Plain text response
        return LlmResponse(
            text = trimmed,
            toolExecutionRequests = emptyList(),
        )
    }

    /**
     * Attempt to extract a tool call from the model output.
     * Supports multiple formats:
     * 1. <tool_call>{...}</tool_call>
     * 2. ```tool_call\n{...}\n```
     * 3. Bare JSON: {"name": "...", "arguments": {...}}
     * 4. function_call: {...}
     */
    private fun extractToolCall(output: String): ToolExecutionRequest? {
        // Pattern 1: <tool_call>...</tool_call>
        TOOL_CALL_TAG_PATTERN.find(output)?.let { match ->
            return parseToolCallJson(match.groupValues[1].trim())
        }

        // Pattern 2: ```tool_call ... ```
        TOOL_CALL_BLOCK_PATTERN.find(output)?.let { match ->
            return parseToolCallJson(match.groupValues[1].trim())
        }

        // Pattern 3: Bare JSON object with "name" key
        JSON_OBJECT_PATTERN.find(output)?.let { match ->
            return parseToolCallJson(match.groupValues[0])
        }

        // Pattern 4: function_call: {...}
        FUNCTION_CALL_PREFIX_PATTERN.find(output)?.let { match ->
            return parseToolCallJson(match.groupValues[1].trim())
        }

        return null
    }

    private fun parseToolCallJson(json: String): ToolExecutionRequest? {
        return try {
            val trimmed = json.trim()
            val map = GSON.fromJson(trimmed, Map::class.java) as? Map<*, *> ?: return null
            val name = map["name"]?.toString() ?: return null
            val args = map["arguments"]
            val argsJson = when (args) {
                is Map<*, *> -> GSON.toJson(args)
                is String -> args
                null -> "{}"
                else -> GSON.toJson(args)
            }
            ToolExecutionRequest.builder()
                .id("bitnet_${System.currentTimeMillis()}")
                .name(name)
                .arguments(argsJson)
                .build()
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to parse tool call JSON: $json", e)
            null
        }
    }

    companion object {
        private const val TAG = "BitNetLlmClient"

        private val STOP_SEQUENCES = listOf("<|im_end|>", "</tool_call>", "<|end|>", "<|eot_id|>")

        private val TOOL_CALL_TAG_PATTERN = Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        private val TOOL_CALL_BLOCK_PATTERN = Regex("""```tool_call\s*\n(.*?)\n\s*```""", RegexOption.DOT_MATCHES_ALL)
        private val JSON_OBJECT_PATTERN = Regex("""\{"name"\s*:\s*"[^"]+""[^}]*\}""", RegexOption.DOT_MATCHES_ALL)
        private val FUNCTION_CALL_PREFIX_PATTERN = Regex("""(?:function_call|tool_call)\s*:\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)

        private const val DEFAULT_BITNET_SYSTEM_PROMPT = """You are Arya, a helpful AI assistant running on an Android phone. You can have conversations, answer questions, and control the user's phone using tools.

When the user asks you to do something on their phone, use the available tools.
When the user is just chatting or asking a question, reply directly with text.

To call a tool, output a JSON block like: {"name": "tool_name", "arguments": {"param": "value"}}

Available tools include: open_app, tap, input_text, system_key, get_screen_info, get_device_info, send_message, get_notifications, clipboard, finish"""
    }
}
