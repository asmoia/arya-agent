package io.agents.arya.agent.llm

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process-wide download ledger. The UI observes this; the foreground
 * service writes it. One job per model id. Downloads resume via Range.
 */
object ModelDownloadHub {

    enum class Phase { QUEUED, RUNNING, DONE, FAILED }

    data class Job(
        val modelId: String,
        val displayName: String,
        val fileName: String,
        val phase: Phase,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val bytesPerSecond: Long = 0L,
        val error: String? = null,
        val path: String? = null,
    ) {
        val progress: Float
            get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

        val percent: Int get() = (progress * 100).toInt()
    }

    private val _jobs = MutableStateFlow<Map<String, Job>>(emptyMap())
    val jobs: StateFlow<Map<String, Job>> = _jobs.asStateFlow()

    fun job(modelId: String): Job? = _jobs.value[modelId]

    fun isBusy(modelId: String): Boolean {
        val p = _jobs.value[modelId]?.phase
        return p == Phase.QUEUED || p == Phase.RUNNING
    }

    fun anyBusy(): Boolean = _jobs.value.values.any { it.phase == Phase.QUEUED || it.phase == Phase.RUNNING }

    fun start(context: Context, model: LocalModelManager.ModelInfo) {
        if (isBusy(model.id)) return
        _jobs.update { current ->
            current + (model.id to Job(
                modelId = model.id,
                displayName = model.displayName,
                fileName = model.fileName,
                phase = Phase.QUEUED,
                totalBytes = model.sizeBytes,
            ))
        }
        val intent = Intent(context, ModelDownloadService::class.java)
            .putExtra(ModelDownloadService.EXTRA_MODEL_ID, model.id)
        ContextCompat.startForegroundService(context.applicationContext, intent)
    }

    internal fun markRunning(modelId: String) {
        _jobs.update { map ->
            val j = map[modelId] ?: return@update map
            map + (modelId to j.copy(phase = Phase.RUNNING))
        }
    }

    internal fun progress(modelId: String, bytes: Long, total: Long, speed: Long) {
        _jobs.update { map ->
            val j = map[modelId] ?: return@update map
            map + (modelId to j.copy(
                phase = Phase.RUNNING,
                bytesDownloaded = bytes,
                totalBytes = if (total > 0) total else j.totalBytes,
                bytesPerSecond = speed,
            ))
        }
    }

    internal fun complete(modelId: String, path: String) {
        _jobs.update { map ->
            val j = map[modelId] ?: return@update map
            val total = if (j.totalBytes > 0) j.totalBytes else j.bytesDownloaded
            map + (modelId to j.copy(
                phase = Phase.DONE,
                path = path,
                bytesDownloaded = total,
                totalBytes = total,
            ))
        }
    }

    internal fun fail(modelId: String, error: String) {
        _jobs.update { map ->
            val j = map[modelId] ?: return@update map
            map + (modelId to j.copy(phase = Phase.FAILED, error = error))
        }
    }

    fun lookupModel(modelId: String): LocalModelManager.ModelInfo? {
        return LocalModelManager.AVAILABLE_MODELS.firstOrNull { it.id == modelId }
            ?: LocalModelManager.customModel()?.takeIf { it.id == modelId }
    }
}
