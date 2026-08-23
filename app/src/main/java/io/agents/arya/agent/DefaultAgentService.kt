package io.agents.arya.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import io.agents.arya.ClawApplication
import io.agents.arya.R
import io.agents.arya.agent.llm.ChatMsg
import io.agents.arya.agent.llm.LlmClient
import io.agents.arya.agent.llm.LlmClientFactory
import io.agents.arya.agent.llm.LlmEvent
import io.agents.arya.agent.llm.LlmResponse
import io.agents.arya.agent.llm.Role
import io.agents.arya.agent.llm.ToolCallSpec
import io.agents.arya.agent.llm.ToolSpec
import io.agents.arya.service.ClawAccessibilityService
import io.agents.arya.tool.ToolRegistry
import io.agents.arya.tool.ToolResult
import io.agents.arya.tool.impl.GetScreenInfoTool
import io.agents.arya.tool.toToolSpecs
import io.agents.arya.utils.XLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The single agent loop. Streams through LlmClient (local AIDL or cloud SSE).
 */
class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private const val MAX_API_RETRIES = 3
        private const val LOOP_DETECT_WINDOW = 4
        private const val SCREEN_SETTLE_MS = 500L
        private val ACTION_TOOLS = setOf(
            "phone_click_node", "phone_tap", "phone_swipe", "phone_long_press",
            "tap", "long_press", "swipe", "scroll_to_find",
            "input_text", "type_text", "system_key", "open_app",
            "clipboard", "send_file", "repeat_actions", "wait",
            "find_and_tap", "tap_node",
        )

        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: java.io.File? = null

        private const val LOCAL_TASK_PROMPT = """You are Arya, a bilingual (Persian/English) phone assistant. You control an Android phone using tools. Complete the user's task.

## Language
- Persian input → Persian reply (conversational). English input → English reply.
- App names stay in English.

## How to work
1. Call a tool first (open_app or get_screen_info).
2. Prefer find_and_tap(text=...) and wait_after on open_app.
3. Check the screen after each action.
4. finish(summary=real outcome). Stay under 8 steps when possible.

## Tools
- open_app, tap / tap_node / find_and_tap, input_text, system_key
- swipe / scroll_to_find, send_message, make_call
- get_device_info, get_notifications, clipboard, get_installed_apps
- take_screenshot, wait, finish

## Rules
- One tool call per turn when the outcome is uncertain.
- After 3 failures, finish and explain.
- finish(summary) must contain the actual data the user asked for.
- Never auto-fill passwords, confirm payments, or delete data.
- Never claim you cannot access a tool that exists."""
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private lateinit var toolSpecs: List<ToolSpec>
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var taskFuture: java.util.concurrent.Future<*>? = null

    override fun initialize(config: AgentConfig) {
        this.config = config
        val app = ClawApplication.instance
        this.llmClient = LlmClientFactory.create(app, config, app.engineClient)
        this.toolSpecs = ToolRegistry.getInstance().toToolSpecs()
        this.executor = Executors.newSingleThreadExecutor()
        XLog.i(TAG, "Agent initialized: provider=${config.provider} model=${config.modelName}")
    }

    override fun updateConfig(config: AgentConfig) {
        val prev = if (::config.isInitialized) this.config else null
        val sameModel = prev != null &&
            prev.provider == config.provider &&
            prev.baseUrl == config.baseUrl &&
            prev.modelName == config.modelName &&
            prev.apiKey == config.apiKey
        if (sameModel && ::llmClient.isInitialized) {
            this.config = config
            this.toolSpecs = ToolRegistry.getInstance().toToolSpecs()
            return
        }
        if (running.get()) cancel()
        executor?.shutdownNow()
        if (::llmClient.isInitialized) {
            try { llmClient.close() } catch (_: Exception) {}
        }
        initialize(config)
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }
        running.set(true)
        cancelled.set(false)
        var terminal: (() -> Unit)? = null
        val proxy = object : AgentCallback {
            override fun onLoopStart(round: Int) = callback.onLoopStart(round)
            override fun onContent(round: Int, content: String) = callback.onContent(round, content)
            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) =
                callback.onToolCall(round, toolId, toolName, parameters)
            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) =
                callback.onToolResult(round, toolId, toolName, parameters, result)
            override fun onTokenUpdate(status: TokenMonitor.Status) = callback.onTokenUpdate(status)
            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int, modelName: String?) {
                terminal = { callback.onComplete(round, finalAnswer, totalTokens, modelName) }
            }
            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                terminal = { callback.onError(round, error, totalTokens) }
            }
            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                terminal = { callback.onSystemDialogBlocked(round, totalTokens) }
            }
        }
        taskFuture = executor?.submit {
            try {
                runAgentLoop(userPrompt, proxy)
            } catch (e: Exception) {
                if (terminal == null) {
                    terminal = if (cancelled.get()) {
                        { callback.onComplete(0, ClawApplication.instance.getString(R.string.agent_task_cancel), 0) }
                    } else {
                        { callback.onError(0, e, 0) }
                    }
                }
            } finally {
                running.set(false)
                terminal?.invoke()
            }
        }
    }

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        if (ClawAccessibilityService.getInstance() == null && looksLikePhoneTask(userPrompt)) {
            // Non-interactive device-data tasks can still proceed; orchestrator already gated most cases.
        }

        val parsed = TaskPromptEnvelope.parse(userPrompt)
        val raw = parsed.currentRequest
        val inAppSearchGuard = InAppSearchGuard.fromTask(raw)
        val emailComposeGuard = EmailComposeGuard.fromTask(raw)
        val directDeviceDataGuard = DirectDeviceDataGuard.fromTask(raw)

        val playbook = if (config.provider.isLocal) {
            PlaybookManager.match(raw)?.let { "\n\n## Playbook: ${it.name}\n${it.body}" }.orEmpty()
        } else ""

        val system = buildString {
            append(if (config.provider.isLocal) LOCAL_TASK_PROMPT else config.systemPrompt)
            append(playbook)
            append(inAppSearchGuard.buildPromptSection())
            append(emailComposeGuard.buildPromptSection())
            append(directDeviceDataGuard.buildPromptSection())
            append(buildDeviceContext())
        }

        val messages = mutableListOf(
            ChatMsg(Role.SYSTEM, system),
            ChatMsg(Role.USER, enrichWithScreenIfTask(raw, parsed)),
        )

        var iterations = 0
        var totalTokens = 0
        var actualModelName: String? = null
        val maxIterations = if (config.provider.isLocal) minOf(config.maxIterations, 8) else config.maxIterations
        val tokenMonitor = TokenMonitor(config.modelName)
        val stuckDetector = StuckDetector()
        val taskBudget = TaskBudget.fromSettings()
        var lastScreenHash = 0
        val loopHistory = LinkedList<String>()

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)
            val response = try {
                chatOnce(messages, callback, iterations)
            } catch (e: Exception) {
                callback.onError(iterations, e, totalTokens)
                return
            }
            if (cancelled.get()) {
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                return
            }
            if (actualModelName == null) actualModelName = response.modelName
            response.tokenUsage?.totalTokenCount()?.let { totalTokens += it }
            tokenMonitor.record(
                step = iterations,
                inputTokens = response.tokenUsage?.inputTokenCount(),
                outputTokens = response.tokenUsage?.outputTokenCount(),
                totalTokenCount = response.tokenUsage?.totalTokenCount(),
            )
            callback.onTokenUpdate(tokenMonitor.getStatus())

            when (taskBudget.check(tokenMonitor.getStatus().totalTokens, tokenMonitor.getStatus().estimatedCostUsd)) {
                TaskBudget.Status.HARD_LIMIT -> {
                    callback.onComplete(iterations, "Stopped: budget limit reached.", totalTokens, actualModelName)
                    return
                }
                TaskBudget.Status.SOFT_LIMIT -> {
                    messages += ChatMsg(Role.USER, "[System] Approaching budget. Finish soon.")
                }
                TaskBudget.Status.OK -> {}
            }

            messages += ChatMsg(
                role = Role.ASSISTANT,
                content = response.text.orEmpty(),
                toolCalls = response.toolCalls,
            )

            if (!response.hasToolExecutionRequests()) {
                val text = response.text.orEmpty()
                if (text.isNotEmpty()) {
                    if (inAppSearchGuard.shouldBlockTextOnlyCompletion()) {
                        messages += ChatMsg(Role.USER, inAppSearchGuard.buildCompletionCorrection())
                        continue
                    }
                    if (directDeviceDataGuard.shouldBlockTextOnlyCompletion()) {
                        messages += ChatMsg(Role.USER, directDeviceDataGuard.buildCompletionCorrection())
                        continue
                    }
                    if (emailComposeGuard.shouldBlockTextOnlyCompletion()) {
                        messages += ChatMsg(Role.USER, emailComposeGuard.buildCompletionCorrection())
                        continue
                    }
                    callback.onComplete(iterations, text, totalTokens, actualModelName)
                    return
                }
                callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens, actualModelName)
                return
            }

            for (toolRequest in response.toolCalls) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
                    return
                }
                val toolName = toolRequest.name
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.argumentsJson.ifBlank { "{}" }
                val params = parseArgs(toolArgs)

                val blockedFinish = if (toolName == "finish") {
                    val screenInfo = try {
                        ToolRegistry.getInstance().getTool("get_screen_info")
                            ?.execute(emptyMap())?.takeIf { it.isSuccess }?.data
                    } catch (_: Exception) { null }
                    directDeviceDataGuard.maybeBlockFinish()
                        ?: inAppSearchGuard.maybeBlockFinish(screenInfo)
                        ?: emailComposeGuard.maybeBlockFinish(screenInfo)
                } else null
                if (blockedFinish != null) {
                    val blocked = ToolResult.error(blockedFinish)
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blocked)
                    messages += ChatMsg(Role.TOOL, blockedFinish, toolCallId = toolRequest.id)
                    continue
                }

                callback.onToolCall(iterations, toolName, displayName, toolArgs)
                directDeviceDataGuard.recordToolAttempt(toolName)
                emailComposeGuard.recordToolAttempt(toolName)
                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                callback.onToolResult(iterations, toolName, displayName, params.toString(), result)
                if (result.isSuccess) {
                    inAppSearchGuard.recordSuccessfulTool(toolName, params)
                    emailComposeGuard.recordSuccessfulTool(toolName)
                }
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    return
                }
                if (toolName == "finish" && result.isSuccess) {
                    callback.onComplete(
                        iterations,
                        result.data ?: ClawApplication.instance.getString(R.string.agent_task_completed),
                        totalTokens,
                        actualModelName,
                    )
                    return
                }

                var resultText = result.data ?: result.error ?: ""
                if (toolName in ACTION_TOOLS) {
                    try {
                        Thread.sleep(SCREEN_SETTLE_MS)
                        val screen = ToolRegistry.getInstance().getTool("get_screen_info")?.execute(emptyMap())
                        if (screen != null && screen.isSuccess && !screen.data.isNullOrBlank()) {
                            lastScreenHash = screen.data.hashCode()
                            resultText = "$resultText\n\nScreen after action:\n${screen.data}"
                        }
                    } catch (_: Exception) {}
                } else if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                    lastScreenHash = result.data.hashCode()
                }
                loopHistory.addLast("$toolName:$toolArgs:$lastScreenHash")
                if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
                messages += ChatMsg(Role.TOOL, resultText, toolCallId = toolRequest.id)
            }

            val lastAction = response.toolCalls.firstOrNull()?.let { "${it.name}:${it.argumentsJson.take(50)}" }.orEmpty()
            val detection = stuckDetector.record(lastAction, lastScreenHash, 0, null)
            if (detection != null) {
                if (detection.level == StuckDetector.RecoveryLevel.AUTO_KILL) {
                    callback.onComplete(iterations, "Stopped: agent was stuck (${detection.signal.description}).", totalTokens, actualModelName)
                    return
                }
                messages += ChatMsg(Role.USER, detection.recoveryHint)
            }
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens, actualModelName)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
        }
    }

    private fun chatOnce(messages: List<ChatMsg>, callback: AgentCallback, iteration: Int): LlmResponse {
        var last: Exception? = null
        val attempts = if (config.provider.isLocal) 1 else MAX_API_RETRIES
        repeat(attempts) { attempt ->
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                return collectResponse(messages, callback, iteration)
            } catch (e: Exception) {
                last = e
                if (attempt + 1 < attempts) {
                    try { Thread.sleep((1L shl attempt) * 1000L) } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
        throw last ?: IllegalStateException("LLM call failed")
    }

    private fun collectResponse(messages: List<ChatMsg>, callback: AgentCallback, iteration: Int): LlmResponse {
        var text = ""
        val tools = mutableListOf<ToolCallSpec>()
        var model: String? = null
        runBlocking {
            llmClient.chatStream(messages, toolSpecs).collect { ev ->
                when (ev) {
                    is LlmEvent.Text -> {
                        if (!ev.isReasoning) {
                            text += ev.delta
                            callback.onContent(iteration, ev.delta)
                        }
                    }
                    is LlmEvent.ToolCall -> tools += ToolCallSpec(
                        id = "call_${System.currentTimeMillis()}_${tools.size}",
                        name = ev.name,
                        argumentsJson = ev.argsJson,
                    )
                    is LlmEvent.Error -> throw IllegalStateException(ev.message)
                    is LlmEvent.Finished -> {}
                    else -> {}
                }
            }
        }
        return LlmResponse(text = text.ifEmpty { null }, toolCalls = tools, modelName = model)
    }

    private fun parseArgs(json: String): Map<String, Any> {
        return try {
            val o = JSONObject(json)
            val out = LinkedHashMap<String, Any>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.get(k)
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun looksLikePhoneTask(text: String): Boolean {
        val l = text.lowercase()
        return listOf("open ", "send ", "tap ", "باز ", "بفرست", "تلگرام", "واتساپ").any { l.contains(it) }
    }

    private fun enrichWithScreenIfTask(raw: String, parsed: TaskPromptEnvelope): String {
        val base = if (parsed.hasChatHistory || parsed.hasBackgroundState) {
            buildString {
                parsed.backgroundState?.let { append("Current background status:\n$it\n\n") }
                parsed.chatHistory?.let { append("Chatroom so far:\n$it\n\n") }
                append("Current user request:\n").append(raw)
            }
        } else raw
        if (!looksLikePhoneTask(raw)) return base
        return try {
            val screen = ToolRegistry.getInstance().getTool("get_screen_info")?.execute(emptyMap())
            if (screen != null && screen.isSuccess && !screen.data.isNullOrBlank()) {
                "$base\n\nCurrent screen:\n${screen.data}"
            } else base
        } catch (_: Exception) {
            base
        }
    }

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder("\n\n## Device Info\n")
        sb.append("- Brand: ").append(Build.BRAND).append('\n')
        sb.append("- Model: ").append(Build.MODEL).append('\n')
        sb.append("- Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        try {
            val wm = app.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- Screen: ").append(dm.widthPixels).append('x').append(dm.heightPixels).append('\n')
        } catch (_: Exception) {}
        return sb.toString()
    }

    override fun cancel() {
        cancelled.set(true)
        try { taskFuture?.cancel(true) } catch (_: Exception) {}
        if (::llmClient.isInitialized && config.provider.isLocal) {
            try {
                runBlocking { ClawApplication.instance.engineClient.cancelActive() }
            } catch (_: Exception) {}
        }
    }

    override fun shutdown() {
        cancel()
        executor?.shutdownNow()
        if (::llmClient.isInitialized) {
            try { llmClient.close() } catch (_: Exception) {}
        }
    }

    override fun isRunning(): Boolean = running.get()
}
