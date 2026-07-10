package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRuntimePolicyTest {
    private val e4b = "/models/gemma-4-E4B-it.litertlm"

    @Test
    fun e4bRejectsTheLowFreeRamStateSeenBeforeConversationCreation() {
        val reason = LocalRuntimePolicy.admissionReason(
            modelPath = e4b,
            owner = LocalInferenceOwner.CHAT,
            availableMb = 5_105L,
        )

        assertNotNull(reason)
        check(reason!!.contains("needs 6.5GB free RAM"))
    }

    @Test
    fun e4bUsesReducedInteractiveConversationBudget() {
        assertEquals(1_536, LocalRuntimePolicy.maxNumTokens(e4b, LocalInferenceOwner.CHAT))
        assertEquals(1_536, LocalRuntimePolicy.maxNumTokens(e4b, LocalInferenceOwner.TASK))
        assertEquals(768, LocalRuntimePolicy.maxNumTokens(e4b, LocalInferenceOwner.BACKGROUND))
    }

    @Test
    fun nonE4bDoesNotReceiveTheE4bMemoryGate() {
        assertNull(
            LocalRuntimePolicy.admissionReason(
                modelPath = "/models/other-local-model.litertlm",
                owner = LocalInferenceOwner.TASK,
                availableMb = 1_000L,
            )
        )
    }
}
