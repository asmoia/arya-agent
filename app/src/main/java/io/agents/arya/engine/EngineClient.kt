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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
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
    private val pendingBind = AtomicReference<CancellableContinuation<IEngine>?>(null)

    private val deathRecipient = IBinder.DeathRecipient {
        _state.value = EngineState.Crashed("Engine process died")
        val model = activeModelPath
        if (model != null) recordCrash(model)
        engineBinder = null
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                service?.linkToDeath(deathRecipient, 0)
                val bound = IEngine.Stub.asInterface(service)
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
        activeModelPath = modelPath
        EngineLog.i("EngineClient", "ensureLoaded begin path=$modelPath ctx=$ctxSize")
        val binder = getOrBindService()
        EngineLog.i("EngineClient", "ensureLoaded bound alive=${binder.asBinder().isBinderAlive}")
        // Already resident? skip the async round-trip.
        try {
            val existing = binder.ensureLoaded(modelPath, ctxSize, nThreads)
            if (existing.contains("\"loaded\":true") || existing.contains("\"loaded\": true")) {
                _state.value = EngineState.Ready(modelPath)
                _loadProgress.value = EngineLoadProgress(100, "Model ready")
                return existing
            }
        } catch (_: Exception) {
            // expected: service throws "use requestLoad" when not yet mapped
        }
        val requestId = nextLoadRequestId.getAndIncrement()
        _state.value = EngineState.Loading(0, "Starting local engine…")
        _loadProgress.value = EngineLoadProgress(0, "Starting local engine…")
        return try {
            withTimeout(120_000L) {
                suspendCancellableCoroutine { cont ->
                    val cb = object : IEngineCallback.Stub() {
                        override fun onDelta(id: Int, textDelta: String?) {}
                        override fun onDone(id: Int, statsJson: String?) {}
                        override fun onError(id: Int, code: Int, message: String?) {
                            if (id != requestId && id != 0) return
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
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                    cont.invokeOnCancellation {
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
                trySend(EngineEvent.Done(statsJson ?: "{}"))
                _state.value = EngineState.Ready(activeModelPath.orEmpty())
                close()
            }

            override fun onError(requestId: Int, code: Int, message: String?) {
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
        val reqId = binder.generate(requestJson)
        if (reqId < 0) {
            trySend(EngineEvent.Failed(EngineError.ERR_BUSY, EngineError.message(EngineError.ERR_BUSY)))
            _state.value = EngineState.Ready(activeModelPath.orEmpty())
            close()
        }

        awaitClose {
            try {
                if (reqId >= 0) binder.cancel(reqId)
            } catch (_: Exception) {
            }
        }
    }

    suspend fun cancelActive() {
        try {
            engineBinder?.cancel(0)
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
        try {
            engineBinder?.unload()
        } catch (_: Exception) {
        }
        _state.value = EngineState.Disconnected
    }

    fun isQuarantined(modelPath: String): Boolean = quarantinedModels.contains(modelPath)

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
