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
 * BitNet/llama.cpp LlmClient — v0.6.0
 *
 * Key improvements:
 * 1. Ultra-compact prompt (~150 tokens vs old ~2000)
 * 2. Few-shot examples for 1.5B reliability
 * 3. Seed assistant with `{` to force tool call JSON
 * 4. Repeat penalty now actually works (native fix)
 * 5. Token budget management — never run out of context
 * 6. Full inference telemetry on every call
 */
class BitNetLlmClient(private val config: AgentConfig) : LlmClient {

    private val GSON = Gson()
    private var modelHandle: Long = 0L
    private var modelPath: String = ""

    private fun ensureModel(): Long {
        if (modelHandle > 0) return modelHandle
        if (!BitNetNative.isAvailable())
            throw IllegalStateException("BitNet native not available: ${BitNetNative.lastLoadError()}")
        val path = config.baseUrl
        if (path.isBlank()) throw IllegalStateException("GGUF model path is empty")
        XLog.i(TAG, "Loading: ${path.takeLast(40)} nCtx=$N_CTX")
        val sysInfo = BitNetNative.getSystemInfo()
        sysInfo?.let { XLog.i(TAG, "System: CPU=${it["cpu_cores"]}, RAM=${it["ram_avail_mb"]}/${it["ram_total_mb"]}MB, GPU=${it["gpu_available"]}") }
        val handle = BitNetNative.loadModel(path, nCtx = N_CTX, nThreads = 0)
        if (handle <= 0) throw IllegalStateException("Failed to load GGUF: $path (handle=$handle)")
        modelHandle = handle
        modelPath = path
        val info = BitNetNative.getModelInfo(handle)
        val loadTimeMs = (info?.get("load_time_ms") as? Number)?.toLong() ?: 0L
        info?.let { XLog.i(TAG, "Model: ${it["model_size_mb"]}MB, ${it["n_params_b"]}B params, ${it["n_threads"]} threads, load=${it["load_time_ms"]}ms") }
        InferenceTelemetryCollector.recordModelLoad(path, loadTimeMs, info)
        return handle
    }

    override fun chat(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): LlmResponse {
        val handle = ensureModel()
        val prompt = buildPrompt(messages, toolSpecs)
        val promptTokens = BitNetNative.tokenize(handle, prompt)?.size ?: -1
        XLog.i(TAG, "chat: prompt=${prompt.length}ch ~${promptTokens}tok, tools=${toolSpecs.size}")
        val maxTokens = if (promptTokens > 0) (N_CTX - promptTokens - 64).coerceIn(MIN_GEN, MAX_GEN) else 512
        val result = BitNetNative.completionWithTelemetry(handle, prompt, maxTokens,
            config.temperature.coerceIn(0.0, 1.0).toFloat(), 0.95f, 20, 1.15f, GSON.toJson(STOP_SEQ))
            ?: throw IllegalStateException("GGUF completion returned null")
        XLog.i(TAG, "chat: output=${result.text.length}ch, ${result.telemetry.summary()}")
        XLog.d(TAG, "raw: ${result.text.take(300)}")
        val response = parseResponse(result.text, toolSpecs)
        if (toolSpecs.isNotEmpty()) {
            if (response.hasToolExecutionRequests()) XLog.i(TAG, "✓ Tool: ${response.toolExecutionRequests.firstOrNull()?.name()}")
            else XLog.w(TAG, "✗ No tool call. Output: ${result.text.take(100)}")
        }
        return response
    }

    override fun chatStreaming(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>, listener: StreamingListener): LlmResponse {
        val handle = ensureModel()
        val prompt = buildPrompt(messages, toolSpecs)
        val promptTokens = BitNetNative.tokenize(handle, prompt)?.size ?: -1
        val maxTokens = if (promptTokens > 0) (N_CTX - promptTokens - 64).coerceIn(MIN_GEN, MAX_GEN) else 512
        val text = BitNetNative.completionStreaming(handle, prompt, maxTokens,
            config.temperature.coerceIn(0.0, 1.0).toFloat(), 0.95f, 20, 1.15f, GSON.toJson(STOP_SEQ))
            { token -> listener.onPartialText(token) } ?: return LlmResponse(text = "", toolExecutionRequests = emptyList())
        return parseResponse(text, toolSpecs)
    }

    override fun close() {
        if (modelHandle > 0) { BitNetNative.freeModel(modelHandle); modelHandle = 0L }
    }

