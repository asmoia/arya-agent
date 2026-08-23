package io.agents.arya.agent.llm

data class CatalogModel(
    val id: String,
    val nameFa: String,
    val descriptionFa: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeMb: Int,
    val minRamGb: Int,
    val isDefault: Boolean = false
)

object ModelCatalog {
    val MODELS = listOf(
        CatalogModel(
            id = "qwen3-0.6b",
            nameFa = "Qwen3 0.6B (خیلی سبک)",
            descriptionFa = "مناسب دستگاه‌های با رم کم (۳ گیگابایت). سریع اما محدود در کارهای پیچیده.",
            fileName = "Qwen3-0.6B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            sizeMb = 500,
            minRamGb = 3
        ),
        CatalogModel(
            id = "qwen3-1.7b",
            nameFa = "Qwen3 1.7B (پیش‌فرض پیشنهادی)",
            descriptionFa = "تعادل عالی بین سرعت و هوشمندی. مناسب رم ۴ گیگابایت و بالاتر.",
            fileName = "Qwen3-1.7B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            sizeMb = 1200,
            minRamGb = 4,
            isDefault = true
        ),
        CatalogModel(
            id = "qwen3-4b",
            nameFa = "Qwen3 4B (پرقدرت محلی)",
            descriptionFa = "بالاترین دقت و استدلال محلی. نیاز به حداقل ۸ گیگابایت رم.",
            fileName = "Qwen3-4B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeMb = 2500,
            minRamGb = 8
        )
    )

    fun isModelSupported(model: CatalogModel, totalRamGb: Int): Boolean {
        return totalRamGb >= model.minRamGb
    }
}
