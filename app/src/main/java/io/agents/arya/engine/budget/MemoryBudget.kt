package io.agents.arya.engine.budget

object MemoryBudget {
    data class Inputs(
        val totalRamBytes: Long,
        val availRamBytes: Long,
        val modelFileBytes: Long,
        val isLowRamDevice: Boolean,
    )

    data class DeviceProfile(
        val version: Int = 1,
        val bigCores: Int = 4,
        val bestThreads: Int = 4,
        val memBwGbs: Double = 10.0,
        val flashMbps: Double = 200.0,
        val ramClass: String = "4GB"
    )

    sealed interface Plan {
        data class Load(val ctxSize: Int, val nThreads: Int, val useMlock: Boolean) : Plan
        data class Refuse(val reasonFa: String, val suggestSmallerModel: Boolean) : Plan
    }

    fun estimateWorkingSetBytes(modelFileBytes: Long, ctxSize: Int): Long {
        val kvBytes = ctxSize.toLong() * 64L * 1024L // ~64KB per context token estimate
        return modelFileBytes + kvBytes
    }

    fun plan(i: Inputs, profile: DeviceProfile?): Plan {
        val nThreads = profile?.bestThreads ?: 4

        // 1. Model size > 45% of total RAM
        if (i.modelFileBytes > (i.totalRamBytes * 0.45)) {
            return Plan.Refuse("مدل برای رم دستگاه بزرگ است", suggestSmallerModel = true)
        }

        val requiredMargin = 600L * 1024L * 1024L // 600 MB
        val candidateCtxs = if (i.isLowRamDevice) {
            listOf(1024, 512)
        } else {
            listOf(4096, 2048, 1024, 512)
        }

        for (ctx in candidateCtxs) {
            val workingSet = estimateWorkingSetBytes(i.modelFileBytes, ctx)
            if (i.availRamBytes - workingSet >= requiredMargin) {
                return Plan.Load(ctxSize = ctx, nThreads = nThreads, useMlock = false)
            }
        }

        return Plan.Refuse("حافظه رم آزاد کافی برای اجرای مدل وجود ندارد", suggestSmallerModel = true)
    }
}
