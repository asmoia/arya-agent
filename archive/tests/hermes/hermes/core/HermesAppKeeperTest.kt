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

    @Test
    fun sequentialPureChatEndsDoNotPoisonNextPhoneTaskRelease() {
        // Two pure-chat outer finalizers have no lease to release. The next
        // phone task still begins at depth one and ends cleanly at zero.
        assertNull(HermesAppKeeper.nextDepthAfterEnd(0))
        assertNull(HermesAppKeeper.nextDepthAfterEnd(0))
        assertEquals(0, HermesAppKeeper.nextDepthAfterEnd(1))
    }
}
