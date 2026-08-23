package io.agents.arya.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadHubTest {
    @Test
    fun percentFromBytes() {
        val job = ModelDownloadHub.Job(
            modelId = "qwen3-1.7b",
            displayName = "Qwen3 1.7B",
            fileName = "m.gguf",
            phase = ModelDownloadHub.Phase.RUNNING,
            bytesDownloaded = 640L,
            totalBytes = 1280L,
        )
        assertEquals(0.5f, job.progress, 0.001f)
        assertEquals(50, job.percent)
        assertFalse(job.phase == ModelDownloadHub.Phase.DONE)
        assertTrue(job.phase == ModelDownloadHub.Phase.RUNNING)
    }
}
