package io.agents.arya.agent.llm

/**
 * S6 retry: one retry on IOException before any delta; never after partial output.
 */
object CloudRetry {
    fun shouldRetry(receivedAnyDelta: Boolean, attempt: Int, isIo: Boolean): Boolean {
        return isIo && !receivedAnyDelta && attempt == 0
    }
}
