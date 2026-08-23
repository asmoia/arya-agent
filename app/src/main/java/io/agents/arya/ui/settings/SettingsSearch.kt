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
}
