package io.agents.arya.agent.llm

import android.content.Context
import io.agents.arya.agent.AgentConfig
import io.agents.arya.engine.EngineClient
import io.agents.arya.engine.EngineError
import io.agents.arya.engine.EngineEvent
import io.agents.arya.engine.EngineRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import io.agents.arya.utils.XLog
import kotlin.coroutines.resume

/**
 * Main-process local client. Never loads natives — all work goes over AIDL.
 */
class LocalLlmClient(
    @Suppress("unused") private val context: Context,
    private val config: AgentConfig,
    private val engineClient: EngineClient,
) : LlmClient {

    companion object {
        private const val TAG = "LocalLlmClient"

        /** Generation budget shared with the request built below. */
        const val MAX_TOKENS = 192

        /**
         * On-device inference window. Kept at 2048 (not larger) so the planner
         * reliably supplies it even on constrained devices, and the 270M model
         * stays crisp. LocalPromptBudget trims history/tools to fit this, so a
         * prompt never overflows the window with "prompt_exceeds_ctx".
         */
        const val DEFAULT_CTX = 2048
    }

    override fun chatStream(messages: List<ChatMsg>, tools: List<ToolSpec>): Flow<LlmEvent> = flow {
        var modelPath = config.baseUrl
        if (modelPath.isEmpty()) {
            emit(LlmEvent.Error(2, "No local model path configured"))
            return@flow
        }
        if (LocalModelManager.oemKillsHeavyLocalModels() && isKnownHeavyQwen(modelPath)) {
            emit(LlmEvent.Status("Huawei/Honor: using a small on-device model (1.7B is killed by EMUI)."))
            val small = ensureSmallFallback()
            if (small.isNullOrBlank()) {
                emit(LlmEvent.Error(3, "Download FunctionGemma 270M or Qwen3 0.6B. EMUI kills the 1.7B engine after load."))
                return@flow
            }
            val smallId = if (small.contains("functiongemma", ignoreCase = true)) "functiongemma-270m" else "qwen3-0.6b"
            ModelConfigRepository.saveLocalDefault(small, smallId, activateNow = true)
            modelPath = small
        }
        val promptMessages = if (messages.any { it.role == Role.SYSTEM }) {
            messages
        } else {
            listOf(
                ChatMsg(
                    Role.SYSTEM,
                    "You are Arya, a concise on-device assistant. Answer in a few short sentences. Never write <think> tags.",
                ),
            ) + messages
        }
        val useGemma = isFunctionGemma(modelPath)

        // Size the window from the *actual* prompt and trim history/tools so it
        // always fits. Previously the engine loaded ctx=1024 while the prompt
        // was 1100–4600 tokens → every generation died with prompt_exceeds_ctx.
        val prepared = LocalPromptBudget.prepare(
            messages = promptMessages,
            tools = tools,
            gemma = useGemma,
            ctxSize = DEFAULT_CTX,
            maxTokens = MAX_TOKENS,
        )
        val trimmedMessages = prepared.messages
        val trimmedTools = prepared.tools
        val promptText = if (useGemma) {
            FunctionGemmaPrompt.build(trimmedMessages, trimmedTools)
        } else {
            ChatMlPrompt.build(trimmedMessages, trimmedTools, enableThinking = false)
        }
        try {
            emit(LlmEvent.Status("Starting local engine… reading weights into RAM"))
            XLog.i(TAG, "ensureLoaded $modelPath ctx=${prepared.ctxSize}")
            withTimeout(240_000L) {
                engineClient.ensureLoaded(modelPath, ctxSize = prepared.ctxSize)
            }
            emit(LlmEvent.Status("Model in RAM. Writing…"))
        } catch (e: Exception) {
            XLog.e(TAG, "ensureLoaded failed", e)
            val fallback = if (isKnownHeavyQwen(modelPath)) {
                emit(LlmEvent.Status("1.7B engine failed. Preparing Qwen3 0.6B fallback…"))
                ensureSmallFallback()
            } else {
                null
            }
            if (fallback.isNullOrBlank()) {
                emit(LlmEvent.Error(3, "Local engine could not load this model safely. Qwen3 0.6B fallback is unavailable. (${e.message})"))
                return@flow
            }
            try {
                ModelConfigRepository.saveLocalDefault(fallback, "qwen3-0.6b", activateNow = true)
                withTimeout(240_000L) {
                    engineClient.ensureLoaded(fallback, ctxSize = prepared.ctxSize, nThreads = 2)
                }
                emit(LlmEvent.Status("Qwen3 0.6B ready. Writing…"))
            } catch (fallbackError: Exception) {
                XLog.e(TAG, "fallback ensureLoaded failed", fallbackError)
                emit(LlmEvent.Error(3, "Neither the selected model nor Qwen3 0.6B could load safely. (${fallbackError.message})"))
                return@flow
            }
        }

        val assembler = StreamAssembler()
        XLog.i(TAG, "generate template=${if (useGemma) "functiongemma" else "chatml"} promptChars=${promptText.length} tools=${trimmedTools.size} ctx=${prepared.ctxSize}")
        io.agents.arya.engine.EngineLog.breadcrumb(
            TAG,
            "generate template=${if (useGemma) "functiongemma" else "chatml"} path=${modelPath.takeLast(80)} chars=${promptText.length} tools=${trimmedTools.size} ctx=${prepared.ctxSize} head=${promptText.take(180).replace('\n', ' ')}",
        )
        val req = EngineRequest(
            prompt = promptText,
            promptMode = "full",
            maxTokens = MAX_TOKENS,
            temperature = 0.2,
            topP = 0.9,
            topK = 20,
            stop = if (isFunctionGemma(modelPath)) {
                listOf("<end_of_turn>", "<end_function_call>", "<start_function_response>")
            } else {
                listOf("<|im_end|>", "</tool_call>")
            },
            deadlineMs = 90_000L,
            tokenDeadlineMs = 12_000L,
        )
        suspend fun generateOnce(target: StreamAssembler): Throwable? {
            return try {
                withTimeout(120_000L) {
                    engineClient.generate(req).collect { engineEv ->
                        when (engineEv) {
                            is EngineEvent.Delta -> target.feed(engineEv.text).forEach { emit(it) }
                            is EngineEvent.Done -> target.finish().forEach { emit(it) }
                            is EngineEvent.Failed -> throw GenerationFailure(engineEv.code, engineEv.message)
                            is EngineEvent.LoadProgress -> emit(LlmEvent.Status(engineEv.phase.ifBlank { "Loading…" }))
                        }
                    }
                }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }

        val generationError = generateOnce(assembler)
        if (generationError == null) return@flow

        XLog.e(TAG, "generate failed", generationError)
        if (generationError !is GenerationFailure || generationError.code != EngineError.ERR_NATIVE) {
            val code = (generationError as? GenerationFailure)?.code ?: 5
            emit(LlmEvent.Error(code, "Local model generation failed safely. (${generationError.message})"))
            return@flow
        }
        if (!isKnownHeavyQwen(modelPath)) {
            emit(LlmEvent.Error(5, "Local model generation failed safely. (${generationError.message})"))
            return@flow
        }

        emit(LlmEvent.Status("1.7B failed during inference. Switching to Qwen3 0.6B…"))
        val fallback = ensureSmallFallback()
        if (fallback.isNullOrBlank()) {
            emit(LlmEvent.Error(5, "The 1.7B model ran out of memory during inference and Qwen3 0.6B is unavailable. Download the 0.6B model and try again."))
            return@flow
        }
        try {
            ModelConfigRepository.saveLocalDefault(fallback, "qwen3-0.6b", activateNow = true)
            emit(LlmEvent.Status("Loading Qwen3 0.6B fallback…"))
            withTimeout(240_000L) {
                engineClient.ensureLoaded(fallback, ctxSize = prepared.ctxSize, nThreads = 2)
            }
            emit(LlmEvent.Status("Qwen3 0.6B ready. Retrying this request…"))
            val fallbackError = generateOnce(StreamAssembler())
            if (fallbackError != null) throw fallbackError
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            XLog.e(TAG, "fallback generation failed", e)
            emit(LlmEvent.Error(5, "Qwen3 0.6B also could not generate a response safely. (${e.message})"))
        }
            }.flowOn(Dispatchers.IO)

    private fun isKnownHeavyQwen(path: String): Boolean =
        path.endsWith("Qwen_Qwen3-1.7B-Q4_K_M.gguf", ignoreCase = true) ||
            path.contains("qwen3-1.7b", ignoreCase = true)

    private fun isFunctionGemma(path: String): Boolean =
        path.contains("functiongemma", ignoreCase = true)

    private suspend fun ensureSmallFallback(): String? {
        val preferred = listOf("functiongemma-270m", "qwen3-0.6b")
        for (id in preferred) {
            val model = LocalModelManager.AVAILABLE_MODELS.firstOrNull { it.id == id } ?: continue
            LocalModelManager.getModelPath(context, model)?.let { return it }
        }
        val model = LocalModelManager.AVAILABLE_MODELS.firstOrNull { it.id == "functiongemma-270m" }
            ?: LocalModelManager.AVAILABLE_MODELS.firstOrNull { it.id == "qwen3-0.6b" }
            ?: return null
        LocalModelManager.getModelPath(context, model)?.let { return it }
        return suspendCancellableCoroutine { continuation ->
            LocalModelManager.downloadModel(context, model, object : LocalModelManager.DownloadCallback {
                override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) = Unit

                override fun onComplete(modelPath: String) {
                    if (continuation.isActive) continuation.resume(modelPath)
                }

                override fun onError(error: String) {
                    XLog.e(TAG, "0.6B fallback download failed: $error")
                    if (continuation.isActive) continuation.resume(null)
                }
            })
        }
    }

    private class GenerationFailure(val code: Int, message: String) : IllegalStateException(message)

    override fun chatSync(messages: List<ChatMsg>, tools: List<ToolSpec>): LlmResponse {
        var fullText = ""
        val toolCallsList = mutableListOf<ToolCallSpec>()
        runBlocking {
            chatStream(messages, tools).collect { event ->
                when (event) {
                    is LlmEvent.Text -> if (!event.isReasoning) fullText += event.delta
                    is LlmEvent.ToolCall -> toolCallsList += ToolCallSpec(
                        id = "local_${System.currentTimeMillis()}",
                        name = event.name,
                        argumentsJson = event.argsJson,
                    )
                    is LlmEvent.Error -> throw IllegalStateException(event.message)
                    else -> {}
                }
            }
        }
        return LlmResponse(
            text = fullText.ifEmpty { null },
            toolCalls = toolCallsList,
            modelName = config.modelName.ifEmpty { config.baseUrl },
        )
    }
}

