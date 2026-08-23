package io.agents.arya.ui.chat

import android.Manifest
import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.agents.arya.ClawApplication
import io.agents.arya.R
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
    private var holdActive = false

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) actuallyStartVoice() else onMicPermissionDenied()
    }

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
                    onStartVoiceInput = { startVoiceInput() },
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
            startVoiceInput()
        }
    }

    private fun startVoiceInput() {
        if (VoiceCapture.hasRecordAudio(this)) {
            actuallyStartVoice()
            return
        }
        getSharedPreferences("arya_voice", MODE_PRIVATE)
            .edit()
            .putBoolean("mic_asked", true)
            .apply()
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun actuallyStartVoice() {
        voicePartialText = ""
        voiceErrorMessage = null
        voiceCapture?.start()
    }

    private fun onMicPermissionDenied() {
        val asked = getSharedPreferences("arya_voice", MODE_PRIVATE)
            .getBoolean("mic_asked", false)
        val showRationale = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
        if (asked && !showRationale) {
            voiceErrorMessage = getString(R.string.voice_mic_denied_forever)
            AlertDialog.Builder(this)
                .setMessage(R.string.voice_mic_denied_forever)
                .setPositiveButton(R.string.voice_mic_open_settings) { _, _ ->
                    VoiceCapture.openAppSettings(this)
                }
                .setNegativeButton(R.string.common_cancel, null)
                .show()
        } else {
            voiceErrorMessage = getString(R.string.voice_need_mic)
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
