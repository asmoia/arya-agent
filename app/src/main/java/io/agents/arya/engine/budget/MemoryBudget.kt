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
        /**
         * The context the caller needs in order for its prompt to fit
         * (prompt tokens + maxTokens + reserve). The planner never picks a
         * context smaller than this: a prompt will always be regenerated long
         * before a 1024-token window is enough, so loading at 1024 and then
         * failing every generation with "prompt_exceeds_ctx" is worse than
         * either loading bigger or refusing. Default 2048 (was the old default
         * ceiling) so a too-small window is never silently selected.
         */
        val minCtxSize: Int = 2048,
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
        val requestedThreads = (profile?.bestThreads ?: 4).coerceIn(1, 6)
        val nThreads = if (i.isLowRamDevice || i.availRamBytes < 6L * 1024 * 1024 * 1024) {
            requestedThreads.coerceAtMost(2)
        } else {
            requestedThreads
        }

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

        // The context must be big enough for the caller's prompt. We cap at
        // 2048 for two reasons: (1) 2048 is the largest window that is safe to
        // request without exceeding the model's trained context (n_ctx_train)
        // for the small on-device GEMs we ship — requesting 4096 can make
        // llama_init_from_model fail outright; and (2) it keeps the KV cache and
        // compute graph small, which is what OOM-killed :engine on Huawei 1.7B.
        // The client trims its prompt to fit this window, so generation always
        // has room. We never silently drop below minCtxSize — if a 1024 window
        // is all that fits, loading it and then failing every generate with
        // "prompt_exceeds_ctx" is strictly worse than refusing up front.
        val requestedCtx = i.minCtxSize.coerceIn(512, 2048)
        val highCtx = 2048
        val candidates = listOf(
            highCtx,
            requestedCtx.coerceAtMost(highCtx),
            minOf(2048, highCtx),
            1024,
            512,
        ).distinct().sortedByDescending { it }

        val fits = { ctx: Int ->
            val working = modelWorkingSet(i.modelFileBytes, ctx, i.modelMeta)
            val fitsSystemRam = i.availRamBytes - working >= MARGIN_BYTES
            val fitsProcessBudget = processBudget <= 0L ||
                (kvBytes(ctx, i.modelMeta, i.modelFileBytes) + NATIVE_TRANSIENT_RESERVE_BYTES) <= processBudget
            fitsSystemRam && fitsProcessBudget
        }

        // Prefer the largest context that both fits memory and satisfies the
        // caller's minimum window, so short prompts still get room to grow.
        val accepted = candidates.firstOrNull { it >= requestedCtx && fits(it) }
        if (accepted != null) {
            return Plan.Load(
                ctxSize = accepted,
                nThreads = nThreads,
                useMlock = false,
            )
        }

        // Nothing big enough fits. If a *smaller* window would physically fit,
        // it is still unusable for the caller's prompt — refuse with a clear
        // reason instead of loading something that will fail every generate.
        return Plan.Refuse(
            reasonEn = "Not enough free RAM for a context that fits this prompt (requested $requestedCtx). Free memory or use a smaller model.",
            reasonFa = "حافظه رم آزاد برای پنجرهٔ متنی (context) کافی برای این پیام وجود ندارد. حافظه آزاد کنید یا از مدل کوچکتری استفاده کنید.",
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
