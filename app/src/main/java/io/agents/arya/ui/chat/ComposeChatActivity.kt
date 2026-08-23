package io.agents.arya.ui.chat

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.agents.arya.ClawApplication
import io.agents.arya.R
import io.agents.arya.TaskSessionStore
import io.agents.arya.agent.AgentConfig
import io.agents.arya.engine.EngineClient
import io.agents.arya.utils.KVUtils
import java.util.Locale

class ComposeChatActivity : ComponentActivity() {

    private lateinit var engineClient: EngineClient
    private lateinit var chatRuntime: ChatRuntime
    private lateinit var taskSessionStore: TaskSessionStore

    private var speechRecognizer: SpeechRecognizer? = null
    private var isVoiceListening by mutableStateOf(false)
    private var voicePartialText by mutableStateOf("")
    private var voiceErrorMessage by mutableStateOf<String?>(null)

    private var textToSpeech: TextToSpeech? = null
    private var isTtsEnabled by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()

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

        initSpeechRecognizer()
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

    private fun initSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isVoiceListening = true
                        voiceErrorMessage = null
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        isVoiceListening = false
                        voiceErrorMessage = getString(R.string.voice_no_speech)
                    }

                    override fun onResults(results: Bundle?) {
                        isVoiceListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val transcript = matches?.firstOrNull() ?: ""
                        if (transcript.isNotBlank()) {
                            if (KVUtils.isVoiceAutoSend()) {
                                chatRuntime.send(transcript, AgentConfig())
                            } else {
                                chatRuntime.setDraft(transcript)
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        voicePartialText = matches?.firstOrNull() ?: ""
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    private fun startVoiceInput() {
        voicePartialText = ""
        voiceErrorMessage = null
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        speechRecognizer?.startListening(intent)
        isVoiceListening = true
    }

    private fun stopVoiceInput() {
        speechRecognizer?.stopListening()
        isVoiceListening = false
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
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}
