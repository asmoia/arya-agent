package io.agents.arya.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenStructureMatchersTest {
    @Test
    fun prefersRoleAndIdOverBareText() {
        val nodes = listOf(
            ScreenStructureMatchers.NodeHint(text = "Send later"),
            ScreenStructureMatchers.NodeHint(id = "composer_send", role = "button", desc = "Send"),
        )
        val best = ScreenStructureMatchers.best(nodes, "button", "send")
        assertEquals("composer_send", best?.id)
        assertTrue(ScreenStructureMatchers.isSend(nodes[1]))
    }

    @Test
    fun searchAndBackHeuristics() {
        assertTrue(ScreenStructureMatchers.isSearch(ScreenStructureMatchers.NodeHint(role = "edittext", text = "Search")))
        assertTrue(ScreenStructureMatchers.isBack(ScreenStructureMatchers.NodeHint(role = "button", desc = "Navigate up")))
    }
}
