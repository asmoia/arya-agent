package io.agents.arya.agent.llm

/** LiteRT backend health was deleted (S8). Stubs keep debug reports compiling. */
object LocalBackendHealth {
    fun isCpuSafeModeEnabled(): Boolean = true
    fun hasVerifiedGpuSuccess(): Boolean = false
    fun hasPendingGpuInitMarker(): Boolean = false
    fun cpuSafeReason(): String = "CPU-only engine (v1)"
    fun shouldRearmVerifiedGpu(
        isCpuSafeModeEnabled: Boolean,
        hasVerifiedGpuSuccess: Boolean,
        hasPendingGpuInitMarker: Boolean,
        cpuSafeReason: String,
        cpuSafeAtMs: Long,
        nowMs: Long,
    ): Boolean = false
    fun debugStateSummary(): String = "cpu-only llama.cpp"
    fun currentDeviceKey(): String = "cpu"
    fun debugDeviceDescriptor(): String = "cpu-only"
    fun isConservativeCpuModeSuggested(): Boolean = true
}

object LocalInferenceCoordinator {
    data class Snapshot(
        val owner: String = "none",
        val phase: String = "idle",
        val backend: String? = "llama.cpp",
        val generation: Int = 0,
        val failure: String? = null,
        val lastFailure: String? = null,
    )
    fun snapshot(): Snapshot = Snapshot()
}
