package io.agents.arya.agent.llm

import io.agents.arya.ClawApplication
import io.agents.arya.utils.XLog

/**
 * Single-shot helpers over the new LlmClient (no LangChain4j).
 */
object LlmSessionManager {
    private const val TAG = "LlmSessionManager"
    private const val DEFAULT_LOCAL_SYSTEM_PROMPT = "You are a helpful assistant. Answer concisely."

    fun createCloudClient(temperature: Double = 0.7): LlmClient? {
        val config = ModelConfigRepository.snapshot()
        if (config.activeMode == ActiveModelMode.LOCAL) return null
        val cloud = config.activeCloud
        if (cloud.apiKey.isEmpty() || cloud.modelName.isEmpty()) {
            XLog.w(TAG, "createCloudClient: incomplete cloud config")
            return null
        }
        val agent = config.toAgentConfig(temperature = temperature, maxIterations = 60)
        return LlmClientFactory.create(ClawApplication.instance, agent, ClawApplication.instance.engineClient)
    }

    fun singleShot(prompt: String, temperature: Double = 0.3): String? {
        return if (ModelConfigRepository.snapshot().activeMode == ActiveModelMode.CLOUD) {
            singleShotCloud(prompt, temperature)
        } else {
            singleShotLocal(prompt, temperature)
        }
    }

    fun singleShotCloud(prompt: String, temperature: Double = 0.7): String? {
        val client = createCloudClient(temperature) ?: return null
        return try {
            client.chatSync(listOf(ChatMsg(Role.USER, prompt))).text
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotCloud failed: ${e.message}")
            null
        } finally {
            client.close()
        }
    }

    fun singleShotCloud(systemPrompt: String, userPrompt: String, temperature: Double = 0.7): String? {
        val client = createCloudClient(temperature) ?: return null
        return try {
            client.chatSync(
                listOf(
                    ChatMsg(Role.SYSTEM, systemPrompt),
                    ChatMsg(Role.USER, userPrompt),
                ),
            ).text
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotCloud failed: ${e.message}")
            null
        } finally {
            client.close()
        }
    }

    fun singleShotLocal(prompt: String, temperature: Double = 0.3): String? =
        singleShotLocal(DEFAULT_LOCAL_SYSTEM_PROMPT, prompt, temperature)

    fun singleShotLocal(systemPrompt: String, prompt: String, temperature: Double = 0.3): String? {
        return try {
            val modelPath = ModelConfigRepository.snapshot().local.modelPath
            if (modelPath.isNullOrEmpty()) return null
            if (LocalModelManager.isRetiredHeavyLocalModel(modelPath)) return null
            val app = ClawApplication.instance
            val config = io.agents.arya.agent.AgentConfig(
                apiKey = "",
                baseUrl = modelPath,
                modelName = modelPath.substringAfterLast('/').substringBeforeLast('.'),
                systemPrompt = systemPrompt,
                temperature = temperature,
                provider = io.agents.arya.agent.LlmProvider.LOCAL,
            )
            val client = LocalLlmClient(app, config, app.engineClient)
            try {
                client.chatSync(
                    listOf(
                        ChatMsg(Role.SYSTEM, systemPrompt),
                        ChatMsg(Role.USER, prompt),
                    ),
                ).text
            } finally {
                client.close()
            }
        } catch (e: Exception) {
            XLog.w(TAG, "singleShotLocal failed: ${e.message}")
            null
        }
    }

    fun isCloudConfigured(): Boolean = ModelConfigRepository.snapshot().defaultCloud.isConfigured
}
