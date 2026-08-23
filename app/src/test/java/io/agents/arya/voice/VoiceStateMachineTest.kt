package io.agents.arya.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

enum class VoiceState {
    IDLE,
    LISTENING,
    PARTIAL,
    FINAL,
    ERROR
}

class FakeVoiceRecognizerStateMachine {
    var state: VoiceState = VoiceState.IDLE
        private set
    var partialText: String = ""
        private set
    var finalResult: String = ""
        private set
    var errorMessage: String = ""
        private set

    fun startListening() {
        state = VoiceState.LISTENING
        partialText = ""
        errorMessage = ""
    }

    fun onPartial(text: String) {
        state = VoiceState.PARTIAL
        partialText = text
    }

    fun onFinal(text: String) {
        state = VoiceState.FINAL
        finalResult = text
    }

    fun onError(msg: String) {
        state = VoiceState.ERROR
        errorMessage = msg
    }

    fun reset() {
        state = VoiceState.IDLE
        partialText = ""
        finalResult = ""
        errorMessage = ""
    }
}

class VoiceStateMachineTest {

    @Test
    fun testVoiceStateTransitionLifecycle() {
        val recognizer = FakeVoiceRecognizerStateMachine()
        assertEquals(VoiceState.IDLE, recognizer.state)

        recognizer.startListening()
        assertEquals(VoiceState.LISTENING, recognizer.state)

        recognizer.onPartial("سلام")
        assertEquals(VoiceState.PARTIAL, recognizer.state)
        assertEquals("سلام", recognizer.partialText)

        recognizer.onFinal("سلام چطوری")
        assertEquals(VoiceState.FINAL, recognizer.state)
        assertEquals("سلام چطوری", recognizer.finalResult)
    }

    @Test
    fun testVoiceStateErrorTransition() {
        val recognizer = FakeVoiceRecognizerStateMachine()
        recognizer.startListening()
        recognizer.onError("صدایی شنیده نشد؛ دوباره امتحان کنید")

        assertEquals(VoiceState.ERROR, recognizer.state)
        assertEquals("صدایی شنیده نشد؛ دوباره امتحان کنید", recognizer.errorMessage)
    }
}
