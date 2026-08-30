package io.agents.arya.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.agents.arya.engine.budget.DeviceProfileStore
import io.agents.arya.engine.budget.MemoryBudget
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thin JNI wrapper. Owns the "exactly one model, exactly one generation" invariant.
 * Runs only in the `:engine` process.
 */
class EngineCore(private val context: Context) {
    private val lock = ReentrantLock()
    private var handle: Long = 0L
    private var currentModelPath: String? = null
    private var currentCtxSize: Int = 2048
    private var currentThreads: Int = 4
    private var modelHash8: String = ""
    private var committedPromptHash: String = ""
    private var committedPrefixKey: String? = null
    private var nPast: Int = 0
    private val generating = AtomicBoolean(false)
    @Volatile
    private var unloadWhenIdle = false
    private val prefixCache = PrefixCache(File(context.filesDir, "prefix-cache"))

    val isLoaded: Boolean
        get() = lock.withLock { handle != 0L }

    val isBusy: Boolean
        get() = generating.get()

    fun ensureLoaded(
        modelPath: String,
        ctxSize: Int,
        nThreads: Int,
        onProgress: ((Int, String) -> Unit)? = null,
    ): String {
        lock.withLock {
            // Same GGUF under a different path (FUSE vs filesDir/fast) must
            // NOT unload+reload. That race was killing generate on Huawei.
            if (handle != 0L && ModelPaths.sameModel(currentModelPath, modelPath)) {
                val existing = statsLocked()
                if (ModelPaths.isResident(existing)) {
                    EngineLog.i("EngineCore", "already loaded ${currentModelPath} (asked $modelPath ctx=$ctxSize have=$currentCtxSize)")
                    return existing
                }
                EngineLog.w("EngineCore", "handle present but not resident, reloading")
                unloadLocked()
            }
            if (generating.get()) {
                throw EngineLoadException(EngineError.ERR_BUSY, "Cannot switch models while generation is active")
            }
            if (handle != 0L) unloadLocked()

            val file = File(modelPath)
            if (!file.isFile || !io.agents.arya.agent.llm.LocalModelManager.isUsableModelFile(file)) {
                throw EngineLoadException(EngineError.ERR_LOAD_FAILED, "Model is not a usable GGUF file: $modelPath")
            }
            if (io.agents.arya.engine.budget.GgufHeaderParser.parse(file) == null) {
                throw EngineLoadException(EngineError.ERR_LOAD_FAILED, "GGUF header could not be parsed: $modelPath")
            }

            val mem = readDeviceRam()
            val profile = DeviceProfileStore.read(context)
            val meta = MemoryBudget.parseModelMeta(
                try {
                    EngineNative.nativeModelMeta(modelPath)
                } catch (_: Throwable) {
                    "{}"
                }
            ) ?: io.agents.arya.engine.budget.GgufHeaderParser.parse(file)?.asModelMeta()
            val plan = MemoryBudget.plan(
                MemoryBudget.Inputs(
                    totalRamBytes = mem.total,
                    availRamBytes = mem.avail,
                    modelFileBytes = file.length(),
                    isLowRamDevice = mem.lowRam,
                    modelMeta = meta,
                    processMemoryLimitBytes = mem.processLimit,
                ),
                profile,
            )
            EngineLog.i(
                "EngineCore",
                "memory plan total=${mem.total / (1024 * 1024)}MB avail=${mem.avail / (1024 * 1024)}MB " +
                    "processLimit=${mem.processLimit / (1024 * 1024)}MB file=${file.length() / (1024 * 1024)}MB plan=$plan",
            )
            when (plan) {
                is MemoryBudget.Plan.Refuse -> {
                    throw EngineLoadException(EngineError.ERR_OOM_PREVENTED, plan.reasonEn)
                }
                is MemoryBudget.Plan.Load -> {
                    EngineLog.breadcrumb("EngineCore", "nativeLoadModel begin path=$modelPath ctx=${plan.ctxSize} threads=${plan.nThreads} fileBytes=${file.length()}")
                    val t0 = System.currentTimeMillis()
                    val newHandle = EngineNative.nativeLoadModel(
                        modelPath,
                        plan.ctxSize,
                        plan.nThreads,
                        // Do not re-enter the Java binder callback from the
                        // llama.cpp loader. On Huawei the callback/JNI boundary
                        // was the last unobserved code path before process death;
                        // Service emits coarse progress around this call.
                        null,
                    )
                    EngineLog.i("EngineCore", "nativeLoadModel done handle=$newHandle ms=${System.currentTimeMillis() - t0}")
                    if (newHandle <= 0) {
                        val why = when (newHandle) {
                            -2L -> "llama_model_load_from_file failed"
                            -3L -> "llama_init_from_model failed"
                            -4L -> "warmup decode failed"
                            -6L -> "weights were not resident in RAM"
                            -7L, -8L -> "native exception during load"
                            else -> "native load code $newHandle"
                        }
                        throw EngineLoadException(EngineError.ERR_LOAD_FAILED, why)
                    }
                    handle = newHandle
                    unloadWhenIdle = false
                    currentModelPath = modelPath
                    currentCtxSize = plan.ctxSize
                    currentThreads = plan.nThreads
                    modelHash8 = PrefixCache.fileHash8(file)
                    committedPromptHash = ""
                    committedPrefixKey = null
                    nPast = 0
                    prefixCache.deleteStale(modelHash8)
                    val stats = statsLocked()
                    if (!ModelPaths.isResident(stats)) {
                        EngineLog.e("EngineCore", "rejecting fake-ready load stats=${stats.take(280)}")
                        unloadLocked()
                        throw EngineLoadException(
                            EngineError.ERR_LOAD_FAILED,
                            "native load was not resident in RAM",
                        )
                    }
                    EngineLog.i("EngineCore", "resident load ok ${stats.take(280)}")
                    return stats
                }
            }
        }
    }

