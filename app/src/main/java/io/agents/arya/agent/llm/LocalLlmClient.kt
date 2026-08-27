package io.agents.arya.agent.llm

import android.content.Context
import io.agents.arya.agent.AgentConfig
import io.agents.arya.engine.EngineClient
import io.agents.arya.engine.EngineEvent
import io.agents.arya.engine.EngineRequest
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
        val modelPath = config.baseUrl
        if (modelPath.isEmpty()) {
            emit(LlmEvent.Error(2, "No local model path configured"))
            return@flow
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
            deadlineMs = 90_000L,
            tokenDeadlineMs = 12_000L,
        )
        try {
            withTimeout(120_000L) {
                engineClient.generate(req).collect { engineEv ->
                    when (engineEv) {
                        is EngineEvent.Delta -> assembler.feed(engineEv.text).forEach { emit(it) }
                        is EngineEvent.Done -> {
                            assembler.finish().forEach { emit(it) }
                        }
                        is EngineEvent.Failed -> emit(LlmEvent.Error(engineEv.code, engineEv.message))
                        is EngineEvent.LoadProgress -> emit(LlmEvent.Status(engineEv.phase.ifBlank { "Loading…" }))
                    }
                }
            }
        } catch (e: Exception) {
            XLog.e(TAG, "generate failed", e)
            emit(
                LlmEvent.Error(
                    5,
                    "Local model timed out or the engine crashed. Send again — if it keeps failing, switch to Qwen3 0.6B. (${e.message})",
                ),
            )
        }
            }.flowOn(Dispatchers.IO)

    private fun isKnownHeavyQwen(path: String): Boolean =
        path.endsWith("Qwen_Qwen3-1.7B-Q4_K_M.gguf", ignoreCase = true) ||
            path.contains("qwen3-1.7b", ignoreCase = true)

    private suspend fun ensureSmallFallback(): String? {
        val model = LocalModelManager.AVAILABLE_MODELS.firstOrNull { it.id == "qwen3-0.6b" }
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
    fun build(messages: List<ChatMsg>, tools: List<ToolSpec>, enableThinking: Boolean): String {
        val sb = StringBuilder()
        for (msg in messages) {
            when (msg.role) {
                Role.SYSTEM -> {
                    sb.append("<|im_start|>system\n").append(msg.content)
                    if (!enableThinking) sb.append("\n/no_think")
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
        if (!enableThinking) sb.append("/no_think\n")
        return sb.toString()
    }
}
