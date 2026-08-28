package io.agents.arya.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPathsTest {
    @Test
    fun sameFileNameAcrossFuseAndInternal() {
        val fuse = "/storage/emulated/0/Android/data/io.agents.arya/files/models/Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        val fast = "/data/user/0/io.agents.arya/files/models/fast/Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        assertTrue(ModelPaths.sameModel(fast, fuse))
        assertTrue(ModelPaths.sameModel(fuse, fast))
        assertTrue(ModelPaths.statsLooksLike("""{"model_path":"$fast","loaded":true}""", fuse))
    }

    @Test
    fun differentFilesAreNotTheSame() {
        val a = "/models/Qwen_Qwen3-0.6B-Q4_K_M.gguf"
        val b = "/models/Qwen_Qwen3-1.7B-Q4_K_M.gguf"
        assertFalse(ModelPaths.sameModel(a, b))
        assertFalse(ModelPaths.sameModel(null, a))
        assertFalse(ModelPaths.sameModel(a, ""))
    }

    @Test
    fun fakeMmapReadyIsNotResident() {
        val fake = """{"loaded":true,"model_path":"/x/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            "model_info":{"model_size_mb":1223.1,"rss_mb":70.0,"uses_mmap":true}}"""
        assertFalse(ModelPaths.isResident(fake))
    }

    @Test
    fun realRamLoadIsResident() {
        val real = """{"loaded":true,"model_path":"/x/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
            "model_info":{"model_size_mb":1223.1,"rss_mb":1180.0,"uses_mmap":false}}"""
        assertTrue(ModelPaths.isResident(real))
    }

    @Test
    fun missingRssFallsBackToFileSize() {
        val old = """{"loaded":true,"model_info":{"model_size_mb":1223.1}}"""
        assertTrue(ModelPaths.isResident(old))
        assertFalse(ModelPaths.isResident("""{"loaded":false}"""))
        assertFalse(ModelPaths.isResident("""{"loaded":true,"model_info":{"model_size_mb":12.0}}"""))
    }
}
