package io.agents.arya.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPolicyTest {
    @Test
    fun routineVsCloudVsVisual() {
        assertEquals(Tier.ROUTINE, RoutingPolicy.classify("open telegram"))
        assertEquals(Tier.CLOUD, RoutingPolicy.classify("summarize this article"))
        assertEquals(Tier.VISUAL, RoutingPolicy.classify("find the hidden toggle"))
    }

    @Test
    fun escalateOnlyWithOptIn() {
        assertFalse(RoutingPolicy.shouldEscalate(false, 5, 5))
        assertTrue(RoutingPolicy.shouldEscalate(true, 2, 0))
        assertTrue(RoutingPolicy.shouldEscalate(true, 0, 3))
    }
}
