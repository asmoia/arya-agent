package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class LegacyModelCleanupTest {
    @Test
    fun findsLitertlmAndTaskOnly() {
        val dir = createTempDirectory("models").toFile()
        File(dir, "keep.gguf").writeBytes(ByteArray(10))
        File(dir, "old.litertlm").writeBytes(ByteArray(2048))
        File(dir, "gemma.task").writeBytes(ByteArray(1024))
        val found = LegacyModelCleanup.findLegacyFiles(dir)
        assertEquals(2, found.size)
        assertTrue(LegacyModelCleanup.totalBytes(found) >= 3072)
        dir.deleteRecursively()
    }
}
