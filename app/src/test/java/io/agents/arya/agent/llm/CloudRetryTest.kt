package io.agents.arya.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRetryTest {
    @Test
    fun onlyOnceBeforeDelta() {
        assertTrue(CloudRetry.shouldRetry(false, 0, true))
        assertFalse(CloudRetry.shouldRetry(true, 0, true))
        assertFalse(CloudRetry.shouldRetry(false, 1, true))
        assertFalse(CloudRetry.shouldRetry(false, 0, false))
    }
}
