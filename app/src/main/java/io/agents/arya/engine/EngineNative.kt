package io.agents.arya.engine

/**
 * JNI wrapper for libarya-engine.so.
 *
 * Loaded by EngineService (now the foreground app process on Huawei).
 * UI code still must not touch this class directly — go through EngineClient.
 */
object EngineNative {
    init {
        System.loadLibrary("arya-engine")
    }

    external fun nativeLoadModel(
        modelPath: String,
        nCtx: Int,
        nThreads: Int,
        progress: NativeLoadCallback?,
    ): Long

    @androidx.annotation.Keep
    interface NativeLoadCallback {
        fun onProgress(pct: Int, phase: String)
    }
    external fun nativeSetCrashLogPath(path: String)
    external fun nativeSetLoadStagePath(path: String)
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

    @androidx.annotation.Keep
    interface NativeStreamCallback {
        fun onDeltaPiece(piece: String)
    }

    /** Named JNI target so R8 cannot rename onDeltaPiece on an anonymous class. */
    @androidx.annotation.Keep
    class StreamBridge(private val sink: (String) -> Unit) : NativeStreamCallback {
        override fun onDeltaPiece(piece: String) {
            sink(piece)
        }
    }
}
