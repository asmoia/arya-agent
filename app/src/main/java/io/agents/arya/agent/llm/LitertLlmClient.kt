// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.llm

/**
 * PHASE 0 FOUNDATION — NPU/GPU inference backend via Google AI Edge LiteRT-LM.
 *
 * This is the single biggest speed lever for "Siri-class" latency:
 *   llama.cpp on CPU  ~ 5-15 tok/s  (current Arya)
 *   LiteRT-LM on NPU  ~ 30-60+ tok/s decode (Qualcomm Hexagon / MediaTek NPU)
 *
 * Implements the existing [LlmClient] contract, so it drops into the current
 * agent loop with no changes to TaskOrchestrator / DefaultAgentService.
 *
 * IMPORTANT (read before shipping):
 *  - Requires the Gradle dependency `com.google.ai.edge.litertlm:litertlm-android`
 *    and a model in `.litertlm` or `.tflite` format (Gemma-4-E2B / Qwen3.5), NOT a GGUF.
 *    GGUF will NOT load in LiteRT-LM. Convert via Google AI Edge / Qualcomm AI Engine Direct.
 *  - LiteRT auto-selects NPU > GPU > CPU per device. NPU needs a model compiled
 *    for that SoC's accelerator (per-device .litertlm, or AICore-delivered).
 *  - Prompt formatting mirrors BitNet's proven compact style. Tune the template
 *    to your specific LiteRT model (Gemma vs Qwen) before QA.
 *  - Tool calling reuses the text-JSON parser (same as BitNet) for safety;
 *    LiteRT-LM's native function-calling API can be adopted later.
 */
class LitertLlmClient(private val config: AgentConfig) : LlmClient {

    private val gson = com.google.gson.Gson()
    private var engine: com.google.ai.edge.litertlm.LlmInference? = null
    private var modelPath: String = ""

    private fun ensureEngine(): com.google.ai.edge.litertlm.LlmInference {
        engine?.let { return it }
        val ctx = io.agents.arya.ClawApplication.getInstance()
        val path = config.baseUrl
        if (path.isBlank()) {
            throw IllegalStateException("LiteRT model path (baseUrl) is empty")
        }
        io.agents.arya.utils.XLog.i(TAG, "LiteRT loading: ${path.takeLast(48)}")
        val options = com.google.ai.edge.litertlm.LlmInferenceOptions.builder()
            .setModelPath(path)
            .setMaxTokens(N_CTX)
            .setTopK(40)
            .setTemperature(config.temperature.coerceIn(0.0, 1.0).toFloat())
            .setRandomSeed(42)
            .build()
        val inst = com.google.ai.edge.litertlm.LlmInference.createFromOptions(ctx, options)
        engine = inst
        modelPath = path
        io.agents.arya.utils.XLog.i(TAG, "LiteRT loaded (NPU/GPU/CPU auto-selected by runtime)")
        return inst
    }

    override fun chat(
        messages: List<dev.langchain4j.data.message.ChatMessage>,
        toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    ): LlmResponse {
        val inst = ensureEngine()
        val prompt = buildPrompt(messages, toolSpecs)
        io.agents.arya.utils.XLog.i(TAG, "chat: prompt=${prompt.length}ch, tools=${toolSpecs.size}")
        val raw = inst.generateResponse(prompt)
            ?: throw IllegalStateException("LiteRT returned null response")
        io.agents.arya.utils.XLog.d(TAG, "raw: ${raw.take(300)}")
        return parseResponse(raw, toolSpecs)
    }

    override fun chatStreaming(
        messages: List<dev.langchain4j.data.message.ChatMessage>,
        toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>,
        listener: StreamingListener
    ): LlmResponse {
        val inst = ensureEngine()
        val prompt = buildPrompt(messages, toolSpecs)
        val sb = StringBuilder()
        try {
            inst.generateResponseAsync(prompt) { partialResult, done ->
                if (partialResult != null) {
                    sb.append(partialResult)
                    listener.onPartialText(partialResult)
                }
                if (done) {
                    // Stream complete — parse final response
                    val raw = sb.toString()
                    val response = parseResponse(raw, toolSpecs)
                    listener.onComplete(response)
                }
            }
        } catch (e: Throwable) {
            listener.onError(e)
            return LlmResponse(text = "Streaming error: ${e.message}", toolExecutionRequests = emptyList())
        }
        // Note: generateResponseAsync is async; the callback handles onComplete.
        // Return a placeholder; the listener callback carries the real result.
        val raw = sb.toString()
        return if (raw.isNotEmpty()) parseResponse(raw, toolSpecs)
        else LlmResponse(text = "", toolExecutionRequests = emptyList())
    }

    override fun close() {
        // Free the NPU/GPU weights immediately when the task ends so the phone
        // does not sit under memory pressure (root cause of crashes/hangs).
        engine?.close()
        engine = null
        io.agents.arya.utils.XLog.i(TAG, "LiteRT engine released")
    }