/**
 * Trims a chat history + tool list so the rendered prompt always fits the
 * engine's context window, and reports the window that should be requested.
 *
 * Why this exists: the engine refuses to generate whenever `n_prompt >= n_ctx`.
 * On the customer's Kirin device the planner used to load ctx=1024 while the
 * FunctionGemma prompt (system + 9 tools) tokenized to ~1.2k and the full
 * agent prompt (29 tools) to ~4.6k, so *every* message failed immediately with
 * "prompt_exceeds_ctx". We now (a) request a window large enough for the prompt
 * and (b) drop oldest turns / shrink the tool list so the prompt can never
 * overflow whichever window we end up with.
 */
object LocalPromptBudget {
    private const val RESERVE_TOKENS = 64
    private const val TOKENS_PER_CHAR = 0.28
    private const val TOKEN_OVERHEAD = 32
    private const val MAX_TOOLS_LOCAL = 12
    private const val MIN_TOOLS_LOCAL = 3

    /**
     * On-device agents must be able to drive the phone they are controlling.
     * These tools are registered *after* the generic set, so a naive tools.take()
     * in registry order silently dropped every one of them (tap/swipe/scroll,
     * send_message, open_messaging_chat, telegram_read_chat …) — which is why the
     * model could never call the Telegram reader the user asked for. We keep this
     * ordered set first, then fill the budget with the remaining tools.
     */
    // Ordered by how essential a tool is for driving the phone on-device. The
    // first MAX_TOOLS_LOCAL entries are what the model actually sees; the rest
    // only fill in if there is budget. Key point: messaging/long-task tools are
    // placed high enough to survive the 12-tool cap (they are registered after
    // the generic set and were previously dropped by a registry-order take()).
    private val PRIORITY_TOOLS = listOf(
        // Observe + control the phone (always needed).
        "get_screen_info", "find_node_info", "open_app", "input_text",
        // Long-task messaging: read back a whole chat so the model can summarise it.
        "telegram_read_chat", "open_messaging_chat", "send_message",
        // Touch / scroll / find (drive the UI to reach a chat).
        "tap", "tap_node", "long_press", "swipe", "scroll_to_find", "find_and_tap",
        // Context + orchestration.
        "system_key", "take_screenshot", "get_installed_apps", "get_notifications",
        "wait", "wait_for_ui", "finish",
    )

