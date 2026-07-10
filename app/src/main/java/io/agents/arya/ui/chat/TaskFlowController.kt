// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.ui.chat

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.SnapshotStateList
import io.agents.arya.AppCapabilityCoordinator
import io.agents.arya.AppViewModel
import io.agents.arya.ServiceBindingState
import io.agents.arya.TaskEvent
import io.agents.arya.agent.DirectDeviceDataGuard
import io.agents.arya.agent.PipelineRouter
import io.agents.arya.agent.skill.SkillRegistry
import io.agents.arya.agent.TaskPromptEnvelope
import io.agents.arya.agent.TaskAcknowledgement
import io.agents.arya.agent.TaskPerfTrace
import io.agents.arya.agent.llm.ModelConfigRepository
import io.agents.arya.agent.llm.LocalInferenceOwner
import io.agents.arya.agent.llm.LocalRuntimePolicy
import io.agents.arya.service.ClawAccessibilityService
import io.agents.arya.service.ForegroundService
import io.agents.arya.service.AutoReplyManager
import io.agents.arya.tool.ToolRegistry
import io.agents.arya.ui.settings.SettingsActivity
import io.agents.arya.utils.KVUtils
import io.agents.arya.utils.XLog
import java.util.concurrent.ExecutorService

data class TaskFlowUiState(
    val messages: SnapshotStateList<ChatMessage>,
    val modelStatus: MutableState<String>,
    val isAwaitingReply: MutableState<Boolean>,
    val isTaskRunning: MutableState<Boolean>,
)

/**
 * Owns task-mode send flow, typed TaskEvent rendering, and monitor start wiring.
 *
 * ComposeChatActivity keeps the shell; this controller keeps task-specific behavior.
 */
