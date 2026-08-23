package io.agents.arya.agent

import io.agents.arya.utils.XLog

/**
 * One agent loop. Hermes is archived (S8); this always returns DefaultAgentService.
 */
object AgentServiceFactory {
    private const val TAG = "AgentServiceFactory"

    @JvmStatic
    fun create(): AgentService = create(null)

    @JvmStatic
    fun create(config: AgentConfig?): AgentService {
        XLog.i(TAG, "Creating DefaultAgentService (single agent loop)")
        return DefaultAgentService()
    }
}
