// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent

/** A short, deterministic sequence compiled from an explicit user command. */
data class DeterministicPlan(
    val sourceText: String,
    val description: String,
    val steps: List<DeterministicPlanStep>,
    val allowAgentFallback: Boolean = false,
)

data class DeterministicPlanStep(
    val toolName: String,
    val params: Map<String, Any>,
    val description: String,
)

data class DeterministicPlanResult(
    val success: Boolean,
    val summary: String,
    val failedStep: Int? = null,
)