class TaskFlowController(
    private val activity: ComponentActivity,
    private val executor: ExecutorService,
    private val appViewModel: AppViewModel,
    private val chatSessionController: ChatSessionController,
    private val currentConversationId: () -> String,
    private val uiState: TaskFlowUiState,
    private val onPersistConversation: () -> Unit,
    private val onTaskSettled: (() -> Unit)? = null,
    private val onTaskTerminal: ((TaskEvent) -> Unit)? = null,
) {

    companion object {
        private const val TAG = "TaskFlowController"
    }

    private var sendTaskRetryCount = 0
    private var lastMonitorStatusNote: String? = null
    private var activeTraceId: String? = null
    private val pipelineRouter = PipelineRouter(activity)

    fun sendTask(text: String) {
        if (appViewModel.isTaskRunning()) {
            addSystem("Another task is still running. Stop it first.")
            onTaskTerminal?.invoke(TaskEvent.Failed("Another task is still running. Stop it first."))
            return
        }

        // Pure chat / greetings must NEVER enter the heavy phone-agent loop on local models.
        // That path loads tools + long system prompts and can hang/heat the phone for minutes.
        if (isPureChatMessage(text)) {
            XLog.i(TAG, "sendTask: pure chat fast-path → sendChat (skip agent loop)")
            addSystem("حالت Task برای کار روی گوشی است. «$text» مثل چت عادی جواب داده می‌شود.")
            chatSessionController.sendChat(text)
            onTaskTerminal?.invoke(TaskEvent.Completed("Routed pure chat to chat mode."))
            return
        }

        if (ModelConfigRepository.snapshot().isLocalActive() && isLikelyMonitorRequest(text)) {
            addUser(text)
            addSystem("Local mode starts monitoring from the Background card. Open Background, choose the app/contact, then tap Start Monitoring.")
            onTaskTerminal?.invoke(TaskEvent.Failed("Local mode starts monitoring from the Background card."))
            return
        }

        DirectDeviceDataGuard.deterministicToolCall(text)?.let { directTool ->
            XLog.i(TAG, "sendTask: executing deterministic direct tool before LLM/accessibility gates")
            executeDirectToolTask(text, directTool)
            return
        }

        when (AppCapabilityCoordinator.accessibilityState(activity)) {
            ServiceBindingState.DISABLED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool without Accessibility")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task without Accessibility")
                } else {
                Toast.makeText(activity, "Enable Accessibility Service to run tasks", Toast.LENGTH_LONG).show()
                addSystem("⚠️ Task mode needs Accessibility Service enabled. Opening Settings...")
                openSettings()
                sendTaskRetryCount = 0
                onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility Service is required for this task."))
                return
                }
            }
            ServiceBindingState.CONNECTING -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Accessibility connects")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Accessibility connects")
                } else {
                if (sendTaskRetryCount >= 1) {
                    Toast.makeText(activity, "Accessibility service not connected. Try toggling it off and on.", Toast.LENGTH_LONG).show()
                    addSystem("Accessibility service didn't connect. Try toggling it off and on in Settings.")
                    openSettings()
                    sendTaskRetryCount = 0
                    onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility service did not connect."))
                    return
                }
                sendTaskRetryCount++
                addSystem("Accessibility service connecting, please wait...")
                executor.submit {
                    val connected = ClawAccessibilityService.awaitRunning(5000)
                    activity.runOnUiThread {
                        if (connected) {
                            sendTask(text)
                        } else {
                            Toast.makeText(activity, "Accessibility service didn't connect", Toast.LENGTH_LONG).show()
                            addSystem("Accessibility service didn't connect. Go to Settings and toggle it off then on.")
                            sendTaskRetryCount = 0
                            onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility service did not connect."))
                        }
                    }
                }
                return
                }
            }
            ServiceBindingState.DEGRADED -> {
                val directTool = DirectDeviceDataGuard.deterministicToolCall(text)
                if (directTool != null) {
                    XLog.i(TAG, "sendTask: executing non-interactive direct tool while Accessibility is degraded")
                    executeDirectToolTask(text, directTool)
                    return
                }
                if (canRunWithoutAccessibility(text)) {
                    XLog.i(TAG, "sendTask: allowing non-interactive task while Accessibility is degraded")
                } else {
                    Toast.makeText(activity, "Accessibility service disconnected. Open Settings and toggle it back on.", Toast.LENGTH_LONG).show()
                    addSystem("Accessibility service disconnected. Open Settings and toggle it off then on.")
                    openSettings()
                    sendTaskRetryCount = 0
                    onTaskTerminal?.invoke(TaskEvent.Failed("Accessibility service is disconnected."))
                    return
                }
            }
            ServiceBindingState.READY -> Unit
        }
        sendTaskRetryCount = 0

        ensureNotificationPermission()
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false

        val initialRoute = pipelineRouter.route(text)
        val needsLlm = initialRoute is PipelineRouter.Route.AgentLoop ||
            initialRoute is PipelineRouter.Route.Chat ||
            initialRoute is PipelineRouter.Route.PrimeThenAgent
        val routeMayFallbackToAgent = (initialRoute as? PipelineRouter.Route.Skill)
            ?.let { SkillRegistry.findById(it.skillId)?.allowAgentFallback == true }
            ?: false
        val localMode = ModelConfigRepository.snapshot().isLocalActive()

        if (needsLlm && hasLocalModelLoadFailure()) {
            addUser(text)
            val message = "مدل محلی آماده نیست. اول روی وضعیت مدل بالا بزن و مدل را دوباره بارگذاری کن؛ سپس task را اجرا کن."
            addSystem("⚠️ $message")
            onTaskTerminal?.invoke(TaskEvent.Failed(message))
            return
        }
        if (needsLlm && !KVUtils.hasLlmConfig()) {
            Toast.makeText(activity, "Configure LLM in Settings first", Toast.LENGTH_LONG).show()
            onTaskTerminal?.invoke(TaskEvent.Failed("Configure LLM in Settings first."))
            return
        }

        // A LiteRT Engine permits one native Conversation. Close Chat before an
        // agent task (or a skill that may explicitly escalate) takes its lease;
        // the old order reserved TASK first and left the Chat session alive.
        if (localMode && (needsLlm || routeMayFallbackToAgent)) {
            chatSessionController.prepareForTaskStart()
        }
        if (needsLlm && localMode) {
            val modelPath = ModelConfigRepository.snapshot().local.modelPath
            if (modelPath.isNotBlank()) {
                LocalRuntimePolicy.checkAdmission(activity, modelPath, LocalInferenceOwner.TASK)?.let { reason ->
                    addUser(text); addSystem("⚠️ $reason"); onTaskTerminal?.invoke(TaskEvent.Failed(reason)); return
                }
                LocalRuntimePolicy.checkBackendAdmission(modelPath)?.let { reason ->
                    addUser(text); addSystem("⚠️ $reason"); onTaskTerminal?.invoke(TaskEvent.Failed(reason)); return
                }
            }
        }

        val agentPromptOverride = buildAgentPromptOverride(text)
        // HermesBootstrapActions is the single owner of agent-loop bootstrap.
        // TaskFlowController only acknowledges and dispatches; it must not open
        // the same app a second time and reset the initial UI state.
        addUser(text)
        // Both flags true from the first second so Stop (✕) is always available.
        uiState.isAwaitingReply.value = true
        uiState.isTaskRunning.value = true
        XLog.i(TAG, "sendTask: isProcessing=TRUE isTaskRunning=TRUE")
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))
        addSystem("⚡ ${TaskAcknowledgement.forTask(text, initialRoute)}")

        val taskId = "task_${System.currentTimeMillis()}"
        activeTraceId = taskId
        TaskPerfTrace.start(taskId, initialRoute::class.simpleName ?: "unknown", text)
        TaskPerfTrace.mark(taskId, "acknowledged")

        executor.submit {
            // Bootstrap ownership is intentionally inside HermesAgentService.
            // Direct routes own their own app launch; this UI layer only dispatches.

            activity.runOnUiThread {
                try {
                    TaskPerfTrace.mark(taskId, "task_dispatched")
                    appViewModel.startTask(text, taskId, agentPromptOverride = agentPromptOverride) { event ->
                        activity.runOnUiThread { handleTaskEvent(event) }
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "sendTask failed: ${e.message}", e)
                    addSystem("Error: ${e.message}")
                    cleanupAfterTask()
                }
            }
        }
    }

    private fun executeDirectToolTask(text: String, toolCall: DirectDeviceDataGuard.DeterministicToolCall) {
        ensureNotificationPermission()
        addUser(text)
        uiState.isAwaitingReply.value = true
        uiState.isTaskRunning.value = false
        uiState.messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "..."))

        executor.submit {
            try {
                val result = ToolRegistry.getInstance().executeTool(toolCall.toolName, toolCall.params)
                activity.runOnUiThread {
                    val answer = result.data ?: result.error ?: "Done."
                    replaceTypingIndicator(answer)
                    onTaskTerminal?.invoke(TaskEvent.Completed(answer))
                    cleanupAfterTask()
                }
            } catch (e: Exception) {
                XLog.e(TAG, "executeDirectToolTask failed: ${e.message}", e)
                activity.runOnUiThread {
                    replaceTypingIndicator("Error: ${e.message}")
                    onTaskTerminal?.invoke(TaskEvent.Failed(e.message ?: "Direct tool failed"))
                    cleanupAfterTask()
                }
            }
        }
    }

    private fun canRunWithoutAccessibility(text: String): Boolean {
        if (DirectDeviceDataGuard.matchesNonInteractiveDeviceDataTask(text)) {
            return true
        }
        return when (pipelineRouter.route(text)) {
            is PipelineRouter.Route.DirectIntent -> true
            else -> false
        }
    }

    fun handleMonitorTask(text: String) {
        val target = MonitorTargetParser.fromTaskText(text)
        if (target == null) {
            addUser(text)
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        startMonitor(target, typedInput = text)
    }

    fun startMonitor(target: MonitorTargetSpec, typedInput: String? = null) {
        val trimmedLabel = target.label.trim()
        if (trimmedLabel.isEmpty()) {
            addSystem("Could not figure out who to monitor. Try: \"Monitor Mom on WhatsApp\"")
            return
        }

        typedInput?.let { addUser(it) }
        val missing = AppCapabilityCoordinator.missingMonitorRequirements(activity)
        if (missing.isNotEmpty()) {
            Toast.makeText(
                activity,
                "Enable ${missing.joinToString(" & ") { it.label }} in Settings first",
                Toast.LENGTH_LONG
            ).show()
            openSettings()
            onTaskTerminal?.invoke(TaskEvent.Failed("Missing required permissions for monitoring."))
            return
        }

        val contact = trimmedLabel
        val app = target.app
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        addSystem("Setting up auto-reply for $contact on $app...")

        val autoReplyManager = AutoReplyManager.getInstance()
        autoReplyManager.addTarget(contact, app)
        autoReplyManager.setEnabled(true)
        XLog.i(TAG, "startMonitor: enabled auto-reply for '${target.displayLabel}'")

        Handler(Looper.getMainLooper()).postDelayed({
            uiState.isAwaitingReply.value = false
            uiState.isTaskRunning.value = false
            addSystem("✓ Auto-reply is now active for ${target.displayLabel}.\nMonitoring in background — you can stop anytime from the bar above.")
            XLog.i(TAG, "startMonitor: monitor active, staying in Arya")
        }, 1500)
    }

    private fun handleTaskEvent(event: TaskEvent) {
        try {
            when (event) {
                is TaskEvent.Completed -> {
                    TaskPerfTrace.finish(activeTraceId, "completed")
                    activeTraceId = null
                    replaceTypingIndicator(event.answer, event.modelName)
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                    checkAutoReplyConfirmation()
                }
                is TaskEvent.Failed -> {
                    TaskPerfTrace.finish(activeTraceId, "failed:${event.error.take(80)}")
                    activeTraceId = null
                    replaceTypingIndicator(event.error)
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.Cancelled -> {
                    TaskPerfTrace.finish(activeTraceId, "cancelled")
                    activeTraceId = null
                    removeTypingIndicator()
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.Blocked -> {
                    replaceTypingIndicator("Blocked by system dialog.")
                    onTaskTerminal?.invoke(event)
                    cleanupAfterTask()
                }
                is TaskEvent.ToolAction -> {
                    TaskPerfTrace.mark(activeTraceId, "tool_requested", event.toolName)
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    if (!event.toolName.contains("Finish", ignoreCase = true)) {
                        removeTypingIndicator()
                        addSystem("${event.toolName}...")
                    }
                }
                is TaskEvent.ToolResult -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    if (!event.success) addSystem("${event.toolName} failed")
                }
                is TaskEvent.Response -> {
                    uiState.isAwaitingReply.value = false
                    replaceTypingIndicator(event.text)
                }
                is TaskEvent.Progress -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    addSystem(event.description)
                }
                is TaskEvent.LoopStart -> {
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                }
                is TaskEvent.Status -> {
                    TaskPerfTrace.mark(activeTraceId, "status", event.message)
                    uiState.isAwaitingReply.value = false
                    uiState.isTaskRunning.value = true
                    // Replace last status system line if consecutive, else add
                    val last = uiState.messages.lastOrNull()
                    val line = "⏳ ${event.message}"
                    if (last != null && last.role == ChatMessage.Role.SYSTEM && last.content.startsWith("⏳ ")) {
                        uiState.messages[uiState.messages.lastIndex] = ChatMessage(ChatMessage.Role.SYSTEM, line)
                    } else {
                        addSystem(line)
                    }
                }
                is TaskEvent.Thinking -> {
                    uiState.isTaskRunning.value = true
                    // stream partial text into typing bubble if present
                    val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && (it.content == "..." || it.content.startsWith("…")) }
                    // keep indicator; optional partial not required
                }
                is TaskEvent.TokenUpdate -> {
                    uiState.isTaskRunning.value = true
                }
            }
        } catch (e: Exception) {
            XLog.w(TAG, "handleTaskEvent error", e)
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
        onPersistConversation()
    }

    private fun removeTypingIndicator() {
        val idx = uiState.messages.indexOfLast { it.role == ChatMessage.Role.ASSISTANT && it.content == "..." }
        if (idx >= 0) uiState.messages.removeAt(idx)
    }

    private fun cleanupAfterTask() {
        XLog.i(TAG, "cleanupAfterTask: isProcessing=FALSE")
        uiState.isAwaitingReply.value = false
        uiState.isTaskRunning.value = false
        appViewModel.clearTaskCallback()
        onTaskSettled?.invoke()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                chatSessionController.loadModelIfReady(
                    conversationId = currentConversationId(),
                    visibleMessages = uiState.messages.toList(),
                )
            } catch (e: Exception) {
                XLog.e(TAG, "cleanupAfterTask: loadModel error", e)
            }
        }, 500)
    }

    private fun checkAutoReplyConfirmation() {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) {
            lastMonitorStatusNote = null
            return
        }
        val contacts = autoReplyManager.monitoredContacts.joinToString(", ")
        if (contacts.isBlank()) {
            lastMonitorStatusNote = null
            return
        }
        val note = "✓ Auto-reply active for $contacts.\nMonitoring in background — stop from bar above."
        if (note == lastMonitorStatusNote) return
        addSystem(note)
        lastMonitorStatusNote = note
        XLog.i(TAG, "checkAutoReplyConfirmation: monitor active, staying in Arya")
    }

    private fun ensureNotificationPermission() {
        if (!AppCapabilityCoordinator.isNotificationPermissionGranted(activity)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun addUser(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.USER, text))
    }

    private fun addSystem(text: String) {
        uiState.messages.add(ChatMessage(ChatMessage.Role.SYSTEM, text))
    }

    private fun openSettings() {
        activity.startActivity(Intent(activity, SettingsActivity::class.java))
    }

    private fun buildAgentPromptOverride(rawTask: String): String? {
        if (ModelConfigRepository.snapshot().isLocalActive()) {
            return null
        }

        val historyLines = CloudContextHandoffFormatter.conversationLines(uiState.messages)
        val backgroundStatus = buildBackgroundStatusContext()

        return TaskPromptEnvelope.build(
            chatHistoryLines = historyLines,
            currentRequest = rawTask,
            backgroundState = backgroundStatus,
        )
    }

    private fun buildBackgroundStatusContext(): String? {
        val autoReplyManager = AutoReplyManager.getInstance()
        if (!autoReplyManager.isEnabled) return null

        val contacts = autoReplyManager.monitoredContacts.toList()
        if (contacts.isEmpty()) return null

        return buildString {
            append("Background monitor active for: ")
            append(contacts.joinToString(", "))
            append('.')
        }
    }

    private fun hasLocalModelLoadFailure(): Boolean {
        if (!ModelConfigRepository.snapshot().isLocalActive()) return false
        val status = uiState.modelStatus.value.lowercase()
        return status.contains("model load failed") || status.contains("load failed") ||
            status.contains("failed to load model") || status.contains("model error") ||
            status.contains("e4b needs") || status.contains("gpu/npu unavailable")
    }

    private fun isLikelyMonitorRequest(text: String): Boolean {
        val lower = text.lowercase()
        val mentionsMonitor = lower.contains("monitor") ||
            lower.contains("auto-reply") ||
            lower.contains("auto reply") ||
            lower.contains("autoreply")
        val looksLikeWatchMessages = lower.contains("watch") &&
            (lower.contains("message") || lower.contains("messages") || lower.contains("reply"))
        return mentionsMonitor || looksLikeWatchMessages
    }
    /** Greetings / Q&A that should not spin the phone-control agent. */
    private fun isPureChatMessage(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val lower = t.lowercase()
        val taskHints = listOf(
            "open ", "send ", "tap ", "install ", "call ", "message ", "whatsapp", "telegram",
            "chrome", "browser", "youtube", "play ", "saved",
            "باز کن", "بفرست", "تماس", "پیام", "واتساپ", "تلگرام", "نصب", "تنظیمات",
            "monitor", "مانیتور", "اسکرین", "screenshot", "پخش", "پلی", "سیو", "آهنگ",
            "برو به", "برو تو", "میتونی بری", "می‌تونی بری", "کروم", "مرورگر", "یوتیوب"
        )
        if (taskHints.any { lower.contains(it) || t.contains(it) }) return false
        if (t.length > 40) return false
        val chatHints = listOf(
            "سلام", "درود", "خداحافظ", "ممنون", "مرسی", "خوبی", "چطوری",
            "hello", "hi", "hey", "thanks", "thank you", "ok", "okay", "باشه"
        )
        if (chatHints.any { lower == it || t == it || lower.startsWith("$it ") }) return true
        return t.length <= 16 && !t.contains("http")
    }


}
