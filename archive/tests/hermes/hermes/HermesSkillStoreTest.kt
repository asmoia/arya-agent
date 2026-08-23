package io.agents.arya.agent.hermes

import io.agents.arya.agent.hermes.core.HermesPromptBuilder
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests that do not need Android runtime / ClawApplication.
 */
class HermesSkillStoreTest {

    @Test
    fun aryaIdentity_isPersianFirstAndMentionsHermes() {
        val prompt = HermesPromptBuilder.ARYA_HERMES_IDENTITY
        assertTrue(prompt.contains("آریا"))
        assertTrue(prompt.contains("Hermes"))
        assertTrue(prompt.contains("Persian") || prompt.contains("فارسی") || prompt.contains("Persian"))
        assertTrue(prompt.contains("hermes_memory") || prompt.contains("memory"))
    }

    @Test
    fun aryaIdentity_hasExecutionProtocol() {
        val prompt = HermesPromptBuilder.ARYA_HERMES_IDENTITY
        assertTrue(prompt.contains("Observe") || prompt.contains("get_screen_info"))
        assertTrue(prompt.contains("finish"))
    }
}
