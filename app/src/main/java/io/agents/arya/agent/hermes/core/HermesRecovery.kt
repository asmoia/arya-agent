// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Crash / force-stop / reboot recovery for the embedded Hermes core.

package io.agents.arya.agent.hermes.core

import io.agents.arya.agent.hermes.memory.HermesMemoryStore
import io.agents.arya.agent.hermes.session.HermesSessionStore
import io.agents.arya.agent.hermes.skills.HermesSkillStore
import io.agents.arya.utils.KVUtils
import io.agents.arya.utils.XLog

/**
 * Runs once on process start to leave Hermes stores in a consistent state
 * after force-stop, OOM kill, or reboot mid-task.
 *
 * Safe to call multiple times (idempotent for a given process lifetime).
 */
object HermesRecovery {

    private const val TAG = "HermesRecovery"
    private const val KEY_LAST_RECOVERY_AT = "KEY_HERMES_LAST_RECOVERY_AT"

    @Volatile
    private var ranThisProcess = false

    /**
     * @return human-readable summary for logs / optional UI toast
     */
    fun runOnAppStart(): String {
        if (ranThisProcess) return "already_ran"
        ranThisProcess = true

        return try {
            // Ensure skill seeds exist even before first task
            HermesSkillStore.getInstance().ensureSeedSkills()
            // Touch memory so DEFAULT_MEMORY is created if missing
            HermesMemoryStore.getInstance().readProfile()

            val sessions = HermesSessionStore.getInstance()
            val closed = sessions.closeAllOpenSessions(reason = "recovered_after_process_death")
            val now = System.currentTimeMillis()
            KVUtils.putLong(KEY_LAST_RECOVERY_AT, now)

            if (closed > 0) {
                // Episodic note so the model can "know" last run was interrupted
                HermesMemoryStore.getInstance().appendEpisode(
                    "Recovery: closed $closed open Hermes session(s) after process death / reboot / force-stop."
                )
            }

            val summary = "hermes_recovery closed_open_sessions=$closed"
            XLog.i(TAG, summary)
            summary
        } catch (e: Exception) {
            XLog.e(TAG, "recovery failed", e)
            "hermes_recovery_failed: ${e.message}"
        }
    }

    fun lastRecoveryAt(): Long = KVUtils.getLong(KEY_LAST_RECOVERY_AT, 0L)
}
