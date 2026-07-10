// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent.llm

/** Serializes LiteRT conversations across Chat, Task and background work. */
enum class LocalInferenceOwner { NONE, CHAT, TASK, BACKGROUND }
enum class LocalInferencePhase { IDLE, LOADING, READY, BUSY, FAILED }
data class LocalInferenceSnapshot(val owner: LocalInferenceOwner, val phase: LocalInferencePhase, val modelPath: String?, val backend: String?, val failure: String?, val generation: Long)
class LocalInferenceBusyException(message: String) : IllegalStateException(message)

object LocalInferenceCoordinator {
    private val lock = Any()
    private var owner = LocalInferenceOwner.NONE
    private var phase = LocalInferencePhase.IDLE
    private var modelPath: String? = null
    private var backend: String? = null
    private var failure: String? = null
    private var generation = 0L

    fun acquire(requester: LocalInferenceOwner, path: String) = synchronized(lock) {
        require(requester != LocalInferenceOwner.NONE)
        if (owner != LocalInferenceOwner.NONE && owner != requester) throw LocalInferenceBusyException("Local model is busy with ${owner.name.lowercase()}.")
        owner = requester; phase = LocalInferencePhase.LOADING; modelPath = path; failure = null; generation++
    }
    fun markReady(requester: LocalInferenceOwner, activeBackend: String?) = synchronized(lock) { if (owner == requester) { phase = LocalInferencePhase.READY; backend = activeBackend; failure = null } }
    fun markBusy(requester: LocalInferenceOwner) = synchronized(lock) { if (owner == requester) phase = LocalInferencePhase.BUSY }
    fun markFailed(requester: LocalInferenceOwner, error: Throwable?) = synchronized(lock) { if (owner == requester) { phase = LocalInferencePhase.FAILED; failure = error?.message?.take(180) ?: "Unknown local runtime failure" } }
    fun release(requester: LocalInferenceOwner) = synchronized(lock) { if (owner == requester) { owner = LocalInferenceOwner.NONE; phase = LocalInferencePhase.IDLE; modelPath = null; backend = null; failure = null; generation++ } }
    fun reset() = synchronized(lock) { owner = LocalInferenceOwner.NONE; phase = LocalInferencePhase.IDLE; modelPath = null; backend = null; failure = null; generation++ }
    fun snapshot(): LocalInferenceSnapshot = synchronized(lock) { LocalInferenceSnapshot(owner, phase, modelPath, backend, failure, generation) }
}
