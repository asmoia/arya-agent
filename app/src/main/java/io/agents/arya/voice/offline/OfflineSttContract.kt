package io.agents.arya.voice.offline

/**
 * Overflow #1 — whisper.cpp tiny/base-q5 fa, optional.
 * Same AIDL isolation pattern as the LLM engine, but NOT wired in v1.
 * Settings toggle label: settings_offline_stt.
 */
interface OfflineSttBackend {
    fun isAvailable(): Boolean
    fun transcribeWav(path: String): String
}

class UnavailableOfflineStt : OfflineSttBackend {
    override fun isAvailable(): Boolean = false
    override fun transcribeWav(path: String): String =
        throw UnsupportedOperationException("Offline STT is not bundled in v1")
}