    data class PreparedPrompt(
        val messages: List<ChatMsg>,
        val tools: List<ToolSpec>,
        val ctxSize: Int,
    )

    /** Conservative token estimate (over-counts ~28% of chars) -> never under-budgets. */
    fun estimateTokens(text: CharSequence): Int =
        (text.length * TOKENS_PER_CHAR).toInt() + TOKEN_OVERHEAD

    fun prepare(
        messages: List<ChatMsg>,
        tools: List<ToolSpec>,
        gemma: Boolean,
        ctxSize: Int,
        maxTokens: Int,
    ): PreparedPrompt {
        val budget = ctxSize - maxTokens - RESERVE_TOKENS
        // A 270M on-device model uses a small, focused tool set well, but it must
        // be the RIGHT one: prioritize phone-control/messaging tools (which are
        // registered after the generic set and would otherwise be dropped by a
        // plain take()). Order by priority first, then fill with the remainder.
        val byName = tools.associateBy { it.name }
        val prioritized = mutableListOf<ToolSpec>()
        for (name in PRIORITY_TOOLS) byName[name]?.let { prioritized += it }
        for (t in tools) if (t.name !in PRIORITY_TOOLS) prioritized += t
        val initialTools = prioritized.take(MAX_TOOLS_LOCAL)
        val msgs = messages.toMutableList()
        var tl = initialTools

        while (true) {
            val rendered = render(msgs, tl, gemma)
            if (estimateTokens(rendered) <= budget) break

            // (1) Drop the oldest conversation turn that is not the final user
            // one. Recompute the final-user index every pass: removing earlier
            // messages shifts indices and a stale index would let us delete the
            // very message the model is supposed to answer.
            val lastUserIdx = msgs.indexOfLast { it.role == Role.USER }
            val lastUser = msgs.getOrNull(lastUserIdx)
            val droppableIdx = msgs.indexOfFirst { it.role != Role.SYSTEM && it !== lastUser }
            if (droppableIdx >= 0) {
                msgs.removeAt(droppableIdx)
                continue
            }

            // (2) Shrink the tool list down to the minimum safe set.
            if (tl.size > MIN_TOOLS_LOCAL) {
                tl = tl.take(tl.size - 2)
                continue
            }

            // (3) Nothing left to drop: a single oversized user/assistant message
            // must be truncated rather than removed (removing it would leave the
            // model with nothing to answer).
            var truncated = false
            for (k in msgs.indices) {
                if (msgs[k].role == Role.SYSTEM) continue
                val trimmed = truncate(msgs[k].content, budget)
                if (trimmed.length < msgs[k].content.length) {
                    msgs[k] = msgs[k].copy(content = trimmed)
                    truncated = true
                    break
                }
            }
            if (!truncated) break
        }
        return PreparedPrompt(msgs, tl, ctxSize)
    }

