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
import android.os.PowerManager
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
    private val liveCallbacks = ConcurrentHashMap<IBinder, IEngineCallback>()
    private var wakeLock: PowerManager.WakeLock? = null
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
            releaseWorkLock()
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
                now - generateStartedMs > 90_000L
            if (overdue || stalled || noFirstToken) {
                engineCore.cancel(activeRequestId)
            } else {
                watchdogHandler.postDelayed(this, 500L)
            }
        }
    }

    private val binder = object : IEngine.Stub() {
        override fun registerCallback(cb: IEngineCallback?) {
            if (cb == null) return
            sessionCallback = cb
            liveCallbacks[cb.asBinder()] = cb
        }

        override fun ensureLoaded(modelPath: String, ctxSize: Int, nThreads: Int): String {
            touch()
            // Binder-thread fast path only. Full mmap + bench must go through requestLoad.
            if (engineCore.isLoaded) {
                val stats = engineCore.stats()
                if (ModelPaths.statsLooksLike(stats, modelPath)) return stats
            }
            // Do not throw: a RemoteException here shows up as a scary JavaBinder
            // stack even though requestLoad is the intended path.
            return """{"loaded":false,"use":"requestLoad"}"""
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
                    acquireWorkLock()
                    emitProgress(requestId, 3, "Preparing engine")
                    if (profileManager.getProfile() == null) {
                        profileManager.runBenchIfNeeded { pct, phase ->
                            emitProgress(requestId, (3 + pct * 0.07).toInt().coerceIn(3, 10), phase)
                        }
                    }
                    val src = java.io.File(modelPath)
                    val fast = ModelFileLocalizer.ensureFastPath(this@EngineService, src) { pct, phase ->
                        emitProgress(requestId, 10 + (pct * 0.35).toInt(), phase)
                    }
                    emitProgress(requestId, 48, "Reading weights into RAM")
                    EngineLog.i("EngineService", "native ensureLoaded begin id=$requestId path=${fast.absolutePath}")
                    val info = engineCore.ensureLoaded(fast.absolutePath, ctxSize, nThreads) { pct, phase ->
                        emitProgress(requestId, 48 + (pct * 0.50).toInt().coerceAtMost(50), phase)
                    }
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
            acquireWorkLock()
            val cb = sessionCallback
            EngineLog.i("EngineService", "generate binder chars=${requestJson.length} loaded=${engineCore.isLoaded} busy=${engineCore.isBusy} cb=${cb != null}")
            if (cb == null) {
                EngineLog.e("EngineService", "generate aborted: no callback registered")
                return -1
            }
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
            // Keep the request callback until EngineCore emits terminal cancelled/error.
            // Dropping it here makes callbackFlow wait for a timeout forever.
            engineCore.cancel(requestId)
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
        EngineLog.w("EngineService", "onTrimMemory level=$level busy=${engineCore.isBusy} loaded=${engineCore.isLoaded}")
        // Never drop a loaded GGUF just because the system is nervous.
        // Unloading here + a concurrent generate was a use-after-free on Huawei.
    }

    override fun onDestroy() {
        EngineLog.w("EngineService", "onDestroy pid=${android.os.Process.myPid()}")
        watchdogHandler.removeCallbacksAndMessages(null)
        mainHandler.removeCallbacksAndMessages(null)
        inferenceThread.quitSafely()
        engineCore.unload()
        releaseWorkLock()
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

    private fun callbackFor(requestId: Int): IEngineCallback? = callbacks[requestId]

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

    private fun acquireWorkLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "arya:engine").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(15 * 60 * 1000L)
                EngineLog.i("EngineService", "wake lock acquired")
            }
        } catch (e: Exception) {
            EngineLog.w("EngineService", "wake lock failed: ${e.message}")
        }
    }

    private fun releaseWorkLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                EngineLog.i("EngineService", "wake lock released")
            }
        } catch (_: Exception) {
        }
    }
}
