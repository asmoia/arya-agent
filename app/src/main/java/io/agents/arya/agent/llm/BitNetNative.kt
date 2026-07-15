// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.llm

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.agents.arya.utils.XLog

/**
 * JNI bridge to llama.cpp — v0.6.0 with telemetry, streaming, model/system info.
 */
object BitNetNative {

    private const val LIB_NAME = "bitnet-jni"
    private var loaded = false
    private var loadError: String? = null
    private val GSON = Gson()

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadError != null) return false
        return try {
            System.loadLibrary(LIB_NAME)
            loaded = true
            XLog.i(TAG, "bitnet-jni loaded")
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = "bitnet-jni not available: ${e.message}"
            XLog.w(TAG, loadError!!)
            false
        } catch (e: Exception) {
            loadError = "Failed to load bitnet-jni: ${e.message}"
            XLog.w(TAG, loadError!!, e)
            false
        }
    }

    fun isAvailable(): Boolean = ensureLoaded()
    fun lastLoadError(): String? = loadError

    fun loadModel(modelPath: String, nCtx: Int = 2048, nThreads: Int = 0): Long {
        if (!ensureLoaded()) return -1L
        val startMs = System.currentTimeMillis()
        val handle = nativeLoadModel(modelPath, nCtx, nThreads)
        XLog.i(TAG, "loadModel: ${modelPath.takeLast(30)} handle=$handle ${System.currentTimeMillis()-startMs}ms")
        return handle
    }

    fun freeModel(handle: Long) {
        if (loaded && handle > 0) nativeFreeModel(handle)
    }

    fun completionWithTelemetry(
        handle: Long, prompt: String, maxTokens: Int = 512,
        temperature: Float = 0.7f, topP: Float = 0.95f, topK: Int = 20,
        repeatPenalty: Float = 1.15f, stopSequences: String = "[]",
    ): CompletionResult? {
        if (!loaded || handle <= 0) return null
        val startMs = System.currentTimeMillis()
        val text = nativeCompletion(handle, prompt, maxTokens, temperature, topP, topK, repeatPenalty, stopSequences)
            ?: return null
        val elapsedMs = System.currentTimeMillis() - startMs
        val telemetry = InferenceTelemetry(
            totalWallMs = elapsedMs.toDouble(), promptChars = prompt.length,
            outputChars = text.length, modelInfo = getModelInfo(handle),
            kvCacheUsedCells = getKvCacheUsedCells(handle))
        XLog.i(TAG, "completion: ${telemetry.summary()}")
        return CompletionResult(text, telemetry)
    }

    fun completion(handle: Long, prompt: String, maxTokens: Int = 512,
        temperature: Float = 0.7f, topP: Float = 0.95f, topK: Int = 20,
        repeatPenalty: Float = 1.15f, stopSequences: String = "[]"): String? {
        return completionWithTelemetry(handle, prompt, maxTokens, temperature, topP, topK, repeatPenalty, stopSequences)?.text
    }

    fun completionStreaming(handle: Long, prompt: String, maxTokens: Int = 512,
        temperature: Float = 0.7f, topP: Float = 0.95f, topK: Int = 20,
        repeatPenalty: Float = 1.15f, stopSequences: String = "[]",
        onToken: (String) -> Unit): String? {
        if (!loaded || handle <= 0) return null
        return nativeCompletionStreaming(handle, prompt, maxTokens, temperature, topP, topK, repeatPenalty, stopSequences,
            object : StreamingCallback { override fun onToken(token: String) = onToken(token) })
    }

    fun tokenize(handle: Long, text: String): IntArray? {
        if (!loaded || handle <= 0) return null
        return nativeTokenize(handle, text)
    }
    fun detokenize(handle: Long, tokens: IntArray): String? {
        if (!loaded || handle <= 0) return null
        return nativeDetokenize(handle, tokens)
    }
    fun eosToken(handle: Long): Int {
        if (!loaded || handle <= 0) return -1
        return nativeEosToken(handle)
    }
    fun getModelInfo(handle: Long): Map<String, Any>? {
        if (!loaded || handle <= 0) return null
        val json = nativeGetModelInfo(handle) ?: return null
        return try { GSON.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type) }
        catch (e: Exception) { null }
    }
    fun getSystemInfo(): Map<String, Any>? {
        if (!loaded) return null
        val json = nativeGetSystemInfo() ?: return null
        return try { GSON.fromJson(json, object : TypeToken<Map<String, Any>>() {}.type) }
        catch (e: Exception) { null }
    }
    fun getKvCacheUsedCells(handle: Long): Int {
        if (!loaded || handle <= 0) return -1
        return nativeKvCacheUsedCells(handle)
    }

    data class CompletionResult(val text: String, val telemetry: InferenceTelemetry)
    data class InferenceTelemetry(
        val totalWallMs: Double, val promptChars: Int, val outputChars: Int,
        val modelInfo: Map<String, Any>?, val kvCacheUsedCells: Int) {
        fun summary() = "wall=${totalWallMs.toInt()}ms prompt=${promptChars}ch output=${outputChars}ch kv=$kvCacheUsedCells"
    }
    interface StreamingCallback { fun onToken(token: String) }

    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeCompletion(handle: Long, prompt: String, maxTokens: Int,
        temperature: Float, topP: Float, topK: Int, repeatPenalty: Float, stopSequences: String): String?
    private external fun nativeCompletionStreaming(handle: Long, prompt: String, maxTokens: Int,
        temperature: Float, topP: Float, topK: Int, repeatPenalty: Float, stopSequences: String,
        callback: StreamingCallback): String?
    private external fun nativeTokenize(handle: Long, text: String): IntArray?
    private external fun nativeDetokenize(handle: Long, tokens: IntArray): String?
    private external fun nativeEosToken(handle: Long): Int
    private external fun nativeGetModelInfo(handle: Long): String?
    private external fun nativeGetSystemInfo(): String?
    private external fun nativeKvCacheUsedCells(handle: Long): Int

    private const val TAG = "BitNetNative"
}
