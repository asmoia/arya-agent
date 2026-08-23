package io.agents.arya.engine.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GgufHeaderParserTest {
    @Test
    fun rejectsNonGguf() {
        val f = File.createTempFile("not", ".bin")
        f.writeBytes("NOPE".toByteArray())
        assertNull(GgufHeaderParser.parse(f))
        f.delete()
    }

    @Test
    fun parsesMinimalHeader() {
        val buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("GGUF".toByteArray())
        buf.putInt(3)
        buf.putLong(0) // n tensors
        buf.putLong(0) // n kv
        val f = File.createTempFile("mini", ".gguf")
        f.writeBytes(buf.array().copyOf(buf.position()))
        val h = GgufHeaderParser.parse(f)
        assertEquals(3, h?.version)
        f.delete()
    }
}
