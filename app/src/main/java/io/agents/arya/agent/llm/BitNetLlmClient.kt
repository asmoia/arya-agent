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
 * LlmClient implementation using llama.cpp via [BitNetNative] JNI.
 *
 * Key design decisions for small on-device models (1-3B):
 *
 * 1. **Compact prompt format** — ChatML (compatible with Qwen, Phi, etc.)
 * 2. **Simple tool calling convention** — JSON block in output, not XML tags.
 *    Small models struggle with XML. A simple {"name":"x","arguments":{}}
 *    is far more reliable.
 * 3. **Strict tool-only responses** — when tools are available, the model
 *    MUST call a tool or say it doesn't know. No free-form essays.
 * 4. **Robust parsing** — handles malformed JSON, extra text, markdown
 *    fences, and partial tool calls gracefully.
 * 5. **Stateless** — full prompt rebuilt each call, no session state issues.
 */
class BitNetLlmClient(private val config: AgentConfig) : LlmClient {

    private val GSON = Gson()
    private var modelHandle: Long = 0L
    private var modelPath: String = ""

    // ---- Model lifecycle ----

    private fun ensureModel(): Long {
        if (modelHandle > 0) return modelHandle
        if (!BitNetNative.isAvailable()) {
            throw IllegalStateException("BitNet native library not available: ${BitNetNative.lastLoadError()}")
        }
        val path = config.baseUrl
        if (path.isBlank()) throw IllegalStateException("GGUF model path is empty")
        // nCtx=2048: 1024 was too small — system prompt + tool specs alone consume ~800 tokens,
        // leaving only ~100 for chat+generation which caused immediate "done" responses.
        val handle = BitNetNative.loadModel(path, nCtx = 2048, nThreads = 0)
        if (handle <= 0) throw IllegalStateException("Failed to load GGUF model: $path (handle=$handle)")
        modelHandle = handle
        modelPath = path
        XLog.i(TAG, "Model loaded: $path handle=$handle")
        return handle
    }

    // ---- LlmClient ----

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        val handle = ensureModel()
        val prompt = buildPrompt(messages, toolSpecs)
        val toolNames = toolSpecs.map { it.name() }
        XLog.i(TAG, "═══ CHAT START ═══")
        XLog.i(TAG, "  prompt_len=${prompt.length} chars, tools=${toolSpecs.size} toolNames=$toolNames")
        XLog.i(TAG, "  temperature=${config.temperature} maxTokens=512 nCtx=2048")
        // Log full prompt for debugging (first 2000 chars)
        XLog.i(TAG, "  PROMPT_START>>>\n${prompt.take(2000)}\n<<<PROMPT_END")

        val t0 = System.currentTimeMillis()
        val rawOutput = BitNetNative.completion(
            handle = handle,
            prompt = prompt,
            maxTokens = 512,
            temperature = config.temperature.coerceIn(0.0, 1.0).toFloat(),
            topP = 0.95f,
            topK = 20,
            repeatPenalty = 1.1f,
            stopSequences = GSON.toJson(STOP_SEQUENCES),
        ) ?: throw IllegalStateException("GGUF completion returned null")

        val elapsed = System.currentTimeMillis() - t0
        XLog.i(TAG, "  RAW_OUTPUT (${rawOutput.length} chars, ${elapsed}ms):")
        XLog.i(TAG, "  >>>${rawOutput.take(500)}<<<")

