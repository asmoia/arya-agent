// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent.llm

import android.app.ActivityManager
import android.content.Context

/** E4B latency/memory policy for interactive Android use. */
object LocalRuntimePolicy {
    private const val MB = 1024L * 1024L
    private const val E4B_MIN_FREE_TASK_MB = 6_000L
    private const val E4B_MIN_FREE_CHAT_MB = 5_000L

    // One shared E4B Engine context means Chat↔Task handoff closes only Conversation.
    fun maxNumTokens(modelPath: String, owner: LocalInferenceOwner): Int = when (owner) {
        LocalInferenceOwner.TASK -> if (isE4b(modelPath)) 2_048 else 3_072
        LocalInferenceOwner.CHAT -> if (isE4b(modelPath)) 2_048 else 4_096
        LocalInferenceOwner.BACKGROUND -> 1_024
        LocalInferenceOwner.NONE -> if (isE4b(modelPath)) 2_048 else 3_072
    }

    fun checkAdmission(context: Context, modelPath: String, owner: LocalInferenceOwner): String? {
        if (!isE4b(modelPath)) return null
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val freeMb = info.availMem / MB
        val required = if (owner == LocalInferenceOwner.TASK) E4B_MIN_FREE_TASK_MB else E4B_MIN_FREE_CHAT_MB
        return if (freeMb < required) "Gemma 4 E4B needs ${required / 1000.0}GB free RAM for ${owner.name.lowercase()} (currently ${freeMb / 1000.0}GB). Close heavy apps or wait for memory to recover." else null
    }

    fun isE4b(modelPath: String): Boolean {
        val path = modelPath.lowercase()
        return path.contains("e4b") || path.contains("4b-it") || path.contains("gemma-4-4b")
    }
}