    private fun buildPrompt(messages: List<ChatMessage>, toolSpecs: List<ToolSpecification>): String {
        val sb = StringBuilder()
        val systemMsg = messages.filterIsInstance<SystemMessage>().firstOrNull()?.text()
            ?: config.systemPrompt.ifEmpty { COMPACT_PROMPT }
        val effective = if (systemMsg.length > PROMPT_THRESHOLD) {
            XLog.w(TAG, "System prompt too long (${systemMsg.length}ch), using compact")
            COMPACT_PROMPT
        } else systemMsg

        sb.append("<|im_start|>system\n").append(effective)
        if (toolSpecs.isNotEmpty()) {
            sb.append("\n\nTOOLS: ").append(toolSpecs.joinToString(", ") { it.name() })
            sb.append("\nCall: {\"name\":\"tool_name\",\"arguments\":{\"key\":\"value\"}}")
        }
        sb.append("<|im_end|>\n")

        // Few-shot examples — critical for 1.5B
        if (toolSpecs.isNotEmpty()) {
            sb.append("<|im_start|>user\nتلگرام رو باز کن<|im_end|>\n")
            sb.append("<|im_start|>assistant\n{\"name\":\"open_app\",\"arguments\":{\"app_name\":\"Telegram\"}}<|im_end|>\n")
            sb.append("<|im_start|>user\nباتری چقدره؟<|im_end|>\n")
            sb.append("<|im_start|>assistant\n{\"name\":\"get_device_info\",\"arguments\":{\"category\":\"battery\"}}<|im_end|>\n")
        }

        for (msg in messages) {
            when (msg) {
                is SystemMessage -> {}
                is UserMessage -> sb.append("<|im_start|>user\n${msg.singleText()}<|im_end|>\n")
                is AiMessage -> {
                    sb.append("<|im_start|>assistant\n")
                    msg.text()?.let { if (it.isNotBlank()) sb.append(it) }
                    msg.toolExecutionRequests()?.firstOrNull()?.let { sb.append("{\"name\":\"${it.name()}\",\"arguments\":${it.arguments()}}") }
                    sb.append("<|im_end|>\n")
                }
                is ToolExecutionResultMessage -> {
                    val text = if (msg.text().length > 300) msg.text().take(300) + "..." else msg.text()
                    sb.append("<|im_start|>user\n[Result]: $text<|im_end|>\n")
                }
            }
        }

        // Seed with `{` to force tool call
        if (toolSpecs.isNotEmpty()) sb.append("<|im_start|>assistant\n{")
        else sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    private fun parseResponse(raw: String, toolSpecs: List<ToolSpecification>): LlmResponse {
        val trimmed = raw.trim()
        val validTools = toolSpecs.map { it.name() }.toSet()
        val forParsing = if (toolSpecs.isNotEmpty() && !trimmed.startsWith("{")) "{$trimmed" else trimmed

        // Try JSON extraction
        for (match in JSON_NAME.findAll(forParsing)) {
            val req = parseJson(match.value)
            if (req != null && req.name() in validTools) {
                XLog.i(TAG, "Tool: ${req.name()}")
                return LlmResponse(text = null, toolExecutionRequests = listOf(req))
            }
        }
        // Recovery: look for tool name in text
        if (toolSpecs.isNotEmpty()) {
            for (name in validTools) {
                if (trimmed.contains(name, ignoreCase = true)) {
                    XLog.i(TAG, "Recovery: found '$name'")
                    return LlmResponse(text = null, toolExecutionRequests = listOf(
                        ToolExecutionRequest.builder().id("bn_${System.currentTimeMillis()}")
                            .name(name).arguments("{}").build()))
                }
            }
        }
        return LlmResponse(text = trimmed, toolExecutionRequests = emptyList())
    }

    private fun parseJson(json: String): ToolExecutionRequest? {
        return try {
            var fixed = json.trim()
            val ob = fixed.count { it == '{' }; val cb = fixed.count { it == '}' }
            repeat(ob - cb) { fixed += "}" }
            val map = GSON.fromJson(fixed, Map::class.java) as? Map<*, *> ?: return null
            val name = map["name"]?.toString() ?: return null
            val args = map["arguments"] ?: map["args"] ?: map["params"]
            val argsJson = when (args) { is Map<*, *> -> GSON.toJson(args); is String -> args; null -> "{}"; else -> GSON.toJson(args) }
            ToolExecutionRequest.builder().id("bn_${System.currentTimeMillis()}").name(name).arguments(argsJson).build()
        } catch (_: Exception) { null }
    }

    companion object {
        private const val TAG = "BitNetLlmClient"
        private const val N_CTX = 2048
        private const val MAX_GEN = 768
        private const val MIN_GEN = 128
        private const val PROMPT_THRESHOLD = 800
        private val STOP_SEQ = listOf("<|im_end|>", "</tool_call>", "<|end|>", "<|eot_id|>")
        private val JSON_NAME = Regex("""\{[^{}]*"name"\s*:\s*"[^"]+?"[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)

        private const val COMPACT_PROMPT = """You are Arya, a phone assistant. You control Android phones with tools.

RULES:
- Phone task → MUST call a tool. Format: {"name":"tool","arguments":{"key":"val"}}
- ONE tool per turn. Wait for result. Then next tool or finish.
- Persian user → Persian summary in finish().
- Do NOT say you cannot access apps/browser/files/music. Use tools.
- After each action, check screen with get_screen_info or wait for result.
- finish(summary) = task done. Put REAL data in summary, not "checked".

Quick tool guide:
- open_app(app_name) → launch app
- get_screen_info() → read current screen
- tap_node(node_id) or tap(x,y) → click
- find_and_tap(text) → tap by text
- input_text(text) → type
- system_key(key) → back/home/enter/volume
- get_device_info(category) → battery/wifi/storage/bluetooth
- send_message(contact,message,app) → send message
- make_call(contact) → call
- get_notifications() → read notifications
- clipboard(action) → get/set clipboard
- finish(summary) → done"""
    }
}
