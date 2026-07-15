// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.llm

/**
 * JNI bridge to llama.cpp for on-device GGUF model inference.
 *
 * Supports standard GGUF models (Q4_K_M, Q5_K_M, etc.) and BitNet i2_s
 * when built with bitnet.cpp kernels. The native library is loaded lazily
 * so the app works even if the .so is absent (graceful fallback).
 */
object BitNetNative {

    private const val LIB_NAME = "bitnet-jni"
    private var loaded = false
    private var loadError: String? = null

    /** Load the native library. Returns true on success. */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadError != null) return false
        return try {
            System.loadLibrary(LIB_NAME)
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = "bitnet-jni native library not available: ${e.message}"
            io.agents.arya.utils.XLog.w(TAG, loadError!!)
            false
        } catch (e: Exception) {
            loadError = "Failed to load bitnet-jni: ${e.message}"
            io.agents.arya.utils.XLog.w(TAG, loadError!!, e)
            false
        }
    }

    fun isAvailable(): Boolean = ensureLoaded()

    fun lastLoadError(): String? = loadError

    // ---- Model lifecycle ----

    /**
     * Load a GGUF model from disk.
     * @param modelPath Absolute path to the .gguf file
     * @param nCtx Context window size (tokens)
     * @param nThreads Number of threads for inference (0 = auto)
     * @return Opaque model handle (>0 on success, <=0 on error)
     */
    fun loadModel(modelPath: String, nCtx: Int = 2048, nThreads: Int = 0): Long {
        if (!ensureLoaded()) return -1L
        return nativeLoadModel(modelPath, nCtx, nThreads)
    }

    /**
     * Free a loaded model.
     * @param handle Model handle from loadModel
     */
    fun freeModel(handle: Long) {
        if (loaded && handle > 0) nativeFreeModel(handle)
    }

    // ---- Completion ----

    /**
     * Run a completion request.
     *
     * @param handle Model handle
     * @param prompt Full prompt string (system + chat history + user message)
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature
     * @param topP Top-p sampling threshold
     * @param topK Top-k sampling
     * @param repeatPenalty Repeat penalty
     * @param stopSequences JSON array of stop sequences, e.g. '["</tool_call>","<|end|>"]'
     * @return Generated text, or null on error
     */
    fun completion(
        handle: Long,
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topP: Float = 0.95f,
        topK: Int = 20,
        repeatPenalty: Float = 1.1f,
        stopSequences: String = "[]",
    ): String? {
        if (!loaded || handle <= 0) return null
        return nativeCompletion(handle, prompt, maxTokens, temperature, topP, topK, repeatPenalty, stopSequences)
    }

    /**
     * Streaming completion. Calls [onToken] for each generated token.
     * Returns the full generated text or null on error.
     */
    fun completionStreaming(
        handle: Long,
        prompt: String,
        maxTokens: Int = 256,
        temperature: Float = 0.7f,
        topP: Float = 0.95f,
        topK: Int = 20,
        repeatPenalty: Float = 1.1f,
        stopSequences: String = "[]",
        onToken: (String) -> Unit,
    ): String? {
        if (!loaded || handle <= 0) return null
        return nativeCompletionStreaming(handle, prompt, maxTokens, temperature, topP, topK, repeatPenalty, stopSequences, onToken)
    }

    // ---- Tokenization helpers ----

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

    // ---- Native methods ----

    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Long
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeCompletion(
        handle: Long, prompt: String, maxTokens: Int,
        temperature: Float, topP: Float, topK: Int, repeatPenalty: Float,
        stopSequences: String,
    ): String?
    private external fun nativeCompletionStreaming(
        handle: Long, prompt: String, maxTokens: Int,
        temperature: Float, topP: Float, topK: Int, repeatPenalty: Float,
        stopSequences: String, onToken: (String) -> Unit,
    ): String?
    private external fun nativeTokenize(handle: Long, text: String): IntArray?
    private external fun nativeDetokenize(handle: Long, tokens: IntArray): String?
    private external fun nativeEosToken(handle: Long): Int

    private const val TAG = "BitNetNative"
}
