package io.agents.arya.agent.llm

import android.content.Context
import io.agents.arya.agent.AgentConfig

object LlmClientFactory {

    fun create(
        context: Context,
        config: AgentConfig,
        engineClient: EngineClient
    ): LlmClient {
        return if (config.isLocalModel) {
            LocalLlmClient(context, config, engineClient)
        } else {
            val dialect = if (config.provider.lowercase().contains("anthropic")) {
                CloudDialect.ANTHROPIC
            } else {
                CloudDialect.OPENAI
            }

            val cloudConfig = CloudConfig(
                baseUrl = config.baseUrl.ifEmpty { "https://api.openai.com/v1" },
                apiKey = config.apiKey,
                model = config.modelName.ifEmpty { "gpt-4o-mini" },
                dialect = dialect,
                temperature = config.temperature
            )
            CloudLlmClient(cloudConfig)
        }
    }
}
