package io.agents.arya.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The ONLY main-process class that touches AIDL. Never loads libarya-engine.so.
 */
class EngineClient(private val app: Context) {

    private val _state = MutableStateFlow<EngineState>(EngineState.Disconnected)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    @Volatile
    private var engineBinder: IEngine? = null
    @Volatile
    private var activeModelPath: String? = null

    private val crashTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val quarantinedModels = ConcurrentHashMap.newKeySet<String>()

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
                engineBinder = IEngine.Stub.asInterface(service)
                val model = activeModelPath
                _state.value = if (model.isNullOrBlank()) {
                    EngineState.Ready("")
                } else {
                    EngineState.Ready(model)
                }
            } catch (e: Exception) {
                _state.value = EngineState.Crashed("Failed to attach: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engineBinder = null
            _state.value = EngineState.Disconnected
        }
    }

    suspend fun ensureLoaded(model: ModelRef): EngineInfo {
        return ensureLoaded(model.path, model.ctxSize, model.nThreads)
    }

    suspend fun ensureLoaded(modelPath: String, ctxSize: Int = 2048, nThreads: Int = 4): String {
        if (quarantinedModels.contains(modelPath)) {
            _state.value = EngineState.Quarantined(modelPath)
            throw IllegalStateException(
                "This model is unstable on this device. Try a smaller model.",
            )
        }
        activeModelPath = modelPath
        val binder = getOrBindService()
        return try {
            val json = binder.ensureLoaded(modelPath, ctxSize, nThreads)
            _state.value = EngineState.Ready(modelPath)
            json
        } catch (e: Exception) {
            throw e
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
                trySend(EngineEvent.LoadProgress(pct, phase.orEmpty()))
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
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        } catch (_: Exception) {
        }
        app.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        return withTimeout(8_000L) {
            suspendCancellableCoroutine { cont ->
                val start = System.currentTimeMillis()
                fun poll() {
                    val b = engineBinder
                    if (b != null && b.asBinder().isBinderAlive) {
                        if (cont.isActive) cont.resume(b)
                    } else if (System.currentTimeMillis() - start > 7_500) {
                        if (cont.isActive) {
                            cont.resumeWithException(IllegalStateException("EngineService binding timeout"))
                        }
                    } else {
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ poll() }, 80)
                    }
                }
                poll()
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
