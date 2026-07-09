// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent

import io.agents.arya.agent.hermes.core.HermesAgentService
import io.agents.arya.utils.KVUtils
import io.agents.arya.utils.XLog

/**
 * Creates the active [AgentService] implementation.
 *
 * - Embedded Hermes core (default for Arya): full in-process brain with memory/skills
 * - DefaultAgentService: classic PokeClaw loop (fallback)
 *
 * Selection order:
 * 1. Explicit [AgentConfig.hermesEnabled] when a config is provided
 * 2. [KVUtils.isHermesEmbeddedEnabled] (default true)
 */
object AgentServiceFactory {

    private const val TAG = "AgentServiceFactory"

    @JvmStatic
    fun create(): AgentService = create(null)

    @JvmStatic
    fun create(config: AgentConfig?): AgentService {
        val useHermes = config?.hermesEnabled ?: KVUtils.isHermesEmbeddedEnabled()
        return if (useHermes) {
            XLog.i(TAG, "Creating HermesAgentService (embedded core)")
            HermesAgentService()
        } else {
            XLog.i(TAG, "Creating DefaultAgentService")
            DefaultAgentService()
        }
    }
}
