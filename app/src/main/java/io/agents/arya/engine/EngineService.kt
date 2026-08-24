package io.agents.arya.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import io.agents.arya.engine.budget.DeviceProfileManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Isolated `:engine` process. Owns the model and a single inference thread.
 */
class EngineService : Service() {

    private lateinit var engineCore: EngineCore
    private lateinit var profileManager: DeviceProfileManager
    private lateinit var inferenceThread: HandlerThread
    private lateinit var inferenceHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var sessionCallback: IEngineCallback? = null
    private val callbacks = ConcurrentHashMap<Int, IEngineCallback>()
    @Volatile
    private var lastActivityMs: Long = System.currentTimeMillis()
    @Volatile
    private var lastTokenMs: Long = 0L
    @Volatile
    private var generateStartedMs: Long = 0L
    @Volatile
    private var requestDeadlineMs: Long = 0L
    @Volatile
    private var activeRequestId: Int = -1

    private val nextRequestId = AtomicInteger(1)
    private val idleTimeoutMs = 5 * 60 * 1000L

    private val idleRunnable = Runnable {
        if (!engineCore.isLoaded && !engineCore.isBusy) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!engineCore.isBusy) return
            val now = System.currentTimeMillis()
            val tokenGap = if (lastTokenMs == 0L) 0L else now - lastTokenMs
            val overdue = requestDeadlineMs > 0 && now > requestDeadlineMs
            val stalled = lastTokenMs > 0 && tokenGap > 6_000L
            val noFirstToken = lastTokenMs == 0L && generateStartedMs > 0L &&
                now - generateStartedMs > 15_000L
            if (overdue || stalled || noFirstToken) {
                engineCore.cancel(activeRequestId)
            } else {
                watchdogHandler.postDelayed(this, 500L)
            }
        }
    }

    private val binder = object : IEngine.Stub() {
        override fun registerCallback(cb: IEngineCallback?) {
            sessionCallback = cb
        }

        override fun ensureLoaded(modelPath: String, ctxSize: Int, nThreads: Int): String {
            touch()
            // Binder-thread fast path only. Full mmap + bench must go through requestLoad.
            if (engineCore.isLoaded) {
                val stats = engineCore.stats()
                if (stats.contains(modelPath)) return stats
            }
            throw RemoteException("use requestLoad")
        }

        override fun requestLoad(modelPath: String, ctxSize: Int, nThreads: Int, requestId: Int) {
            touch()
            EngineLog.i("EngineService", "requestLoad id=$requestId path=$modelPath ctx=$ctxSize threads=$nThreads cb=${sessionCallback != null}")
            val cb = sessionCallback
            if (cb == null) {
                EngineLog.e("EngineService", "requestLoad id=$requestId aborted: no callback registered")
                return
            }
            callbacks[requestId] = cb
            inferenceHandler.post {
                try {
                    emitProgress(requestId, 5, "Preparing engine")
                    if (profileManager.getProfile() == null) {
                        profileManager.runBenchIfNeeded { pct, phase ->
                            emitProgress(requestId, (5 + pct * 0.15).toInt().coerceIn(5, 20), phase)
                        }
                    }
                    emitProgress(requestId, 25, "Loading GGUF")
                    EngineLog.i("EngineService", "native ensureLoaded begin id=$requestId")
                    val info = engineCore.ensureLoaded(modelPath, ctxSize, nThreads)
                    EngineLog.i("EngineService", "native ensureLoaded ok id=$requestId info=${info.take(240)}")
                    startForegroundIfNeeded()
                    emitProgress(requestId, 100, "Model ready")
                    try {
                        callbackFor(requestId)?.onLoadResult(requestId, info)
                    } catch (_: RemoteException) {
                    }
                } catch (e: EngineLoadException) {
                    EngineLog.e("EngineService", "requestLoad EngineLoadException id=$requestId code=${e.code} ${e.message}", e)
                    try {
                        callbackFor(requestId)?.onError(requestId, e.code, EngineError.message(e.code, e.message))
                    } catch (_: RemoteException) {
                    }
                } catch (e: Exception) {
                    EngineLog.e("EngineService", "requestLoad failed id=$requestId", e)
                    try {
                        callbackFor(requestId)?.onError(
                            requestId,
                            EngineError.ERR_LOAD_FAILED,
                            EngineError.message(EngineError.ERR_LOAD_FAILED, e.message),
                        )
                    } catch (_: RemoteException) {
                    }
                } finally {
                    dropCallback(requestId)
                    touch()
                }
            }
        }

        override fun generate(requestJson: String): Int {
            touch()
            val cb = sessionCallback
            if (cb == null) return -1
            if (engineCore.isBusy) {
                try {
                    cb.onError(0, EngineError.ERR_BUSY, EngineError.message(EngineError.ERR_BUSY))
                } catch (_: RemoteException) {
                }
                return -1
            }
            if (!engineCore.isLoaded) {
                try {
                    cb.onError(0, EngineError.ERR_NO_MODEL, EngineError.message(EngineError.ERR_NO_MODEL))
                } catch (_: RemoteException) {
                }
                return -1
            }
            val requestId = nextRequestId.getAndIncrement()
            activeRequestId = requestId
            lastTokenMs = 0L
            generateStartedMs = System.currentTimeMillis()
            val req = try {
                EngineRequest.parse(requestJson)
            } catch (_: Exception) {
                EngineRequest(prompt = "")
            }
            requestDeadlineMs = System.currentTimeMillis() + req.deadlineMs
            val wrapped = object : IEngineCallback.Stub() {
                override fun onDelta(id: Int, textDelta: String?) {
                    val first = lastTokenMs == 0L
                    lastTokenMs = System.currentTimeMillis()
                    if (first) {
                        EngineLog.i("EngineService", "LAB_FIRST_TOKEN id=$requestId chars=${textDelta?.length ?: 0}")
                    }
                    try {
                        callbackFor(requestId)?.onDelta(id, textDelta)
                    } catch (_: RemoteException) {
                    }
                }

                override fun onDone(id: Int, statsJson: String?) {
                    stopWatchdog()
                    try {
                        callbackFor(requestId)?.onDone(id, statsJson)
                    } catch (_: RemoteException) {
                    }
                    dropCallback(requestId)
                }

                override fun onError(id: Int, code: Int, message: String?) {
                    stopWatchdog()
                    try {
                        callbackFor(requestId)?.onError(id, code, message)
                    } catch (_: RemoteException) {
                    }
                    dropCallback(requestId)
                }

                override fun onLoadProgress(pct: Int, phase: String?) {
                    try {
                        callbackFor(requestId)?.onLoadProgress(pct, phase)
                    } catch (_: RemoteException) {
                    }
                }

                override fun onLoadResult(id: Int, infoJson: String?) {
                    try {
                        callbackFor(requestId)?.onLoadResult(id, infoJson)
                    } catch (_: RemoteException) {
                    }
                    dropCallback(requestId)
                }
            }
            callbacks[requestId] = cb
            startWatchdog()
            inferenceHandler.post {
                engineCore.generateStream(requestJson, requestId, wrapped)
                touch()
            }
            return requestId
        }

        override fun cancel(requestId: Int) {
            engineCore.cancel(requestId)
            dropCallback(requestId)
        }

        override fun stats(): String = engineCore.stats()

        override fun unload() {
            engineCore.unload()
            stopForeground(STOP_FOREGROUND_REMOVE)
            scheduleIdleStop()
        }

        override fun savePrefixState(key: String): Boolean = engineCore.savePrefixState(key)

        override fun loadPrefixState(key: String): Boolean = engineCore.loadPrefixState(key)

        override fun countTokens(text: String): Int = engineCore.countTokens(text)
    }

    override fun onCreate() {
        super.onCreate()
        EngineLog.init(this)
        EngineLog.i("EngineService", "onCreate pid=${android.os.Process.myPid()}")
        engineCore = EngineCore(this)
        profileManager = DeviceProfileManager(this)
        inferenceThread = HandlerThread("inference").apply { start() }
        inferenceHandler = Handler(inferenceThread.looper)
        startForegroundIfNeeded()
        EngineLog.i("EngineService", "onCreate done foreground=started thread=${inferenceThread.id}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundIfNeeded()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            if (engineCore.isBusy) return
            if (engineCore.isLoaded) {
                engineCore.unload()
                stopForeground(STOP_FOREGROUND_REMOVE)
                emitProgress(0, 0, "unloaded_low_memory")
            }
        }
    }

    override fun onDestroy() {
        watchdogHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        inferenceThread.quitSafely()
        engineCore.unload()
        super.onDestroy()
    }

    private fun startForegroundIfNeeded() {
        val channelId = "arya_engine_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Arya local model",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val text = if (engineCore.isLoaded) {
            "Local model is ready"
        } else {
            "Starting local engine"
        }
        updateForeground(text)
    }

    private fun callbackFor(requestId: Int): IEngineCallback? =
        callbacks[requestId] ?: sessionCallback

    private fun dropCallback(requestId: Int) {
        callbacks.remove(requestId)
    }

    private fun emitProgress(requestId: Int, pct: Int, phase: String) {
        try {
            callbackFor(requestId)?.onLoadProgress(pct, phase)
        } catch (_: RemoteException) {
        }
        val text = if (pct in 1..99) "$phase ($pct%)" else phase.ifBlank {
            if (engineCore.isLoaded) "Local model is ready" else "Starting local engine"
        }
        updateForeground(text)
    }

    private fun updateForeground(text: String) {
        val channelId = "arya_engine_channel"
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Arya")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        startForeground(1001, notification)
    }

    private fun touch() {
        lastActivityMs = System.currentTimeMillis()
        scheduleIdleStop()
    }

    private fun scheduleIdleStop() {
        mainHandler.removeCallbacks(idleRunnable)
        mainHandler.postDelayed(idleRunnable, idleTimeoutMs)
    }

    private fun startWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, 500L)
    }

    private fun stopWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        activeRequestId = -1
        requestDeadlineMs = 0L
    }
}
