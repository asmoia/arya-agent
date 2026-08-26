package io.agents.arya.ui.chat

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.delay
import io.agents.arya.AppCapabilityCoordinator
import io.agents.arya.ClawApplication
import io.agents.arya.TaskEvent
import io.agents.arya.agent.llm.LlmEvent
import io.agents.arya.automation.ExternalAutomationContract
import io.agents.arya.automation.ExternalAutomationEntrypoint
import io.agents.arya.R
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
    private var capTick by mutableStateOf(0)
    private var readiness by mutableStateOf<ModelReadiness>(
        ModelReadiness.NeedsSetup("", null),
    )
    private var handledExternalLaunchKey: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        refreshReadiness()
        capTick++
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            actuallyStartVoice()
        } else {
            onMicPermissionDenied()
        }
    }

    private val sttFallbackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
            .orEmpty()
        if (spoken.isNotBlank()) {
            if (KVUtils.isVoiceAutoSend()) trySend(spoken) else chatRuntime.setDraft(spoken)
        } else if (result.resultCode != RESULT_CANCELED) {
            voiceErrorMessage = getString(R.string.voice_no_speech)
        }
    }

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
                LaunchedEffect(showCaps) {
                    while (showCaps) {
                        capTick++
                        delay(800)
                    }
                }

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
                    onHoldCancel = {
                        holdActive = false
                        cancelVoiceInput()
                    },
                    onStopStreaming = { chatRuntime.stopStreaming() },
                    onRequestStopTask = { ClawApplication.appViewModelInstance.stopTask() },
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
                        capTick
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
        handleExternalIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshReadiness()
        capTick++
        prewarmIfPossible()
    }

    private fun refreshReadiness() {
        readiness = ModelSession.resolve(this).let { resolved ->
            if (resolved is ModelReadiness.NeedsSetup && resolved.reason.isBlank()) {
                ModelReadiness.NeedsSetup(getString(R.string.model_checking), null)
            } else {
                resolved
            }
        }
    }

    private fun prewarmIfPossible() {
        val gate = readiness as? ModelReadiness.Local ?: return
        val client = (application as ClawApplication).engineClient
        Thread({
            try {
                kotlinx.coroutines.runBlocking { client.ensureLoaded(gate.path) }
            } catch (_: Exception) {
            }
        }, "arya-prewarm").start()
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

    private fun handleExternalIntent(incoming: Intent?) {
        if (incoming == null) return
        val task = incoming.getStringExtra(ExternalAutomationEntrypoint.EXTRA_TASK)?.trim().orEmpty()
        val chat = incoming.getStringExtra(ExternalAutomationEntrypoint.EXTRA_CHAT)?.trim().orEmpty()
        if (task.isBlank() && chat.isBlank()) return

        val text: String
        val mode: ExternalAutomationContract.Mode
        if (incoming.action == ExternalAutomationContract.ACTION_RUN_TASK ||
            (task.isNotBlank() && chat.isBlank())
        ) {
            text = task.ifBlank { chat }
            mode = ExternalAutomationContract.Mode.TASK
        } else {
            text = chat.ifBlank { task }
            mode = ExternalAutomationContract.Mode.CHAT
        }
        if (text.isBlank()) return

        val request = ExternalLaunch(
            mode = mode,
            text = text,
            requestId = incoming.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_REQUEST_ID),
            returnAction = incoming.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_RETURN_ACTION),
            returnPackage = incoming.getStringExtra(ExternalAutomationEntrypoint.EXTRA_EXTERNAL_RETURN_PACKAGE),
        )
        val key = listOf(request.mode, request.requestId, request.text).joinToString("|")
        if (handledExternalLaunchKey == key) return
        handledExternalLaunchKey = key

        when (request.mode) {
            ExternalAutomationContract.Mode.CHAT -> dispatchExternalChat(request)
            ExternalAutomationContract.Mode.TASK -> dispatchExternalTask(request)
        }
    }

    private fun dispatchExternalChat(request: ExternalLaunch) {
        val gate = ModelSession.resolve(this)
        readiness = gate
        when (gate) {
            is ModelReadiness.NeedsSetup -> {
                chatRuntime.setDraft(request.text)
                showModels = true
                notifyExternal(request, ExternalAutomationContract.STATUS_FAILED, error = gate.reason)
            }
            is ModelReadiness.Local -> chatRuntime.send(request.text, gate.config) { event ->
                notifyExternalFromChat(request, event)
            }
            is ModelReadiness.Cloud -> chatRuntime.send(request.text, gate.config) { event ->
                notifyExternalFromChat(request, event)
            }
        }
    }

    private fun dispatchExternalTask(request: ExternalLaunch) {
        val taskId = request.requestId ?: "external-${System.currentTimeMillis()}"
        ClawApplication.appViewModelInstance.startTask(request.text, taskId) { event ->
            when (event) {
                is TaskEvent.Completed -> notifyExternal(
                    request,
                    ExternalAutomationContract.STATUS_COMPLETED,
                    result = event.answer,
                )
                is TaskEvent.Failed -> notifyExternal(
                    request,
                    ExternalAutomationContract.STATUS_FAILED,
                    error = event.error,
                )
                TaskEvent.Cancelled -> notifyExternal(request, ExternalAutomationContract.STATUS_CANCELLED)
                TaskEvent.Blocked -> notifyExternal(request, ExternalAutomationContract.STATUS_BLOCKED)
                else -> Unit
            }
        }
    }

    private fun notifyExternalFromChat(request: ExternalLaunch, event: LlmEvent) {
        when (event) {
            is LlmEvent.Finished -> notifyExternal(
                request,
                ExternalAutomationContract.STATUS_COMPLETED,
                result = event.text.ifBlank { "completed" },
            )
            is LlmEvent.Error -> notifyExternal(request, ExternalAutomationContract.STATUS_FAILED, error = event.message)
            else -> Unit
        }
    }

    private fun notifyExternal(
        request: ExternalLaunch,
        status: String,
        result: String? = null,
        error: String? = null,
    ) {
        ExternalAutomationContract.sendCallback(
            context = this,
            returnAction = request.returnAction,
            requestId = request.requestId,
            status = status,
            result = result,
            error = error,
            returnPackage = request.returnPackage,
            mode = request.mode,
        )
    }

    private data class ExternalLaunch(
        val mode: ExternalAutomationContract.Mode,
        val text: String,
        val requestId: String?,
        val returnAction: String?,
        val returnPackage: String?,
    )

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

    private fun stopVoiceInput() {
        voiceCapture?.stop()
    }

    fun cancelVoiceInput() {
        holdActive = false
        voiceCapture?.cancel()
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
