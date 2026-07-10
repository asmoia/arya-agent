// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent.llm

import android.app.ActivityManager
import android.content.Context

/**
 * Adaptive E4B admission policy for interactive Android use.
 *
 * Available RAM is a moving budget, not a binary device capability. The old
 * policy rejected a healthy 5–6 GB free-RAM phone before trying a smaller
 * Conversation. This policy preserves a hard safety floor, but automatically
 * shrinks E4B context in the middle band instead of forcing the user to close
 * apps whenever the full 1536-token profile is unavailable.
 */
object LocalRuntimePolicy {
    private const val MB = 1024L * 1024L

    // Below this floor E4B model + Android overhead has repeatedly failed during
    // native Conversation allocation. Above it Arya may enter a compact profile.
    private const val E4B_HARD_MIN_FREE_MB = 4_800L
    private const val E4B_COMPACT_FREE_MB = 5_500L
    private const val E4B_FULL_CHAT_FREE_MB = 6_500L
    private const val E4B_FULL_TASK_FREE_MB = 7_000L

    enum class E4bMemoryMode(val label: String) {
        FULL("Full"),
        BALANCED("Balanced"),
        COMPACT("Compact"),
        BLOCKED("Blocked"),
    }

    data class E4bMemoryBudget(
        val availableMb: Long,
        val mode: E4bMemoryMode,
        val maxNumTokens: Int,
        val admissionReason: String? = null,
    )

    // 1536 is the full interactive profile. Adaptive budget() may lower this
    // before Engine creation; non-E4B models retain their existing policy.
    fun maxNumTokens(modelPath: String, owner: LocalInferenceOwner): Int = when (owner) {
        LocalInferenceOwner.TASK -> if (isE4b(modelPath)) 1_536 else 3_072
        LocalInferenceOwner.CHAT -> if (isE4b(modelPath)) 1_536 else 4_096
        LocalInferenceOwner.BACKGROUND -> if (isE4b(modelPath)) 768 else 1_024
        LocalInferenceOwner.NONE -> if (isE4b(modelPath)) 1_536 else 3_072
    }

    /** Minimum free RAM that permits an adaptive E4B attempt, not the full profile. */
    fun requiredFreeMb(modelPath: String, owner: LocalInferenceOwner): Long? {
        if (!isE4b(modelPath)) return null
        return E4B_HARD_MIN_FREE_MB
    }

    fun preferredFreeMb(modelPath: String, owner: LocalInferenceOwner): Long? {
        if (!isE4b(modelPath)) return null
        return if (owner == LocalInferenceOwner.TASK) E4B_FULL_TASK_FREE_MB else E4B_FULL_CHAT_FREE_MB
    }

    fun memoryBudget(context: Context, modelPath: String, owner: LocalInferenceOwner): E4bMemoryBudget {
        return budgetForAvailable(modelPath, owner, availableMemoryMb(context))
    }

    /** Pure helper for JVM tests and diagnostics. */
    internal fun budgetForAvailable(
        modelPath: String,
        owner: LocalInferenceOwner,
        availableMb: Long,
    ): E4bMemoryBudget {
        val requested = maxNumTokens(modelPath, owner)
        if (!isE4b(modelPath)) {
            return E4bMemoryBudget(availableMb, E4bMemoryMode.FULL, requested)
        }
        if (availableMb < E4B_HARD_MIN_FREE_MB) {
            return E4bMemoryBudget(
                availableMb = availableMb,
                mode = E4bMemoryMode.BLOCKED,
                maxNumTokens = 0,
                admissionReason = "Gemma 4 E4B needs at least ${formatGb(E4B_HARD_MIN_FREE_MB)}GB free RAM to create a safe local conversation " +
                    "(currently ${formatGb(availableMb)}GB). Close heavy apps, then retry.",
            )
        }
        val fullThreshold = if (owner == LocalInferenceOwner.TASK) E4B_FULL_TASK_FREE_MB else E4B_FULL_CHAT_FREE_MB
        return when {
            availableMb >= fullThreshold -> E4bMemoryBudget(availableMb, E4bMemoryMode.FULL, requested)
            availableMb >= E4B_COMPACT_FREE_MB -> E4bMemoryBudget(
                availableMb,
                E4bMemoryMode.BALANCED,
                minOf(requested, 1_024),
            )
            else -> E4bMemoryBudget(
                availableMb,
                E4bMemoryMode.COMPACT,
                minOf(requested, 768),
            )
        }
    }

    /** A pure helper retained for existing tests/callers. */
    internal fun admissionReason(
        modelPath: String,
        owner: LocalInferenceOwner,
        availableMb: Long,
    ): String? = budgetForAvailable(modelPath, owner, availableMb).admissionReason

    fun checkAdmission(context: Context, modelPath: String, owner: LocalInferenceOwner): String? {
        return memoryBudget(context, modelPath, owner).admissionReason
    }

    /**
     * Applies the dynamic E4B budget without ever increasing a caller's more
     * restrictive request. This is the single place that turns ~6GB free RAM
     * into a 1024/768-token Conversation instead of a hard rejection.
     */
    fun effectiveMaxNumTokens(
        context: Context,
        modelPath: String,
        owner: LocalInferenceOwner,
        requestedMaxNumTokens: Int = maxNumTokens(modelPath, owner),
    ): Int {
        val budget = memoryBudget(context, modelPath, owner)
        require(budget.mode != E4bMemoryMode.BLOCKED) { budget.admissionReason ?: "Local model admission failed" }
        return minOf(requestedMaxNumTokens, budget.maxNumTokens)
    }

    /**
     * E4B is deliberately not silently moved to CPU: it turns a short phone
     * action into a multi-minute loop. A known GPU quarantine is surfaced before
     * an agent fallback starts.
     */
    fun checkBackendAdmission(modelPath: String): String? {
        if (!isE4b(modelPath)) return null
        if (!LocalBackendHealth.isCpuSafeModeEnabled()) return null
        return "Gemma 4 E4B GPU/NPU is currently unavailable on this device. " +
            "Arya will not run E4B tasks on CPU. Retry after restarting Arya or choose a different local model."
    }

    fun isE4b(modelPath: String): Boolean {
        val path = modelPath.lowercase()
        return path.contains("e4b") || path.contains("4b-it") || path.contains("gemma-4-4b")
    }

    private fun availableMemoryMb(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return Long.MAX_VALUE
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return info.availMem / MB
    }

    private fun formatGb(mb: Long): String = String.format(java.util.Locale.US, "%.1f", mb / 1_000.0)
}
