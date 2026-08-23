package io.agents.arya.ui.chat

import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.agents.arya.ClawApplication
import io.agents.arya.TaskSessionStore
import io.agents.arya.agent.AgentConfig
import io.agents.arya.engine.EngineClient
import io.agents.arya.utils.KVUtils
import io.agents.arya.voice.VoiceCapture
import java.util.Locale

class ComposeChatActivity : ComponentActivity() {

    private lateinit var engineClient: EngineClient
    private lateinit var chatRuntime: ChatRuntime
    private lateinit var taskSessionStore: TaskSessionStore

    private var voiceCapture: VoiceCapture? = null
    private var isVoiceListening by mutableStateOf(false)
    private var voicePartialText by mutableStateOf("")
    private var voiceErrorMessage by mutableStateOf<String?>(null)

    private var textToSpeech: TextToSpeech? = null
    private var isTtsEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as ClawApplication
        engineClient = app.engineClient
        taskSessionStore = app.taskSessionStore

        val historyStore = ChatHistoryStore(this)
        chatRuntime = ChatRuntimeRegistry.getOrCreate(
            context = this,
            conversationId = intent.getStringExtra("conversation_id") ?: "default",
            engineClient = engineClient,
            historyStore = historyStore
        )

        initVoiceCapture()
        isTtsEnabled = KVUtils.isVoiceTtsEnabled()
        initTts()

        setContent {
            val chatUiState by chatRuntime.uiState.collectAsState()
            val taskState by taskSessionStore.state.collectAsState()

            ChatScreen(
                chatUiState = chatUiState,
                taskState = taskState,
                agentConfig = AgentConfig(),
                onSendText = { text ->
                    chatRuntime.send(text, AgentConfig())
                },
                onStopStreaming = {
                    chatRuntime.stopStreaming()
                },
                onRequestStopTask = {
                    taskSessionStore.requestStop()
                },
                isVoiceListening = isVoiceListening,
                voicePartialText = voicePartialText,
                voiceErrorMessage = voiceErrorMessage,
                onStartVoiceInput = { startVoiceInput() },
                onStopVoiceInput = { stopVoiceInput() }
            )
        }
    }

    private fun initVoiceCapture() {
        voiceCapture = VoiceCapture(
            activity = this,
            onListeningChanged = { isVoiceListening = it },
            onPartial = { voicePartialText = it },
            onFinal = { transcript ->
                if (KVUtils.isVoiceAutoSend()) {
                    chatRuntime.send(transcript, AgentConfig())
                } else {
                    chatRuntime.setDraft(transcript)
                }
            },
            onError = { msg -> voiceErrorMessage = msg.ifBlank { null } },
        )
    }

    private fun startVoiceInput() {
        voicePartialText = ""
        voiceErrorMessage = null
        voiceCapture?.start()
    }

    private fun stopVoiceInput() {
        voiceCapture?.stop()
    }

    private fun initTts() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.language = Locale("fa")
            }
        }
    }

    private fun speakShortAnswer(text: String) {
        if (isTtsEnabled && text.length <= 100) {
            textToSpeech?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_id")
        }
    }

    override fun onDestroy() {
        voiceCapture?.destroy()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}