        val response = parseResponse(rawOutput, toolSpecs)
        XLog.i(TAG, "  PARSED: hasTools=${response.hasToolExecutionRequests()} " +
            "toolCalls=${response.toolExecutionRequests?.map { "${it.name()}(${it.arguments()?.take(60)})" }} " +
            "text=${response.text?.take(100)}")
        XLog.i(TAG, "═══ CHAT END (${elapsed}ms) ═══")
        return response
    }

    override fun chatStreaming(
        messages: List<ChatMessage>,
        toolSpecs: List<ToolSpecification>,
        listener: StreamingListener,
    ): LlmResponse {
        val response = chat(messages, toolSpecs)
        val text = response.text
        if (text != null) {
            listener.onPartialText(text)
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

    // ---- Prompt formatting (ChatML) ----

    /**
     * Build a ChatML prompt optimized for small on-device models.
     *
     * Key optimizations over a generic prompt:
     * - Tool names and descriptions only (no full JSON schema — too verbose)
     * - Explicit instruction: "output ONLY a JSON block to call a tool"
     * - Example tool call in the system prompt so the model has a template
     * - Short, direct language
     */
    private fun buildPrompt(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): String {
        val sb = StringBuilder()

        // System message
        val systemMsg = messages.filterIsInstance<SystemMessage>().firstOrNull()?.text()
            ?: config.systemPrompt.ifEmpty { DEFAULT_SYSTEM_PROMPT }

        sb.append("<|im_start|>system\n")
        sb.append(systemMsg)

        // Tool declarations — compact format for small models
        if (toolSpecs.isNotEmpty()) {
            sb.append("\n\n--- TOOLS ---\n")
            sb.append("You MUST use tools to interact with the phone. To call a tool, output EXACTLY this format:\n")
            sb.append("{\"name\": \"tool_name\", \"arguments\": {\"param\": \"value\"}}\n")
            sb.append("Do NOT add extra text before or after the JSON.\n")
            sb.append("If you don't need any tool, just reply with text.\n\n")
            sb.append("Available tools:\n")
            for (spec in toolSpecs) {
                val params = spec.parameters()
                val paramList = params?.properties()?.entries?.joinToString(", ") { (name, schema) ->
                    name
                } ?: ""
                sb.append("- ${spec.name()}")
                if (paramList.isNotBlank()) sb.append("($paramList)")
                val desc = spec.description()
                if (!desc.isNullOrBlank()) sb.append(": $desc")
                sb.append("\n")
            }
        }

        sb.append("<|im_end|>\n")

        // Chat history
        for (msg in messages) {
            when (msg) {
                is SystemMessage -> { /* handled above */ }
                is UserMessage -> {
                    sb.append("<|im_start|>user\n${msg.singleText()}<|im_end|>\n")
                }
                is AiMessage -> {
                    sb.append("<|im_start|>assistant\n")
                    val text = msg.text()
                    if (!text.isNullOrBlank()) {
                        sb.append(text)
                    }
                    msg.toolExecutionRequests()?.firstOrNull()?.let { req ->
                        sb.append("{\"name\": \"${req.name()}\", \"arguments\": ${req.arguments()}}")
                    }
                    sb.append("<|im_end|>\n")
                }
                is ToolExecutionResultMessage -> {
                    val reduced = if (msg.text().length > 500) {
                        msg.text().take(500) + "..."
                    } else {
                        msg.text()
                    }
                    sb.append("<|im_start|>user\n[Result of ${msg.toolName()}]: $reduced<|im_end|>\n")
                }
            }
        }

        // Start assistant turn
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    // ---- Response parsing ----

    /**
     * Parse the model output into an LlmResponse.
     *
     * Strategy:
     * 1. Try to find a JSON tool call in the output
     * 2. Validate the tool name against the available tool specs
     * 3. If no valid tool call found, treat as plain text
     *
     * This is much more robust than the old approach because:
     * - It validates tool names against actual available tools
     * - It handles partial/malformed JSON gracefully
     * - It strips markdown fences, extra text, etc.
     */
    private fun parseResponse(rawOutput: String, toolSpecs: List<ToolSpecification>): LlmResponse {
        val trimmed = rawOutput.trim()

        // Build a set of valid tool names for validation
        val validToolNames = toolSpecs.map { it.name() }.toSet()

        // Try to extract and validate a tool call
        val toolCall = extractAndValidateToolCall(trimmed, validToolNames)
        if (toolCall != null) {
            XLog.i(TAG, "Tool call: ${toolCall.name()} args=${toolCall.arguments().take(100)}")
            return LlmResponse(
                text = null,
                toolExecutionRequests = listOf(toolCall),
            )
        }

        // Plain text response
        XLog.d(TAG, "Text response: ${trimmed.take(100)}")
        return LlmResponse(
            text = trimmed,
            toolExecutionRequests = emptyList(),
        )
    }

    /**
     * Extract a tool call from model output, with multiple fallback patterns.
     * Only returns a ToolExecutionRequest if the tool name is in [validToolNames].
     */
    private fun extractAndValidateToolCall(output: String, validToolNames: Set<String>): ToolExecutionRequest? {
        // Try all patterns in order of specificity
        val candidates = mutableListOf<Pair<String, String>>() // (source, json)

        // Pattern 1: <tool_call>...</tool_call>
        TOOL_CALL_TAG.find(output)?.let { match ->
            candidates.add("tag" to match.groupValues[1].trim())
        }

        // Pattern 2: ```tool_call\n...\n```  or  ```json\n...\n```
        TOOL_CALL_BLOCK.find(output)?.let { match ->
            candidates.add("block" to match.groupValues[1].trim())
        }

        // Pattern 3: Bare JSON with "name" key — find the outermost { }
        // This is the most common pattern from small models
        for (match in JSON_WITH_NAME.findAll(output)) {
            candidates.add("json" to match.value)
        }

        // Pattern 4: function_call: or tool_call: prefix
        FUNC_CALL_PREFIX.find(output)?.let { match ->
            candidates.add("prefix" to match.groupValues[1].trim())
        }

        // Try to parse each candidate
        for ((source, json) in candidates) {
            val request = parseToolCallJson(json)
            if (request != null && request.name() in validToolNames) {
                XLog.d(TAG, "Tool call found via $source pattern: ${request.name()}")
                return request
            }
            if (request != null) {
                XLog.w(TAG, "Tool call '${request.name()}' not in valid tools: $validToolNames (source=$source)")
            }
        }

        // Last resort: try to find a tool name directly mentioned in the output
        // Sometimes small models output: "I will call open_app" or "Calling: tap_node"
        for (toolName in validToolNames) {
            // Look for patterns like "call open_app" or "use open_app" or "execute open_app"
            val directPattern = Regex("""(?:call|use|execute|invoke|run)\s+$toolName""", RegexOption.IGNORE_CASE)
            if (directPattern.containsMatchIn(output)) {
                XLog.d(TAG, "Direct tool mention found: $toolName")
                return ToolExecutionRequest.builder()
                    .id("bitnet_${System.currentTimeMillis()}")
                    .name(toolName)
                    .arguments("{}")
                    .build()
            }
        }

        return null
    }

    private fun parseToolCallJson(json: String): ToolExecutionRequest? {
        return try {
            val trimmed = json.trim()
            // Auto-close missing braces
            var fixedJson = trimmed
            val openBraces = fixedJson.count { it == '{' }
            val closeBraces = fixedJson.count { it == '}' }
            repeat(openBraces - closeBraces) { fixedJson += "}" }

            val map = GSON.fromJson(fixedJson, Map::class.java) as? Map<*, *> ?: return null
            val name = map["name"]?.toString() ?: return null
            val args = map["arguments"] ?: map["args"] ?: map["params"]
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
            XLog.w(TAG, "Failed to parse tool call JSON: ${json.take(100)}", e)
            null
        }
    }

    companion object {
        private const val TAG = "BitNetLlmClient"

        private val STOP_SEQUENCES = listOf("<|im_end|>", "</tool_call>", "<|end|>", "<|eot_id|>")

        // Pattern 1: <tool_call>{...}</tool_call>
        private val TOOL_CALL_TAG = Regex("""<tool_call>(.*?)</tool_call>""", RegexOption.DOT_MATCHES_ALL)

        // Pattern 2: ```tool_call\n...\n```  or  ```json\n{...}\n```
        private val TOOL_CALL_BLOCK = Regex("""```(?:tool_call|json)\s*\n(.*?)\n\s*```""", RegexOption.DOT_MATCHES_ALL)

        // Pattern 3: Bare JSON with "name" key — balanced brace search
        private val JSON_WITH_NAME = Regex("""\{[^{}]*"name"\s*:\s*"[^"]+?"[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)

        // Pattern 4: function_call: or tool_call: prefix
        private val FUNC_CALL_PREFIX = Regex("""(?:function_call|tool_call|call)\s*:\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)

        private const val DEFAULT_SYSTEM_PROMPT = """You are Arya, a phone assistant. You help users control their Android phone.

RULES:
- To control the phone, you MUST call a tool. Output ONLY: {"name": "tool_name", "arguments": {"key": "value"}}
- For normal questions, just answer with text.
- ONE tool per response. Then wait for the result.
- Do NOT guess tool results. Do NOT make up screen contents.

Tool examples:
- Open an app: {"name": "open_app", "arguments": {"app_name": "WhatsApp"}}
- Tap something: {"name": "tap_node", "arguments": {"node_id": "n3"}}
- Type text: {"name": "input_text", "arguments": {"text": "hello"}}
- Go back: {"name": "system_key", "arguments": {"key": "back"}}
- Read screen: {"name": "get_screen_info", "arguments": {}}
- Send message: {"name": "send_message", "arguments": {"contact": "Ali", "message": "salam"}}
- Check battery: {"name": "get_device_info", "arguments": {"category": "battery"}}
- Task done: {"name": "finish", "arguments": {"summary": "Task completed"}}"""
    }
}
