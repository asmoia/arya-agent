package io.agents.arya.agent.visual

import io.agents.arya.ClawApplication
import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.LlmProvider
import io.agents.arya.agent.llm.ChatMsg
import io.agents.arya.agent.llm.LlmClientFactory
import io.agents.arya.agent.llm.Role
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolRegistry
import io.agents.arya.tool.ToolResult
import io.agents.arya.utils.XLog

class VisualControlAgent(
    private val task: String,
    private val maxIterations: Int = 8,
) {
    companion object {
        private const val TAG = "VisualAgent"
        private const val SYSTEM_PROMPT = """
You control an Android phone by acting on screen elements.
Available actions (respond with ONE action per step):
- tap <id>            : tap element id (e.g. tap n3)
- type <id> <text>    : type text into element id
- swipe <id> up|down|left|right : scroll a list
- back                : go back
- home                : go home
- finish <summary>    : task done
Only output the action, nothing else.
"""
    }

    private val perceiver = ScreenPerceiver()
    private val reflector = ActionReflector()
    private val config: AgentConfig = AgentConfig(
        apiKey = "",
        baseUrl = "",
        provider = LlmProvider.LOCAL,
        systemPrompt = SYSTEM_PROMPT,
    )
    private val client = LlmClientFactory.create(
        ClawApplication.instance,
        config,
        ClawApplication.instance.engineClient,
    )

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
            val resp = client.chatSync(
                messages = listOf(
                    ChatMsg(Role.SYSTEM, SYSTEM_PROMPT),
                    ChatMsg(Role.USER, prompt),
                ),
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
                history.add("Reflection: previous action had NO effect. Pick a different element.")
            }
        }
        return "Reached iteration limit ($maxIterations) without completing: $task"
    }

    private fun buildPrompt(task: String, elements: String, history: List<String>): String =
        buildString {
            append("TASK: $task\n\nSCREEN ELEMENTS:\n$elements\n")
            if (history.isNotEmpty()) {
                append("\nHISTORY:\n").append(history.joinToString("\n")).append('\n')
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
