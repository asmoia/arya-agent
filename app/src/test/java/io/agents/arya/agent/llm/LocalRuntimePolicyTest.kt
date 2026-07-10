package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalRuntimePolicyTest {
    private val e4b = "/models/gemma-4-E4B-it.litertlm"

    @Test
    fun e4bUsesCompactProfileInsteadOfRejectingFiveGbFreeRam() {
        val budget = LocalRuntimePolicy.budgetForAvailable(
            modelPath = e4b,
            owner = LocalInferenceOwner.CHAT,
            availableMb = 5_105L,
        )

        assertEquals(LocalRuntimePolicy.E4bMemoryMode.COMPACT, budget.mode)
        assertEquals(768, budget.maxNumTokens)
        assertNull(budget.admissionReason)
    }

    @Test
    fun e4bUsesBalancedProfileAtSixGbFreeRam() {
        val budget = LocalRuntimePolicy.budgetForAvailable(
            modelPath = e4b,
            owner = LocalInferenceOwner.TASK,
            availableMb = 6_000L,
        )

        assertEquals(LocalRuntimePolicy.E4bMemoryMode.BALANCED, budget.mode)
        assertEquals(1_024, budget.maxNumTokens)
        assertNull(budget.admissionReason)
    }

    @Test
    fun e4bBlocksOnlyBelowHardSafetyFloor() {
        val budget = LocalRuntimePolicy.budgetForAvailable(
            modelPath = e4b,
            owner = LocalInferenceOwner.CHAT,
            availableMb = 4_700L,
        )

        assertEquals(LocalRuntimePolicy.E4bMemoryMode.BLOCKED, budget.mode)
        assertEquals(0, budget.maxNumTokens)
        assertNotNull(budget.admissionReason)
        check(budget.admissionReason!!.contains("4.8GB free RAM"))
    }

    @Test
    fun e4bUsesFullProfileWhenMemoryIsHealthy() {
        assertEquals(
            LocalRuntimePolicy.E4bMemoryMode.FULL,
            LocalRuntimePolicy.budgetForAvailable(e4b, LocalInferenceOwner.TASK, 7_000L).mode,
        )
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
