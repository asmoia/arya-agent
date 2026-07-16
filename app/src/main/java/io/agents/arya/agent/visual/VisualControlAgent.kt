// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.visual

import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.LlmProvider
import io.agents.arya.agent.llm.LlmClientFactory
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolRegistry
import io.agents.arya.tool.ToolResult
import io.agents.arya.utils.XLog
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.UserMessage

/**
 * PHASE 2 — Visual control agent for AMBIGUOUS / NOVEL tasks.
 *
 * Loop: perceive -> plan (LLM) -> act (existing tools via ToolRegistry) ->
 * reflect -> repeat, capped by an iteration budget.
 *
 * Reuses the existing tool implementations (including the new reliable
 * system_key from Phase 0) through ToolRegistry, so no tool logic is duplicated.
 * For routine tasks, the System Orchestrator (Phase 3) will NOT route here.
 */
class VisualControlAgent(
    private val task: String,
    private val maxIterations: Int = 8
) {

    companion object {
        private const val TAG = "VisualAgent"
        private const val SYSTEM_PROMPT = """
You control an Android phone by acting on screen elements.
Available actions (respond with ONE action per step):
- tap <id>            : tap element id (e.g. tap n3)
- type <id> <text>    : type text into element id
- swipe <id> up|down|left|right : scroll a list
- back                : go back (uses reliable system back)
- home                : go home
- finish <summary>    : task done; summarize what was achieved
Only output the action, nothing else. If stuck after reflection, try a different element.
"""
    }

    private val perceiver = ScreenPerceiver()
    private val reflector = ActionReflector()

    // Reuse the same local model client the chat uses (Phase 0 backend).
    private val config: AgentConfig = AgentConfig(
        apiKey = "",
        baseUrl = "",
        provider = LlmProvider.BITNET, // prefer local; user can switch to LITERT via settings
        systemPrompt = SYSTEM_PROMPT
    )
    private val client = LlmClientFactory.create(config)

    /**
     * Run the visual agent loop and return a final summary.
     */
    fun run(): String {
        val history = mutableListOf<String>()
        repeat(maxIterations) { step ->
            val before = perceiver.perceive()
            val elements = before?.describe().orEmpty()
            if (elements.isBlank()) {
                XLog.w(TAG, "step $step: no screen; aborting")
                return "Could not read the screen."
            }

            val prompt = buildPrompt(task, elements, history)
            val resp = client.chat(
                messages = listOf(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(prompt)
                ),
                toolSpecs = emptyList()
            )
            val action = (resp.text ?: "").trim()
            XLog.i(TAG, "step $step: $action")

            if (action.startsWith("finish")) {
                return action.removePrefix("finish").trim()
            }

            val expectedChange = !action.startsWith("type")
            execute(action)
            val after = perceiver.perceive()
            val r = reflector.reflect(before, after, expectedChange)
            history.add("Action: $action -> ${if (r.changed) "OK" else "FAILED: ${r.reason}"}")
            if (!r.changed && expectedChange) {
                // Reflection says nothing happened -> tell the model to adapt next step.
                history.add("Reflection: previous action had NO effect. Pick a different element or approach.")
            }
        }
        return "Reached iteration limit ($maxIterations) without completing: $task"
    }

    private fun buildPrompt(task: String, elements: String, history: List<String>): String =
        buildString {
            append("TASK: $task\n\nSCREEN ELEMENTS:\n$elements\n")
            if (history.isNotEmpty()) {
                append("\nHISTORY:\n").append(history.joinToString("\n")).append("\n")
            }
            append("\nNext action:")
        }

    private fun execute(action: String) {
        val parts = action.split(" ", limit = 3)
        when (parts.firstOrNull()) {
            "tap" -> tool("tap_node", mapOf("node_id" to (parts.getOrNull(1) ?: "")))
            "type" -> {
                val id = parts.getOrNull(1) ?: ""
                val text = parts.getOrNull(2) ?: ""
                tool("input_text", mapOf("node_id" to id, "text" to text))
            }
            "swipe" -> tool("swipe", mapOf("node_id" to (parts.getOrNull(1) ?: ""), "dir" to (parts.getOrNull(2) ?: "up")))
            "back" -> tool("system_key", mapOf("key" to "back"))
            "home" -> tool("system_key", mapOf("key" to "home"))
            else -> XLog.w(TAG, "unknown action: $action")
        }
    }

    private fun tool(name: String, params: Map<String, Any?>): ToolResult? {
        val t: BaseTool? = ToolRegistry.getTool(name)
        if (t == null) {
            XLog.w(TAG, "tool not found: $name")
            return null
        }
        @Suppress("UNCHECKED_CAST")
        return t.execute(params as Map<String, Any>)
    }
}
