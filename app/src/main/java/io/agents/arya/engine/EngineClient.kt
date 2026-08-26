package io.agents.arya.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The ONLY main-process class that touches AIDL. Never loads libarya-engine.so.
 */
class EngineClient(private val app: Context) {

    private val _state = MutableStateFlow<EngineState>(EngineState.Disconnected)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val _loadProgress = MutableStateFlow(EngineLoadProgress())
    val loadProgress: StateFlow<EngineLoadProgress> = _loadProgress.asStateFlow()

    @Volatile
    private var engineBinder: IEngine? = null
    @Volatile
    private var activeModelPath: String? = null

    private val crashTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val quarantinedModels = ConcurrentHashMap.newKeySet<String>()
    private val nextLoadRequestId = AtomicInteger(1)
    private val activeGenerateRequestId = AtomicInteger(-1)
    private val pendingBind = AtomicReference<CancellableContinuation<IEngine>?>(null)
    private val loadGate = Mutex()
    private val deathListeners = CopyOnWriteArrayList<(String) -> Unit>()

    private val deathRecipient = IBinder.DeathRecipient {
        EngineLog.e("EngineClient", "engine binder died model=$activeModelPath")
        _state.value = EngineState.Crashed("Engine process died")
        val model = activeModelPath
        if (model != null) recordCrash(model)
        engineBinder = null
        activeGenerateRequestId.set(-1)
        pendingBind.getAndSet(null)?.let { waiter ->
            if (waiter.isActive) waiter.resumeWithException(IllegalStateException("Engine process died"))
        }
        deathListeners.forEach { listener ->
            try {
                listener("Engine process died")
            } catch (_: Exception) {
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val remote = service ?: throw IllegalStateException("EngineService returned a null binder")
                remote.linkToDeath(deathRecipient, 0)
                val bound = IEngine.Stub.asInterface(remote)
                engineBinder = bound
                val model = activeModelPath
                _state.value = if (model.isNullOrBlank()) {
                    EngineState.Ready("")
                } else {
                    EngineState.Ready(model)
                }
                pendingBind.getAndSet(null)?.let { waiter ->
                    if (waiter.isActive) waiter.resume(bound)
                }
            } catch (e: Exception) {
                _state.value = EngineState.Crashed("Failed to attach: ${e.message}")
                pendingBind.getAndSet(null)?.let { waiter ->
                    if (waiter.isActive) waiter.resumeWithException(e)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engineBinder = null
            activeGenerateRequestId.set(-1)
            _state.value = EngineState.Disconnected
            pendingBind.getAndSet(null)?.let { waiter ->
                if (waiter.isActive) {
                    waiter.resumeWithException(IllegalStateException("EngineService disconnected"))
                }
            }
        }
    }

    suspend fun ensureLoaded(model: ModelRef): EngineInfo {
        val json = ensureLoaded(model.path, model.ctxSize, model.nThreads)
        return EngineInfo.parse(json)
    }

    suspend fun ensureLoaded(modelPath: String, ctxSize: Int = 2048, nThreads: Int = 4): String {
        if (quarantinedModels.contains(modelPath)) {
            _state.value = EngineState.Quarantined(modelPath)
            throw IllegalStateException(
                "This model is unstable on this device. Try a smaller model.",
            )
        }
        // Serialize prewarm + chat so they cannot steal each other's callback
        // or unload+reload the GGUF mid-generate.
        return loadGate.withLock {
            ensureLoadedLocked(modelPath, ctxSize, nThreads)
        }
    }

    private suspend fun ensureLoadedLocked(modelPath: String, ctxSize: Int, nThreads: Int): String {
        activeModelPath = modelPath
        EngineLog.i("EngineClient", "ensureLoaded begin path=$modelPath ctx=$ctxSize")
        val binder = getOrBindService()
        EngineLog.i("EngineClient", "ensureLoaded bound alive=${binder.asBinder().isBinderAlive}")
        // Already resident? skip the async round-trip.
        try {
            val existing = binder.ensureLoaded(modelPath, ctxSize, nThreads)
            if (isReallyLoaded(existing, modelPath)) {
                _state.value = EngineState.Ready(modelPath)
                _loadProgress.value = EngineLoadProgress(100, "Model in RAM")
                return existing
            }
        } catch (_: Exception) {
            // expected: service throws "use requestLoad" when not yet mapped
        }
        val requestId = nextLoadRequestId.getAndIncrement()
        _state.value = EngineState.Loading(0, "Starting local engine…")
        _loadProgress.value = EngineLoadProgress(0, "Starting local engine…")
        return try {
            withTimeout(240_000L) {
                suspendCancellableCoroutine { cont ->
                    val onDied: (String) -> Unit = { reason ->
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException(reason))
                        }
                    }
                    deathListeners.add(onDied)
                    val cb = object : IEngineCallback.Stub() {
                        override fun onDelta(id: Int, textDelta: String?) {}
                        override fun onDone(id: Int, statsJson: String?) {}
                        override fun onError(id: Int, code: Int, message: String?) {
                            if (id != requestId && id != 0) return
                            deathListeners.remove(onDied)
                            if (cont.isActive) {
                                cont.resumeWithException(
                                    IllegalStateException(message ?: EngineError.message(code)),
                                )
                            }
                        }
                        override fun onLoadProgress(pct: Int, phase: String?) {
                            val p = EngineLoadProgress(pct, phase.orEmpty())
                            _loadProgress.value = p
                            _state.value = EngineState.Loading(pct, phase.orEmpty())
                        }
                        override fun onLoadResult(id: Int, infoJson: String?) {
                            if (id != requestId && id != 0) return
                            deathListeners.remove(onDied)
                            _loadProgress.value = EngineLoadProgress(100, "Model ready")
                            _state.value = EngineState.Ready(modelPath)
                            if (cont.isActive) cont.resume(infoJson ?: "{}")
                        }
                    }
                    try {
                        binder.registerCallback(cb)
                        EngineLog.i("EngineClient", "requestLoad dispatch id=$requestId")
                        binder.requestLoad(modelPath, ctxSize, nThreads, requestId)
                    } catch (e: Exception) {
                        deathListeners.remove(onDied)
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                    cont.invokeOnCancellation {
                        deathListeners.remove(onDied)
                        try {
                            binder.cancel(requestId)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Model load failed or timed out: ${e.message}", e)
        }
    }

    fun generate(req: EngineRequest): Flow<EngineEvent> = generate(req.toJson())

    fun generate(requestJson: String): Flow<EngineEvent> = callbackFlow {
        val binder = engineBinder
        if (binder == null || !binder.asBinder().isBinderAlive) {
            trySend(EngineEvent.Failed(EngineError.ERR_NO_MODEL, "Engine process not bound"))
            close()
            return@callbackFlow
        }

        val callback = object : IEngineCallback.Stub() {
            override fun onDelta(requestId: Int, textDelta: String?) {
                if (!textDelta.isNullOrEmpty()) trySend(EngineEvent.Delta(textDelta))
            }

            override fun onDone(requestId: Int, statsJson: String?) {
                activeGenerateRequestId.compareAndSet(requestId, -1)
                trySend(EngineEvent.Done(statsJson ?: "{}"))
                _state.value = EngineState.Ready(activeModelPath.orEmpty())
                close()
            }

            override fun onError(requestId: Int, code: Int, message: String?) {
                activeGenerateRequestId.compareAndSet(requestId, -1)
                if (code == EngineError.ERR_NATIVE) {
                    _state.value = EngineState.Crashed(message ?: "native")
                } else {
                    _state.value = EngineState.Ready(activeModelPath.orEmpty())
                }
                trySend(EngineEvent.Failed(code, message ?: EngineError.message(code)))
                close()
            }

            override fun onLoadProgress(pct: Int, phase: String?) {
                _loadProgress.value = EngineLoadProgress(pct, phase.orEmpty())
                trySend(EngineEvent.LoadProgress(pct, phase.orEmpty()))
            }

            override fun onLoadResult(requestId: Int, infoJson: String?) {
                // generate path does not use load-result
            }
        }

        binder.registerCallback(callback)
        _state.value = EngineState.Busy
        val onDied: (String) -> Unit = { reason ->
            trySend(EngineEvent.Failed(EngineError.ERR_NATIVE, reason))
            close()
        }
        deathListeners.add(onDied)
        val reqId = try {
            binder.generate(requestJson)
        } catch (e: Exception) {
            deathListeners.remove(onDied)
            trySend(EngineEvent.Failed(EngineError.ERR_NATIVE, e.message ?: "generate failed"))
            close()
            return@callbackFlow
        }
        EngineLog.i("EngineClient", "generate dispatched id=$reqId")
        if (reqId >= 0) activeGenerateRequestId.set(reqId)
        if (reqId < 0) {
            activeGenerateRequestId.set(-1)
            deathListeners.remove(onDied)
            trySend(EngineEvent.Failed(EngineError.ERR_BUSY, EngineError.message(EngineError.ERR_BUSY)))
            _state.value = EngineState.Ready(activeModelPath.orEmpty())
            close()
        }

        awaitClose {
            deathListeners.remove(onDied)
            if (reqId >= 0) activeGenerateRequestId.compareAndSet(reqId, -1)
            try {
                if (reqId >= 0) binder.cancel(reqId)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun cancelActive() {
        val requestId = activeGenerateRequestId.getAndSet(-1)
        if (requestId < 0) return
        try {
            engineBinder?.cancel(requestId)
        } catch (_: Exception) {
        }
    }

    suspend fun stats(): EngineStats {
        val raw = engineBinder?.stats() ?: """{"loaded":false}"""
        return EngineStats(rawJson = raw, loaded = raw.contains("\"loaded\":true") || raw.contains("\"loaded\": true"))
    }

    suspend fun savePrefixState(key: String): Boolean = engineBinder?.savePrefixState(key) ?: false
    suspend fun loadPrefixState(key: String): Boolean = engineBinder?.loadPrefixState(key) ?: false
    suspend fun countTokens(text: String): Int = engineBinder?.countTokens(text) ?: 0

    fun unload() {
        val requestId = activeGenerateRequestId.getAndSet(-1)
        try {
            val binder = engineBinder
            if (binder != null && requestId >= 0) binder.cancel(requestId)
            binder?.unload()
        } catch (_: Exception) {
        }
        _state.value = EngineState.Disconnected
    }

    fun isQuarantined(modelPath: String): Boolean = quarantinedModels.contains(modelPath)

    private fun isReallyLoaded(json: String, requestedPath: String = ""): Boolean {
        if (json.isBlank()) return false
        return try {
            val o = org.json.JSONObject(json)
            if (!o.optBoolean("loaded", false)) return false
            val sizeMb = o.optJSONObject("model_info")?.optDouble("model_size_mb", 0.0) ?: 0.0
            // Real GGUF reports hundreds of MB. ~70 MB is an empty process / failed mmap.
            if (sizeMb < 80.0) return false
            if (requestedPath.isBlank()) return true
            val loadedPath = o.optString("model_path")
            loadedPath.isBlank() || ModelPaths.sameModel(loadedPath, requestedPath)
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun getOrBindService(): IEngine {
        val existing = engineBinder
        if (existing != null && existing.asBinder().isBinderAlive) return existing

        _state.value = EngineState.Connecting
        val intent = Intent(app, EngineService::class.java)
        // Start BEFORE bind so EngineService.startForeground() is legal on Android 12+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.startForegroundService(intent)
        } else {
            app.startService(intent)
        }

        return withTimeout(15_000L) {
            suspendCancellableCoroutine { cont ->
                pendingBind.set(cont)
                val already = engineBinder
                if (already != null && already.asBinder().isBinderAlive) {
                    pendingBind.compareAndSet(cont, null)
                    if (cont.isActive) cont.resume(already)
                    return@suspendCancellableCoroutine
                }
                val ok = app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
                if (!ok) {
                    pendingBind.compareAndSet(cont, null)
                    if (cont.isActive) {
                        cont.resumeWithException(IllegalStateException("bindService returned false"))
                    }
                    return@suspendCancellableCoroutine
                }
                cont.invokeOnCancellation {
                    pendingBind.compareAndSet(cont, null)
                }
            }
        }
    }

    private fun recordCrash(modelPath: String) {
        val now = System.currentTimeMillis()
        val list = crashTimestamps.getOrPut(modelPath) { ArrayList() }
        synchronized(list) {
            list.add(now)
            list.removeAll { now - it > 10 * 60 * 1000L }
            if (list.size >= 3) {
                quarantinedModels.add(modelPath)
                _state.value = EngineState.Quarantined(modelPath)
            }
        }
    }
}

data class ModelRef(
    val path: String,
    val ctxSize: Int = 2048,
    val nThreads: Int = 4,
)
