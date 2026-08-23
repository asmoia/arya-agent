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
        "This task needs a cloud model or a phone with at least 8 GB of RAM."

    fun recommended(models: List<CatalogModel>, ramClass: String): CatalogModel? {
        val allowed = models.filter { isAllowed(it, ramClass) }
        return allowed.firstOrNull { it.isDefault } ?: allowed.maxByOrNull { it.minRamGb }
    }
}
