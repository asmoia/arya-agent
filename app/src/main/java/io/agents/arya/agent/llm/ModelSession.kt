package io.agents.arya.agent.llm

import android.content.Context
import io.agents.arya.agent.AgentConfig
import java.io.File

/**
 * Single readiness gate for chat and voice. A non-empty preference is not enough:
 * local readiness requires a usable GGUF file, and cloud readiness requires both
 * a model and an API key.
 */
sealed class ModelReadiness {
    data class Local(val config: AgentConfig, val label: String, val path: String) : ModelReadiness()
    data class Cloud(val config: AgentConfig, val label: String) : ModelReadiness()
    data class NeedsSetup(val reason: String, val recommended: LocalModelManager.ModelInfo?) : ModelReadiness()
}

object ModelSession {

    fun resolve(context: Context): ModelReadiness {
        val snapshot = ModelConfigRepository.snapshot()
        val recommended = LocalModelManager.bestSupportedModel(context)
            ?: LocalModelManager.AVAILABLE_MODELS.minByOrNull { it.minRamGb }

        // An explicitly selected, valid cloud configuration wins over a local file.
        if (snapshot.activeMode == ActiveModelMode.CLOUD && snapshot.activeCloud.isConfigured) {
            return cloud(snapshot)
        }

        val local = resolveLocal(context, snapshot)
        if (local != null) {
            // Repair persisted state when a stale FUSE/external path resolves to an
            // internal managed copy. This keeps future launches on the safe path.
            if (snapshot.local.modelPath != local.path || snapshot.activeMode != ActiveModelMode.LOCAL) {
                ModelConfigRepository.saveLocalDefault(
                    modelPath = local.path,
                    modelId = local.model.id,
                    activateNow = true,
                )
            }
            val healed = ModelConfigRepository.snapshot()
            return ModelReadiness.Local(
                config = healed.toLocalAgentConfig(
                    temperature = 0.3,
                    maxIterations = 8,
                    streaming = true,
                    modelPath = local.path,
                    modelId = local.model.id,
                ),
                label = local.model.displayName,
                path = local.path,
            )
        }

        // If local mode has no usable file, a valid cloud config is still a safer
        // fallback than silently constructing an empty OPENAI client.
        if (snapshot.activeCloud.isConfigured) {
            return cloud(snapshot)
        }

        return ModelReadiness.NeedsSetup(
            reason = "Download a local GGUF model to use Arya offline, or configure a cloud API key.",
            recommended = recommended,
        )
    }

    private fun cloud(snapshot: ResolvedModelConfig): ModelReadiness.Cloud {
        return ModelReadiness.Cloud(
            config = snapshot.toCloudAgentConfig(
                temperature = 0.4,
                maxIterations = 10,
                streaming = true,
            ),
            label = snapshot.activeCloud.modelName.ifBlank { "Cloud" },
        )
    }

    private fun resolveLocal(
        context: Context,
        snapshot: ResolvedModelConfig,
    ): LocalCandidate? {
        val configured = snapshot.local.modelPath.takeIf { it.isNotBlank() }?.let(::File)
        if (configured != null && LocalModelManager.isUsableModelFile(configured)) {
            val model = modelForPath(snapshot, configured)
            if (model != null) {
                if (LocalModelManager.oemKillsHeavyLocalModels() && model.id == "qwen3-1.7b") {
                    val small = LocalModelManager.AVAILABLE_MODELS.firstOrNull { it.id == "qwen3-0.6b" }
                    val smallPath = small?.let { LocalModelManager.getModelPath(context, it) }
                    if (small != null && smallPath != null) {
                        return LocalCandidate(small, smallPath)
                    }
                }
                return LocalCandidate(model, configured.absolutePath)
            }
        }

        val configuredModel = LocalModelManager.AVAILABLE_MODELS.firstOrNull { model ->
            snapshot.local.modelId.equals(model.id, ignoreCase = true) ||
                snapshot.local.modelPath.endsWith(model.fileName, ignoreCase = true)
        }
        if (configuredModel != null) {
            LocalModelManager.getModelPath(context, configuredModel)?.let { path ->
                return LocalCandidate(configuredModel, path)
            }
        }

        val catalogCandidate = LocalModelManager.catalog(context)
            .asSequence()
            .filter { it.isDownloaded && it.isSupported && it.path != null }
            .let { seq ->
                if (LocalModelManager.oemKillsHeavyLocalModels()) {
                    seq.minByOrNull { it.model.minRamGb }
                } else {
                    seq.maxByOrNull { it.model.minRamGb }
                }
            }
        if (catalogCandidate?.path != null) {
            return LocalCandidate(catalogCandidate.model, catalogCandidate.path)
        }

        val recovered = LocalModelManager.findAnyGguf(context) ?: return null
        val recoveredModel = LocalModelManager.AVAILABLE_MODELS.firstOrNull {
            recovered.name.contains(it.fileName.substringBefore(".gguf"), ignoreCase = true)
        } ?: LocalModelManager.AVAILABLE_MODELS.firstOrNull {
            it.minRamGb <= LocalModelManager.getDeviceRamGb(context)
        } ?: return null
        if (!LocalModelManager.isModelSupportedOnDevice(context, recoveredModel)) return null
        return LocalCandidate(recoveredModel, recovered.absolutePath)
    }

    private fun modelForPath(snapshot: ResolvedModelConfig, file: File): LocalModelManager.ModelInfo? {
        return LocalModelManager.AVAILABLE_MODELS.firstOrNull {
            snapshot.local.modelId.equals(it.id, ignoreCase = true) ||
                file.name.equals(it.fileName, ignoreCase = true) ||
                file.name.contains(it.fileName.substringBefore(".gguf"), ignoreCase = true)
        } ?: LocalModelManager.customModel()?.takeIf { it.fileName.equals(file.name, ignoreCase = true) }
    }

    private data class LocalCandidate(
        val model: LocalModelManager.ModelInfo,
        val path: String,
    )

    fun label(readiness: ModelReadiness): String = when (readiness) {
        is ModelReadiness.Local -> readiness.label
        is ModelReadiness.Cloud -> readiness.label
        is ModelReadiness.NeedsSetup -> "No model"
    }

    fun isReady(readiness: ModelReadiness): Boolean =
        readiness is ModelReadiness.Local || readiness is ModelReadiness.Cloud
}
