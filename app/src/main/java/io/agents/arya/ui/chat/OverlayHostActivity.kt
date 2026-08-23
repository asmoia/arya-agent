package io.agents.arya.ui.chat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.agents.arya.ClawApplication
import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.llm.ModelReadiness
import io.agents.arya.agent.llm.ModelSession
import io.agents.arya.ui.chat.ui.AssistantOverlaySheet
import io.agents.arya.ui.chat.ui.VoiceListeningSheet
import io.agents.arya.utils.KVUtils
import io.agents.arya.voice.VoiceCapture

/**
 * Thin window into the SAME ChatRuntime / TaskSessionStore (time-contract §UI).
 * Launched from the floating circle over other apps. Extra `start_voice=true`
 * (long-press) starts SpeechRecognizer immediately.
 */
class OverlayHostActivity : ComponentActivity() {

    private var isVoiceListening by mutableStateOf(false)
    private var voicePartialText by mutableStateOf("")
    private var voiceErrorMessage by mutableStateOf<String?>(null)
    private var voiceCapture: VoiceCapture? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ClawApplication
        val runtime = ChatRuntimeRegistry.getOrCreate(
            context = this,
            conversationId = "default",
            engineClient = app.engineClient,
            historyStore = ChatHistoryStore(this),
        )

        voiceCapture = VoiceCapture(
            activity = this,
            onListeningChanged = { isVoiceListening = it },
            onPartial = { voicePartialText = it },
            onFinal = { transcript ->
                if (KVUtils.isVoiceAutoSend()) {
                    runtime.send(transcript, currentConfig())
                } else {
                    runtime.setDraft(transcript)
                }
            },
            onError = { msg ->
                voiceErrorMessage = msg.ifBlank { null }
            },
        )

        setContent {
            val chat by runtime.uiState.collectAsState()
            val task by app.taskSessionStore.state.collectAsState()
            Column {
                AssistantOverlaySheet(
                    chatUiState = chat,
                    taskState = task,
                    onSendText = { text ->
                        when (val gate = ModelSession.resolve(this@OverlayHostActivity)) {
                            is ModelReadiness.Local -> runtime.send(text, gate.config)
                            is ModelReadiness.Cloud -> runtime.send(text, gate.config)
                            is ModelReadiness.NeedsSetup -> runtime.setDraft(text)
                        }
                    },
                    onStartVoiceInput = { voiceCapture?.start() },
                    onRequestStopTask = { app.taskSessionStore.requestStop() },
                    onDismiss = { finish() },
                )
                if (isVoiceListening) {
                    VoiceListeningSheet(
                        partialText = voicePartialText,
                        errorMessage = voiceErrorMessage,
                        onStopListening = { voiceCapture?.stop() },
                    )
                }
            }
        }

        if (intent.getBooleanExtra(EXTRA_START_VOICE, false)) {
            voiceCapture?.start()
        }
    }

    override fun onDestroy() {
        voiceCapture?.destroy()
        super.onDestroy()
    }

    private fun currentConfig(): AgentConfig = when (val gate = ModelSession.resolve(this)) {
        is ModelReadiness.Local -> gate.config
        is ModelReadiness.Cloud -> gate.config
        is ModelReadiness.NeedsSetup -> AgentConfig()
    }

    companion object {
        const val EXTRA_START_VOICE = "start_voice"
    }
}
