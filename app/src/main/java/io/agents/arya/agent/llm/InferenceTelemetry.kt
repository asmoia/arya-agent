// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import io.agents.arya.ClawApplication
import io.agents.arya.utils.XLog
import com.google.gson.Gson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Deep performance telemetry system for inference optimization.
 *
 * Collects metrics at every layer:
 * - Model load: time, RAM before/after, file size, mmap
 * - Inference: prompt eval time, gen time, tokens/sec, KV cache pressure
 * - System: CPU cores, RAM, GPU availability, thermal throttling
 * - Task: iterations, tool call success rate, stuck detection events
 */
object InferenceTelemetryCollector {

    private const val TAG = "InfTelemetry"
    private val GSON = Gson()

    private val totalInferences = AtomicLong(0)
    private val totalPromptEvalMs = AtomicLong(0)
    private val totalGenMs = AtomicLong(0)
    private val totalPromptTokens = AtomicLong(0)
    private val totalGenTokens = AtomicLong(0)
    private val totalToolCalls = AtomicLong(0)
    private val totalToolCallFailures = AtomicLong(0)
    private val totalFinishCalls = AtomicLong(0)
    private val totalStuckEvents = AtomicLong(0)

    data class ModelStats(
        var loadCount: Int = 0,
        var lastLoadMs: Long = 0,
        var avgLoadMs: Long = 0,
        var inferenceCount: Int = 0,
        var avgPromptTokPerS: Double = 0.0,
        var avgGenTokPerS: Double = 0.0,
        var lastModelInfo: Map<String, Any>? = null,
    )

    private val modelStats = ConcurrentHashMap<String, ModelStats>()

    data class TaskMetrics(
        val taskId: String,
        val startTimeMs: Long,
        val userPrompt: String,
        var endTimeMs: Long = 0,
        var iterations: Int = 0,
        var toolCalls: Int = 0,
        var toolCallSuccesses: Int = 0,
        var toolCallFailures: Int = 0,
        var stuckEvents: Int = 0,
        var finishReason: String = "",
        var promptTokensUsed: Long = 0,
        var genTokensUsed: Long = 0,
        var totalInferenceMs: Long = 0,
        var outcome: String = "",
    )

    private val activeTask = ConcurrentHashMap<String, TaskMetrics>()

    data class SystemSnapshot(
        val timestampMs: Long = System.currentTimeMillis(),
        val cpuCores: Int = 0,
        val ramTotalMb: Long = 0,
        val ramAvailMb: Long = 0,
        val ramUsedMb: Long = 0,
        val nativeHeapMb: Long = 0,
        val gpuAvailable: Boolean = false,
        val thermalStatus: Int = -1,
        val batteryLevel: Int = -1,
        val isCharging: Boolean = false,
        val processMemoryMb: Long = 0,
    )

    fun systemSnapshot(): SystemSnapshot {
        val context = ClawApplication.instance
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val nativeHeap = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try { am.currentThermalStatus } catch (_: Exception) { -1 }
        } else -1

        val batteryLevel = try {
            val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        } catch (_: Exception) { -1 }

