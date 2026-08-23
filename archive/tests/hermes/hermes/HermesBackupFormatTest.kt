package io.agents.arya.agent.hermes

import io.agents.arya.agent.hermes.backup.HermesBackupManager
import io.agents.arya.agent.hermes.core.HermesPromptBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesBackupFormatTest {
    @Test
    fun backupFormatVersion_isPositive() {
        assertTrue(HermesBackupManager.FORMAT_VERSION >= 1)
        assertEquals(1, HermesBackupManager.FORMAT_VERSION)
    }

    @Test
    fun identityStillMentionsHermes() {
        assertTrue(HermesPromptBuilder.ARYA_HERMES_IDENTITY.contains("Hermes"))
    }
}
