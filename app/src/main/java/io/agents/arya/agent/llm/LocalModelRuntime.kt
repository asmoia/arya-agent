// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.SamplerConfig
import io.agents.arya.utils.XLog

data class LocalEngineLease(val engine: Engine, val backendLabel: String)
data class LocalConversationLease(val engine: Engine, val conversation: Conversation, val backendLabel: String)
data class LocalSingleShotResult(val text: String?, val backendLabel: String)

object LocalModelRuntime {
    private const val TAG = "LocalModelRuntime"
    private const val DEFAULT_RETRY_COUNT = 2
    private const val DEFAULT_RETRY_SLEEP_MS = 500L

    fun acquireSharedEngine(context: Context, modelPath: String, preferCpu: Boolean = false, maxNumTokens: Int = LocalRuntimePolicy.maxNumTokens(modelPath, LocalInferenceOwner.TASK), allowCpuFallback: Boolean = !LocalRuntimePolicy.isE4b(modelPath)): LocalEngineLease {
        val shouldUseCpu = LocalBackendHealth.shouldForceCpu(preferCpu)
        if (shouldUseCpu) {
            if (!allowCpuFallback) throw IllegalStateException("Gemma 4 E4B GPU/NPU backend is unavailable; CPU task fallback is disabled.")
            return LocalEngineLease(EngineHolder.getOrCreate(modelPath, context.cacheDir.path, Backend.CPU(), maxNumTokens), "CPU")
        }
        return try {
            val engine = EngineHolder.getOrCreate(modelPath, context.cacheDir.path, Backend.GPU(), maxNumTokens)
            LocalEngineLease(engine, EngineHolder.getBackendLabel(modelPath) ?: "GPU")
        } catch (e: Exception) {
            if (!isGpuBackendFailure(e)) throw e
            LocalBackendHealth.noteRecoverableGpuFailure(modelPath, e)
            if (!allowCpuFallback) throw IllegalStateException("Gemma 4 E4B GPU/NPU backend failed. Arya will not run this task on CPU.", e)
            XLog.w(TAG, "GPU runtime failed; using CPU fallback: ${e.message}")
            forceCpuEngine(context, modelPath, maxNumTokens)
        }
    }

    fun forceCpuEngine(context: Context, modelPath: String, maxNumTokens: Int = LocalRuntimePolicy.maxNumTokens(modelPath, LocalInferenceOwner.TASK)): LocalEngineLease {
        resetSharedEngine()
        return LocalEngineLease(EngineHolder.getOrCreate(modelPath, context.cacheDir.path, Backend.CPU(), maxNumTokens), "CPU")
    }
    fun resetSharedEngine() { try { EngineHolder.close() } catch (e: Exception) { XLog.w(TAG, "resetSharedEngine: ${e.message}") } }
    fun currentBackendLabel(modelPath: String?): String? = EngineHolder.getBackendLabel(modelPath)

    fun openConversation(context: Context, modelPath: String, conversationConfig: ConversationConfig, preferCpu: Boolean = false, owner: LocalInferenceOwner = LocalInferenceOwner.TASK, maxNumTokens: Int = LocalRuntimePolicy.maxNumTokens(modelPath, owner), maxRetries: Int = DEFAULT_RETRY_COUNT): LocalConversationLease {
        LocalRuntimePolicy.checkAdmission(context, modelPath, owner)?.let { throw IllegalStateException(it) }
        LocalInferenceCoordinator.acquire(owner, modelPath)
        var lastError: Exception? = null
        try {
            repeat(maxRetries) { attempt ->
                try {
                    val lease = acquireSharedEngine(context, modelPath, preferCpu, maxNumTokens, allowCpuFallback = !LocalRuntimePolicy.isE4b(modelPath))
                    val conversation = lease.engine.createConversation(conversationConfig)
                    LocalInferenceCoordinator.markReady(owner, lease.backendLabel)
                    return LocalConversationLease(lease.engine, conversation, lease.backendLabel)
                } catch (e: Exception) {
                    lastError = e
                    XLog.w(TAG, "openConversation attempt ${attempt + 1}/$maxRetries failed: ${e.message}")
                    if (isSessionConflict(e) || e.message?.contains("will not run this task on CPU") == true) throw e
                    if (attempt + 1 < maxRetries) Thread.sleep(DEFAULT_RETRY_SLEEP_MS)
                }
            }
            throw RuntimeException("Failed to create conversation after $maxRetries retries: ${lastError?.message}", lastError)
        } catch (e: Exception) {
            LocalInferenceCoordinator.markFailed(owner, e)
            LocalInferenceCoordinator.release(owner)
            throw e
        }
    }

    fun runSingleShot(context: Context, modelPath: String, systemPrompt: String, prompt: String, temperature: Double = 0.3, preferCpu: Boolean = false): LocalSingleShotResult {
        val owner = LocalInferenceOwner.BACKGROUND
        val lease = openConversation(context, modelPath, ConversationConfig(systemInstruction = Contents.of(systemPrompt), samplerConfig = SamplerConfig(topK = 32, topP = 0.9, temperature = temperature)), preferCpu, owner, LocalRuntimePolicy.maxNumTokens(modelPath, owner))
        return try {
            LocalInferenceCoordinator.markBusy(owner)
            val response = lease.conversation.sendMessage(prompt, emptyMap())
            LocalSingleShotResult(response.contents?.toString()?.trim(), lease.backendLabel)
        } finally {
            try { lease.conversation.close() } catch (e: Exception) { XLog.w(TAG, "singleShot close: ${e.message}") }
            LocalInferenceCoordinator.release(owner)
        }
    }

    fun isGpuBackendFailure(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        return message.contains("OpenCL", true) || message.contains("GPU", true) || message.contains("nativeSendMessage", true) || message.contains("Failed to create engine", true) || message.contains("compiled model", true)
    }
    fun isSessionConflict(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        return message.contains("A session already exists", true) || message.contains("Only one session is supported at a time", true) || message.contains("session already in use", true)
    }
}
