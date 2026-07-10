// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.ui.chat

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage
import io.agents.arya.agent.ModelPricing
import io.agents.arya.agent.llm.LlmClient
import io.agents.arya.agent.llm.LlmSessionManager
import io.agents.arya.agent.llm.LocalModelManager
import io.agents.arya.agent.llm.LocalModelRuntime
import io.agents.arya.agent.llm.LocalInferenceCoordinator
import io.agents.arya.agent.llm.LocalInferenceBusyException
import io.agents.arya.agent.llm.LocalBackendHealth
import io.agents.arya.agent.llm.LocalInferenceOwner
import io.agents.arya.agent.llm.LocalRuntimePolicy
import io.agents.arya.agent.llm.ModelConfigRepository
import io.agents.arya.utils.XLog
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
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
        private const val BASE_SYSTEM_PROMPT = "You are a helpful AI assistant on an Android phone."
    }

    private var engine: Engine? = null
    private var loadedModelPath: String? = null
    private var conversation: Conversation? = null
    private var isModelReady = false

    private var cloudClient: LlmClient? = null
    private var cloudModelName: String? = null
    private val cloudHistory = mutableListOf<dev.langchain4j.data.message.ChatMessage>()
    private var localUiGeneration: Long = 0
    private var suppressNextCloudSwitchMessage: Boolean = false

    fun isModelReady(): Boolean = isModelReady

    fun loadModelIfReady(
        conversationId: String? = null,
        visibleMessages: List<ChatMessage> = emptyList(),
    ) {
        val resolvedConfig = ModelConfigRepository.snapshot()

        if (!resolvedConfig.isLocalActive()) {
            localUiGeneration++
            val cloudConfig = resolvedConfig.activeCloud
            if (cloudConfig.apiKey.isNotEmpty() && cloudConfig.modelName.isNotEmpty()) {
                val previousModel = cloudModelName
                cloudClient = LlmSessionManager.createCloudClient(temperature = 0.7)
                if (cloudClient == null) {
                    uiState.modelStatus.value = "No model selected"
                    isModelReady = false
                    setButtonsEnabled(false)
                    return
                }
                cloudModelName = cloudConfig.modelName
                if (previousModel == null || cloudHistory.isEmpty()) {
                    rebuildCloudHistoryFromVisibleMessages()
                } else if (previousModel != cloudConfig.modelName) {
                    cloudHistory.add(
                        SystemMessage.from(
                            "The user has switched from $previousModel to ${cloudConfig.modelName}. Continue the conversation naturally."
                        )
                    )
                    if (suppressNextCloudSwitchMessage) {
                        suppressNextCloudSwitchMessage = false
                    } else {
                        addSystem("Switched to ${cloudConfig.modelName}")
                    }
                }
                isModelReady = true
                uiState.modelStatus.value = "● ${cloudConfig.modelName} · Cloud"
                setButtonsEnabled(true)
                XLog.i(TAG, "Cloud chat ready: ${cloudConfig.modelName} via ${cloudConfig.resolvedBaseUrl}")
            } else {
                uiState.modelStatus.value = "No model selected"
                isModelReady = false
                setButtonsEnabled(false)
            }
            return
        }

        cloudClient = null
        val modelPath = resolvedConfig.local.modelPath
        if (isTaskRunning()) {
            uiState.modelStatus.value = "● Local task using model"
            isModelReady = false
            setButtonsEnabled(false)
            return
        }
        XLog.d(TAG, "loadModelIfReady: stored=$modelPath loaded=$loadedModelPath engine=${engine != null}")

        if (modelPath.isNotEmpty() && engine != null && modelPath != loadedModelPath) {
            XLog.d(TAG, "loadModelIfReady: model changed ($loadedModelPath -> $modelPath), closing conversation")
            val oldConv = conversation
            engine = null
            conversation = null
            isModelReady = false
            loadedModelPath = null
            executor.submit {
                try {
                    oldConv?.close()
                } catch (e: Exception) {
                    XLog.w(TAG, "loadModelIfReady: conv close error", e)
                } finally {
                    LocalInferenceCoordinator.release(LocalInferenceOwner.CHAT)
                }
                postToMain { loadModelIfReady() }
            }
            return
        }

        if (modelPath.isEmpty()) {
            val deviceSupport = LocalModelManager.deviceSupport(activity)
            val defaultModel = deviceSupport.bestSupportedModel
            if (defaultModel == null) {
                uiState.modelStatus.value = "Local model unavailable on this device"
                uiState.isDownloading.value = false
                setButtonsEnabled(false)
                addSystem(
                    "This device reports ${deviceSupport.deviceRamGb}GB RAM. Current built-in local models need at least ${deviceSupport.minimumBuiltInRamGb}GB."
                )
                return
            }
            uiState.modelStatus.value = "Downloading ${defaultModel.displayName}..."
            uiState.isDownloading.value = true
            uiState.downloadProgress.value = 0
            setButtonsEnabled(false)

            executor.submit {
                LocalModelManager.downloadModel(activity, defaultModel, object : LocalModelManager.DownloadCallback {
                    override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                        val pct = if (totalBytes > 0) (bytesDownloaded * 100 / totalBytes).toInt() else 0
                        postToMain {
                            uiState.downloadProgress.value = pct
                            uiState.modelStatus.value = "Downloading: $pct%"
                        }
                    }

                    override fun onComplete(modelPath: String) {
                        val currentPath = ModelConfigRepository.snapshot().local.modelPath
                        if (currentPath.isEmpty() || currentPath == modelPath) {
                            ModelConfigRepository.activateLocal(modelPath, defaultModel.id)
                        }
                        postToMain {
                            uiState.isDownloading.value = false
                            loadModelIfReady()
                        }
                    }

                    override fun onError(error: String) {
                        postToMain {
                            uiState.isDownloading.value = false
                            uiState.modelStatus.value = "Download failed"
                            addSystem("Download failed: $error")
                        }
                    }
                })
            }
            return
        }

        val restoredSystemPrompt = buildRestoredSystemPrompt(conversationId, visibleMessages)
        uiState.modelStatus.value = "Loading..."
        setButtonsEnabled(false)
        val generation = ++localUiGeneration
        executor.submit { loadModel(modelPath, generation, restoredSystemPrompt) }
    }

    fun onResume(
        conversationId: String,
        visibleMessages: List<ChatMessage>,
    ) {
        val config = ModelConfigRepository.snapshot()
        if (!config.isLocalActive()) {
            syncUiToActiveModel()
            return
        }

        val currentModelPath = config.local.modelPath
        if (currentModelPath.isBlank()) {
            syncUiToActiveModel()
            return
        }
        if (isTaskRunning()) {
            uiState.modelStatus.value = "● Local task using model"
            setButtonsEnabled(false)
            return
        }

        // A single load entry-point prevents onResume + syncUiToActiveModel from
        // issuing two createConversation calls for the one-session LiteRT Engine.
        if (currentModelPath != loadedModelPath || !isModelReady || conversation == null) {
            loadModelIfReady(conversationId, visibleMessages)
        } else {
            updateLocalModelStatus(currentModelPath)
            setButtonsEnabled(true)
        }
    }

    fun onPause(conversationId: String) {
        // Do not start an invisible model-powered compaction on pause. LiteRT has
        // one native Conversation; a background summary can race the next task
        // and was not worth a failed foreground command. Existing saved digests
        // are still used when a conversation is restored.
        executor.submit {
            closeLocalConversation()
            isModelReady = false
        }
    }

    fun onDestroy() {
        executor.submit {
            XLog.i(TAG, "onDestroy: closing conversation (engine stays in EngineHolder)")
            closeLocalConversation()
            isModelReady = false
        }
    }

    fun releaseForTask() {
        closeLocalConversation()
        isModelReady = false
    }

    /** Called before the task path reserves the only LiteRT conversation. */
    fun prepareForTaskStart() {
        closeLocalConversation()
        isModelReady = false
    }

    fun sendChat(text: String) {
        addUser(text)
        uiState.isAwaitingReply.value = true
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        executor.submit {
            try {
                if (cloudClient != null) {
                    ensureCloudHistoryInitialized()
                    cloudHistory.add(UserMessage.from(text))
                    val llmResponse = cloudClient!!.chat(cloudHistory, emptyList())
                    val responseText = llmResponse.text ?: "(no response)"
                    cloudHistory.add(AiMessage.from(responseText))
                    val usage = llmResponse.tokenUsage
                    val inputTokens = usage?.inputTokenCount() ?: (text.length / 4 + 1)
                    val outputTokens = usage?.outputTokenCount() ?: (responseText.length / 4 + 1)
                    val fallbackModelName = cloudModelName ?: ModelConfigRepository.snapshot().activeCloud.modelName
                    val modelTag = llmResponse.modelName ?: fallbackModelName
                    XLog.d(TAG, "sendChat: cloud response modelName='${llmResponse.modelName}', fallback='$fallbackModelName'")
                    postToMain {
                        replaceTypingIndicator(responseText, modelTag)
                        uiState.isAwaitingReply.value = false
                        uiState.sessionTokens.value += inputTokens + outputTokens
                        uiState.sessionCost.value += ModelPricing.estimateCost(modelTag, inputTokens, outputTokens)
                        onPersistConversation()
                    }
                } else {
                    val currentConversation = conversation
                    if (currentConversation == null || !isModelReady) {
                        throw IllegalStateException("Local model is still loading. Try again in a moment.")
                    }
                    LocalInferenceCoordinator.markBusy(LocalInferenceOwner.CHAT)
                    val response = try {
                        currentConversation.sendMessage(text)
                    } finally {
                        LocalInferenceCoordinator.markReady(
                            LocalInferenceOwner.CHAT,
                            LocalModelRuntime.currentBackendLabel(loadedModelPath),
                        )
                    }
                    val responseText = response?.toString() ?: "(no response)"
                    val inputTokensEst = text.length / 4 + 1
                    val outputTokensEst = responseText.length / 4 + 1
                    val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                    val localModelTag = localModelTag(modelPath)
                    postToMain {
                        replaceTypingIndicator(responseText, localModelTag)
                        uiState.isAwaitingReply.value = false
                        uiState.sessionTokens.value += inputTokensEst + outputTokensEst
                        onPersistConversation()
                    }
                }
            } catch (e: Exception) {
                val localPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                if (conversation != null &&
                    !LocalRuntimePolicy.isE4b(localPath) &&
                    LocalModelRuntime.isGpuBackendFailure(e)
                ) {
                    XLog.w(TAG, "GPU inference failed, falling back to CPU: ${e.message}")
                    try {
                        val modelPath = localPath
                        val responseText = retryLocalChatOnCpu(modelPath, text)
                        val inputTokensEst = text.length / 4 + 1
                        val outputTokensEst = responseText.length / 4 + 1
                        val cpuModelTag = localModelTag(modelPath)
                        postToMain {
                            replaceTypingIndicator(responseText, cpuModelTag)
                            uiState.isAwaitingReply.value = false
                            uiState.sessionTokens.value += inputTokensEst + outputTokensEst
                            updateLocalModelStatus(modelPath)
                            onPersistConversation()
                        }
                        return@submit
                    } catch (cpuError: Exception) {
                        XLog.e(TAG, "CPU fallback also failed", cpuError)
                    }
                }
                if (ModelConfigRepository.snapshot().isLocalActive()) {
                    if (LocalModelRuntime.isGpuBackendFailure(e)) {
                        LocalBackendHealth.noteRecoverableGpuFailure(localPath, e)
                    }
                    LocalInferenceCoordinator.markFailed(LocalInferenceOwner.CHAT, e)
                    closeLocalConversation()
                    isModelReady = false
                }
                XLog.e(TAG, "Chat error", e)
                postToMain {
                    replaceTypingIndicator("Error: ${e.message}")
                    if (ModelConfigRepository.snapshot().isLocalActive()) {
                        uiState.modelStatus.value = "⚠ Model error — tap to retry"
                        setButtonsEnabled(false)
                    }
                    uiState.isAwaitingReply.value = false
                }
            }
        }
    }

    fun switchModel(modelId: String, displayName: String) {
        if (modelId == "NONE") {
            uiState.modelStatus.value = "No model selected"
            isModelReady = false
            setButtonsEnabled(false)
            XLog.i(TAG, "switchModel: NONE — no model configured for current tab")
            return
        }
        if (modelId == "LOCAL") {
            val localConfig = ModelConfigRepository.snapshot().local
            if (!localConfig.isConfigured) {
                uiState.modelStatus.value = "No model selected"
                isModelReady = false
                setButtonsEnabled(false)
                XLog.i(TAG, "switchModel: LOCAL requested but no local default configured")
                return
            }
            ModelConfigRepository.activateLocal(localConfig.modelPath, localConfig.modelId)
            uiState.modelStatus.value = "● ${localConfig.displayName} · On-device"
            addSystem("Switched to local model")
            loadModelIfReady()
        } else {
            localUiGeneration++
            ModelConfigRepository.activateCloudSelection(modelId)
            suppressNextCloudSwitchMessage = true
            loadModelIfReady()
            addSystem("Switched to $displayName")
        }
        XLog.i(TAG, "Model switched to: $modelId ($displayName)")
    }

    fun startNewConversationRuntime() {
        if (cloudClient != null) {
            cloudHistory.clear()
            cloudHistory.add(SystemMessage.from(BASE_SYSTEM_PROMPT))
            postToMain {
                addSystem("New conversation started.")
                onRefreshSidebarHistory()
            }
            return
        }

        executor.submit {
            closeLocalConversation()
            val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
            if (modelPath.isNotEmpty()) {
                val lease = LocalModelRuntime.openConversation(
                    context = activity,
                    modelPath = modelPath,
                    conversationConfig = buildConversationConfig(),
                    owner = LocalInferenceOwner.CHAT,
                    maxNumTokens = LocalRuntimePolicy.maxNumTokens(modelPath, LocalInferenceOwner.CHAT),
                )
                engine = lease.engine
                conversation = lease.conversation
                isModelReady = true
            }
            postToMain {
                addSystem("New conversation started.")
                onRefreshSidebarHistory()
            }
        }
    }

    fun restoreConversationRuntime(conversationId: String, messages: List<ChatMessage>) {
        if (cloudClient != null) {
            rebuildCloudHistoryFromVisibleMessages()
            return
        }
        if (engine != null) {
            executor.submit {
                try {
                    closeLocalConversation()
                    val recentMsgs = messages.takeLast(5)
                    val systemPrompt = ConversationCompactor.buildRestoredSystemPrompt(activity, conversationId, recentMsgs)
                    val modelPath = ModelConfigRepository.snapshot().local.modelPath.ifEmpty { loadedModelPath.orEmpty() }
                    val lease = LocalModelRuntime.openConversation(
                        context = activity,
                        modelPath = modelPath,
                        conversationConfig = ConversationConfig(
                            systemInstruction = Contents.of(systemPrompt),
                            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
                        ),
                        owner = LocalInferenceOwner.CHAT,
                        maxNumTokens = LocalRuntimePolicy.maxNumTokens(modelPath, LocalInferenceOwner.CHAT),
                    )
                    engine = lease.engine
                    conversation = lease.conversation
                    isModelReady = true
                    postToMain {
                        setButtonsEnabled(true)
                        addSystem("Conversation restored.")
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to restore conversation", e)
                    postToMain { addSystem("History loaded. New context started.") }
                }
            }
        }
    }

    private fun loadModel(
        modelPath: String,
        generation: Long,
        restoredSystemPrompt: String? = null,
    ) {
        try {
            XLog.i(TAG, "loadModel: acquiring shared runtime for $modelPath")
            closeLocalConversation()

            val lease = LocalModelRuntime.openConversation(
                context = activity,
                modelPath = modelPath,
                conversationConfig = buildConversationConfig(restoredSystemPrompt),
                owner = LocalInferenceOwner.CHAT,
                maxNumTokens = LocalRuntimePolicy.maxNumTokens(modelPath, LocalInferenceOwner.CHAT),
            )
            engine = lease.engine
            XLog.i(TAG, "loadModel: engine ready (${lease.backendLabel})")
            conversation = lease.conversation

            isModelReady = true
            loadedModelPath = modelPath
            postToMain {
                if (!isLocalUiStillExpected(modelPath, generation)) {
                    XLog.i(TAG, "Ignoring stale local UI update for $modelPath (generation=$generation)")
                    return@postToMain
                }
                updateLocalModelStatus(modelPath)
                setButtonsEnabled(true)
            }
        } catch (e: Exception) {
            LocalInferenceCoordinator.markFailed(LocalInferenceOwner.CHAT, e)
            LocalInferenceCoordinator.release(LocalInferenceOwner.CHAT)
            XLog.e(TAG, "Model load failed", e)
            val isSessionConflict = LocalModelRuntime.isSessionConflict(e) ||
                e is LocalInferenceBusyException
            postToMain {
                if (isSessionConflict) {
                    uiState.modelStatus.value = "⚠ Model busy — tap model to retry"
                    addSystem("Model is being used by a background task. Wait for it to finish, then tap the model name above to reload.")
                    Toast.makeText(
                        activity,
                        "Model is busy. Wait for the task to finish, then tap the model name to retry.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val failure = e.message.orEmpty()
                    val status = when {
                        failure.contains("needs", ignoreCase = true) &&
                            failure.contains("free RAM", ignoreCase = true) ->
                            "⚠ E4B needs more free RAM — tap model to retry"
                        failure.contains("GPU/NPU", ignoreCase = true) ->
                            "⚠ E4B GPU/NPU unavailable — tap model to retry"
                        else -> "⚠ Load failed — tap model to retry"
                    }
                    uiState.modelStatus.value = status
                    addSystem("Failed to load model: ${failure.take(160)}")
                    Toast.makeText(
                        activity,
                        "Model load failed: ${failure.take(140)}",
                        Toast.LENGTH_LONG
                    ).show()
                }
                setButtonsEnabled(false)
            }
        }
    }

    private fun retryLocalChatOnCpu(modelPath: String, text: String): String {
        require(modelPath.isNotEmpty()) { "Local model path missing for CPU retry" }
        require(!LocalRuntimePolicy.isE4b(modelPath)) {
            "Gemma 4 E4B CPU fallback is disabled for interactive use."
        }
        closeLocalConversation()
        LocalModelRuntime.forceCpuEngine(
            activity,
            modelPath,
            LocalRuntimePolicy.maxNumTokens(modelPath, LocalInferenceOwner.CHAT),
        )
        val lease = LocalModelRuntime.openConversation(
            context = activity,
            modelPath = modelPath,
            conversationConfig = buildConversationConfig(),
            preferCpu = true,
            owner = LocalInferenceOwner.CHAT,
            maxNumTokens = LocalRuntimePolicy.maxNumTokens(modelPath, LocalInferenceOwner.CHAT),
        )
        engine = lease.engine
        loadedModelPath = modelPath
        conversation = lease.conversation
        XLog.i(TAG, "retryLocalChatOnCpu: CPU runtime ready, retrying sendMessage")
        return conversation!!.sendMessage(text)?.toString() ?: "(no response)"
    }

    private fun buildConversationConfig(systemPrompt: String? = null): ConversationConfig {
        val finalPrompt = io.agents.arya.agent.PromptUtils
            .applyGlobalPrompt(systemPrompt ?: BASE_SYSTEM_PROMPT)
        return ConversationConfig(
            systemInstruction = Contents.of(finalPrompt),
            samplerConfig = SamplerConfig(topK = 64, topP = 0.95, temperature = 0.7)
        )
    }

    private fun buildRestoredSystemPrompt(
        conversationId: String?,
        visibleMessages: List<ChatMessage>,
    ): String? {
        val meaningfulMessages = visibleMessages.filter {
            it.role == ChatMessage.Role.USER || it.role == ChatMessage.Role.ASSISTANT
        }
        if (conversationId.isNullOrBlank() || meaningfulMessages.isEmpty()) return null
        return ConversationCompactor.buildRestoredSystemPrompt(
            activity,
            conversationId,
            meaningfulMessages.takeLast(6)
        )
    }

    private fun rebuildCloudHistoryFromVisibleMessages() {
        cloudHistory.clear()
        cloudHistory.add(SystemMessage.from(BASE_SYSTEM_PROMPT))
        uiState.messages.forEach { msg ->
            when (msg.role) {
                ChatMessage.Role.USER -> cloudHistory.add(UserMessage.from(msg.content))
                ChatMessage.Role.ASSISTANT -> cloudHistory.add(AiMessage.from(msg.content))
                else -> Unit
            }
        }
    }

    private fun ensureCloudHistoryInitialized() {
        if (cloudHistory.isEmpty()) {
            rebuildCloudHistoryFromVisibleMessages()
        }
    }

    private fun replaceTypingIndicator(text: String, actualModelName: String? = null) {
        val modelTag = actualModelName
            ?: uiState.modelStatus.value.removePrefix("● ").split(" ·").firstOrNull()?.trim()
            ?: ""
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) {
            uiState.messages[idx] = ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag)
        } else {
            uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, text, modelName = modelTag))
        }
    }

    private fun addUser(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        val last = uiState.messages.lastOrNull()
        if (last?.role == ChatMessage.Role.SYSTEM && last.content.equals(text, ignoreCase = true)) {
            return
        }
        uiState.messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun updateLocalModelStatus(modelPath: String?) {
        if (modelPath.isNullOrEmpty()) {
            uiState.modelStatus.value = "No model selected"
            return
        }
        val modelInfo = LocalModelManager.AVAILABLE_MODELS.find { modelPath.endsWith(it.fileName) }
        val modelName = modelInfo?.displayName ?: modelPath.substringAfterLast('/').substringBeforeLast('.')
        val backendLabel = LocalModelRuntime.currentBackendLabel(modelPath) ?: "On-device"
        val budgetLabel = LocalRuntimePolicy.memoryBudget(activity, modelPath, LocalInferenceOwner.CHAT)
            .takeIf { LocalRuntimePolicy.isE4b(modelPath) && it.mode != LocalRuntimePolicy.E4bMemoryMode.FULL }
            ?.let { " · ${it.mode.label}" }
            .orEmpty()
        uiState.modelStatus.value = "● $modelName · $backendLabel$budgetLabel"
    }

    fun syncUiToActiveModel() {
        val config = ModelConfigRepository.snapshot()
        if (config.isLocalActive()) {
            val modelPath = config.local.modelPath
            if (modelPath.isNullOrBlank()) {
                uiState.modelStatus.value = "No model selected"
                setButtonsEnabled(false)
                return
            }
            if (loadedModelPath == modelPath && isModelReady && cloudClient == null) {
                updateLocalModelStatus(modelPath)
                setButtonsEnabled(true)
                return
            }
            loadModelIfReady()
            return
        }

        val cloud = config.activeCloud
        if (!cloud.isConfigured) {
            uiState.modelStatus.value = "No model selected"
            setButtonsEnabled(false)
            return
        }
        if (conversation != null) {
            closeLocalConversation()
            isModelReady = false
        }
        loadedModelPath = null
        if (cloudClient == null || cloudModelName != cloud.modelName || !isModelReady) {
            loadModelIfReady()
            return
        }
        uiState.modelStatus.value = "● ${cloud.modelName} · Cloud"
        setButtonsEnabled(true)
    }

    private fun isLocalUiStillExpected(modelPath: String, generation: Long): Boolean {
        val config = ModelConfigRepository.snapshot()
        return generation == localUiGeneration &&
            config.isLocalActive() &&
            config.local.modelPath == modelPath
    }

    private fun localModelTag(modelPath: String): String {
        val baseName = modelPath.takeIf { it.isNotEmpty() }?.let { File(it).nameWithoutExtension } ?: "Local"
        val backendLabel = LocalModelRuntime.currentBackendLabel(modelPath)
        return if (backendLabel.isNullOrBlank() || backendLabel.equals("GPU", ignoreCase = true)) {
            baseName
        } else {
            "$baseName ($backendLabel)"
        }
    }

    /** Close Arya's native chat session and surrender its single-session lease. */
    private fun closeLocalConversation(releaseLease: Boolean = true) {
        val current = conversation
        conversation = null
        try {
            current?.close()
        } catch (e: Exception) {
            XLog.w(TAG, "closeLocalConversation: close error", e)
        } finally {
            if (releaseLease) LocalInferenceCoordinator.release(LocalInferenceOwner.CHAT)
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        uiState.inputEnabled.value = enabled
    }

    private fun postToMain(action: () -> Unit) {
        activity.runOnUiThread(action)
    }
}
