package io.agents.arya.debug

/**
 * Overflow #7 — rough per-task mAh estimate from wall time + local vs cloud.
 * Not a lab measurement; labeled as estimate in the task summary.
 */
object BatteryEstimate {
    fun estimateMah(elapsedMs: Long, usedLocalModel: Boolean, toolCalls: Int): Double {
        val minutes = elapsedMs / 60_000.0
        val base = if (usedLocalModel) 18.0 else 4.0 // mAh / minute ballpark on mid-range SoC
        val tools = toolCalls * 0.15
        return ((base * minutes) + tools).coerceAtLeast(0.05)
    }

    fun format(mah: Double): String = String.format("~%.1f mAh (estimate)", mah)
}
