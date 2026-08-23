package io.agents.arya.agent.llm

data class CatalogModel(
    val id: String,
    val nameEn: String,
    val descriptionEn: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeMb: Int,
    val minRamGb: Int,
    val role: Role = Role.TIER2_LITE,
    val isDefault: Boolean = false,
) {
    enum class Role { TIER2_LITE, TIER2_PLUS_SHORT_TIER3, FULL_TIER3, CUSTOM }

    /** Back-compat aliases used by older UI. */
    val nameFa: String get() = nameEn
    val descriptionFa: String get() = descriptionEn
}

object ModelCatalog {
    val MODELS = listOf(
        CatalogModel(
            id = "qwen3-0.6b",
            nameEn = "Qwen3 0.6B (very light)",
            descriptionEn = "For 3 GB phones. Fast, limited on complex tasks. Tier2-lite only.",
            fileName = "Qwen_Qwen3-0.6B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/main/Qwen_Qwen3-0.6B-Q4_K_M.gguf",
            sizeMb = 500,
            minRamGb = 3,
            role = CatalogModel.Role.TIER2_LITE,
        ),
        CatalogModel(
            id = "qwen3-1.7b",
            nameEn = "Qwen3 1.7B (default)",
            descriptionEn = "Best speed/quality balance. Needs 4 GB+. Tier2-lite + short Tier3.",
            fileName = "Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/main/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            sizeMb = 1280,
            minRamGb = 4,
            role = CatalogModel.Role.TIER2_PLUS_SHORT_TIER3,
            isDefault = true,
        ),
        CatalogModel(
            id = "qwen3-4b",
            nameEn = "Qwen3 4B Instruct 2507",
            descriptionEn = "Full local Tier3. Needs 8 GB RAM.",
            fileName = "Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF/resolve/main/Qwen_Qwen3-4B-Instruct-2507-Q4_K_M.gguf",
            sizeMb = 2500,
            minRamGb = 8,
            role = CatalogModel.Role.FULL_TIER3,
        ),
    )

    fun isModelSupported(model: CatalogModel, totalRamGb: Int): Boolean =
        totalRamGb >= model.minRamGb

    fun byId(id: String): CatalogModel? = MODELS.firstOrNull { it.id == id }
}
