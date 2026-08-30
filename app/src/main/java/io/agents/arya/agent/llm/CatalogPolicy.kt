package io.agents.arya.agent.llm

import io.agents.arya.engine.budget.MemoryBudget

/**
 * S7: Tier roles are enforced by RAM class, not aspirational.
 */
object CatalogPolicy {
    const val TIER3_MIN_RAM_GB = 8

    fun isAllowed(model: CatalogModel, ramClass: String): Boolean {
        return MemoryBudget.ramClassGb(ramClass) >= model.minRamGb
    }

    fun canRunLocalTier3(ramClass: String): Boolean {
        return MemoryBudget.ramClassGb(ramClass) >= TIER3_MIN_RAM_GB
    }

    fun refuseLocalTier3Message(): String =
        "This task needs Qwen3 0.6B / FunctionGemma on this phone, or a cloud model. The 1.7B+ path is disabled on EMUI."

    fun recommended(models: List<CatalogModel>, ramClass: String): CatalogModel? {
        val allowed = models.filter { isAllowed(it, ramClass) }
        return allowed.firstOrNull { it.isDefault } ?: allowed.maxByOrNull { it.minRamGb }
    }
}
