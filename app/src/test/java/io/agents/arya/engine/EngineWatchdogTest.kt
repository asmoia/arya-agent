package io.agents.arya.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineWatchdogTest {
    @Test
    fun overdueAndStall() {
        assertTrue(EngineWatchdog.isOverdue(100, 50))
        assertFalse(EngineWatchdog.isOverdue(40, 50))
        assertTrue(EngineWatchdog.isStalled(10_000, 1_000, 4_000, true))
        assertFalse(EngineWatchdog.isStalled(10_000, 8_000, 4_000, true))
        assertFalse(EngineWatchdog.isStalled(10_000, 1_000, 4_000, false))
    }
}
