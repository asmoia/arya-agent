// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.llm

import dev.langchain4j.agent.tool.ToolExecutionRequest
import dev.langchain4j.model.output.TokenUsage

data class LlmResponse(
    val text: String?,
    val toolExecutionRequests: List<ToolExecutionRequest>,
    val tokenUsage: TokenUsage? = null,
    val modelName: String? = null,
    /** Inference telemetry from local models (BitNet/llama.cpp). Null for cloud. */
    val telemetry: BitNetNative.InferenceTelemetry? = null,
) {
    fun hasToolExecutionRequests(): Boolean = toolExecutionRequests.isNotEmpty()
}
