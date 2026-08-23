package io.agents.arya.ui.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.agents.arya.ClawApplication
import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.llm.ModelConfigRepository
import io.agents.arya.ui.chat.ui.AssistantOverlaySheet

/**
 * Thin window into the SAME ChatRuntime / TaskSessionStore (time-contract §UI).
 * Launched from the floating circle over other apps.
 */
class OverlayHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ClawApplication
        val runtime = ChatRuntimeRegistry.getOrCreate(
            context = this,
            conversationId = "default",
            engineClient = app.engineClient,
            historyStore = ChatHistoryStore(this),
        )
        setContent {
            val chat by runtime.uiState.collectAsState()
            val task by app.taskSessionStore.state.collectAsState()
            AssistantOverlaySheet(
                chatUiState = chat,
                taskState = task,
                onSendText = { text ->
                    val cfg = try {
                        ModelConfigRepository.snapshot().toAgentConfig(0.3, 8)
                    } catch (_: Exception) {
                        AgentConfig()
                    }
                    runtime.send(text, cfg)
                },
                onStartVoiceInput = { },
                onRequestStopTask = { app.taskSessionStore.requestStop() },
                onDismiss = { finish() },
            )
        }
    }
}
