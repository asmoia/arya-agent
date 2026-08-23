package io.agents.arya.engine

import android.content.Context
import io.agents.arya.engine.budget.MemoryBudget
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class EngineCore(private val context: Context) {
    private var handle: Long = 0L
    private var currentModelPath: String? = null
    private var currentCtxSize: Int = 2048
    private val lock = ReentrantLock()
    @Volatile
    private var isGenerating = false

    val isLoaded: Boolean
        get() = lock.withLock { handle != 0L }

    fun ensureLoaded(modelPath: String, ctxSize: Int, nThreads: Int): String {
        lock.withLock {
            if (handle != 0L && currentModelPath == modelPath && currentCtxSize == ctxSize) {
                return stats()
            }

            if (handle != 0L) {
                unloadInternal()
            }

            val file = File(modelPath)
            if (!file.exists()) {
                throw IllegalStateException("Model file does not exist: $modelPath")
            }

            val runtime = Runtime.getRuntime()
            val totalRam = runtime.totalMemory() + runtime.freeMemory() // approximate fallback
            val availRam = runtime.freeMemory()

            val inputs = MemoryBudget.Inputs(
                totalRamBytes = totalRam,
                availRamBytes = availRam,
                modelFileBytes = file.length(),
                isLowRamDevice = totalRam < 3L * 1024 * 1024 * 1024
            )

            val plan = MemoryBudget.plan(inputs, null)
            if (plan is MemoryBudget.Plan.Refuse) {
                throw IllegalStateException("ERR_OOM_PREVENTED: ${plan.reasonFa}")
            }

            val finalCtx = if (plan is MemoryBudget.Plan.Load) plan.ctxSize else ctxSize
            val finalThreads = if (plan is MemoryBudget.Plan.Load) plan.nThreads else nThreads

            val newHandle = EngineNative.nativeLoadModel(modelPath, finalCtx, finalThreads)
            if (newHandle <= 0) {
                throw IllegalStateException("Native model load failed with code $newHandle")
            }

            handle = newHandle
            currentModelPath = modelPath
            currentCtxSize = finalCtx
            return stats()
        }
    }

    fun generateStream(
        requestJson: String,
        requestId: Int,
        callback: IEngineCallback
    ) {
        val h = lock.withLock {
            if (handle == 0L) {
                callback.onError(requestId, 2 /* ERR_NO_MODEL */, "No model loaded")
                return
            }
            if (isGenerating) {
                callback.onError(requestId, 1 /* ERR_BUSY */, "Engine is busy")
                return
            }
            isGenerating = true
            handle
        }

        try {
            val json = JSONObject(requestJson)
            val prompt = json.optString("prompt", "")
            val promptMode = json.optString("promptMode", "full")
            val maxTokens = json.optInt("maxTokens", 512)
            val temp = json.optDouble("temperature", 0.3).toFloat()
            val topP = json.optDouble("topP", 0.9).toFloat()
            val topK = json.optInt("topK", 32)
            val repeatPenalty = json.optDouble("repeatPenalty", 1.12).toFloat()
            val stopArray = json.optJSONArray("stop") ?: JSONArray()
            val stopList = ArrayList<String>()
            for (i in 0 until stopArray.length()) {
                stopList.add(stopArray.getString(i))
            }
            val stopJson = JSONArray(stopList).toString()
            val deadlineMs = json.optLong("deadlineMs", 45000L)
            val tokenDeadlineMs = json.optLong("tokenDeadlineMs", 4000L)

            val nativeCb = object : EngineNative.NativeStreamCallback {
                override fun onDeltaPiece(piece: String) {
                    try {
                        callback.onDelta(requestId, piece)
                    } catch (e: Exception) {
                        // Client binder disconnected or failed
                    }
                }
            }

            val statsJson = EngineNative.nativeGenerateStream(
                h,
                prompt,
                promptMode,
                maxTokens,
                temp,
                topP,
                topK,
                repeatPenalty,
                stopJson,
                deadlineMs,
                tokenDeadlineMs,
                nativeCb
            )

            callback.onDone(requestId, statsJson)
        } catch (e: Exception) {
            callback.onError(requestId, 6 /* ERR_NATIVE */, e.message ?: "Generation error")
        } finally {
            lock.withLock {
                isGenerating = false
            }
        }
    }

    fun cancel(requestId: Int) {
        lock.withLock {
            if (handle != 0L) {
                EngineNative.nativeCancel(handle)
            }
        }
    }

    fun savePrefixState(key: String): Boolean {
        return lock.withLock {
            if (handle == 0L) return false
            val dir = File(context.filesDir, "prefix-cache").apply { mkdirs() }
            val stateFile = File(dir, "$key.state")
            EngineNative.nativeSaveState(handle, stateFile.absolutePath)
        }
    }

    fun loadPrefixState(key: String): Boolean {
        return lock.withLock {
            if (handle == 0L) return false
            val dir = File(context.filesDir, "prefix-cache")
            val stateFile = File(dir, "$key.state")
            if (!stateFile.exists()) return false
            EngineNative.nativeLoadState(handle, stateFile.absolutePath)
        }
    }

    fun countTokens(text: String): Int {
        return lock.withLock {
            if (handle == 0L) return 0
            EngineNative.nativeCountTokens(handle, text)
        }
    }

    fun stats(): String {
        return lock.withLock {
            if (handle == 0L) {
                "{\"loaded\": false}"
            } else {
                val modelInfo = EngineNative.nativeGetModelInfo(handle)
                val sysInfo = EngineNative.nativeGetSystemInfo()
                "{\"loaded\": true, \"model_path\": \"${currentModelPath ?: ""}\", \"model_info\": $modelInfo, \"sys_info\": $sysInfo}"
            }
        }
    }

    fun unload() {
        lock.withLock {
            unloadInternal()
        }
    }

    private fun unloadInternal() {
        if (handle != 0L) {
            EngineNative.nativeFreeModel(handle)
            handle = 0L
            currentModelPath = null
        }
    }
}
