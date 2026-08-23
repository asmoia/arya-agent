package io.agents.arya.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceStateMachineTest {

    @Test
    fun idleToListeningToPartialToFinal() {
        val c = VoiceInputController()
        assertEquals(VoicePhase.IDLE, c.state.phase)
        c.start()
        assertEquals(VoicePhase.LISTENING, c.state.phase)
        c.onPartial("hello")
        assertEquals(VoicePhase.PARTIAL, c.state.phase)
        assertEquals("hello", c.state.partialText)
        c.onFinal("hello there")
        assertEquals(VoicePhase.FINAL, c.state.phase)
        assertEquals("hello there", c.state.finalText)
    }

    @Test
    fun listeningToError() {
        val c = VoiceInputController()
        c.start()
        c.onError("No speech heard. Try again.")
        assertEquals(VoicePhase.ERROR, c.state.phase)
        assertEquals("No speech heard. Try again.", c.state.errorMessage)
    }

    @Test
    fun stopReturnsIdle() {
        val c = VoiceInputController()
        c.start()
        c.onPartial("x")
        c.stop()
        assertEquals(VoicePhase.IDLE, c.state.phase)
    }
}
