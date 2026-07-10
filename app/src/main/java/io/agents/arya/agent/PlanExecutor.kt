// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent

import io.agents.arya.tool.ToolRegistry

/**
 * Executes only compiler-produced bounded plans. Sensitive operations still
 * pass through ToolRegistry and SensitiveActionGate.
 */
class PlanExecutor {
    fun execute(
        plan: DeterministicPlan,
        onProgress: (step: Int, total: Int, description: String) -> Unit = { _, _, _ -> },
    ): DeterministicPlanResult {
        var lastSummary = plan.description
        for ((index, step) in plan.steps.withIndex()) {
            onProgress(index + 1, plan.steps.size, step.description)
            val result = ToolRegistry.getInstance().executeTool(step.toolName, step.params)
            if (!result.isSuccess) {
                return DeterministicPlanResult(
                    success = false,
                    failedStep = index + 1,
                    summary = "${step.description}: ${result.error ?: "ناموفق"}",
                )
            }
            lastSummary = result.data?.takeIf { it.isNotBlank() } ?: step.description
        }
        return DeterministicPlanResult(success = true, summary = lastSummary)
    }
}
