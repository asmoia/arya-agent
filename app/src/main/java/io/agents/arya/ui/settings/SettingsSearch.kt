package io.agents.arya.ui.settings

/**
 * Overflow #4 — group + search helper for the settings redesign.
 * Groups: model / permissions / voice / advanced.
 */
enum class SettingsGroup { MODEL, PERMISSIONS, VOICE, ADVANCED }

data class SettingsRow(
    val id: String,
    val group: SettingsGroup,
    val title: String,
    val keywords: List<String> = emptyList(),
)

object SettingsSearch {
    fun filter(rows: List<SettingsRow>, query: String): List<SettingsRow> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return rows
        return rows.filter { row ->
            row.title.lowercase().contains(q) ||
                row.keywords.any { it.lowercase().contains(q) } ||
                row.group.name.lowercase().contains(q)
        }
    }

    fun grouped(rows: List<SettingsRow>): Map<SettingsGroup, List<SettingsRow>> =
        SettingsGroup.entries.associateWith { g -> rows.filter { it.group == g } }

    fun visibleGroups(rows: List<SettingsRow>, query: String): Set<SettingsGroup> =
        filter(rows, query).map { it.group }.toSet()
}

object SettingsCatalog {
    fun defaultRows(): List<SettingsRow> = listOf(
        SettingsRow("llm", SettingsGroup.MODEL, "LLM Config", listOf("model", "openai", "anthropic", "gguf", "qwen")),
        SettingsRow("budget", SettingsGroup.MODEL, "Task Budget", listOf("tokens", "cost")),
        SettingsRow("prompt", SettingsGroup.MODEL, "Global prompt", listOf("system", "instructions")),
        SettingsRow("custom_url", SettingsGroup.MODEL, "Custom model URL", listOf("download", "gguf")),
        SettingsRow("a11y", SettingsGroup.PERMISSIONS, "Accessibility", listOf("a11y", "service")),
        SettingsRow("notif", SettingsGroup.PERMISSIONS, "Notifications", listOf("push")),
        SettingsRow("overlay", SettingsGroup.PERMISSIONS, "Overlay", listOf("float", "bubble")),
        SettingsRow("battery", SettingsGroup.PERMISSIONS, "Battery", listOf("unrestricted")),
        SettingsRow("storage", SettingsGroup.PERMISSIONS, "Storage", listOf("files", "models")),
        SettingsRow("voice_auto", SettingsGroup.VOICE, "Auto-send voice", listOf("speech", "mic", "stt")),
        SettingsRow("voice_tts", SettingsGroup.VOICE, "Speak short answers", listOf("tts", "speech")),
        SettingsRow("offline_stt", SettingsGroup.VOICE, "Offline speech", listOf("whisper", "stt")),
        SettingsRow("theme", SettingsGroup.ADVANCED, "Theme", listOf("dark", "light")),
        SettingsRow("debug", SettingsGroup.ADVANCED, "Debug report", listOf("log", "bug")),
        SettingsRow("github", SettingsGroup.ADVANCED, "GitHub", listOf("source", "release")),
        SettingsRow("telegram", SettingsGroup.ADVANCED, "Telegram bot", listOf("channel", "remote")),
    )
}
