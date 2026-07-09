// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Embedded Hermes core — full in-process agent brain (no Termux / no external gateway).

package io.agents.arya.agent.hermes.core

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import io.agents.arya.ClawApplication
import io.agents.arya.R
import io.agents.arya.agent.AgentCallback
import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.AgentService
import io.agents.arya.agent.DirectDeviceDataGuard
import io.agents.arya.agent.EmailComposeGuard
import io.agents.arya.agent.InAppSearchGuard
import io.agents.arya.agent.LlmProvider
import io.agents.arya.agent.StuckDetector
import io.agents.arya.agent.TaskBudget
import io.agents.arya.agent.TaskPromptEnvelope
import io.agents.arya.agent.TokenMonitor
import io.agents.arya.agent.hermes.memory.HermesMemoryStore
import io.agents.arya.agent.hermes.session.HermesSessionStore
import io.agents.arya.agent.hermes.skills.HermesSkillStore
import io.agents.arya.agent.hermes.tools.HermesMetaTools
import io.agents.arya.agent.langchain.LangChain4jToolBridge
import io.agents.arya.agent.llm.LlmClient
import io.agents.arya.agent.llm.LlmClientFactory
import io.agents.arya.agent.llm.LlmResponse
import io.agents.arya.agent.llm.StreamingListener
import io.agents.arya.tool.ToolRegistry
import io.agents.arya.tool.ToolResult
import io.agents.arya.tool.impl.GetScreenInfoTool
import io.agents.arya.utils.XLog
import java.util.LinkedList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Self-contained Hermes agent loop running inside the Arya APK.
 *
 * - Uses the same phone [ToolRegistry] as DefaultAgentService
 * - Adds Hermes memory + skill meta-tools
 * - Persists sessions and learns across turns
 * - Does **not** require Termux or an external Hermes gateway
 */
class HermesAgentService : AgentService {

