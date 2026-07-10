// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.tool

/**
 * Event/predicate-oriented wait primitive. It returns as soon as a UI condition
 * is true instead of sleeping an unconditional fixed duration.
 */
object UiWaitEngine {
    data class Result(val satisfied: Boolean, val elapsedMs: Long)

    fun until(
        timeoutMs: Long,
        pollMs: Long = 80L,
        condition: () -> Boolean,
    ): Result {
        val started = System.currentTimeMillis()
        val deadline = started + timeoutMs.coerceAtLeast(0L)
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return Result(true, System.currentTimeMillis() - started)
            try {
                Thread.sleep(pollMs.coerceIn(20L, 250L))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Result(false, System.currentTimeMillis() - started)
            }
        }
        return Result(condition(), System.currentTimeMillis() - started)
    }
}
