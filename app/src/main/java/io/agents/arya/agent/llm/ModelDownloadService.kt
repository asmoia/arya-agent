package io.agents.arya.agent.llm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import io.agents.arya.R
import io.agents.arya.ui.chat.ComposeChatActivity
import java.util.concurrent.Executors

/**
 * Foreground download so a 0.5–2.5 GB GGUF can finish with the UI closed.
 * Resume is handled inside [LocalModelManager.downloadModel].
 */
class ModelDownloadService : Service() {

    private val executor = Executors.newSingleThreadExecutor()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val modelId = intent?.getStringExtra(EXTRA_MODEL_ID)
        val model = modelId?.let { ModelDownloadHub.lookupModel(it) }
        if (model == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(NOTIF_ID, buildNotification(model.displayName, 0, 0, "Preparing…"))
        ModelDownloadHub.markRunning(model.id)

        executor.execute {
            LocalModelManager.downloadModel(applicationContext, model, object : LocalModelManager.DownloadCallback {
                override fun onProgress(bytesDownloaded: Long, totalBytes: Long, bytesPerSecond: Long) {
                    ModelDownloadHub.progress(model.id, bytesDownloaded, totalBytes, bytesPerSecond)
                    val pct = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotification(model.displayName, pct, 100, "$pct%"))
                }

                override fun onComplete(modelPath: String) {
                    ModelDownloadHub.complete(model.id, modelPath)
                    ModelConfigRepository.saveLocalDefault(
                        modelPath = modelPath,
                        modelId = model.id,
                        activateNow = true,
                    )
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotification(model.displayName, 100, 100, "Ready"))
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf(startId)
                }

                override fun onError(error: String) {
                    ModelDownloadHub.fail(model.id, error)
                    val nm = getSystemService(NotificationManager::class.java)
                    nm.notify(NOTIF_ID, buildNotification(model.displayName, 0, 0, error))
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelf(startId)
                }
            })
        }
        return START_STICKY
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model downloads",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Background GGUF downloads"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(name: String, progress: Int, max: Int, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, ComposeChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Arya · $name")
            .setContentText(text)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(max > 0 && progress < max)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        if (max > 0) builder.setProgress(max, progress, false)
        return builder.build()
    }

    companion object {
        const val EXTRA_MODEL_ID = "model_id"
        private const val CHANNEL_ID = "arya_model_download"
        private const val NOTIF_ID = 4201
    }
}
