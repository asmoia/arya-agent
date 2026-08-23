package io.agents.arya.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchTest {
    private val rows = listOf(
        SettingsRow("llm", SettingsGroup.MODEL, "LLM Config", listOf("model", "openai")),
        SettingsRow("acc", SettingsGroup.PERMISSIONS, "Accessibility", listOf("a11y")),
        SettingsRow("mic", SettingsGroup.VOICE, "Voice input", listOf("speech", "tts")),
        SettingsRow("dbg", SettingsGroup.ADVANCED, "Debug report", listOf("log")),
    )

    @Test
    fun filtersByKeyword() {
        assertEquals(listOf("llm"), SettingsSearch.filter(rows, "openai").map { it.id })
        assertEquals(listOf("mic"), SettingsSearch.filter(rows, "tts").map { it.id })
    }

    @Test
    fun groupsAllFour() {
        val g = SettingsSearch.grouped(rows)
        assertEquals(1, g[SettingsGroup.MODEL]?.size)
        assertEquals(1, g[SettingsGroup.VOICE]?.size)
    }
}
