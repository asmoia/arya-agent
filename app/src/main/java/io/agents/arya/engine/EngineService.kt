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
import android.os.RemoteException
import androidx.core.app.NotificationCompat
import io.agents.arya.engine.budget.DeviceProfileManager

class EngineService : Service() {

    private lateinit var engineCore: EngineCore
    private lateinit var profileManager: DeviceProfileManager
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private var callback: IEngineCallback? = null
    private var idleHandler = Handler()
    private val idleTimeoutMs = 5 * 60 * 1000L // 5 minutes
    private val idleRunnable = Runnable {
        if (!engineCore.isLoaded) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private val binder = object : IEngine.Stub() {
        override fun registerCallback(cb: IEngineCallback?) {
            this@EngineService.callback = cb
        }

        override fun ensureLoaded(modelPath: String, ctxSize: Int, nThreads: Int): String {
            resetIdleTimer()
            val profile = profileManager.getProfile()
            if (profile == null) {
                profileManager.runBenchIfNeeded { pct, phase ->
                    try {
                        callback?.onLoadProgress(pct, phase)
                    } catch (_: RemoteException) {}
                }
            }

            val result = engineCore.ensureLoaded(modelPath, ctxSize, nThreads)
            startForegroundIfNeeded()
            return result
        }

        override fun generate(requestJson: String): Int {
            resetIdleTimer()
            val requestId = (System.currentTimeMillis() % 1000000).toInt()
            val cb = callback ?: return -1

            handler.post {
                engineCore.generateStream(requestJson, requestId, cb)
                resetIdleTimer()
            }
            return requestId
        }

        override fun cancel(requestId: Int) {
            engineCore.cancel(requestId)
        }

        override fun stats(): String {
            return engineCore.stats()
        }

        override fun unload() {
            engineCore.unload()
            stopForeground(STOP_FOREGROUND_REMOVE)
            resetIdleTimer()
        }

        override fun savePrefixState(key: String): Boolean {
            return engineCore.savePrefixState(key)
        }

        override fun loadPrefixState(key: String): Boolean {
            return engineCore.loadPrefixState(key)
        }

        override fun countTokens(text: String): Int {
            return engineCore.countTokens(text)
        }
    }

    override fun onCreate() {
        super.onCreate()
        engineCore = EngineCore(this)
        profileManager = DeviceProfileManager(this)
        handlerThread = HandlerThread("InferenceThread").apply { start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_CRITICAL) {
            if (!engineCore.isLoaded) return
            // If model is loaded but idle, unload to save system RAM
            engineCore.unload()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    override fun onDestroy() {
        handlerThread.quitSafely()
        engineCore.unload()
        super.onDestroy()
    }

    private fun startForegroundIfNeeded() {
        val channelId = "arya_engine_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "آریا - پردازشگر هوش مصنوعی",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("آریا در حال اجراست")
            .setContentText("مدل هوش مصنوعی محلی در حافظه بارگذاری شده است")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        startForeground(1001, notification)
    }

    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        idleHandler.postDelayed(idleRunnable, idleTimeoutMs)
    }
}
