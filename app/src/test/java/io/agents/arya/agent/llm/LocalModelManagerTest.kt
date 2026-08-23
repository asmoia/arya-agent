package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelManagerTest {

    @Test
    fun `catalog is three verified Qwen3 Q4_K_M GGUFs and no BitNet`() {
        val models = LocalModelManager.AVAILABLE_MODELS
        assertEquals(3, models.size)
        assertTrue(models.all { it.displayName.contains("Qwen3") })
        assertTrue(models.all { it.fileName.contains("Qwen3") && it.fileName.endsWith("Q4_K_M.gguf") })
        assertTrue(models.none { it.id.contains("bitnet", ignoreCase = true) || it.url.contains("bitnet", ignoreCase = true) })
        assertEquals(484_220_320L, models.first { it.id == "qwen3-0.6b" }.sizeBytes)
        assertEquals(1_282_439_584L, models.first { it.id == "qwen3-1.7b" }.sizeBytes)
        assertEquals(2_497_280_736L, models.first { it.id == "qwen3-4b" }.sizeBytes)
        assertEquals(3, models.first { it.id == "qwen3-0.6b" }.minRamGb)
        assertEquals(4, models.first { it.id == "qwen3-1.7b" }.minRamGb)
        assertEquals(8, models.first { it.id == "qwen3-4b" }.minRamGb)
    }


    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `model directory uses external app storage when it can be created`() {
        val externalRoot = temporaryFolder.newFolder("external")
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(externalRoot, internalRoot)

        assertEquals(externalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external path is unusable`() {
        val externalRoot = temporaryFolder.newFolder("external")
        externalRoot.resolve("models").writeText("blocking file")
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(externalRoot, internalRoot)

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external path is not writable`() {
        val externalRoot = temporaryFolder.newFolder("external")
        val internalRoot = temporaryFolder.newFolder("internal")
        val externalModelDir = externalRoot.resolve("models")

        val dir = LocalModelManager.resolveUsableModelDir(
            externalRoot = externalRoot,
            internalRoot = internalRoot,
            canWriteDirectory = { candidate -> candidate != externalModelDir },
        )

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `model directory falls back to internal storage when external root is missing`() {
        val internalRoot = temporaryFolder.newFolder("internal")

        val dir = LocalModelManager.resolveUsableModelDir(null, internalRoot)

        assertEquals(internalRoot.resolve("models"), dir)
        assertTrue(dir.isDirectory)
    }
}
