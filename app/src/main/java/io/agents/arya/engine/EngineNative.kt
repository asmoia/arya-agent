package io.agents.arya.engine

/**
 * JNI wrapper for libarya-engine.so.
 *
 * MUST ONLY be referenced from the `:engine` process (EngineCore / EngineService /
 * DeviceProfileManager). Main-process code must never load this class.
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
        streamCallback: NativeStreamCallback?,
    ): String

    external fun nativeGetModelInfo(handle: Long): String
    external fun nativeGetSystemInfo(): String
    external fun nativeModelMeta(modelPath: String): String
    external fun nativeDetectBigCores(): Int
    external fun nativeBench(nThreads: Int): String
    external fun nativeClearKv(handle: Long)

    interface NativeStreamCallback {
        fun onDeltaPiece(piece: String)
    }
}
