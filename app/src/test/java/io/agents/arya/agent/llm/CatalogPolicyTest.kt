package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPolicyTest {
    private val models = ModelCatalog.MODELS

    @Test
    fun threeGigAllowsOnly06b() {
        val allowed = models.filter { CatalogPolicy.isAllowed(it, "3GB") }
        assertEquals(listOf("qwen3-0.6b"), allowed.map { it.id })
        assertFalse(CatalogPolicy.canRunLocalTier3("3GB"))
    }

    @Test
    fun fourGigAllowsDefault17b() {
        assertTrue(CatalogPolicy.isAllowed(models.first { it.id == "qwen3-1.7b" }, "4GB"))
        assertFalse(CatalogPolicy.isAllowed(models.first { it.id == "qwen3-4b" }, "4GB"))
        assertEquals("qwen3-1.7b", CatalogPolicy.recommended(models, "4GB")?.id)
    }

    @Test
    fun eightGigUnlocksTier3() {
        assertTrue(CatalogPolicy.canRunLocalTier3("8GB+"))
        assertTrue(CatalogPolicy.isAllowed(models.first { it.id == "qwen3-4b" }, "8GB+"))
    }
}
