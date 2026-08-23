package io.agents.arya.ui.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.agents.arya.AppCapabilityCoordinator
import io.agents.arya.AppRequirement
import io.agents.arya.ClawApplication
import io.agents.arya.TaskSessionStore
import io.agents.arya.agent.llm.LocalModelManager
import io.agents.arya.agent.llm.ModelConfigRepository
import io.agents.arya.agent.llm.ModelDownloadHub
import io.agents.arya.agent.llm.ModelReadiness
import io.agents.arya.agent.llm.ModelSession
import io.agents.arya.ui.chat.ui.AryaHomeScreen
import io.agents.arya.ui.models.ModelStudioSheet
import io.agents.arya.ui.permissions.CapabilitySheet
import io.agents.arya.ui.settings.SettingsActivity
import io.agents.arya.ui.theme.AryaTheme
import io.agents.arya.utils.KVUtils
import io.agents.arya.voice.VoiceCapture
import java.util.Locale

class ComposeChatActivity : ComponentActivity() {

    private lateinit var chatRuntime: ChatRuntime
    private lateinit var taskSessionStore: TaskSessionStore

    private var voiceCapture: VoiceCapture? = null
    private var isVoiceListening by mutableStateOf(false)
    private var voicePartialText by mutableStateOf("")
    private var voiceErrorMessage by mutableStateOf<String?>(null)
    private var holdActive = false

    private var textToSpeech: TextToSpeech? = null
    private var showModels by mutableStateOf(false)
    private var showCaps by mutableStateOf(false)
    private var readiness by mutableStateOf<ModelReadiness>(
        ModelReadiness.NeedsSetup("Checking models…", null),
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { refreshReadiness() }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as ClawApplication
        taskSessionStore = app.taskSessionStore
        chatRuntime = ChatRuntimeRegistry.getOrCreate(
            context = this,
            conversationId = intent.getStringExtra("conversation_id") ?: "default",
            engineClient = app.engineClient,
            historyStore = ChatHistoryStore(this),
        )

        initVoiceCapture()
        initTts()
        refreshReadiness()
        maybeAskNotifications()

        setContent {
            AryaTheme {
                val chatUiState by chatRuntime.uiState.collectAsState()
                val taskState by taskSessionStore.state.collectAsState()
                val jobs by ModelDownloadHub.jobs.collectAsState()
                LaunchedEffect(jobs) { refreshReadiness() }

                AryaHomeScreen(
                    chatUiState = chatUiState,
                    taskState = taskState,
                    readiness = readiness,
                    listening = isVoiceListening,
                    voicePartial = voicePartialText,
                    voiceError = voiceErrorMessage,
                    onSendText = { text -> trySend(text) },
                    onDraftChange = { chatRuntime.setDraft(it) },
                    onToggleVoice = { toggleVoice() },
                    onHoldStart = {
                        holdActive = true
                        startVoiceInput()
                    },
                    onHoldEnd = {
                        if (holdActive) {
                            holdActive = false
                            stopVoiceInput()
                        }
                    },
                    onStopStreaming = { chatRuntime.stopStreaming() },
                    onRequestStopTask = { taskSessionStore.requestStop() },
                    onOpenModels = { showModels = true },
                    onOpenSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onOpenCapabilities = { showCaps = true },
                    modifier = Modifier.fillMaxSize(),
                )

                if (showModels) {
                    ModalBottomSheet(
                        onDismissRequest = { showModels = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    ) {
                        ModelStudioSheet(
                            catalog = LocalModelManager.catalog(this@ComposeChatActivity),
                            readiness = readiness,
                            ramGb = LocalModelManager.getDeviceRamGb(this@ComposeChatActivity),
                            onDownload = { model ->
                                maybeAskNotifications()
                                ModelDownloadHub.start(this@ComposeChatActivity, model)
                            },
                            onActivate = { entry ->
                                val path = entry.path ?: return@ModelStudioSheet
                                ModelConfigRepository.saveLocalDefault(path, entry.model.id, activateNow = true)
                                refreshReadiness()
                                showModels = false
                            },
                        )
                    }
                }

                if (showCaps) {
                    ModalBottomSheet(
                        onDismissRequest = { showCaps = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    ) {
                        CapabilitySheet(
                            snapshot = AppCapabilityCoordinator.snapshot(this@ComposeChatActivity),
                            onOpenSystem = { req ->
                                AppCapabilityCoordinator.openSystemSettings(this@ComposeChatActivity, req)
                            },
                            onRequestRuntime = { perms -> permissionLauncher.launch(perms) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshReadiness()
    }

    private fun refreshReadiness() {
        readiness = ModelSession.resolve(this)
    }

    private fun trySend(text: String) {
        val gate = ModelSession.resolve(this)
        readiness = gate
        when (gate) {
            is ModelReadiness.NeedsSetup -> {
                chatRuntime.setDraft(text)
                showModels = true
            }
            is ModelReadiness.Local -> chatRuntime.send(text, gate.config)
            is ModelReadiness.Cloud -> chatRuntime.send(text, gate.config)
        }
    }

    private fun toggleVoice() {
        if (isVoiceListening) stopVoiceInput() else startVoiceInput()
    }

    private fun initVoiceCapture() {
        voiceCapture = VoiceCapture(
            activity = this,
            onListeningChanged = { isVoiceListening = it },
            onPartial = { voicePartialText = it },
            onFinal = { transcript ->
                if (KVUtils.isVoiceAutoSend()) trySend(transcript)
                else chatRuntime.setDraft(transcript)
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
                val ok = textToSpeech?.setLanguage(Locale.getDefault())
                if (ok == TextToSpeech.LANG_MISSING_DATA || ok == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech?.language = Locale.US
                }
            }
        }
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    override fun onDestroy() {
        voiceCapture?.destroy()
        textToSpeech?.shutdown()
        super.onDestroy()
    }
}
