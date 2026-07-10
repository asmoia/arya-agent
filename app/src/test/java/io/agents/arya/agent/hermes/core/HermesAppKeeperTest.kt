package io.agents.arya.agent.hermes.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HermesAppKeeperTest {
    @Test
    fun endingWithoutAcquireDoesNotUnderflowDepth() {
        assertNull(HermesAppKeeper.nextDepthAfterEnd(0))
        assertNull(HermesAppKeeper.nextDepthAfterEnd(-1))
    }

    @Test
    fun endingAcquiredLeaseDecrementsExactlyOnce() {
        assertEquals(0, HermesAppKeeper.nextDepthAfterEnd(1))
        assertEquals(1, HermesAppKeeper.nextDepthAfterEnd(2))
    }
}
