// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory latency trace for task routing and execution. It is deliberately
 * lightweight; later it can be exported through the existing debug report.
 */
data class TaskTraceEvent(val name: String, val elapsedMs: Long, val detail: String = "")
data class TaskTrace(
    val id: String,
    val route: String,
    val taskPreview: String,
    val startedAtMs: Long,
    val events: List<TaskTraceEvent>,
    val outcome: String?,
)

object TaskPerfTrace {
    private const val MAX_TRACES = 80
    private data class MutableTrace(
        val id: String,
        val route: String,
        val taskPreview: String,
        val startedAt: Long,
        val events: CopyOnWriteArrayList<TaskTraceEvent> = CopyOnWriteArrayList(),
        @Volatile var outcome: String? = null,
    )

    private val active = ConcurrentHashMap<String, MutableTrace>()
    private val completed = CopyOnWriteArrayList<TaskTrace>()

    fun start(id: String, route: String, task: String) {
        active[id] = MutableTrace(id, route, task.take(160), System.currentTimeMillis())
    }

    fun mark(id: String?, name: String, detail: String = "") {
        val trace = id?.let(active::get) ?: return
        trace.events.add(TaskTraceEvent(name, System.currentTimeMillis() - trace.startedAt, detail.take(180)))
    }

    fun finish(id: String?, outcome: String) {
        val trace = id?.let(active::remove) ?: return
        trace.outcome = outcome
        completed += TaskTrace(trace.id, trace.route, trace.taskPreview, trace.startedAt, trace.events.toList(), outcome)
        while (completed.size > MAX_TRACES) completed.removeAt(0)
    }

    fun recent(): List<TaskTrace> = completed.toList()
}
