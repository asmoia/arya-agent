package io.agents.arya.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import io.agents.arya.TaskState
import io.agents.arya.agent.AgentConfig
import io.agents.arya.ui.chat.ui.EmptyState
import io.agents.arya.ui.chat.ui.InputBar
import io.agents.arya.ui.chat.ui.MessageList
import io.agents.arya.ui.chat.ui.TaskStatusBar
import io.agents.arya.ui.chat.ui.VoiceListeningSheet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    chatUiState: ChatUiState,
    taskState: TaskState,
    agentConfig: AgentConfig,
    onSendText: (String) -> Unit,
    onStopStreaming: () -> Unit,
    onRequestStopTask: () -> Unit,
    isVoiceListening: Boolean = false,
    voicePartialText: String = "",
    voiceErrorMessage: String? = null,
    onStartVoiceInput: () -> Unit = {},
    onStopVoiceInput: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(chatUiState.errorMessage) {
        val err = chatUiState.errorMessage
        if (err != null) {
            snackbarHostState.showSnackbar(err)
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(chatUiState.title) }
                )
                TaskStatusBar(
                    taskState = taskState,
                    onRequestStop = onRequestStopTask
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (isVoiceListening) {
                VoiceListeningSheet(
                    partialText = voicePartialText,
                    errorMessage = voiceErrorMessage,
                    onStopListening = onStopVoiceInput
                )
            } else {
                InputBar(
                    onSendText = onSendText,
                    onStartVoiceInput = onStartVoiceInput,
                    isStreaming = chatUiState.isStreaming,
                    onStopStreaming = onStopStreaming
                )
            }
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (chatUiState.messages.isEmpty()) {
                EmptyState()
            } else {
                MessageList(
                    messages = chatUiState.messages,
                    reasoningText = chatUiState.streamingReasoning
                )
            }
        }
    }
}
