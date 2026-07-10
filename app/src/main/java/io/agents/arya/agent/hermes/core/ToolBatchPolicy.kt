// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent.hermes.core

import dev.langchain4j.agent.tool.ToolExecutionRequest

/** Only batches deterministic navigation/reads; sensitive UI changes stay single-step. */
object ToolBatchPolicy {
    private val readOnly = setOf("get_screen_info", "find_node_info", "get_device_info", "get_notifications", "get_installed_apps", "take_screenshot")
    fun select(requests: List<ToolExecutionRequest>): List<ToolExecutionRequest> {
        if (requests.size <= 1) return requests
        val first = requests.first(); val second = requests.getOrNull(1)
        if (first.name() == "open_app" && second?.name() == "get_screen_info") return requests.take(2)
        if (requests.all { it.name() in readOnly }) return requests.take(3)
        return listOf(first)
    }
}