    fun generateStream(requestJson: String, requestId: Int, callback: IEngineCallback) {
        val h = lock.withLock {
            if (handle == 0L) {
                safeError(callback, requestId, EngineError.ERR_NO_MODEL, EngineError.message(EngineError.ERR_NO_MODEL))
                return
            }
            if (!generating.compareAndSet(false, true)) {
                safeError(callback, requestId, EngineError.ERR_BUSY, EngineError.message(EngineError.ERR_BUSY))
                return
            }
            handle
        }

        try {
            val req = EngineRequest.parse(requestJson)
            val prefixKey = req.prefixKey ?: req.warmupKey
            var mode = req.promptMode
            var prompt = req.prompt

            if (!prefixKey.isNullOrBlank()) {
                val restored = maybeRestorePrefix(prefixKey)
                if (restored && mode == "full") {
                    // Prefix already in KV — treat remaining prompt as a no-op prefill
                    // when this is a warmup (maxTokens=0) or as delta when a suffix follows.
                    mode = if (req.maxTokens == 0) "full" else "delta"
                }
            }

            if (mode == "delta" && committedPromptHash.isNotEmpty()) {
                val incomingHash = PrefixCache.sha256Hex(prompt)
                // Client may send only the suffix; if they sent a full prompt whose
                // prefix hash does not match, fall back to a full prefill.
                if (prompt.startsWith("HASH:")) {
                    val claimed = prompt.removePrefix("HASH:")
                    if (claimed != committedPromptHash) {
                        mode = "full"
                    } else {
                        prompt = ""
                    }
                }
            }

            EngineLog.breadcrumb(
                "EngineCore",
                "generateStream begin id=$requestId promptChars=${prompt.length} mode=$mode maxTokens=${req.maxTokens} deadline=${req.deadlineMs} tokenDeadline=${req.tokenDeadlineMs} handle=$h",
            )
            val stopJson = JSONArray(req.stop).toString()
            var gotDelta = false
            val nativeCb = EngineNative.StreamBridge { piece ->
                gotDelta = true
                try {
                    callback.onDelta(requestId, piece)
                } catch (_: Exception) {
                }
            }

            val tGen = System.currentTimeMillis()
            val statsJson = EngineNative.nativeGenerateStream(
                h,
                prompt,
                mode,
                req.maxTokens,
                req.temperature.toFloat(),
                req.topP.toFloat(),
                req.topK,
                req.repeatPenalty.toFloat(),
                stopJson,
                req.deadlineMs,
                req.tokenDeadlineMs,
                nativeCb,
            )
            EngineLog.i(
                "EngineCore",
                "generateStream native done id=$requestId ms=${System.currentTimeMillis() - tGen} stats=${statsJson.take(220)}",
            )

            val stats = JSONObject(statsJson)
            if (stats.has("error")) {
                val err = stats.optString("error")
                val code = when (err) {
                    "cancelled" -> EngineError.ERR_CANCELLED
                    "deadline", "token_deadline" -> EngineError.ERR_DEADLINE
                    else -> EngineError.ERR_NATIVE
                }
                if (err == "cancelled") {
                    safeError(callback, requestId, EngineError.ERR_CANCELLED, EngineError.message(code, err))
                } else if (err == "deadline" || err == "token_deadline") {
                    safeError(callback, requestId, EngineError.ERR_DEADLINE, EngineError.message(code, err))
                } else {
                    safeError(callback, requestId, EngineError.ERR_NATIVE, EngineError.message(code, err))
                }
                return
            }

            lock.withLock {
                nPast = stats.optInt("prompt_tokens", nPast) + stats.optInt("gen_tokens", 0)
                committedPromptHash = PrefixCache.sha256Hex(req.prompt)
                if (!prefixKey.isNullOrBlank()) committedPrefixKey = prefixKey
            }

            val finish = stats.optString("finish_reason", "stop")
            if (finish == "cancelled") {
                safeError(callback, requestId, EngineError.ERR_CANCELLED, EngineError.message(EngineError.ERR_CANCELLED))
                return
            }
            if (finish == "deadline" || finish == "token_deadline") {
                safeError(callback, requestId, EngineError.ERR_DEADLINE, EngineError.message(EngineError.ERR_DEADLINE, finish))
                return
            }

            if (req.maxTokens == 0 && !prefixKey.isNullOrBlank()) {
                savePrefixState(prefixKey)
            }

            if (!gotDelta) {
                val fallback = stats.optString("text")
                if (fallback.isNotEmpty()) {
                    EngineLog.w("EngineCore", "streaming callback missed; delivering ${fallback.length} chars from stats")
                    try {
                        callback.onDelta(requestId, fallback)
                    } catch (_: Exception) {
                    }
                }
            }

            try {
                callback.onDone(requestId, statsJson)
            } catch (_: Exception) {
            }
        } catch (e: Throwable) {
            EngineLog.e("EngineCore", "generateStream throwable id=$requestId", e)
            safeError(callback, requestId, EngineError.ERR_NATIVE, e.message ?: "generation error")
        } finally {
            generating.set(false)
            lock.withLock {
                if (unloadWhenIdle) {
                    unloadLocked()
                    unloadWhenIdle = false
                }
            }
        }
    }

