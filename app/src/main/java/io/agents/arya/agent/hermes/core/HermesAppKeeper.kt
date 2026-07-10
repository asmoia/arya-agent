// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Keep process useful during tasks: FG service + memory awareness.

package io.agents.arya.agent.hermes.core

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import io.agents.arya.ClawApplication
import io.agents.arya.service.ForegroundService
import io.agents.arya.utils.XLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * While a Hermes task runs:
 * - hold a foreground notification so OEM is less eager to kill the process
 * - track trim-memory level for runtime policy / logging
 *
 * Does NOT disable user cancel. Does NOT promise immortality on HyperOS/EMUI.
 */
object HermesAppKeeper : ComponentCallbacks2 {

    private const val TAG = "HermesAppKeeper"
    private val taskDepth = AtomicInteger(0)
    private val registered = AtomicBoolean(false)
    @Volatile
    var lastTrimLevel: Int = ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
        private set

    fun onTaskStart(status: String = "Arya task running…") {
        ensureRegistered()
        val n = taskDepth.incrementAndGet()
        XLog.i(TAG, "onTaskStart depth=$n")
        try {
            val ctx = ClawApplication.instance
            if (n == 1) {
                // Use existing FG API — keeps a visible notification during work.
                if (!ForegroundService.isRunning()) {
                    ForegroundService.start(ctx, "آریا", status)
                } else {
                    ForegroundService.updateTaskStatus(ctx, status)
                }
            } else {
                ForegroundService.updateTaskStatus(ctx, status)
            }
        } catch (e: Exception) {
            XLog.w(TAG, "FG start/update failed: ${e.message}")
        }
    }

    fun onTaskProgress(status: String) {
        if (taskDepth.get() <= 0) return
        try {
            ForegroundService.updateTaskStatus(ClawApplication.instance, status.take(60))
        } catch (_: Exception) {
        }
    }

    /** Pure release rule, kept visible to JVM tests without Android service setup. */
    internal fun nextDepthAfterEnd(current: Int): Int? =
        if (current <= 0) null else current - 1

    /**
     * Ends one previously acquired task lease. An end without a start is a
     * no-op: clamping only a local return value used to leave [taskDepth] at
     * -1 after a pure-chat fast path, so the next real task failed to become
     * the foreground owner.
     */
    fun onTaskEnd() {
        while (true) {
            val current = taskDepth.get()
            val next = nextDepthAfterEnd(current)
            if (next == null) {
                XLog.d(TAG, "onTaskEnd ignored without an active task lease (depth=$current)")
                return
            }
            if (!taskDepth.compareAndSet(current, next)) continue

            XLog.i(TAG, "onTaskEnd depth=$next")
            if (next == 0) {
                try {
                    ForegroundService.resetToIdle(ClawApplication.instance)
                } catch (e: Exception) {
                    XLog.w(TAG, "FG reset failed: ${e.message}")
                }
            }
            return
        }
    }

    fun isUnderMemoryPressure(): Boolean {
        val (_, _, low) = HermesRuntimePolicy.memorySnapshot()
        return low || lastTrimLevel >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
    }

    fun memoryHintFa(): String {
        val (avail, total, low) = HermesRuntimePolicy.memorySnapshot()
        return when {
            low || avail < 700 -> "RAM کم (${avail}MB)"
            avail < 1200 -> "RAM متوسط (${avail}MB)"
            else -> "RAM ${avail}/${total}MB"
        }
    }

    private fun ensureRegistered() {
        if (registered.compareAndSet(false, true)) {
            try {
                ClawApplication.instance.registerComponentCallbacks(this)
            } catch (e: Exception) {
                XLog.w(TAG, "register callbacks: ${e.message}")
                registered.set(false)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    override fun onLowMemory() {
        lastTrimLevel = ComponentCallbacks2.TRIM_MEMORY_COMPLETE
        XLog.w(TAG, "onLowMemory")
    }

    override fun onTrimMemory(level: Int) {
        lastTrimLevel = level
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            XLog.w(TAG, "onTrimMemory level=$level depth=${taskDepth.get()}")
        }
    }
}
