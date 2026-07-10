package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class LocalInferenceCoordinatorTest {
    @Test
    fun oneConversationLeaseBlocksEverySecondOwnerUntilReleased() {
        LocalInferenceCoordinator.reset()
        try {
            LocalInferenceCoordinator.acquire(LocalInferenceOwner.CHAT, "/models/e4b.litertlm")
            LocalInferenceCoordinator.markReady(LocalInferenceOwner.CHAT, "GPU")

            try {
                LocalInferenceCoordinator.acquire(LocalInferenceOwner.TASK, "/models/e4b.litertlm")
                fail("Task must not acquire while Chat owns the native conversation")
            } catch (_: LocalInferenceBusyException) {
                // Expected: LiteRT permits one native Conversation per Engine.
            }

            LocalInferenceCoordinator.release(LocalInferenceOwner.CHAT)
            LocalInferenceCoordinator.acquire(LocalInferenceOwner.TASK, "/models/e4b.litertlm")
            assertEquals(LocalInferenceOwner.TASK, LocalInferenceCoordinator.snapshot().owner)
        } finally {
            LocalInferenceCoordinator.reset()
        }
    }

    @Test
    fun sameOwnerCannotAccidentallyCreateTwoNativeConversations() {
        LocalInferenceCoordinator.reset()
        try {
            LocalInferenceCoordinator.acquire(LocalInferenceOwner.CHAT, "/models/e4b.litertlm")
            try {
                LocalInferenceCoordinator.acquire(LocalInferenceOwner.CHAT, "/models/e4b.litertlm")
                fail("Duplicate Chat acquire must be rejected")
            } catch (_: LocalInferenceBusyException) {
                // Expected.
            }
        } finally {
            LocalInferenceCoordinator.reset()
        }
    }
}
