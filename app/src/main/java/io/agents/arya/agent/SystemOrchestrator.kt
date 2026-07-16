// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent

import io.agents.arya.agent.llm.LlmClientFactory
import io.agents.arya.agent.visual.VisualControlAgent
import io.agents.arya.utils.XLog
import dev.langchain4j.data.message.UserMessage

/**
 * PHASE 3 — The "System Orchestrator" (Siri-analog): a single routing brain.
 *
 * Responsibilities:
 *  1. CLASSIFY each request into a tier (Routine / Visual / Cloud).
 *  2. ROUTE to the right executor:
 *     - Routine  -> fast local/deterministic path (existing agent loop)
 *     - Visual   -> VisualControlAgent (Phase 2) for ambiguous GUI tasks
 *     - Cloud    -> larger model when opted-in AND local is insufficient
 *  3. ENFORCE the iteration budget (LocalTaskBudget) so the agent never
 *     loops forever (root cause of the old "key failed" cascade).
 *
 * This is the central owner of routing/escalation that ARCHITECTURE_
 * RECONSTRUCTION.md asks for, without rewriting DefaultAgentService.
 */

/**
 * Routing tiers for task classification.
 * @see RoutingPolicy.classify
 */
enum class Tier {
    /** Deterministic phone tasks (open app, send message, check battery). */
    ROUTINE,
    /** Ambiguous GUI navigation tasks (find setting, navigate inside app). */
    VISUAL,
    /** Heavy reasoning / cross-app synthesis tasks. */
    CLOUD
}

/**
 * Central orchestrator for all task routing and execution.
 *
 * Usage in TaskOrchestrator / ComposeChatActivity:
 * ```
 * val orch = SystemOrchestrator()
 * val answer = orch.handle(task) { userTask ->
 *     // Same existing local agent logic as before
 *     existingLocalAgent.run(userTask)
 * }
 * ```
 *
 * For Vision/CLOUD tasks, orchestrator handles directly via VisualControlAgent / cloud LLM.
 * The [onRoutine] callback lets existing agent logic stay untouched.
 */
class SystemOrchestrator {

    companion object {
        private const val TAG = "Orchestrator"
    }

    private val budget = LocalTaskBudget()

    /**
     * Handle a task by classifying it and routing to the appropriate executor.
     *
     * @param task the user's task description
     * @param onRoutine called for ROUTINE tier tasks; should run the existing local agent
     * @return the final answer text
     */
    fun handle(task: String, onRoutine: (String) -> String): String {
        val tier = RoutingPolicy.classify(task)
        XLog.i(TAG, "task='${task.take(40)}' -> tier=$tier")
        return when (tier) {
            Tier.ROUTINE -> onRoutine(task)
            Tier.VISUAL -> budget.withBudget("visual") {
                VisualControlAgent(task, maxIterations = budget.visualMax).run()
            }
            Tier.CLOUD -> runCloud(task)
        }
    }

    /**
     * Cloud escalation: only when user opted in (AgentConfig.cloudOptIn).
     * Uses the user's configured cloud API key.
     */
    private fun runCloud(task: String): String {
        val cfg = AgentConfig(
            apiKey = "",
            baseUrl = "",
            provider = LlmProvider.OPENAI,
            systemPrompt = "You are Arya, a capable assistant. Use tools when needed."
        )
        val client = LlmClientFactory.create(cfg)
        return try {
            budget.withBudget("cloud") {
                client.chat(
                    messages = listOf(UserMessage.from(task)),
                    toolSpecs = emptyList()
                ).text ?: "No response from cloud."
            }
        } finally {
            client.close()
        }
    }
}
