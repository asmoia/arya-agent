package io.agents.arya.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.agents.arya.ClawApplication
import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.llm.ModelConfigRepository
import io.agents.arya.utils.XLog
import java.util.concurrent.ExecutorService

data class ChatSessionUiState(
    val messages: SnapshotStateList<ChatMessage>,
    val modelStatus: MutableState<String>,
    val isAwaitingReply: MutableState<Boolean>,
    val inputEnabled: MutableState<Boolean>,
    val isDownloading: MutableState<Boolean>,
    val downloadProgress: MutableState<Int>,
    val sessionTokens: MutableState<Int>,
    val sessionCost: MutableState<Double>,
)

/**
 * Thin adapter so TaskFlowController can still notify the chat shell.
 * Real streaming lives in ChatRuntime (S9).
 */
class ChatSessionController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val uiState: ChatSessionUiState,
    private val onPersistConversation: () -> Unit,
    private val onRefreshSidebarHistory: () -> Unit,
    private val isTaskRunning: () -> Boolean,
) {
    companion object {
        private const val TAG = "ChatSessionController"
    }

    private var isModelReady = false

    fun isModelReady(): Boolean = isModelReady

    fun loadModelIfReady(
        conversationId: String? = null,
        visibleMessages: List<ChatMessage> = emptyList(),
    ) {
        val resolved = ModelConfigRepository.snapshot()
        if (resolved.isLocalActive()) {
            val path = resolved.local.modelPath
            if (path.isBlank()) {
                uiState.modelStatus.value = "No local model"
                isModelReady = false
                return
            }
            uiState.modelStatus.value = "● ${resolved.local.displayName} · llama.cpp"
            isModelReady = true
            uiState.inputEnabled.value = true
        } else {
            val cloud = resolved.activeCloud
            if (cloud.isConfigured) {
                uiState.modelStatus.value = "● ${cloud.modelName} · Cloud"
                isModelReady = true
                uiState.inputEnabled.value = true
            } else {
                uiState.modelStatus.value = "No model selected"
                isModelReady = false
            }
        }
    }

    fun onResume(conversationId: String, visibleMessages: List<ChatMessage>) {
        loadModelIfReady(conversationId, visibleMessages)
    }

    fun onPause(conversationId: String) {}
    fun onDestroy() {}
    fun releaseForTask() {}
    fun prepareForTaskStart() {}

    fun sendChat(text: String) {
        val app = activity.application as? ClawApplication ?: ClawApplication.instance
        val runtime = ChatRuntimeRegistry.getOrCreate(
            context = activity,
            conversationId = "default",
            engineClient = app.engineClient,
            historyStore = ChatHistoryStore(activity),
        )
        val cfg = try {
            ModelConfigRepository.snapshot().toAgentConfig(temperature = 0.7, maxIterations = 8)
        } catch (_: Exception) {
            AgentConfig()
        }
        runtime.send(text, cfg)
        uiState.messages.add(ChatMessage(ChatMessage.Role.USER, text))
        onPersistConversation()
    }

    fun switchModel(modelId: String, displayName: String) {
        XLog.i(TAG, "switchModel $modelId $displayName")
        loadModelIfReady()
    }

    fun startNewConversationRuntime() {
        onRefreshSidebarHistory()
    }

    fun restoreConversationRuntime(conversationId: String, messages: List<ChatMessage>) {
        loadModelIfReady(conversationId, messages)
    }

    fun syncUiToActiveModel() = loadModelIfReady()
}
