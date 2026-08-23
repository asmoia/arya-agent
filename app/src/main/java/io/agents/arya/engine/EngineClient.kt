package io.agents.arya.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface EngineState {
    object Disconnected : EngineState
    object Connecting : EngineState
    data class Ready(val modelPath: String) : EngineState
    object Busy : EngineState
    data class Crashed(val reason: String) : EngineState
}

sealed interface EngineEvent {
    data class Delta(val text: String) : EngineEvent
    data class Done(val statsJson: String) : EngineEvent
    data class Failed(val code: Int, val message: String) : EngineEvent
    data class LoadProgress(val pct: Int, val phase: String) : EngineEvent
}

class EngineClient(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main)
    private val _state = MutableStateFlow<EngineState>(EngineState.Disconnected)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private var engineBinder: IEngine? = null
    private var activeModelPath: String? = null

    // Crash loop tracker
    private val crashTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val quarantinedModels = ConcurrentHashMap.newKeySet<String>()

    private val deathRecipient = IBinder.DeathRecipient {
        _state.value = EngineState.Crashed("Engine process died unexpectedly")
        val model = activeModelPath
        if (model != null) {
            recordCrash(model)
        }
        engineBinder = null
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                service?.linkToDeath(deathRecipient, 0)
                engineBinder = IEngine.Stub.asInterface(service)
                val activeModel = activeModelPath
                if (activeModel != null) {
                    _state.value = EngineState.Ready(activeModel)
                } else {
                    _state.value = EngineState.Ready("")
                }
            } catch (e: Exception) {
                _state.value = EngineState.Crashed("Failed to register death recipient: ${e.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            engineBinder = null
            _state.value = EngineState.Disconnected
        }
    }

    suspend fun ensureLoaded(modelPath: String, ctxSize: Int = 2048, nThreads: Int = 4): String {
        if (quarantinedModels.contains(modelPath)) {
            throw IllegalStateException("این مدل روی دستگاه شما ناپایدار است؛ مدل سبک‌تر را امتحان کنید")
        }

        activeModelPath = modelPath
        val binder = getOrBindService()

        return suspendCancellableCoroutine { continuation ->
            try {
                binder.registerCallback(object : IEngineCallback.Stub() {
                    override fun onDelta(requestId: Int, textDelta: String?) {}
                    override fun onDone(requestId: Int, statsJson: String?) {}
                    override fun onError(requestId: Int, code: Int, message: String?) {}
                    override fun onLoadProgress(pct: Int, phase: String?) {
                        // Progress reported during model bench / load
                    }
                })

                val stats = binder.ensureLoaded(modelPath, ctxSize, nThreads)
                _state.value = EngineState.Ready(modelPath)
                continuation.resume(stats)
            } catch (e: Exception) {
                continuation.resumeWithException(e)
            }
        }
    }

    fun generate(requestJson: String): Flow<EngineEvent> = callbackFlow {
        val binder = engineBinder
        if (binder == null) {
            trySend(EngineEvent.Failed(2, "Engine process not bound"))
            close()
            return@callbackFlow
        }

        val callback = object : IEngineCallback.Stub() {
            override fun onDelta(requestId: Int, textDelta: String?) {
                if (textDelta != null) {
                    trySend(EngineEvent.Delta(textDelta))
                }
            }

            override fun onDone(requestId: Int, statsJson: String?) {
                trySend(EngineEvent.Done(statsJson ?: "{}"))
                close()
            }

            override fun onError(requestId: Int, code: Int, message: String?) {
                trySend(EngineEvent.Failed(code, message ?: "Unknown engine error"))
                close()
            }

            override fun onLoadProgress(pct: Int, phase: String?) {
                trySend(EngineEvent.LoadProgress(pct, phase ?: ""))
            }
        }

        binder.registerCallback(callback)
        val reqId = binder.generate(requestJson)
        if (reqId < 0) {
            trySend(EngineEvent.Failed(1, "Engine busy or rejected request"))
            close()
        }

        awaitClose {
            try {
                binder.cancel(reqId)
            } catch (_: Exception) {}
        }
    }

    suspend fun savePrefixState(key: String): Boolean {
        return engineBinder?.savePrefixState(key) ?: false
    }

    suspend fun loadPrefixState(key: String): Boolean {
        return engineBinder?.loadPrefixState(key) ?: false
    }

    suspend fun countTokens(text: String): Int {
        return engineBinder?.countTokens(text) ?: 0
    }

    fun unload() {
        try {
            engineBinder?.unload()
        } catch (_: Exception) {}
        _state.value = EngineState.Disconnected
    }

    private suspend fun getOrBindService(): IEngine {
        val existing = engineBinder
        if (existing != null && existing.asBinder().isBinderAlive) {
            return existing
        }

        _state.value = EngineState.Connecting
        val intent = Intent(context, EngineService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // Wait until service binds
        return suspendCancellableCoroutine { continuation ->
            scope.launch {
                var attempts = 0
                while (engineBinder == null && attempts < 50) {
                    kotlinx.coroutines.delay(100)
                    attempts++
                }
                val binder = engineBinder
                if (binder != null) {
                    continuation.resume(binder)
                } else {
                    continuation.resumeWithException(IllegalStateException("EngineService binding timeout"))
                }
            }
        }
    }

    private fun recordCrash(modelPath: String) {
        val now = System.currentTimeMillis()
        val list = crashTimestamps.getOrPut(modelPath) { ArrayList() }
        list.add(now)
        // Keep crashes within last 10 minutes (600,000 ms)
        list.removeAll { now - it > 10 * 60 * 1000L }
        if (list.size >= 3) {
            quarantinedModels.add(modelPath)
        }
    }
}
