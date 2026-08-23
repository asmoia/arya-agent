package io.agents.arya.ui.chat

import android.content.Context
import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.llm.ChatMsg
import io.agents.arya.engine.EngineClient
import io.agents.arya.agent.llm.LlmClient
import io.agents.arya.agent.llm.LlmClientFactory
import io.agents.arya.agent.llm.LlmEvent
import io.agents.arya.agent.llm.Role
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversationId: String = "default",
    val title: String = "Arya",
    val messages: List<ChatMessage> = emptyList(),
    val isStreaming: Boolean = false,
    val streamingReasoning: String? = null,
    val activeToolName: String? = null,
    val errorMessage: String? = null
)

class ChatRuntime(
    private val context: Context,
    val conversationId: String,
    private val engineClient: EngineClient,
    private val historyStore: ChatHistoryStore
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val _uiState = MutableStateFlow(ChatUiState(conversationId = conversationId))
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamJob: Job? = null
    private var lastUserPrompt: String? = null

    init {
        loadHistory()
    }

    fun loadHistory() {
        val loaded = historyStore.loadConversation(conversationId)
        _uiState.value = _uiState.value.copy(messages = loaded)
    }

    fun send(userText: String, agentConfig: AgentConfig) {
        if (userText.isBlank()) return

        lastUserPrompt = userText
        val now = System.currentTimeMillis()
        val userMsg = ChatMessage(role = ChatMessage.Role.USER, content = userText, timestamp = now)
        val updatedMsgs = _uiState.value.messages + userMsg

        _uiState.value = _uiState.value.copy(
            messages = updatedMsgs,
            isStreaming = true,
            errorMessage = null,
            streamingReasoning = null,
            activeToolName = null
        )

        historyStore.saveConversation(conversationId, userText.take(20), updatedMsgs)

        streamJob = scope.launch(Dispatchers.IO) {
            try {
                val client: LlmClient = LlmClientFactory.create(context, agentConfig, engineClient)
                val chatMsgs = updatedMsgs.map { m ->
                    ChatMsg(
                        role = when (m.role) {
                            ChatMessage.Role.USER -> Role.USER
                            ChatMessage.Role.ASSISTANT -> Role.ASSISTANT
                            ChatMessage.Role.SYSTEM -> Role.SYSTEM
                            ChatMessage.Role.TOOL_GROUP -> Role.TOOL
                        },
                        content = m.content
                    )
                }

                var currentAssistantText = ""
                var currentReasoningText = ""

                val placeholderMsg = ChatMessage(
                    role = ChatMessage.Role.ASSISTANT,
                    content = "",
                    timestamp = System.currentTimeMillis()
                )
                val msgsWithPlaceholder = updatedMsgs + placeholderMsg

                _uiState.value = _uiState.value.copy(messages = msgsWithPlaceholder)

                client.chatStream(chatMsgs, emptyList()).collect { event ->
                    when (event) {
                        is LlmEvent.Text -> {
                            if (event.isReasoning) {
                                currentReasoningText += event.delta
                                _uiState.value = _uiState.value.copy(streamingReasoning = currentReasoningText)
                            } else {
                                currentAssistantText += event.delta
                                val lastIdx = msgsWithPlaceholder.lastIndex
                                val updated = msgsWithPlaceholder.toMutableList()
                                updated[lastIdx] = placeholderMsg.copy(content = currentAssistantText)
                                _uiState.value = _uiState.value.copy(messages = updated)
                            }
                        }
                        is LlmEvent.ToolCallStart -> {
                            _uiState.value = _uiState.value.copy(activeToolName = event.name ?: "ابزار")
                        }
                        is LlmEvent.ToolCall -> {
                            val lastIdx = msgsWithPlaceholder.lastIndex
                            val updated = msgsWithPlaceholder.toMutableList()
                            val toolStep = ToolStep(event.name, event.argsJson, true)
                            updated[lastIdx] = placeholderMsg.copy(
                                content = currentAssistantText,
                                toolSteps = listOf(toolStep)
                            )
                            _uiState.value = _uiState.value.copy(messages = updated)
                        }
                        is LlmEvent.Error -> {
                            _uiState.value = _uiState.value.copy(
                                isStreaming = false,
                                errorMessage = event.message
                            )
                        }
                        is LlmEvent.Finished -> {
                            _uiState.value = _uiState.value.copy(
                                isStreaming = false,
                                activeToolName = null
                            )
                            historyStore.saveConversation(conversationId, userText.take(20), _uiState.value.messages)
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isStreaming = false,
                    errorMessage = e.message ?: "خطای ناشناخته در برقراری ارتباط"
                )
            }
        }
    }

    fun stopStreaming() {
        streamJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isStreaming = false,
            activeToolName = null
        )
    }

    fun retry(agentConfig: AgentConfig) {
        val prompt = lastUserPrompt ?: return
        send(prompt, agentConfig)
    }
}

object ChatRuntimeRegistry {
    private val instances = HashMap<String, ChatRuntime>()

    fun getOrCreate(
        context: Context,
        conversationId: String,
        engineClient: EngineClient,
        historyStore: ChatHistoryStore
    ): ChatRuntime {
        return synchronized(instances) {
            instances.getOrPut(conversationId) {
                ChatRuntime(context.applicationContext, conversationId, engineClient, historyStore)
            }
        }
    }
}
