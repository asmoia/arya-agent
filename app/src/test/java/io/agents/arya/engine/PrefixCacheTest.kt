package io.agents.arya.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class PrefixCacheTest {
    @Test
    fun lruKeepsThreeNewest() {
        val dir = createTempDirectory("pc").toFile()
        val cache = PrefixCache(dir)
        repeat(5) { i ->
            val key = "k$i"
            cache.stateFile(key).writeText("state-$i")
            cache.writeSidecar(
                PrefixCache.Sidecar(i, "p", "m", System.currentTimeMillis() + i, 10, key),
            )
            Thread.sleep(5)
        }
        cache.evictLru(3)
        val left = dir.listFiles { f -> f.extension == "state" }!!.size
        assertEquals(3, left)
        dir.deleteRecursively()
    }

    @Test
    fun keyIsStable() {
        val a = PrefixCache.key("abcd1234", "sys-v3", "hello")
        val b = PrefixCache.key("abcd1234", "sys-v3", "hello")
        val c = PrefixCache.key("abcd1234", "sys-v3", "hello!")
        assertEquals(a, b)
        assertFalse(a == c)
        assertTrue(a.length == 64)
    }
}