    // ---- prompt builder (mirrors BitNet's proven compact style) ----
    private fun buildPrompt(
        messages: List<dev.langchain4j.data.message.ChatMessage>,
        toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    ): String {
        val sb = StringBuilder()
        val systemMsg = messages.filterIsInstance<dev.langchain4j.data.message.SystemMessage>()
            .firstOrNull()?.text()
            ?: config.systemPrompt.ifEmpty { COMPACT_PROMPT }
        val effective = if (systemMsg.length > PROMPT_THRESHOLD) {
            io.agents.arya.utils.XLog.w(TAG, "System prompt too long (${systemMsg.length}ch), using compact")
            COMPACT_PROMPT
        } else systemMsg

        sb.append("<|im_start|>system\n").append(effective)
        if (toolSpecs.isNotEmpty()) {
            sb.append("\n\nTOOLS: ").append(toolSpecs.joinToString(", ") { it.name() })
            sb.append("\nCall: {\"name\":\"tool_name\",\"arguments\":{\"key\":\"value\"}}")
        }
        sb.append("<|im_end|>\n")

        if (toolSpecs.isNotEmpty()) {
            // Few-shot examples
            sb.append("<|im_start|>user\nتلگرام رو باز کن<|im_end|>\n")
            sb.append("<|im_start|>assistant\n{\"name\":\"open_app\",\"arguments\":{\"app_name\":\"Telegram\"}}<|im_end|>\n")
            sb.append("<|im_start|>user\nباتری چقدره؟<|im_end|>\n")
            sb.append("<|im_start|>assistant\n{\"name\":\"get_device_info\",\"arguments\":{\"category\":\"battery\"}}<|im_end|>\n")
        }

        for (msg in messages) {
            when (msg) {
                is dev.langchain4j.data.message.SystemMessage -> { /* already handled */ }
                is dev.langchain4j.data.message.UserMessage -> {
                    sb.append("<|im_start|>user\n${msg.singleText()}\n<|im_end|>\n")
                }
                is dev.langchain4j.data.message.AiMessage -> {
                    sb.append("<|im_start|>assistant\n")
                    msg.text()?.let { if (it.isNotBlank()) sb.append(it) }
                    msg.toolExecutionRequests()?.firstOrNull()?.let {
                        sb.append("{\"name\":\"${it.name()}\",\"arguments\":${it.arguments()}}")
                    }
                    sb.append("<|im_end|>\n")
                }
                is dev.langchain4j.data.message.ToolExecutionResultMessage -> {
                    val text = if (msg.text().length > 300) {
                        msg.text().take(300) + "..."
                    } else {
                        msg.text()
                    }
                    sb.append("<|im_start|>user\n[Result]: $text<|im_end|>\n")
                }
                else -> { /* skip other message types */ }
            }
        }

        if (toolSpecs.isNotEmpty()) sb.append("<|im_start|>assistant\n{")
        else sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    // ---- tool-call parser (same multi-pattern approach as BitNet) ----
    private fun parseResponse(
        raw: String,
        toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    ): LlmResponse {
        val trimmed = raw.trim()
        val validTools = toolSpecs.map { it.name() }.toSet()
        val forParsing = if (toolSpecs.isNotEmpty() && !trimmed.startsWith("{")) "{$trimmed" else trimmed

        for (match in JSON_NAME.findAll(forParsing)) {
            val req = parseJson(match.value)
            if (req != null && req.name() in validTools) {
                io.agents.arya.utils.XLog.i(TAG, "Tool: ${req.name()}")
                return LlmResponse(text = null, toolExecutionRequests = listOf(req))
            }
        }
        if (toolSpecs.isNotEmpty()) {
            for (name in validTools) {
                if (trimmed.contains(name, ignoreCase = true)) {
                    io.agents.arya.utils.XLog.i(TAG, "Recovery: found '$name'")
                    return LlmResponse(
                        text = null,
                        toolExecutionRequests = listOf(
                            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                                .id("lr_${System.currentTimeMillis()}")
                                .name(name)
                                .arguments("{}")
                                .build()
                        )
                    )
                }
            }
        }
        return LlmResponse(text = trimmed, toolExecutionRequests = emptyList())
    }

    private fun parseJson(json: String): dev.langchain4j.agent.tool.ToolExecutionRequest? {
        return try {
            var fixed = json.trim()
            val ob = fixed.count { it == '{' }
            val cb = fixed.count { it == '}' }
            repeat(ob - cb) { fixed += "}" }
            @Suppress("UNCHECKED_CAST")
            val map = gson.fromJson(fixed, Map::class.java) as? Map<*, *> ?: return null
            val name = map["name"]?.toString() ?: return null
            val args = map["arguments"] ?: map["args"] ?: map["params"]
            val argsJson = when (args) {
                is Map<*, *> -> gson.toJson(args)
                is String -> args
                null -> "{}"
                else -> gson.toJson(args)
            }
            dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("lr_${System.currentTimeMillis()}")
                .name(name)
                .arguments(argsJson)
                .build()
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "LitertLlmClient"
        private const val N_CTX = 2048
        private const val PROMPT_THRESHOLD = 1400
        private const val COMPACT_PROMPT =
            "You are Arya, a helpful on-device Android assistant. Use tools when the user wants phone actions."
        private val JSON_NAME = Regex("""\{[^{}]*"name"\s*:\s*"[^"]+"[^{}]*\}""")
    }
}
