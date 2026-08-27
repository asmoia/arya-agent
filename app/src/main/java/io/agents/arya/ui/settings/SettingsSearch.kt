package io.agents.arya.ui.settings

import android.content.Context
import io.agents.arya.R

/**
 * Group + search helper for the settings redesign.
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
        SettingsGroup.entries.associateWith { group -> rows.filter { it.group == group } }

    fun visibleGroups(rows: List<SettingsRow>, query: String): Set<SettingsGroup> =
        filter(rows, query).map { it.group }.toSet()
}

object SettingsCatalog {
    /**
     * The no-argument form keeps the pure JVM search tests deterministic in English.
     * The activity passes its context so visible titles follow the active locale.
     */
    fun defaultRows(context: Context? = null): List<SettingsRow> {
        fun title(resId: Int, fallback: String): String = context?.getString(resId) ?: fallback

        return listOf(
            SettingsRow("llm", SettingsGroup.MODEL, title(R.string.menu_llm_config, "LLM Config"), listOf("model", "openai", "anthropic", "gguf", "qwen")),
            SettingsRow("a11y", SettingsGroup.PERMISSIONS, title(R.string.home_card_accessibility_title, "Accessibility"), listOf("a11y", "service")),
            SettingsRow("notif", SettingsGroup.PERMISSIONS, title(R.string.home_card_notification_title, "Notifications"), listOf("push")),
            SettingsRow("overlay", SettingsGroup.PERMISSIONS, title(R.string.home_card_system_window_title, "Overlay"), listOf("float", "bubble")),
            SettingsRow("battery", SettingsGroup.PERMISSIONS, title(R.string.home_card_battery_title, "Battery"), listOf("unrestricted")),
            SettingsRow("storage", SettingsGroup.PERMISSIONS, title(R.string.home_card_storage_title, "Storage"), listOf("files", "models")),
            SettingsRow("voice_auto", SettingsGroup.VOICE, title(R.string.settings_voice_auto_send, "Auto-send voice"), listOf("speech", "mic", "stt")),
            SettingsRow("voice_tts", SettingsGroup.VOICE, title(R.string.settings_voice_tts, "Speak short answers"), listOf("tts", "speech")),
            SettingsRow("safety", SettingsGroup.VOICE, title(R.string.settings_sensitive_confirm, "Sensitive-action confirmation"), listOf("safety", "confirm", "security")),
            SettingsRow("theme", SettingsGroup.ADVANCED, title(R.string.settings_theme, "Theme"), listOf("dark", "light")),
            SettingsRow("debug", SettingsGroup.ADVANCED, title(R.string.settings_report_bug, "Debug report"), listOf("log", "bug")),
        )
    }
}
