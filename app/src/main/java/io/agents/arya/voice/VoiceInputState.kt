package io.agents.arya.voice

/**
 * Siri-like mic capture state machine (time-contract §Voice).
 * IDLE → LISTENING → PARTIAL → FINAL | ERROR
 */
enum class VoicePhase { IDLE, LISTENING, PARTIAL, FINAL, ERROR }

data class VoiceInputUi(
    val phase: VoicePhase = VoicePhase.IDLE,
    val partialText: String = "",
    val finalText: String = "",
    val errorMessage: String? = null,
)

class VoiceInputController {
    var state: VoiceInputUi = VoiceInputUi()
        private set

    fun start(): VoiceInputUi {
        state = VoiceInputUi(phase = VoicePhase.LISTENING)
        return state
    }

    fun onReady(): VoiceInputUi {
        if (state.phase == VoicePhase.IDLE) start()
        state = state.copy(phase = VoicePhase.LISTENING, errorMessage = null)
        return state
    }

    fun onPartial(text: String): VoiceInputUi {
        state = state.copy(phase = VoicePhase.PARTIAL, partialText = text, errorMessage = null)
        return state
    }

    fun onFinal(text: String): VoiceInputUi {
        state = VoiceInputUi(phase = VoicePhase.FINAL, finalText = text, partialText = text)
        return state
    }

    fun onError(message: String): VoiceInputUi {
        state = VoiceInputUi(phase = VoicePhase.ERROR, errorMessage = message)
        return state
    }

    fun stop(): VoiceInputUi {
        state = VoiceInputUi(phase = VoicePhase.IDLE)
        return state
    }
}