        val isCharging = try {
            val ifilter = android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, ifilter)
            val status = batteryStatus?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) { false }

        val gpuAvailable = try {
            BitNetNative.getSystemInfo()?.get("gpu_available") == true
        } catch (_: Exception) { false }

        return SystemSnapshot(
            cpuCores = Runtime.getRuntime().availableProcessors(),
            ramTotalMb = memInfo.totalMem / (1024 * 1024),
            ramAvailMb = memInfo.availMem / (1024 * 1024),
            ramUsedMb = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024),
            nativeHeapMb = nativeHeap,
            gpuAvailable = gpuAvailable,
            thermalStatus = thermal,
            batteryLevel = batteryLevel,
            isCharging = isCharging,
            processMemoryMb = usedMem,
        )
    }

    fun recordModelLoad(modelPath: String, loadTimeMs: Long, modelInfo: Map<String, Any>?) {
        val stats = modelStats.getOrPut(modelPath) { ModelStats() }
        stats.loadCount++
        stats.lastLoadMs = loadTimeMs
        stats.avgLoadMs = ((stats.avgLoadMs * (stats.loadCount - 1)) + loadTimeMs) / stats.loadCount
        stats.lastModelInfo = modelInfo

        val snap = systemSnapshot()
        XLog.i(TAG, "📊 MODEL LOAD: ${modelPath.takeLast(30)} ${loadTimeMs}ms | " +
            "RAM: ${snap.ramAvailMb}/${snap.ramTotalMb}MB | " +
            "Native: ${snap.nativeHeapMb}MB | Thermal: ${snap.thermalStatus} | " +
            "Battery: ${snap.batteryLevel}%${if (snap.isCharging) "⚡" else ""}")
    }

    fun recordInference(
        modelPath: String,
        promptTokens: Int,
        genTokens: Int,
        promptEvalMs: Double,
        genMs: Double,
    ) {
        totalInferences.incrementAndGet()
        totalPromptEvalMs.addAndGet(promptEvalMs.toLong())
        totalGenMs.addAndGet(genMs.toLong())
        totalPromptTokens.addAndGet(promptTokens.toLong())
        totalGenTokens.addAndGet(genTokens.toLong())

        val stats = modelStats.getOrPut(modelPath) { ModelStats() }
        stats.inferenceCount++
        val promptTokPerS = if (promptEvalMs > 0) promptTokens / (promptEvalMs / 1000.0) else 0.0
        val genTokPerS = if (genMs > 0) genTokens / (genMs / 1000.0) else 0.0
        stats.avgPromptTokPerS = ((stats.avgPromptTokPerS * (stats.inferenceCount - 1)) + promptTokPerS) / stats.inferenceCount
        stats.avgGenTokPerS = ((stats.avgGenTokPerS * (stats.inferenceCount - 1)) + genTokPerS) / stats.inferenceCount

        XLog.i(TAG, "📊 INFERENCE: prompt=${promptTokens}tok/${promptEvalMs.toInt()}ms " +
            "(${String.format("%.0f", promptTokPerS)}tok/s) " +
            "gen=${genTokens}tok/${genMs.toInt()}ms " +
            "(${String.format("%.0f", genTokPerS)}tok/s)")
    }

    fun recordToolCall(success: Boolean, toolName: String) {
        totalToolCalls.incrementAndGet()
        if (!success) totalToolCallFailures.incrementAndGet()
        XLog.d(TAG, "📊 TOOL: $toolName success=$success")
    }

    fun recordFinish() { totalFinishCalls.incrementAndGet() }
    fun recordStuck() { totalStuckEvents.incrementAndGet() }

    fun startTask(taskId: String, userPrompt: String) {
        activeTask[taskId] = TaskMetrics(
            taskId = taskId,
            startTimeMs = System.currentTimeMillis(),
            userPrompt = userPrompt,
        )
        val snap = systemSnapshot()
        XLog.i(TAG, "📊 TASK START: '${userPrompt.take(40)}' | " +
            "RAM: ${snap.ramAvailMb}MB | CPU: ${snap.cpuCores} | Thermal: ${snap.thermalStatus}")
    }

    fun endTask(taskId: String, reason: String, outcome: String) {
        val metrics = activeTask.remove(taskId) ?: return
        metrics.endTimeMs = System.currentTimeMillis()
        metrics.finishReason = reason
        metrics.outcome = outcome
        val elapsed = metrics.endTimeMs - metrics.startTimeMs
        val snap = systemSnapshot()
        XLog.i(TAG, "📊 TASK END: '${metrics.userPrompt.take(30)}' " +
            "${elapsed}ms ${metrics.iterations}iters " +
            "tools=${metrics.toolCalls}✓${metrics.toolCallFailures}✗ " +
            "stuck=${metrics.stuckEvents} | RAM: ${snap.ramAvailMb}MB | Thermal: ${snap.thermalStatus}")
    }

    fun summary(): String {
        val snap = systemSnapshot()
        return buildString {
            appendLine("=== Arya Inference Telemetry ===")
            appendLine("System: CPU=${snap.cpuCores} RAM=${snap.ramAvailMb}/${snap.ramTotalMb}MB " +
                "Native=${snap.nativeHeapMb}MB GPU=${snap.gpuAvailable} " +
                "Thermal=${snap.thermalStatus} Battery=${snap.batteryLevel}%")
            appendLine("Total: ${totalInferences.get()} inferences, " +
                "${totalPromptTokens.get()} prompt tok, ${totalGenTokens.get()} gen tok")
            appendLine("Tools: ${totalToolCalls.get()} calls, ${totalToolCallFailures.get()} failures, " +
                "${totalFinishCalls.get()} finishes, ${totalStuckEvents.get()} stuck events")
            for ((path, stats) in modelStats) {
                appendLine("Model: ${path.takeLast(30)} loads=${stats.loadCount} " +
                    "avg_load=${stats.avgLoadMs}ms inferences=${stats.inferenceCount} " +
                    "avg_prompt=${String.format("%.0f", stats.avgPromptTokPerS)}tok/s " +
                    "avg_gen=${String.format("%.0f", stats.avgGenTokPerS)}tok/s")
            }
            if (activeTask.isNotEmpty()) {
                appendLine("Active tasks: ${activeTask.size}")
                for ((_, task) in activeTask) {
                    val elapsed = System.currentTimeMillis() - task.startTimeMs
                    appendLine("  ${task.taskId}: ${elapsed}ms ${task.iterations}iters " +
                        "'${task.userPrompt.take(30)}'")
                }
            }
        }
    }

    fun toJson(): String {
        val data = mapOf(
            "system" to systemSnapshot(),
            "totals" to mapOf(
                "inferences" to totalInferences.get(),
                "promptTokens" to totalPromptTokens.get(),
                "genTokens" to totalGenTokens.get(),
                "toolCalls" to totalToolCalls.get(),
                "toolCallFailures" to totalToolCallFailures.get(),
                "finishCalls" to totalFinishCalls.get(),
                "stuckEvents" to totalStuckEvents.get(),
            ),
            "models" to modelStats.map { (path, stats) ->
                mapOf(
                    "path" to path.takeLast(40),
                    "loadCount" to stats.loadCount,
                    "avgLoadMs" to stats.avgLoadMs,
                    "inferenceCount" to stats.inferenceCount,
                    "avgPromptTokPerS" to String.format("%.1f", stats.avgPromptTokPerS),
                    "avgGenTokPerS" to String.format("%.1f", stats.avgGenTokPerS),
                )
            },
        )
        return GSON.toJson(data)
    }

    fun logFullDump() {
        XLog.i(TAG, summary())
    }
}
