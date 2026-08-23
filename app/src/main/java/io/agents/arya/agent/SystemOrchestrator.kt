package io.agents.arya.agent

import io.agents.arya.ClawApplication
import io.agents.arya.agent.llm.ChatMsg
import io.agents.arya.agent.llm.LlmClientFactory
import io.agents.arya.agent.llm.Role
import io.agents.arya.agent.visual.VisualControlAgent
import io.agents.arya.utils.XLog

class SystemOrchestrator {
    companion object {
        private const val TAG = "Orchestrator"
    }

    private val budget = LocalTaskBudget()

    fun handle(task: String, onRoutine: (String) -> String): String {
        val tier = RoutingPolicy.classify(task)
        XLog.i(TAG, "task='${task.take(40)}' -> tier=$tier")
        return when (tier) {
            Tier.ROUTINE -> onRoutine(task)
            Tier.VISUAL -> budget.withBudget("visual") {
                VisualControlAgent(task, maxIterations = budget.visualMax).run()
            }
            Tier.CLOUD -> runCloud(task)
        }
    }

    private fun runCloud(task: String): String {
        val app = ClawApplication.instance
        val cfg = AgentConfig(
            apiKey = "",
            baseUrl = "",
            provider = LlmProvider.OPENAI,
            systemPrompt = "You are Arya, a capable assistant.",
        )
        val client = LlmClientFactory.create(app, cfg, app.engineClient)
        return try {
            budget.withBudget("cloud") {
                client.chatSync(listOf(ChatMsg(Role.USER, task))).text ?: "No response from cloud."
            }
        } finally {
            client.close()
        }
    }
}
