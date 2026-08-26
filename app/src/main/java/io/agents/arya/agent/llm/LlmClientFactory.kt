package io.agents.arya.agent.llm

import android.content.Context
import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.LlmProvider
import io.agents.arya.engine.EngineClient

object LlmClientFactory {

    fun create(
        context: Context,
        config: AgentConfig,
        engineClient: EngineClient?,
    ): LlmClient {
        return if (config.provider == LlmProvider.LOCAL) {
            LocalLlmClient(
                context,
                config,
                engineClient ?: throw IllegalStateException("Local voice inference requires an engine client"),
            )
        } else {
            val dialect = if (config.provider == LlmProvider.ANTHROPIC) {
                CloudDialect.ANTHROPIC
            } else {
                CloudDialect.OPENAI
            }
            CloudLlmClient(
                CloudConfig(
                    baseUrl = config.baseUrl.ifEmpty { "https://api.openai.com/v1" },
                    apiKey = config.apiKey,
                    model = config.modelName.ifEmpty { "gpt-4o-mini" },
                    dialect = dialect,
                    temperature = config.temperature.toFloat(),
                ),
            )
        }
    }
}
