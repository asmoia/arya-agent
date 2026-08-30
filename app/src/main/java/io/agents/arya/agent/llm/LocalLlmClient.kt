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
        try {
            emit(LlmEvent.Status("Starting local engine… reading weights into RAM"))
            XLog.i(TAG, "ensureLoaded $modelPath")
            withTimeout(240_000L) {
                engineClient.ensureLoaded(modelPath)
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
                    engineClient.ensureLoaded(fallback, ctxSize = 1024, nThreads = 2)
                }
                emit(LlmEvent.Status("Qwen3 0.6B ready. Writing…"))
            } catch (fallbackError: Exception) {
                XLog.e(TAG, "fallback ensureLoaded failed", fallbackError)
                emit(LlmEvent.Error(3, "Neither the selected model nor Qwen3 0.6B could load safely. (${fallbackError.message})"))
                return@flow
            }
        }

        val assembler = StreamAssembler()
        val promptText = ChatMlPrompt.build(promptMessages, tools, enableThinking = false)
        XLog.i(TAG, "generate promptChars=${promptText.length}")
        val req = EngineRequest(
            prompt = promptText,
            promptMode = "full",
            maxTokens = 192,
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
                engineClient.ensureLoaded(fallback, ctxSize = 1024, nThreads = 2)
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
