package io.agents.arya.engine

/**
 * Pure deadline math used by EngineService's watchdog (S1).
 */
object EngineWatchdog {
    fun isOverdue(nowMs: Long, deadlineMs: Long): Boolean =
        deadlineMs > 0L && nowMs > deadlineMs

    fun isStalled(nowMs: Long, lastTokenMs: Long, tokenDeadlineMs: Long, generatedAny: Boolean): Boolean {
        if (!generatedAny || lastTokenMs <= 0L || tokenDeadlineMs <= 0L) return false
        return nowMs - lastTokenMs > tokenDeadlineMs
    }
}