    private fun truncate(text: String, maxTokens: Int): String {
        if (text.length <= maxTokens * 2) return text
        return text.take(maxTokens) + "\n… (truncated for on-device context)"
    }

    private fun render(messages: List<ChatMsg>, tools: List<ToolSpec>, gemma: Boolean): String =
        if (gemma) FunctionGemmaPrompt.build(messages, tools)
        else ChatMlPrompt.build(messages, tools, enableThinking = false)
}

object ChatMlPrompt {
    /**
     * Official Qwen3 hard switch from tokenizer_config.json
     * (Qwen/Qwen3-1.7B, also 0.6B / 4B):
     *
     *     {%- if add_generation_prompt %}
     *         {{- '<|im_start|>assistant\n' }}
     *         {%- if enable_thinking is defined and enable_thinking is false %}
     *             {{- '<think>\n\n</think>\n\n' }}
     *         {%- endif %}
     *     {%- endif %}
     *
     * `/no_think` is only a *soft* switch and is ignored when enable_thinking=False.
     * Prefilling the empty think block is what actually stops the model from
     * spending the whole token budget on a hidden monologue.
     */
    const val QWEN3_NO_THINK_PREFILL = "<think>\n\n</think>\n\n"

    fun build(messages: List<ChatMsg>, tools: List<ToolSpec>, enableThinking: Boolean): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                Role.SYSTEM -> {
                    sb.append("<|im_start|>system\n").append(msg.content)
                    if (tools.isNotEmpty()) {
                        sb.append("\n\nYou may emit tool calls as <tool_call>{\"name\":\"...\",\"arguments\":{...}}</tool_call>\nTools:\n")
                        for (t in tools) {
                            sb.append("- ").append(t.name).append(": ").append(t.descriptionFa).append("\n")
                        }
                    }
                    sb.append("<|im_end|>\n")
                }
                Role.USER -> sb.append("<|im_start|>user\n").append(msg.content).append("<|im_end|>\n")
                Role.ASSISTANT -> {
                    sb.append("<|im_start|>assistant\n")
                    if (msg.content.isNotEmpty()) sb.append(msg.content)
                    for (tc in msg.toolCalls) {
                        sb.append("<tool_call>{\"name\":\"").append(tc.name)
                            .append("\",\"arguments\":").append(tc.argumentsJson).append("}</tool_call>")
                    }
                    sb.append("<|im_end|>\n")
                }
                Role.TOOL -> sb.append("<|im_start|>user\n<tool_response>").append(msg.content)
                    .append("</tool_response><|im_end|>\n")
            }
        }
        sb.append("<|im_start|>assistant\n")
        if (!enableThinking) sb.append(QWEN3_NO_THINK_PREFILL)
        return sb.toString()
    }
}

