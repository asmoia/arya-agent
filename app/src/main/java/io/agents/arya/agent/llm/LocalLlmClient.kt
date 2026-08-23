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

/**
 * Main-process local client. Never loads natives — all work goes over AIDL.
 */
class LocalLlmClient(
    @Suppress("unused") private val context: Context,
    private val config: AgentConfig,
    private val engineClient: EngineClient,
) : LlmClient {

    override fun chatStream(messages: List<ChatMsg>, tools: List<ToolSpec>): Flow<LlmEvent> = flow {
        val modelPath = config.baseUrl
        if (modelPath.isEmpty()) {
            emit(LlmEvent.Error(2, "No local model path configured"))
            return@flow
        }
        try {
            engineClient.ensureLoaded(modelPath)
        } catch (e: Exception) {
            emit(LlmEvent.Error(3, "Failed to load local model: ${e.message}"))
            return@flow
        }

        val assembler = StreamAssembler()
        val promptText = ChatMlPrompt.build(messages, tools, enableThinking = false)
        val req = EngineRequest(
            prompt = promptText,
            promptMode = "full",
            maxTokens = 256,
            temperature = config.temperature,
            deadlineMs = 20_000L,
            tokenDeadlineMs = 4_000L,
        )
        engineClient.generate(req).collect { engineEv ->
            when (engineEv) {
                is EngineEvent.Delta -> assembler.feed(engineEv.text).forEach { emit(it) }
                is EngineEvent.Done -> {
                    assembler.finish().forEach { emit(it) }
                }
                is EngineEvent.Failed -> emit(LlmEvent.Error(engineEv.code, engineEv.message))
                is EngineEvent.LoadProgress -> { }
            }
        }
    }.flowOn(Dispatchers.IO)

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
        return sb.toString()
    }
}
