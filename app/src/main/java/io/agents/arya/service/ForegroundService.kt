// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.agents.arya.AppCapabilityCoordinator
import io.agents.arya.R
import io.agents.arya.ServiceBindingState
import io.agents.arya.agent.llm.InferenceTelemetryCollector
import io.agents.arya.utils.XLog

/**
 * Foreground service — v0.6.0 with WakeLock, telemetry polling, battery optimization.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        private const val MONITOR_HEALTH_POLL_MS = 5_000L
        private const val TELEMETRY_POLL_MS = 15_000L
        const val CHANNEL_ID = "Arya_foreground_channel"
        // UI-process FGS only. EngineService uses 21001 — IDs are per-package.
        const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val DEFAULT_TASK_TITLE = "آریا · در حال انجام تسک"
        private const val DEFAULT_TASK_TEXT = "Running task..."
        private const val DEFAULT_MONITOR_TITLE = "آریا · مانیتورینگ"
        private const val DEGRADED_MONITOR_TITLE = "آریا · مانیتورینگ متوقف"

        private enum class ForegroundMode { IDLE, TASK, MONITOR }
        @Volatile private var _isRunning = false
        @Volatile private var _mode = ForegroundMode.IDLE
        @Volatile private var wakeLock: PowerManager.WakeLock? = null

        fun isRunning(): Boolean = _isRunning

        @Synchronized
        fun acquireWakeLock(context: Context) {
            if (wakeLock?.isHeld == true) return
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Arya::InferenceWakeLock")
                    .apply { acquire(5 * 60 * 1000L) }
                XLog.i(TAG, "WakeLock acquired")
            } catch (e: Exception) { XLog.w(TAG, "WakeLock failed: ${e.message}") }
        }

        @Synchronized
        fun releaseWakeLock() {
            try { if (wakeLock?.isHeld == true) { wakeLock?.release(); XLog.i(TAG, "WakeLock released") }; wakeLock = null }
            catch (e: Exception) { XLog.w(TAG, "WakeLock release failed: ${e.message}") }
        }

        fun requestIgnoreBatteryOptimizations(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                        val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .apply { data = android.net.Uri.parse("package:${context.packageName}") }
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                } catch (_: Exception) {}
            }
        }

        fun updateTaskStatus(context: Context, statusText: String) {
            _mode = ForegroundMode.TASK; showNotification(context, DEFAULT_TASK_TITLE, statusText)
        }
        fun resetToIdle(context: Context) { syncToBackgroundState(context) }

        fun showMonitorStatus(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            if (!manager.isEnabled || manager.monitoredContacts.isEmpty()) { _mode = ForegroundMode.IDLE; stop(context); return false }
            _mode = ForegroundMode.MONITOR
            val capabilities = AppCapabilityCoordinator.snapshot(context)
            if (capabilities.notificationAccessState != ServiceBindingState.READY)
                return showNotification(context, DEGRADED_MONITOR_TITLE, "Notification Access disconnected")
            if (capabilities.accessibilityState != ServiceBindingState.READY)
                return showNotification(context, DEGRADED_MONITOR_TITLE, "Accessibility disconnected")
            val contacts = manager.monitoredContacts.toList()
            val text = when (contacts.size) { 0 -> "Monitoring in background"; 1 -> "Monitoring ${contacts.first()}"; else -> "Monitoring ${contacts.size} chats" }
            return showNotification(context, DEFAULT_MONITOR_TITLE, text)
        }

        fun syncToBackgroundState(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            return if (manager.isEnabled && manager.monitoredContacts.isNotEmpty()) showMonitorStatus(context)
            else { _mode = ForegroundMode.IDLE; releaseWakeLock(); stop(context); false }
        }

        private fun showNotification(context: Context, title: String, text: String): Boolean {
            if (!hasNotificationPermission(context)) return false
            return try {
                if (_isRunning) { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, buildNotification(context, title, text)); true }
                else { start(context, title, text); true }
            } catch (e: Exception) { XLog.w(TAG, "Notification failed", e); false }
        }

        fun start(context: Context, title: String = context.getString(R.string.notification_content_title),
            text: String = context.getString(R.string.notification_content_text)): Boolean {
            // Do NOT gate on POST_NOTIFICATIONS: goForeground() handles a missing
            // permission gracefully, and a task should keep the process alive even
            // if the notification is hidden.
            return try {
                val intent = Intent(context, ForegroundService::class.java).apply { putExtra(EXTRA_TITLE, title); putExtra(EXTRA_TEXT, text) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        context.startForegroundService(intent)
                    } catch (e: Exception) {
                        // API 31+ may reject background-sourced FGS starts; fall back
                        // to a normal start so the task still runs (with no keep-alive).
                        XLog.w(TAG, "startForegroundService rejected (${e.message}), falling back to startService")
                        context.startService(intent)
                    }
                } else context.startService(intent)
                true
            } catch (e: Exception) { XLog.w(TAG, "Service start failed", e); false }
        }

        private fun hasNotificationPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        fun stop(context: Context) {
            _mode = ForegroundMode.IDLE; releaseWakeLock()
            context.stopService(Intent(context, ForegroundService::class.java))
            runCatching { (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIFICATION_ID) }
        }

        private fun buildNotification(context: Context, title: String, text: String): Notification {
            val intent = Intent(context, io.agents.arya.ui.chat.ComposeChatActivity::class.java)
            val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title).setContentText(text).setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pi).setOngoing(true).setOnlyAlertOnce(true).setAutoCancel(false)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE).build()
        }
    }

    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthRunnable = object : Runnable {
        override fun run() { if (!_isRunning) return; if (_mode == ForegroundMode.MONITOR) syncToBackgroundState(applicationContext); healthHandler.postDelayed(this, MONITOR_HEALTH_POLL_MS) }
    }
    private val telemetryHandler = Handler(Looper.getMainLooper())
    private val telemetryRunnable = object : Runnable {
        override fun run() { if (!_isRunning) return; try { InferenceTelemetryCollector.logFullDump() } catch (_: Exception) {}; telemetryHandler.postDelayed(this, TELEMETRY_POLL_MS) }
    }

    override fun onCreate() {
        super.onCreate(); _isRunning = true
        // Create the channel first (fast, no I/O), then startForeground. A
        // service started with startForegroundService() must reach
        // startForeground() within 5s or Android 12 throws
        // ForegroundServiceDidNotStartInTimeException — which is exactly what
        // the Arya debug report shows multiple times. Both calls come early and
        // neither does any heavy work, so the window is easily met. The
        // notification shows regardless of POST_NOTIFICATIONS; only its
        // *visibility* is gated by that permission.
        createNotificationChannel()
        goForeground(DEFAULT_TASK_TITLE, DEFAULT_TASK_TEXT)
        healthHandler.post(healthRunnable); telemetryHandler.post(telemetryRunnable)
        requestIgnoreBatteryOptimizations(this)
        XLog.i(TAG, "ForegroundService created with WakeLock + telemetry")
    }

    override fun onDestroy() {
        super.onDestroy(); _isRunning = false
        healthHandler.removeCallbacksAndMessages(null); telemetryHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Schedule restart after OEM kill
        try {
            val pi = PendingIntent.getService(this, 1, Intent(this, ForegroundService::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            (getSystemService(Context.ALARM_SERVICE) as AlarmManager).set(AlarmManager.ELAPSED_REALTIME_WAKEUP, android.os.SystemClock.elapsedRealtime() + 3000L, pi)
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(
            intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TASK_TITLE,
            intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TASK_TEXT,
        )
        return START_STICKY
    }

    /**
     * Emit the foreground notification unconditionally and immediately. Silently
     * ignores missing POST_NOTIFICATIONS (the FGS itself still counts as started,
     * which is what prevents the API 31 DidNotStartInTime crash). Never throws.
     */
    private fun goForeground(title: String, text: String) {
        try {
            // ServiceCompat handles the type semantics per-API (ignored below
            // Q, required/validated on Android 14+). The manifest declares
            // specialUse for this service.
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
            }
            androidx.core.app.ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(this, title, text), type,
            )
        } catch (t: Throwable) {
            XLog.w(TAG, "startForeground failed; stopping service to avoid ANR", t)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name), NotificationManager.IMPORTANCE_LOW).apply { description = getString(R.string.notification_channel_description); setShowBadge(false) }
            (getSystemService(NotificationManager::class.java)).createNotificationChannel(channel)
        }
    }
}