    companion object {
        private const val TAG = "HermesCore"
        private val GSON = Gson()
        private const val LOOP_DETECT_WINDOW = 6
        private const val SCREEN_SETTLE_MS = 280L
        private val ACTION_TOOLS = setOf(
            "tap", "tap_node", "long_press", "swipe", "scroll_to_find", "find_and_tap",
            "input_text", "system_key", "open_app", "send_message", "make_call",
            "emui_settings", "repeat_actions"
        )
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private lateinit var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>

    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "hermes-agent").apply { isDaemon = true }
    }
    private var taskFuture: Future<*>? = null

    private val sessions get() = HermesSessionStore.getInstance()
    private val memory get() = HermesMemoryStore.getInstance()
    private val skills get() = HermesSkillStore.getInstance()

    private var activeSessionId: String? = null

    override fun initialize(config: AgentConfig) {
        this.config = config
        HermesMetaTools.registerAll()
        // Seed skills lazily via SkillStore.getInstance(); don't rewrite files every task.
        this.llmClient = LlmClientFactory.create(config)
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        XLog.i(TAG, "Hermes embedded core initialized provider=${config.provider} tools=${toolSpecs.size}")
    }

    override fun updateConfig(config: AgentConfig) {
        val prev = if (::config.isInitialized) this.config else null
        this.config = config
        // CRITICAL: do NOT close/recreate the local engine on every task start.
        // startTask() calls updateAgentConfig() every time; killing LiteRT here made
        // Telegram bootstrap wait minutes (or forever) behind engine reload.
        val mustRecreateClient = prev == null ||
            prev.provider != config.provider ||
            prev.baseUrl != config.baseUrl ||
            prev.modelName != config.modelName ||
            prev.apiKey != config.apiKey ||
            !::llmClient.isInitialized
        if (mustRecreateClient) {
            if (::llmClient.isInitialized) {
                try {
                    llmClient.close()
                } catch (_: Exception) {
                }
            }
            this.llmClient = LlmClientFactory.create(config)
            XLog.i(TAG, "Hermes LlmClient recreated provider=${config.provider} model=${config.modelName}")
        } else {
            XLog.d(TAG, "Hermes config updated without engine recreate (temp=${config.temperature})")
        }
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        XLog.i(TAG, "Hermes core config tools=${toolSpecs.size}")
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (!running.compareAndSet(false, true)) {
            callback.onError(0, IllegalStateException("Hermes agent is already running"), 0)
            return
        }
        cancelled.set(false)
        taskFuture = executor.submit {
            try {
                runHermesLoop(userPrompt, callback)
            } catch (e: Exception) {
                if (!cancelled.get()) {
                    XLog.e(TAG, "Hermes loop crashed", e)
                    callback.onError(0, e, 0)
                }
            } finally {
                try {
                    HermesAppKeeper.onTaskEnd()
                } catch (_: Exception) {
                }
                running.set(false)
            }
        }
    }

    private fun runHermesLoop(userPrompt: String, callback: AgentCallback) {
        val parsed = TaskPromptEnvelope.parse(userPrompt)
        val rawUserRequest = parsed.currentRequest

        // Fast path: greetings / short chat must not load the full tool+phone agent loop.
        if (isPureChatFastPath(rawUserRequest)) {
            XLog.i(TAG, "pure chat fast-path (skip tool loop): '${rawUserRequest.take(40)}'")
            callback.onLoopStart(1)
            try {
                val reply = quickChatReply(rawUserRequest)
                callback.onContent(1, reply)
                callback.onComplete(1, reply, 0, config.modelName)
            } catch (e: Exception) {
                XLog.e(TAG, "pure chat failed", e)
                callback.onError(1, e, 0)
            }
            return
        }

        // === IMMEDIATE BOOTSTRAP (before prompt build / session / LLM) ===
        // User pain: minutes of silence, Telegram never opens. Do open_app NOW.
        HermesAppKeeper.onTaskStart("شروع…")
        callback.onStatus("شروع فوری · بدون انتظار مدل")
        val earlyBoot = HermesBootstrapActions.plan(rawUserRequest)
        val earlyBootResults = mutableListOf<Pair<HermesBootstrapActions.Step, ToolResult>>()
        if (earlyBoot != null) {
            XLog.i(TAG, "EARLY bootstrap plan=${earlyBoot.reason}")
            for ((idx, step) in earlyBoot.steps.withIndex()) {
                if (cancelled.get()) break
                callback.onStatus(step.labelFa)
                callback.onLoopStart(idx + 1)
                callback.onToolCall(idx + 1, step.tool, step.tool, com.google.gson.Gson().toJson(step.params))
                val result = try {
                    HermesBootstrapActions.execute(step)
                } catch (e: Exception) {
                    ToolResult.error(e.message ?: "bootstrap failed")
                }
                earlyBootResults += step to result
                callback.onToolResult(idx + 1, step.tool, step.tool, step.params.toString(), result)
                XLog.i(TAG, "EARLY bootstrap ${step.tool} success=${result.isSuccess} err=${result.error}")
                // Only hard-fail open_app; find_and_tap probes are optional.
                if (!result.isSuccess && HermesBootstrapActions.isHardStep(step)) break
                if (step.tool == "open_app" && result.isSuccess) {
                    try { Thread.sleep(350) } catch (_: Exception) {}
                }
            }
            val opened = earlyBootResults.any { it.first.tool == "open_app" && it.second.isSuccess }
            if (opened) callback.onStatus("اپ باز شد · ادامه…")
            else if (earlyBootResults.isNotEmpty()) {
                callback.onStatus("باز کردن اپ ناموفق — ادامه با مدل")
                val err = earlyBootResults.firstOrNull { !it.second.isSuccess }?.second?.error
                callback.onContent(1, "Bootstrap: $err")
            }
        } else {
            callback.onStatus("بدون bootstrap · شروع مدل…")
        }


        // Fresh session per task (memory/skills still persist across sessions).
        // Avoid resuming a half-open session from a crash mid-task.
        sessions.latestOpenSession()?.let { stale ->
            sessions.endSession(stale.id, "superseded")
        }
        val session = sessions.createSession(
            title = rawUserRequest.take(60),
            source = "arya",
            metadata = mapOf("provider" to config.provider.name)
        )
        activeSessionId = session.id
        sessions.appendMessage(session.id, "user", rawUserRequest)

        val inAppSearchGuard = InAppSearchGuard.fromTask(rawUserRequest)
        val emailComposeGuard = EmailComposeGuard.fromTask(rawUserRequest)
        val directDeviceDataGuard = DirectDeviceDataGuard.fromTask(rawUserRequest)

        val extraGuards = buildString {
            append(inAppSearchGuard.buildPromptSection())
            append(emailComposeGuard.buildPromptSection())
            append(directDeviceDataGuard.buildPromptSection())
        }

        val mcpSection = if (config.provider == LlmProvider.LOCAL) {
            ""
        } else try {
            io.agents.arya.agent.hermes.mcp.HermesMcpClient.buildPromptSection()
        } catch (_: Exception) { "" }
        // Local models: shorter base prompt = faster TTFT and fewer refusals.
        val baseIdentity = if (config.provider == LlmProvider.LOCAL) {
            HermesPromptBuilder.ARYA_LOCAL_TASK_IDENTITY
        } else {
            HermesPromptBuilder.ARYA_HERMES_IDENTITY
        }
        val systemPrompt = HermesPromptBuilder.build(
            basePrompt = baseIdentity,
            userTask = rawUserRequest,
            // Local E4B: skip memory vault dump (slow + huge). Skills only if matched.
            includeMemory = config.provider != LlmProvider.LOCAL,
            includeSkills = true,
            extraSections = extraGuards + mcpSection,
            compactTools = config.provider == LlmProvider.LOCAL,
        )

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(systemPrompt))

        val promptForModel = buildString {
            if (parsed.hasChatHistory || parsed.hasBackgroundState) {
                appendLine("You are continuing an existing chatroom.")
                parsed.backgroundState?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    appendLine("Background status:\n$it\n")
                }
                parsed.chatHistory?.trim()?.takeIf { it.isNotEmpty() }?.let {
                    appendLine("Chatroom so far:\n$it\n")
                }
            }
            appendLine("Current user request:")
            append(rawUserRequest)
        }

        val looksLikeTask = looksLikeTask(rawUserRequest)
        val alreadyHaveScreen = earlyBootResults.any { it.first.tool == "get_screen_info" && it.second.isSuccess }
        val enrichedPrompt = if (looksLikeTask && !alreadyHaveScreen) {
            try {
                val screenTool = ToolRegistry.getInstance().getTool("get_screen_info")
                val screenResult = screenTool?.execute(emptyMap())
                if (screenResult != null && screenResult.isSuccess && !screenResult.data.isNullOrBlank()) {
                    XLog.i(TAG, "pre-warm screen attached (${screenResult.data!!.length} chars)")
                    "$promptForModel\n\nCurrent screen:\n${screenResult.data}"
                } else promptForModel
            } catch (_: Exception) {
                promptForModel
            }
        } else {
            promptForModel
        }
        messages.add(UserMessage.from(enrichedPrompt))
        for ((step, result) in earlyBootResults) {
            val summary = if (result.isSuccess) (result.data ?: "ok").take(1200) else "ERROR: ${result.error}"
            messages.add(UserMessage.from("[Bootstrap] tool=${step.tool} → $summary"))
        }
        if (earlyBootResults.isNotEmpty()) {
            val bootScreen = earlyBootResults.lastOrNull { it.first.tool == "get_screen_info" && it.second.isSuccess }?.second?.data
            val nextHint = bootstrapNextHint(rawUserRequest, bootScreen)
            messages.add(
                UserMessage.from(
                    buildString {
                        appendLine("[System] App already opened + screen read. Do NOT open_app again.")
                        appendLine("Call ONE tool now toward the goal (find_and_tap/input_text/swipe/get_screen_info).")
                        appendLine("Prefer find_and_tap with max_scrolls=1 or 2 only when needed.")
                        if (nextHint.isNotBlank()) {
                            appendLine("Suggested next:")
                            appendLine(nextHint)
                        }
                        append("Goal: ${rawUserRequest.take(140)}")
                    }
                )
            )
        }

        var iterations = 0
        var totalTokens = 0
        var actualModelName: String? = null
        val policy = HermesRuntimePolicy.resolve(
            userTask = rawUserRequest,
            providerIsLocal = config.provider == LlmProvider.LOCAL
        )
        val maxIterations = if (config.provider == LlmProvider.LOCAL) policy.maxIterations else minOf(config.maxIterations, policy.maxIterations + 4)
        val screenSettleMs = policy.screenSettleMs
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0
        var previousScreenTexts: Set<String> = emptySet()
        val tokenMonitor = TokenMonitor(config.modelName)
        val stuckDetector = StuckDetector()
        val taskBudget = TaskBudget.fromSettings()
        var softLimitWarned = false
        var finalAnswer: String? = null
        val usedToolsThisTask = mutableListOf<String>()
        for ((step, _) in earlyBootResults) {
            usedToolsThisTask += step.tool
        }

        val recentActions = LinkedList<String>()
        var essayForceUsed = false

        HermesAppKeeper.onTaskStart("شروع task…")
        emitStatus(callback, policy, 0, "شروع · ${policy.resolvedMode.name} · ${HermesAppKeeper.memoryHintFa()}")



        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)
            val phaseLabel = if (usedToolsThisTask.isEmpty()) "فکر مدل…" else "ادامه اقدام…"
            emitStatus(callback, policy, iterations, "نوبت $iterations/$maxIterations · $phaseLabel · ${HermesAppKeeper.memoryHintFa()}")
            HermesAppKeeper.onTaskProgress("$phaseLabel $iterations/$maxIterations")
            if (HermesAppKeeper.isUnderMemoryPressure()) {
                XLog.w(TAG, "memory pressure mid-task — compress harder")
                HermesContextCompressor.compress(messages)
            }

            HermesContextCompressor.compress(messages)

            val llmResponse: LlmResponse = try {
                chatWithRetry(messages, callback, iterations)
            } catch (e: Exception) {
                if (cancelled.get()) {
                    XLog.i(TAG, "LLM aborted by cancel: ${e.message}")
                    val msg = ClawApplication.instance.getString(R.string.agent_task_cancel)
                    callback.onComplete(iterations, msg, totalTokens, actualModelName)
                    sessions.endSession(session.id, "cancelled")
                    return
                }
                XLog.e(TAG, "LLM call failed", e)
                callback.onError(
                    iterations,
                    RuntimeException(
                        ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)
                    ),
                    totalTokens
                )
                sessions.endSession(session.id, "error")
                return
            }

            if (cancelled.get()) {
                val msg = ClawApplication.instance.getString(R.string.agent_task_cancel)
                callback.onComplete(iterations, msg, totalTokens, actualModelName)
                sessions.endSession(session.id, "cancelled")
                return
            }

            if (actualModelName == null && !llmResponse.modelName.isNullOrEmpty()) {
                actualModelName = llmResponse.modelName
            }
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }
            tokenMonitor.record(
                step = iterations,
                inputTokens = llmResponse.tokenUsage?.inputTokenCount(),
                outputTokens = llmResponse.tokenUsage?.outputTokenCount(),
                totalTokenCount = llmResponse.tokenUsage?.totalTokenCount()
            )
            callback.onTokenUpdate(tokenMonitor.getStatus())

            when (taskBudget.check(tokenMonitor.getStatus().totalTokens, tokenMonitor.getStatus().estimatedCostUsd)) {
                TaskBudget.Status.HARD_LIMIT -> {
                    val status = tokenMonitor.getStatus()
                    val msg =
                        "Task stopped: budget limit (${status.formattedTokens}, ${status.formattedCost})."
                    callback.onComplete(iterations, msg, totalTokens, actualModelName)
                    postTurnLearn(session.id, rawUserRequest, msg, usedToolsThisTask)
                    sessions.endSession(session.id, "budget")
                    return
                }
                TaskBudget.Status.SOFT_LIMIT -> {
                    if (!softLimitWarned) {
                        softLimitWarned = true
                        messages.add(
                            UserMessage.from(
                                "[System] Approaching budget limit. Finish efficiently with finish(summary=...)."
                            )
                        )
                    }
                }
                TaskBudget.Status.OK -> Unit
            }

            XLog.i(
                TAG,
                "iter=$iterations tools=${llmResponse.toolExecutionRequests.size} text=${llmResponse.text?.take(200)}"
            )

            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)
            sessions.appendMessage(
                session.id,
                "assistant",
                llmResponse.text ?: "(tool_calls=${llmResponse.toolExecutionRequests.size})"
            )

            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                val suppress =
                    !llmResponse.hasToolExecutionRequests() &&
                        (inAppSearchGuard.shouldBlockTextOnlyCompletion() ||
                            emailComposeGuard.shouldBlockTextOnlyCompletion())
                if (!suppress) callback.onContent(iterations, llmResponse.text)
            }

            // Text-only completion
            if (!llmResponse.hasToolExecutionRequests()) {
                val responseText = llmResponse.text.orEmpty()
                if (responseText.isNotEmpty()) {
                    if (inAppSearchGuard.shouldBlockTextOnlyCompletion()) {
                        messages.add(UserMessage.from(inAppSearchGuard.buildCompletionCorrection()))
                        continue
                    }
                    if (directDeviceDataGuard.shouldBlockTextOnlyCompletion()) {
                        messages.add(UserMessage.from(directDeviceDataGuard.buildCompletionCorrection()))
                        continue
                    }
                    if (emailComposeGuard.shouldBlockTextOnlyCompletion()) {
                        messages.add(UserMessage.from(emailComposeGuard.buildCompletionCorrection()))
                        continue
                    }
                    // Phone tasks must not end as a pure essay without tools.
                    if (policy.forceToolOnEssay && looksLikeTask(rawUserRequest) &&
                        usedToolsThisTask.isEmpty() && !essayForceUsed && iterations <= 3
                    ) {
                        essayForceUsed = true
                        XLog.w(TAG, "text-only on phone task — forcing tool use")
                        emitStatus(callback, policy, iterations, "اجبار اقدام (بدون tool حرف نزن)")
                        messages.add(
                            UserMessage.from(
                                "[System] ACTION REQUIRED: call open_app or get_screen_info NOW. " +
                                    "No essays. No 'I cannot access'. Tool call only this turn. Goal: ${rawUserRequest.take(100)}"
                            )
                        )
                        continue
                    }
                    finalAnswer = responseText
                    callback.onComplete(iterations, responseText, totalTokens, actualModelName)
                    postTurnLearn(session.id, rawUserRequest, responseText, usedToolsThisTask)
                    sessions.endSession(session.id, "completed")
                    return
                }
                callback.onComplete(
                    iterations,
                    ClawApplication.instance.getString(R.string.agent_task_completed),
                    totalTokens,
                    actualModelName
                )
                sessions.endSession(session.id, "empty")
                return
            }

            // Execute tools
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    val msg = ClawApplication.instance.getString(R.string.agent_task_cancel)
                    callback.onComplete(iterations, msg, totalTokens, actualModelName)
                    sessions.endSession(session.id, "cancelled")
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any> = try {
                    GSON.fromJson(toolArgs, mapType) ?: emptyMap()
                } catch (e: Exception) {
                    XLog.w(TAG, "Bad tool args $toolName: $toolArgs", e)
                    emptyMap()
                }

                val blockedFinish = if (toolName == "finish") {
                    val screenInfo = try {
                        ToolRegistry.getInstance()
                            .getTool("get_screen_info")
                            ?.execute(emptyMap())
                            ?.takeIf { it.isSuccess }
                            ?.data
                    } catch (_: Exception) {
                        null
                    }
                    directDeviceDataGuard.maybeBlockFinish()
                        ?: inAppSearchGuard.maybeBlockFinish(screenInfo)
                        ?: emailComposeGuard.maybeBlockFinish(screenInfo)
                } else null

                if (blockedFinish != null) {
                    val blockedResult = ToolResult.error(blockedFinish)
                    callback.onToolCall(iterations, toolName, displayName, toolArgs)
                    callback.onToolResult(iterations, toolName, displayName, params.toString(), blockedResult)
                    messages.add(ToolExecutionResultMessage.from(toolRequest, GSON.toJson(blockedResult)))
                    messages.add(UserMessage.from(blockedFinish))
                    continue
                }

                callback.onToolCall(iterations, toolName, displayName, toolArgs)
                emitStatus(
                    callback, policy, iterations,
                    "اقدام: ${HermesStatusMessages.toolLabelFa(toolName)}"
                )
                directDeviceDataGuard.recordToolAttempt(toolName)
                emailComposeGuard.recordToolAttempt(toolName)

                if (toolName.isNotEmpty()) usedToolsThisTask += toolName
                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                callback.onToolResult(iterations, toolName, displayName, params.toString(), result)
                sessions.appendMessage(
                    session.id,
                    "tool",
                    if (result.isSuccess) result.data ?: "ok" else result.error ?: "error",
                    toolName
                )

                if (result.isSuccess) {
                    inAppSearchGuard.recordSuccessfulTool(toolName, params)
                    emailComposeGuard.recordSuccessfulTool(toolName)
                }

                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    sessions.endSession(session.id, "system_dialog")
                    return
                }

                if (toolName == "finish" && result.isSuccess) {
                    finalAnswer = result.data
                        ?: ClawApplication.instance.getString(R.string.agent_task_completed)
                    callback.onComplete(iterations, finalAnswer!!, totalTokens, actualModelName)
                    postTurnLearn(session.id, rawUserRequest, finalAnswer!!, usedToolsThisTask)
                    sessions.endSession(session.id, "completed")
                    return
                }

                val combinedJson: String = if (toolName in ACTION_TOOLS) {
                    try {
                        Thread.sleep(screenSettleMs)
                        val screenAfter = ToolRegistry.getInstance()
                            .getTool("get_screen_info")
                            ?.execute(emptyMap())
                        if (screenAfter != null && screenAfter.isSuccess && !screenAfter.data.isNullOrBlank()) {
                            lastScreenHash = screenAfter.data!!.hashCode()
                            val currentTexts = screenAfter.data!!.lines()
                                .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                            val added = currentTexts - previousScreenTexts
                            val removed = previousScreenTexts - currentTexts
                            previousScreenTexts = currentTexts
                            val diff = buildString {
                                if (added.isNotEmpty()) append("\nNew on screen: ${added.take(10).joinToString()}")
                                if (removed.isNotEmpty()) append("\nGone: ${removed.take(10).joinToString()}")
                            }
                            val enrichedData = "${result.data ?: ""}\n\nScreen after action:\n${screenAfter.data}$diff"
                            val enriched = if (result.isSuccess) ToolResult.success(enrichedData)
                            else ToolResult.error(result.error ?: "")
                            GSON.toJson(enriched)
                        } else GSON.toJson(result)
                    } catch (e: Exception) {
                        XLog.w(TAG, "screen attach failed after $toolName", e)
                        GSON.toJson(result)
                    }
                } else {
                    if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                        lastScreenHash = result.data.hashCode()
                    }
                    GSON.toJson(result)
                }

                if (toolName.isNotEmpty()) {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) loopHistory.removeFirst()
                }

                messages.add(ToolExecutionResultMessage.from(toolRequest, combinedJson))

                // Action fingerprint for repeat detection
                val actionKey = "$toolName:${toolArgs.take(80)}"
                recentActions.addLast(actionKey)
                if (recentActions.size > 6) recentActions.removeFirst()
                if (recentActions.count { it == actionKey } >= policy.maxSameActionRepeats) {
                    messages.add(
                        UserMessage.from(
                            "[System] You repeated $toolName too many times. Change strategy (new text target / swipe / different app path). Do not retry the same action."
                        )
                    )
                }

                // After open_app / navigation: force progress — don't die after first tool.
                if (result.isSuccess && toolName != "finish" && toolName != "get_screen_info" &&
                    policy.autoScreenAfterAction
                ) {
                    emitStatus(callback, policy, iterations, "ادامه بعد از ${HermesStatusMessages.toolLabelFa(toolName)}…")
                    messages.add(
                        UserMessage.from(
                            "[System] Action done. NEXT must be a tool: get_screen_info then find_and_tap/input_text/swipe toward the user goal. " +
                                "Do not stop with only text. Goal: ${rawUserRequest.take(120)}"
                        )
                    )
                }
            }

            // Stuck recovery
            val lastAction = llmResponse.toolExecutionRequests.firstOrNull()?.let {
                "${it.name()}:${it.arguments()?.take(50)}"
            } ?: ""
            val detection = stuckDetector.record(lastAction, lastScreenHash, previousScreenTexts.size, null)
            if (detection != null) {
                when (detection.level) {
                    StuckDetector.RecoveryLevel.AUTO_KILL -> {
                        val status = tokenMonitor.getStatus()
                        val msg =
                            "Task stopped: stuck (${detection.signal.description}). ${status.formattedTokens}"
                        callback.onComplete(iterations, msg, totalTokens, actualModelName)
                        sessions.endSession(session.id, "stuck")
                        return
                    }
                    else -> messages.add(UserMessage.from(detection.recoveryHint))
                }
            }
        }

        if (cancelled.get()) {
            callback.onComplete(
                iterations,
                ClawApplication.instance.getString(R.string.agent_task_cancel),
                totalTokens,
                actualModelName
            )
            sessions.endSession(session.id, "cancelled")
        } else {
            callback.onError(
                iterations,
                RuntimeException(
                    ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)
                ),
                totalTokens
            )
            sessions.endSession(session.id, "max_iterations")
        }
    }

    /**
     * Post-turn learning: episodic log + light profile note for non-trivial tasks.
     * Mirrors Hermes background memory/skill review in a lightweight form.
     */
    
    private fun postTurnLearn(sessionId: String, userTask: String, answer: String, usedTools: List<String> = emptyList()) {
        try {
            HermesLearning.learnFromTurn(userTask, answer, usedTools)
            XLog.i(TAG, "postTurnLearn session=$sessionId tools=${usedTools.size}")
        } catch (e: Exception) {
            XLog.w(TAG, "postTurnLearn failed", e)
        }
    }

    private fun chatWithRetry(
        messages: List<ChatMessage>,
        callback: AgentCallback,
        iteration: Int
    ): LlmResponse {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            if (cancelled.get()) throw RuntimeException("cancelled")
            try {
                return if (config.streaming) {
                    llmClient.chatStreaming(messages, toolSpecs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, toolSpecs)
                }
            } catch (e: Exception) {
                lastError = e
                XLog.w(TAG, "LLM attempt ${attempt + 1} failed: ${e.message}")
                Thread.sleep(250L * (attempt + 1))
            }
        }
        throw lastError ?: RuntimeException("LLM failed")
    }


    /** Lightweight next-step hints from goal + last screen text (no LLM). */
    private fun bootstrapNextHint(goal: String, screen: String?): String {
        val g = goal.lowercase()
        val fa = goal
        val s = screen?.take(2500).orEmpty()
        val sl = s.lowercase()
        return buildString {
            when {
                fa.contains("سیو") || g.contains("saved") || fa.contains("ذخیره") -> {
                    appendLine("- find_and_tap text='Saved Messages' max_scrolls=1")
                    appendLine("- or find_and_tap text='پیام‌های ذخیره‌شده' max_scrolls=1")
                    appendLine("- or find_and_tap search icon / Search then input_text")
                    if (sl.contains("saved") || s.contains("ذخیره")) {
                        appendLine("- Label may already be on screen — tap it first without scrolling")
                    }
                }
                g.contains("chrome") || fa.contains("کروم") || fa.contains("مرورگر") || g.contains("search") || fa.contains("سرچ") -> {
                    appendLine("- find_and_tap URL/search bar (Search or جستجو) max_scrolls=1")
                    appendLine("- input_text the query, system_key enter")
                }
                g.contains("whatsapp") || fa.contains("واتس") -> {
                    appendLine("- find_and_tap Search / جستجو max_scrolls=1 then type contact")
                }
                else -> {
                    appendLine("- get_screen_info if unsure, then find_and_tap visible target max_scrolls=1")
                }
            }
            if (fa.contains("پخش") || fa.contains("پلی") || g.contains("play") || fa.contains("آهنگ") || fa.contains("ویس")) {
                appendLine("- After opening the right chat/list: tap a play/voice/audio control on screen")
            }
        }.trim()
    }

    private fun looksLikeTask(text: String): Boolean {
        val lower = text.lowercase()
        val en = listOf(
            "open ", "send ", "tap ", "search ", "play ", "take ", "install ",
            "click ", "go to ", "navigate ", "turn on ", "turn off ", "monitor ",
            "close ", "swipe ", "scroll ", "check ", "compose ", "find ", "screen",
            "notification", "read my", "call ", "dial ", "telegram", "whatsapp",
            "chrome", "browser", "youtube", "spotify", "saved message"
        )
        val fa = listOf(
            "باز کن", "بفرست", "بزن", "جستجو", "پیدا کن", "نصب", "ببند",
            "پیام", "تماس", "تنظیمات", "واتساپ", "تلگرام", "اسکرین", "اعلان",
            "روشن", "خاموش", "برو به", "چک کن", "بخون", "بخوان", "سیو", "پخش",
            "پلی", "آهنگ", "موسیقی", "مرورگر", "کروم", "یوتیوب", "برو تو",
            "میتونی بری", "می‌تونی بری", "بازش کن"
        )
        if (HermesBootstrapActions.plan(text) != null) return true
        return en.any { lower.contains(it) } || fa.any { text.contains(it) }
    }


    private fun isPureChatFastPath(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        // Any phone/bootstrap task is NEVER pure chat — even if it ends with ؟
        if (looksLikeTask(t) || HermesBootstrapActions.plan(t) != null) return false
        if (t.length > 40) return false
        val lower = t.lowercase()
        val greetings = listOf(
            "سلام", "درود", "hello", "hi", "hey", "خوبی", "چطوری", "صبح بخیر",
            "شب بخیر", "ممنون", "مرسی", "thanks", "ok", "okay", "باشه"
        )
        if (greetings.any { lower == it || t == it || lower.startsWith("$it ") || t.startsWith("$it ") }) return true
        return t.length <= 16 && !t.contains("http") && !t.contains("؟") && !t.contains("?")
    }

    private fun quickChatReply(userText: String): String {
        // Minimal prompt — no tool specs, no screen, no 60-step agent.
        val system = "تو آریا هستی، دستیار فارسی کوتاه و مودب. فقط جواب بده. ابزار صدا نزن."
        val messages = listOf(
            dev.langchain4j.data.message.SystemMessage.from(system),
            dev.langchain4j.data.message.UserMessage.from(userText)
        )
        // Empty tool list → model cannot tool-call, finishes as text.
        val response = llmClient.chat(messages, emptyList())
        val text = response.text?.trim().orEmpty()
        return text.ifBlank { "سلام! چطور می‌تونم کمکت کنم؟" }
    }

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)


    private fun emitStatus(
        callback: AgentCallback,
        policy: HermesRuntimeSnapshot,
        round: Int,
        messageFa: String
    ) {
        val msg = buildString {
            if (round > 0) append("[$round/${policy.maxIterations}] ")
            append(messageFa)
        }
        try {
            callback.onStatus(msg)
        } catch (e: Exception) {
            XLog.w(TAG, "onStatus: ${e.message}")
        }
        HermesAppKeeper.onTaskProgress(msg)
    }

    override fun cancel() {
        cancelled.set(true)
        XLog.i(TAG, "cancel requested provider=${if (::config.isInitialized) config.provider else null}")
        // Always interrupt the worker. For LOCAL, also close the LLM conversation so
        // LiteRT is less likely to keep the phone hot after the user hits ✕.
        try {
            taskFuture?.cancel(true)
        } catch (e: Exception) {
            XLog.w(TAG, "taskFuture.cancel: ${e.message}")
        }
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
                XLog.i(TAG, "cancel: llmClient.close() to stop local generation pressure")
            } catch (e: Exception) {
                XLog.w(TAG, "cancel close llm: ${e.message}")
            }
        }
        activeSessionId?.let { sid ->
            try {
                sessions.getSession(sid)?.let { s ->
                    if (s.endedAt == null && (taskFuture == null || taskFuture?.isDone == true)) {
                        sessions.endSession(sid, "cancelled_externally")
                    }
                }
            } catch (e: Exception) {
                XLog.w(TAG, "cancel session cleanup: ${e.message}")
            }
        }
    }

    override fun shutdown() {
        cancel()
        try {
            executor.shutdownNow()
        } catch (_: Exception) {
        }
        if (::llmClient.isInitialized) {
            try {
                llmClient.close()
            } catch (_: Exception) {
            }
        }
        activeSessionId?.let {
            try {
                sessions.endSession(it, "shutdown")
            } catch (_: Exception) {
            }
            activeSessionId = null
        }
    }

    override fun isRunning(): Boolean = running.get()
}
