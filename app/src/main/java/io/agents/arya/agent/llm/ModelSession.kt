package io.agents.arya.agent.llm

import android.content.Context
import io.agents.arya.agent.AgentConfig
import java.io.File

/**
 * Single readiness gate for chat. Never silently fall through to OpenAI
 * when the user has no key and no local GGUF.
 */
sealed class ModelReadiness {
    data class Local(val config: AgentConfig, val label: String, val path: String) : ModelReadiness()
    data class Cloud(val config: AgentConfig, val label: String) : ModelReadiness()
    data class NeedsSetup(val reason: String, val recommended: LocalModelManager.ModelInfo?) : ModelReadiness()
}

object ModelSession {

    fun resolve(context: Context): ModelReadiness {
        val snap = ModelConfigRepository.snapshot()
        val recommended = LocalModelManager.bestSupportedModel(context)
            ?: LocalModelManager.AVAILABLE_MODELS.minByOrNull { it.minRamGb }

        val localFile = snap.local.modelPath.takeIf { it.isNotBlank() }?.let { File(it) }
        if (localFile != null && localFile.exists() && localFile.length() > 1_048_576L) {
            return ModelReadiness.Local(
                config = snap.toAgentConfig(temperature = 0.3, maxIterations = 8, streaming = true),
                label = snap.local.displayName.ifBlank { localFile.nameWithoutExtension },
                path = localFile.absolutePath,
            )
        }

        val downloaded = LocalModelManager.catalog(context)
            .filter { it.isDownloaded && it.isSupported }
            .maxByOrNull { it.model.minRamGb }
        if (downloaded?.path != null) {
            ModelConfigRepository.saveLocalDefault(
                modelPath = downloaded.path,
                modelId = downloaded.model.id,
                activateNow = true,
            )
            val healed = ModelConfigRepository.snapshot()
            return ModelReadiness.Local(
                config = healed.toAgentConfig(temperature = 0.3, maxIterations = 8, streaming = true),
                label = downloaded.model.displayName,
                path = downloaded.path,
            )
        }

        if (snap.activeCloud.isConfigured) {
            return ModelReadiness.Cloud(
                config = snap.toAgentConfig(temperature = 0.4, maxIterations = 10, streaming = true),
                label = snap.activeCloud.modelName.ifBlank { "Cloud" },
            )
        }

        return ModelReadiness.NeedsSetup(
            reason = "Download a local model to use Arya offline. Cloud needs an API key.",
            recommended = recommended,
        )
    }

    fun label(readiness: ModelReadiness): String = when (readiness) {
        is ModelReadiness.Local -> readiness.label
        is ModelReadiness.Cloud -> readiness.label
        is ModelReadiness.NeedsSetup -> "No model"
    }

    fun isReady(readiness: ModelReadiness): Boolean =
        readiness is ModelReadiness.Local || readiness is ModelReadiness.Cloud
}
