// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent

/**
 * PHASE 3 — Iteration budget enforcer.
 *
 * The OLD design used AgentConfig.maxIterations = 60, which let local loops
 * run for minutes and (when combined with a broken system_key) cascade into
 * the "key failed" failure. This caps local/visual work tightly.
 */

/**
 * Enforces per-tier step limits to prevent infinite loops.
 *
 * Usage:
 * ```
 * budget.withBudget("local") {
 *     // agent loop body
 * }
 * ```
 *
 * If the budget is exhausted, returns a stop message instead of running.
 */
class LocalTaskBudget(
    val localMax: Int = 5,
    val visualMax: Int = 8,
    val cloudMax: Int = 12
) {
    // @PublishedApi: the public inline function withBudget() below accesses this
    // map; a plain `private` member is not allowed in public-API inline bodies.
    @PublishedApi
    internal val counters = mutableMapOf<String, Int>()

    /**
     * Run [block] only while the budget for [key] allows; else return a stop message.
     */
    inline fun withBudget(key: String, block: () -> String): String {
        val used = counters[key] ?: 0
        val max = when (key) {
            "visual" -> visualMax
            "cloud" -> cloudMax
            else -> localMax
        }
        if (used >= max) {
            io.agents.arya.utils.XLog.w("TaskBudget", "budget exhausted for $key ($max)")
            return "Reached step limit ($max); stopping to avoid an endless loop."
        }
        counters[key] = used + 1
        return block()
    }

    fun reset(key: String) {
        counters[key] = 0
    }
}
