package io.agents.arya.engine.budget

import org.json.JSONObject

/**
 * Pure, Context-free memory planner (S3). Unit-tested; never touches Android APIs.
 */
object MemoryBudget {
    const val PLAN_VERSION = 2
    private const val MARGIN_BYTES = 600L * 1024 * 1024
    private const val FALLBACK_KV_PER_2048_PER_1B = 64L * 1024 * 1024
    private const val NATIVE_TRANSIENT_RESERVE_BYTES = 160L * 1024 * 1024
    private const val PROCESS_HEADROOM_RATIO = 0.88

    data class Inputs(
        val totalRamBytes: Long,
        val availRamBytes: Long,
        val modelFileBytes: Long,
        val isLowRamDevice: Boolean,
        val modelMeta: ModelMeta? = null,
        /** Android's per-process large-heap class in bytes; 0 means unavailable. */
        val processMemoryLimitBytes: Long = 0L,
    )

    data class ModelMeta(
        val nLayers: Int,
        val nKvHeads: Int,
        val headDim: Int,
        val nParams: Long,
    )

    data class DeviceProfile(
        val version: Int = 1,
        val bigCores: Int = 4,
        val bestThreads: Int = 4,
        val memBwGbs: Double = 10.0,
        val flashMbps: Double = 200.0,
        val ramClass: String = "4GB",
    )

    sealed interface Plan {
        data class Load(val ctxSize: Int, val nThreads: Int, val useMlock: Boolean) : Plan
        data class Refuse(val reasonEn: String, val reasonFa: String, val suggestSmallerModel: Boolean) : Plan
    }

    fun ramClassOf(totalRamBytes: Long): String {
        val gb = totalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return when {
            gb < 3.5 -> "3GB"
            gb < 5.0 -> "4GB"
            gb < 7.0 -> "6GB"
            else -> "8GB+"
        }
    }

    fun ramClassGb(ramClass: String): Int = when (ramClass) {
        "3GB" -> 3
        "4GB" -> 4
        "6GB" -> 6
        else -> 8
    }

    fun kvBytes(ctx: Int, meta: ModelMeta?, modelFileBytes: Long): Long {
        if (meta != null && meta.nLayers > 0 && meta.nKvHeads > 0 && meta.headDim > 0) {
            // 2 * layers * kvHeads * headDim * f16 * ctx
            return 2L * meta.nLayers * meta.nKvHeads * meta.headDim * 2L * ctx
        }
        val paramsB = if (meta != null && meta.nParams > 0) {
            meta.nParams.toDouble() / 1e9
        } else {
            // Q4_K_M is ~0.6 bytes/param → params ≈ fileBytes / 0.6
            (modelFileBytes / 0.6) / 1e9
        }
        val scale = (ctx / 2048.0) * paramsB.coerceAtLeast(0.4)
        return (FALLBACK_KV_PER_2048_PER_1B * scale).toLong()
    }

    fun modelWorkingSet(modelFileBytes: Long, ctx: Int, meta: ModelMeta?): Long =
        modelFileBytes + kvBytes(ctx, meta, modelFileBytes)

    fun plan(i: Inputs, profile: DeviceProfile?): Plan {
        val nThreads = (profile?.bestThreads ?: 4).coerceIn(1, 6)

        if (i.modelFileBytes > (i.totalRamBytes * 0.45)) {
            return Plan.Refuse(
                reasonEn = "This model is too large for this device's RAM",
                reasonFa = "مدل برای رم دستگاه بزرگ است",
                suggestSmallerModel = true,
            )
        }

        // getLargeMemoryClass() describes the managed Java heap, not the
        // resident size of read-only mmap pages in a native :engine process.
        // Never compare the whole GGUF file against that value: on Huawei it
        // is 512 MB even though the 12 GB device has several GB free. Use the
        // heap class only as a conservative cap for transient native work
        // (KV cache + llama metadata/allocator headroom).
        val processLimit = i.processMemoryLimitBytes
        val processBudget = if (processLimit > 0L) {
            (processLimit.toDouble() * PROCESS_HEADROOM_RATIO).toLong()
        } else 0L
        val transientNative = kvBytes(2048, i.modelMeta, i.modelFileBytes) + NATIVE_TRANSIENT_RESERVE_BYTES
        if (processBudget > 0L && transientNative > processBudget) {
            return Plan.Refuse(
                reasonEn = "This model's runtime working memory exceeds the app's per-process budget",
                reasonFa = "حافظهٔ کاری اجرای این مدل از سقف پردازش برنامه بیشتر است",
                suggestSmallerModel = true,
            )
        }

        // Mobile chat never needs 4096. 4096 doubles KV + compute and is
        // what OOM-killed :engine on Huawei 1.7B after a "successful" mmap.
        val candidates = if (i.isLowRamDevice) {
            listOf(1024, 512)
        } else {
            listOf(2048, 1024, 512)
        }

        for (ctx in candidates) {
            val working = modelWorkingSet(i.modelFileBytes, ctx, i.modelMeta)
            val fitsSystemRam = i.availRamBytes - working >= MARGIN_BYTES
            val fitsProcessBudget = processBudget <= 0L || (kvBytes(ctx, i.modelMeta, i.modelFileBytes) + NATIVE_TRANSIENT_RESERVE_BYTES) <= processBudget
            if (fitsSystemRam && fitsProcessBudget) {
                return Plan.Load(
                    ctxSize = ctx,
                    nThreads = nThreads,
                    useMlock = false,
                )
            }
        }

        return Plan.Refuse(
            reasonEn = "Not enough free RAM to load this model",
            reasonFa = "حافظه رم آزاد کافی برای اجرای مدل وجود ندارد",
            suggestSmallerModel = true,
        )
    }

    fun parseModelMeta(json: String?): ModelMeta? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val layers = o.optInt("n_layers", 0)
            val kvHeads = o.optInt("n_kv_heads", 0)
            val headDim = o.optInt("head_dim", 0)
            val nParams = o.optLong("n_params", 0L)
            if (layers <= 0) return null
            ModelMeta(
                nLayers = layers,
                nKvHeads = if (kvHeads > 0) kvHeads else o.optInt("n_heads", 0),
                headDim = if (headDim > 0) headDim else {
                    val embd = o.optInt("n_embd", 0)
                    val heads = o.optInt("n_heads", 0)
                    if (embd > 0 && heads > 0) embd / heads else 0
                },
                nParams = nParams,
            )
        } catch (_: Exception) {
            null
        }
    }
}
