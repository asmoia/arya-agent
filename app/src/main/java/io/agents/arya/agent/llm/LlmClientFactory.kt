// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.llm

import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.DefaultAgentService
import io.agents.arya.agent.LlmProvider
import io.agents.arya.agent.langchain.http.OkHttpClientBuilderAdapter

object LlmClientFactory {

    fun create(config: AgentConfig): LlmClient {
        val httpClientBuilder = OkHttpClientBuilderAdapter().apply {
            if (DefaultAgentService.FILE_LOGGING_ENABLED && DefaultAgentService.FILE_LOGGING_CACHE_DIR != null) {
                setFileLoggingEnabled(true, DefaultAgentService.FILE_LOGGING_CACHE_DIR)
            }
        }
        return when (config.provider) {
            LlmProvider.OPENAI -> OpenAiLlmClient(config, httpClientBuilder)
            LlmProvider.ANTHROPIC -> AnthropicLlmClient(config, httpClientBuilder)
            LlmProvider.LOCAL -> LocalLlmClient(config)
            LlmProvider.BITNET -> BitNetLlmClient(config)
        }
    }
}
