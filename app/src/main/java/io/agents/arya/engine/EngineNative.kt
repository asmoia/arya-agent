package io.agents.arya.engine

/**
 * JNI wrapper for libarya-engine.so.
 * MUST ONLY be called inside the :engine process.
 */
object EngineNative {
    init {
        System.loadLibrary("arya-engine")
    }

    external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun nativeFreeModel(handle: Long)
    external fun nativeCancel(handle: Long)
    external fun nativeSaveState(handle: Long, statePath: String): Boolean
    external fun nativeLoadState(handle: Long, statePath: String): Boolean
    external fun nativeCountTokens(handle: Long, text: String): Int
    external fun nativeGenerateStream(
        handle: Long,
        prompt: String,
        promptMode: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        stopJson: String,
        deadlineMs: Long,
        tokenDeadlineMs: Long,
        streamCallback: NativeStreamCallback
    ): String

    external fun nativeGetModelInfo(handle: Long): String
    external fun nativeGetSystemInfo(): String

    interface NativeStreamCallback {
        fun onDeltaPiece(piece: String)
    }
}
