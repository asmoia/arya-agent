// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.visual

/**
 * PHASE 2 — Reflection: verify an action by comparing screen state BEFORE vs
 * AFTER (Mobile-Agent-v2 style).
 *
 * This is the single most important reliability addition: it stops the agent
 * from blind-retrying a failed action — the exact failure mode that cascaded
 * into the "key failed" loop in the old design.
 */

/**
 * Result of reflecting on an action.
 * @param changed whether the screen state actually changed
 * @param reason human-readable explanation
 */
data class ReflectionResult(
    val changed: Boolean,
    val reason: String
)

/**
 * Compares screen state before and after an action to determine if the action
 * had the expected effect.
 */
class ActionReflector {

    companion object {
        private const val TAG = "ActionReflector"
    }

    /**
     * Reflect on an action by comparing the screen state before and after.
     *
     * @param before screen state before the action
     * @param after screen state after the action
     * @param expectedChange true if the action was supposed to navigate/alter UI
     * @return [ReflectionResult] describing whether the action succeeded
     */
    fun reflect(
        before: ScreenState?,
        after: ScreenState?,
        expectedChange: Boolean
    ): ReflectionResult {
        if (after == null) {
            return ReflectionResult(
                changed = false,
                reason = "screen unreadable after action (locked/off)"
            )
        }

        val beforeSig = before?.signature().orEmpty()
        val afterSig = after.signature()
        val changed = beforeSig != afterSig

        return if (expectedChange) {
            if (changed) {
                ReflectionResult(changed = true, reason = "screen changed as expected")
            } else {
                ReflectionResult(
                    changed = false,
                    reason = "NO change after action that should have navigated"
                )
            }
        } else {
            // Actions like 'finish' or 'type' may not change the element set;
            // treat as success unless we explicitly need a change.
            ReflectionResult(
                changed = true,
                reason = "non-navigating action; no structural check"
            )
        }
    }
}
