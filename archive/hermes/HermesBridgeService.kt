// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.hermes

import io.agents.arya.agent.AgentCallback
import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.AgentService
import io.agents.arya.utils.XLog

/**
 * **DEPRECATED** — external HTTP bridge to a Termux/desktop Hermes gateway.
 *
 * Arya now ships an **embedded** Hermes core:
 * [io.agents.arya.agent.hermes.core.HermesAgentService]
 *
 * That core runs fully inside the APK (memory, skills, sessions, phone tools)
 * and does **not** need Termux or `hermes gateway`.
 *
 * This class remains only so older experiments compile; [executeTask] fails fast
 * with a Persian guidance message.
 */
@Deprecated(
    message = "Use embedded HermesAgentService — no external gateway required",
    replaceWith = ReplaceWith(
        "HermesAgentService",
        "io.agents.arya.agent.hermes.core.HermesAgentService"
    )
)
class HermesBridgeService : AgentService {

    companion object {
        private const val TAG = "HermesBridge"
    }

    override fun initialize(config: AgentConfig) {
        XLog.w(TAG, "HermesBridgeService is deprecated; use embedded HermesAgentService")
    }

    override fun updateConfig(config: AgentConfig) = Unit

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        callback.onError(
            0,
            IllegalStateException(
                "پل خارجی هرمس حذف شده. هستهٔ هرمس داخل خود اپ آریا فعال است " +
                    "(HermesAgentService). نیازی به Termux نیست."
            ),
            0
        )
    }

    override fun cancel() = Unit
    override fun shutdown() = Unit
    override fun isRunning(): Boolean = false
}

/** Legacy settings blob — unused by the embedded core. */
@Deprecated("Embedded core uses KVUtils.isHermesEmbeddedEnabled()")
data class HermesBridgeConfig(
    val enabled: Boolean = false,
    val url: String = "http://127.0.0.1:8642",
    val apiKey: String = "",
    val fallbackToLocal: Boolean = true,
)
