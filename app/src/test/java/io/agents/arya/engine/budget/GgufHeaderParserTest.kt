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

    @Test
    fun rejectsTruncatedKnownTensorPayload() {
        val full = minimalF32TensorFile(payloadBytes = 16)
        val truncated = File.createTempFile("truncated", ".gguf")
        truncated.writeBytes(full.readBytes().copyOf(72))
        assertNull(GgufHeaderParser.parse(truncated))
        full.delete()
        truncated.delete()
    }

    @Test
    fun acceptsKnownTensorWhenPayloadFitsAlignedDataSection() {
        val f = minimalF32TensorFile(payloadBytes = 16)
        val h = GgufHeaderParser.parse(f)
        assertEquals(1L, h?.tensorCount)
        assertEquals(64L, h?.dataOffset)
        f.delete()
    }

    private fun minimalF32TensorFile(payloadBytes: Int): File {
        val buf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("GGUF".toByteArray())
        buf.putInt(3)
        buf.putLong(1) // n tensors
        buf.putLong(0) // n kv
        buf.putLong(0) // tensor name length
        buf.putInt(1)  // n dimensions
        buf.putLong(4) // shape
        buf.putInt(0)  // F32
        buf.putLong(0) // tensor data offset
        while (buf.position() < 64) buf.put(0)
        repeat(payloadBytes) { buf.put(0) }
        val f = File.createTempFile("tensor", ".gguf")
        f.writeBytes(buf.array().copyOf(buf.position()))
        return f
    }
}
