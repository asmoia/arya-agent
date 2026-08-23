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
    private var callback: IEngineCallback? = null
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
            callback = cb
        }

        override fun ensureLoaded(modelPath: String, ctxSize: Int, nThreads: Int): String {
            touch()
            val existing = profileManager.getProfile()
            if (existing == null) {
                profileManager.runBenchIfNeeded { pct, phase ->
                    emitProgress(pct, phase)
                }
            }
            return try {
                val info = engineCore.ensureLoaded(modelPath, ctxSize, nThreads)
                startForegroundIfNeeded()
                info
            } catch (e: EngineLoadException) {
                throw RemoteException(EngineError.message(e.code, e.message))
            }
        }

        override fun generate(requestJson: String): Int {
            touch()
            val cb = callback
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
                    lastTokenMs = System.currentTimeMillis()
                    try {
                        cb.onDelta(id, textDelta)
                    } catch (_: RemoteException) {
                    }
                }

                override fun onDone(id: Int, statsJson: String?) {
                    stopWatchdog()
                    try {
                        cb.onDone(id, statsJson)
                    } catch (_: RemoteException) {
                    }
                }

                override fun onError(id: Int, code: Int, message: String?) {
                    stopWatchdog()
                    try {
                        cb.onError(id, code, message)
                    } catch (_: RemoteException) {
                    }
                }

                override fun onLoadProgress(pct: Int, phase: String?) {
                    try {
                        cb.onLoadProgress(pct, phase)
                    } catch (_: RemoteException) {
                    }
                }
            }
            startWatchdog()
            inferenceHandler.post {
                engineCore.generateStream(requestJson, requestId, wrapped)
                touch()
            }
            return requestId
        }

        override fun cancel(requestId: Int) {
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
        engineCore = EngineCore(this)
        profileManager = DeviceProfileManager(this)
        inferenceThread = HandlerThread("inference").apply { start() }
        inferenceHandler = Handler(inferenceThread.looper)
        startForegroundIfNeeded()
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
                emitProgress(0, "unloaded_low_memory")
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
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Arya")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    private fun emitProgress(pct: Int, phase: String) {
        try {
            callback?.onLoadProgress(pct, phase)
        } catch (_: RemoteException) {
        }
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
