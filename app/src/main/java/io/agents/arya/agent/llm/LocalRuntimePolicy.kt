// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent.llm

import android.app.ActivityManager
import android.content.Context

/**
 * E4B latency/memory policy for interactive Android use.
 *
 * Total RAM is not an admission signal. A 3.6 GB E4B file still needs enough
 * *available* RAM for native weights, a Conversation/KV cache, Android and the
 * foreground app. Refuse early rather than repeatedly trying to create a native
 * Conversation and leaving the user at a vague "load failed" state.
 */
object LocalRuntimePolicy {
    private const val MB = 1024L * 1024L
    private const val E4B_MIN_FREE_TASK_MB = 7_000L
    private const val E4B_MIN_FREE_CHAT_MB = 6_500L

    // 2K was needlessly aggressive on phones for the focused, short-context E4B
    // routes. 1536 keeps normal chat/task prompts viable while reducing the
    // Conversation allocation that was failing on 10–12 GB devices.
    fun maxNumTokens(modelPath: String, owner: LocalInferenceOwner): Int = when (owner) {
        LocalInferenceOwner.TASK -> if (isE4b(modelPath)) 1_536 else 3_072
        LocalInferenceOwner.CHAT -> if (isE4b(modelPath)) 1_536 else 4_096
        LocalInferenceOwner.BACKGROUND -> if (isE4b(modelPath)) 768 else 1_024
        LocalInferenceOwner.NONE -> if (isE4b(modelPath)) 1_536 else 3_072
    }

    fun requiredFreeMb(modelPath: String, owner: LocalInferenceOwner): Long? {
        if (!isE4b(modelPath)) return null
        return if (owner == LocalInferenceOwner.TASK) E4B_MIN_FREE_TASK_MB else E4B_MIN_FREE_CHAT_MB
    }

    /** A pure helper so the boundary can be unit-tested without an Android Context. */
    internal fun admissionReason(
        modelPath: String,
        owner: LocalInferenceOwner,
        availableMb: Long,
    ): String? {
        val required = requiredFreeMb(modelPath, owner) ?: return null
        if (availableMb >= required) return null
        return "Gemma 4 E4B needs ${formatGb(required)}GB free RAM for " +
            "${owner.name.lowercase()} (currently ${formatGb(availableMb)}GB). " +
            "Close heavy apps, return to Arya, then retry."
    }

    fun checkAdmission(context: Context, modelPath: String, owner: LocalInferenceOwner): String? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val availableMb = info.availMem / MB
        return admissionReason(modelPath, owner, availableMb)
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

    private fun formatGb(mb: Long): String = String.format(java.util.Locale.US, "%.1f", mb / 1_000.0)
}
