package io.agents.arya.agent.hermes.core

/** Hermes core archived (S8). Stubs keep Settings compiling. */
enum class HermesThinkingMode { ADAPTIVE, INSTANT, THINKING, HIGH }

object HermesRuntimePolicy {
    private var mode: HermesThinkingMode = HermesThinkingMode.ADAPTIVE
    fun currentMode(): HermesThinkingMode = mode
    fun setMode(next: HermesThinkingMode) {
        mode = next
    }
}