    fun cancel(@Suppress("UNUSED_PARAMETER") requestId: Int) {
        lock.withLock {
            if (handle != 0L && generating.get()) EngineNative.nativeCancel(handle)
        }
    }

    fun savePrefixState(key: String): Boolean {
        return lock.withLock {
            if (handle == 0L) return false
            prefixCache.ensureRoot()
            val state = prefixCache.stateFile(key)
            val ok = EngineNative.nativeSaveState(handle, state.absolutePath)
            if (!ok) return false
            if (prefixCache.deleteIfOversized(key)) return false
            prefixCache.writeSidecar(
                PrefixCache.Sidecar(
                    nPast = nPast,
                    promptHash = committedPromptHash,
                    modelHash = modelHash8,
                    createdAt = System.currentTimeMillis(),
                    sizeBytes = state.length(),
                    key = key,
                ),
            )
            prefixCache.evictLru(3)
            committedPrefixKey = key
            true
        }
    }

    fun loadPrefixState(key: String): Boolean = lock.withLock { loadPrefixLocked(key) }

    fun countTokens(text: String): Int {
        return lock.withLock {
            if (handle == 0L) return 0
            EngineNative.nativeCountTokens(handle, text)
        }
    }

    fun stats(): String = lock.withLock { statsLocked() }

    fun unload() {
        lock.withLock {
            if (generating.get()) {
                unloadWhenIdle = true
                if (handle != 0L) EngineNative.nativeCancel(handle)
                return
            }
            unloadLocked()
        }
    }

    private fun maybeRestorePrefix(key: String): Boolean {
        return lock.withLock {
            if (committedPrefixKey == key && nPast > 0) return true
            loadPrefixLocked(key)
        }
    }

    private fun loadPrefixLocked(key: String): Boolean {
        if (handle == 0L) return false
        if (!prefixCache.existsAndValid(key, modelHash8)) return false
        val ok = EngineNative.nativeLoadState(handle, prefixCache.stateFile(key).absolutePath)
        if (!ok) return false
        val sc = prefixCache.readSidecar(key)
        nPast = sc?.nPast ?: 0
        committedPromptHash = sc?.promptHash.orEmpty()
        committedPrefixKey = key
        return true
    }

    private fun unloadLocked() {
        if (handle != 0L) {
            unloadWhenIdle = false
            EngineNative.nativeFreeModel(handle)
            handle = 0L
            currentModelPath = null
            committedPromptHash = ""
            committedPrefixKey = null
            nPast = 0
        }
    }

    private fun statsLocked(): String {
        if (handle == 0L) return """{"loaded":false}"""
        val modelInfo = EngineNative.nativeGetModelInfo(handle)
        val sysInfo = EngineNative.nativeGetSystemInfo()
        return JSONObject().apply {
            put("loaded", true)
            put("model_path", currentModelPath ?: "")
            put("ctx", currentCtxSize)
            put("n_threads", currentThreads)
            put("n_past", nPast)
            put("prefix_key", committedPrefixKey ?: "")
            put("model_info", JSONObject(modelInfo))
            put("sys_info", JSONObject(sysInfo))
        }.toString()
    }

    private fun readDeviceRam(): RamSnapshot {
        // Device RAM — NEVER JVM heap (Runtime.totalMemory is ~256 MB and
        // makes MemoryBudget refuse every GGUF). Use ActivityManager from
        // the :engine Service context.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val low = if (Build.VERSION.SDK_INT >= 19) {
            am.isLowRamDevice
        } else {
            info.totalMem < 3L * 1024 * 1024 * 1024
        }
        val processLimit = am.largeMemoryClass.toLong() * 1024L * 1024L
        return RamSnapshot(info.totalMem, info.availMem, low, processLimit)
    }

    private fun safeError(cb: IEngineCallback, id: Int, code: Int, message: String) {
        try {
            cb.onError(id, code, message)
        } catch (_: Exception) {
        }
    }

    private data class RamSnapshot(
        val total: Long,
        val avail: Long,
        val lowRam: Boolean,
        val processLimit: Long,
    )
}

class EngineLoadException(val code: Int, message: String) : Exception(message)