/**
 * Official FunctionGemma turn format (bartowski GGUF card + Google docs):
 *
 *   <bos><start_of_turn>developer
 *   ...tools...
 *   <end_of_turn>
 *   <start_of_turn>user
 *   ...
 *   <end_of_turn>
 *   <start_of_turn>model
 *
 * Tool calls look like:
 *   <start_function_call>call:name{arg:value}<end_function_call>
 */
object FunctionGemmaPrompt {
    fun build(messages: List<ChatMsg>, tools: List<ToolSpec>): String {
        val sb = StringBuilder()
        val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n") { it.content }
        sb.append("<start_of_turn>developer\n")
        sb.append(
            system.ifBlank {
                "You are a model that can do function calling using the provided functions."
            }.trim(),
        )
        for (t in tools) {
            sb.append("<start_function_declaration>")
            sb.append("declaration:").append(t.name)
            sb.append("{description:<escape>").append(t.descriptionFa.replace("<", " ")).append("<escape>")
            sb.append(",parameters:{properties:{")
            val props = simpleProps(t.paramsJsonSchema)
            sb.append(props)
            sb.append("},type:<escape>OBJECT<escape>}}")
            sb.append("<end_function_declaration>")
        }
        sb.append("<end_of_turn>\n")
        for (msg in messages) {
            when (msg.role) {
                Role.SYSTEM -> Unit
                Role.USER -> sb.append("<start_of_turn>user\n").append(msg.content.trim()).append("<end_of_turn>\n")
                Role.ASSISTANT -> {
                    sb.append("<start_of_turn>model\n")
                    if (msg.content.isNotBlank()) sb.append(msg.content.trim())
                    for (tc in msg.toolCalls) {
                        sb.append("<start_function_call>call:").append(tc.name).append("{")
                        sb.append(flattenArgs(tc.argumentsJson))
                        sb.append("}<end_function_call>")
                    }
                    sb.append("<end_of_turn>\n")
                }
                Role.TOOL -> {
                    sb.append("<start_function_response>response:tool{value:<escape>")
                    sb.append(msg.content.take(400)).append("<escape>}<end_function_response>")
                }
            }
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    private fun simpleProps(schemaJson: String): String {
        if (schemaJson.isBlank() || schemaJson == "{}") return ""
        return try {
            val o = org.json.JSONObject(schemaJson)
            val props = o.optJSONObject("properties") ?: return ""
            val parts = mutableListOf<String>()
            val keys = props.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val p = props.optJSONObject(k)
                val desc = p?.optString("description").orEmpty().ifBlank { k }
                parts += "$k:{description:<escape>$desc<escape>,type:<escape>STRING<escape>}"
            }
            parts.joinToString(",")
        } catch (_: Exception) {
            ""
        }
    }

    private fun flattenArgs(json: String): String {
        return try {
            val o = org.json.JSONObject(json)
            val keys = o.keys()
            val parts = mutableListOf<String>()
            while (keys.hasNext()) {
                val k = keys.next()
                parts += "$k:<escape>${o.optString(k)}<escape>"
            }
            parts.joinToString(",")
        } catch (_: Exception) {
            json.trim().removePrefix("{").removeSuffix("}").replace("\"", "")
        }
    }
}
