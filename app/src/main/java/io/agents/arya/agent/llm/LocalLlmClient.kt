package io.agents.arya.agent.llm

import android.content.Context
import io.agents.arya.agent.AgentConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class LocalLlmClient(
    private val context: Context,
    private val config: AgentConfig,
    private val engineClient: EngineClient
) : LlmClient {

    private val assembler = StreamAssembler()

    override fun chatStream(
        messages: List<ChatMsg>,
        tools: List<ToolSpec>
    ): Flow<LlmEvent> = callbackFlow {
        val modelPath = config.baseUrl
        if (modelPath.isEmpty()) {
            trySend(LlmEvent.Error(2, "مسیر فایل مدل محلی مشخص نشده است"))
            close()
            return@callbackFlow
        }

        try {
            engineClient.ensureLoaded(modelPath, ctxSize = 2048, nThreads = 4)
        } catch (e: Exception) {
            trySend(LlmEvent.Error(3, "خطا در بارگذاری مدل محلی: ${e.message}"))
            close()
            return@callbackFlow
        }

        val promptText = buildChatMlPrompt(messages, tools)

        val reqJson = JSONObject().apply {
            put("prompt", promptText)
            put("promptMode", "full")
            put("maxTokens", 512)
            put("temperature", config.temperature)
            put("topP", 0.9)
            put("topK", 32)
            put("repeatPenalty", 1.12)
            put("stop", JSONArray().apply {
                put("<|im_end|>")
                put("</tool_call>")
            })
            put("deadlineMs", 45000L)
            put("tokenDeadlineMs", 4000L)
        }.toString()

        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            engineClient.generate(reqJson).collect { engineEv ->
                when (engineEv) {
                    is EngineEvent.Delta -> {
                        val events = assembler.feed(engineEv.text)
                        for (ev in events) {
                            trySend(ev)
                        }
                    }
                    is EngineEvent.Done -> {
                        val finalEvents = assembler.finish()
                        for (ev in finalEvents) {
                            trySend(ev)
                        }
                        close()
                    }
                    is EngineEvent.Failed -> {
                        trySend(LlmEvent.Error(engineEv.code, engineEv.message))
                        close()
                    }
                    is EngineEvent.LoadProgress -> {
                        // Handled separately
                    }
                }
            }
        }

        awaitClose {
            job.cancel()
        }
    }

    override fun chatSync(messages: List<ChatMsg>, tools: List<ToolSpec>): LlmResponse {
        var fullText = ""
        val toolCallsList = mutableListOf<ToolCallSpec>()

        runBlocking {
            chatStream(messages, tools).collect { event ->
                when (event) {
                    is LlmEvent.Text -> fullText += event.delta
                    is LlmEvent.ToolCall -> toolCallsList.add(
                        ToolCallSpec(id = "local_${System.currentTimeMillis()}", name = event.name, argumentsJson = event.argsJson)
                    )
                    is LlmEvent.Error -> throw IllegalStateException(event.message)
                    else -> {}
                }
            }
        }

        return LlmResponse(
            text = fullText.ifEmpty { null },
            toolCalls = toolCallsList,
            modelName = config.baseUrl
        )
    }

    override fun close() {
        // Engine lifecycle is managed by EngineClient / EngineService
    }

    private fun buildChatMlPrompt(messages: List<ChatMsg>, tools: List<ToolSpec>): String {
        val sb = StringBuilder()

        for (msg in messages) {
            when (msg.role) {
                Role.SYSTEM -> {
                    sb.append("<|im_start|>system\n").append(msg.content)
                    if (tools.isNotEmpty()) {
                        sb.append("\n\nTools available:\n")
                        for (t in tools) {
                            sb.append("- ").append(t.name).append(": ").append(t.descriptionFa).append("\n")
                        }
                    }
                    sb.append("<|im_end|>\n")
                }
                Role.USER -> {
                    sb.append("<|im_start|>user\n").append(msg.content).append("<|im_end|>\n")
                }
                Role.ASSISTANT -> {
                    sb.append("<|im_start|>assistant\n")
                    if (msg.content.isNotEmpty()) {
                        sb.append(msg.content)
                    }
                    for (tc in msg.toolCalls) {
                        sb.append("<tool_call>{\"name\":\"").append(tc.name).append("\",\"arguments\":").append(tc.argumentsJson).append("}</tool_call>")
                    }
                    sb.append("<|im_end|>\n")
                }
                Role.TOOL -> {
                    sb.append("<|im_start|>user\n[Tool Result]: ").append(msg.content).append("<|im_end|>\n")
                }
            }
        }

        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}
