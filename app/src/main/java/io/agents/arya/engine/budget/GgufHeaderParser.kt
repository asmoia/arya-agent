package io.agents.arya.engine.budget

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Bounded GGUF reader used as a preflight gate before entering llama.cpp.
 *
 * It intentionally does not read tensor payloads. It does, however, consume the
 * complete metadata and tensor-info section and verifies that known tensor types
 * fit inside the file's aligned data section. This catches truncated/corrupt
 * copies that otherwise can turn into SIGBUS/SIGKILL while a native mmap loader
 * touches the weights.
 */
object GgufHeaderParser {
    private const val DEFAULT_ALIGNMENT = 32L
    private const val MAX_STRING_BYTES = 1_000_000L
    private const val MAX_ARRAY_ELEMENTS = 1_000_000L
    private const val MAX_KV_ENTRIES = 1_000_000L
    private const val MAX_TENSORS = 10_000_000L
    private const val MAX_DIMS = 4

    data class Header(
        val version: Int,
        val kv: Map<String, Any?>,
        val tensorCount: Long = 0L,
        val dataOffset: Long = 0L,
    ) {
        fun asModelMeta(): MemoryBudget.ModelMeta? {
            val layers = intOf("llama.block_count", "qwen2.block_count", "qwen3.block_count", "general.block_count")
                ?: return null
            val heads = intOf("llama.attention.head_count", "qwen2.attention.head_count", "qwen3.attention.head_count")
            val kvHeads = intOf(
                "llama.attention.head_count_kv",
                "qwen2.attention.head_count_kv",
                "qwen3.attention.head_count_kv",
            ) ?: heads
            val embd = intOf("llama.embedding_length", "qwen2.embedding_length", "qwen3.embedding_length")
            val headDim = if (embd != null && heads != null && heads > 0) embd / heads else 0
            val params = longOf("general.parameter_count") ?: 0L
            return MemoryBudget.ModelMeta(
                nLayers = layers,
                nKvHeads = kvHeads ?: 0,
                headDim = headDim,
                nParams = params,
            )
        }

        private fun intOf(vararg keys: String): Int? {
            for (k in keys) {
                val v = kv[k] ?: continue
                return when (v) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull()
                    else -> null
                }
            }
            return null
        }

        private fun longOf(vararg keys: String): Long? {
            for (k in keys) {
                val v = kv[k] ?: continue
                return when (v) {
                    is Number -> v.toLong()
                    is String -> v.toLongOrNull()
                    else -> null
                }
            }
            return null
        }
    }

    /**
     * Parses GGUF v2/v3 header and tensor descriptors without reading weight
     * bytes. `maxKv` limits the retained map, not the amount of metadata that is
     * consumed; consuming all entries keeps the tensor-info cursor correct.
     */
    fun parse(file: File, maxKv: Int = 256): Header? {
        if (!file.isFile || file.length() < 24L || maxKv < 0) return null
        return try {
            RandomAccessFile(file, "r").use { input ->
                val fileLength = input.length()
                val magic = ByteArray(4)
                input.readFully(magic)
                if (!magic.contentEquals(byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()))) {
                    return null
                }

                val version = readU32(input).toInt()
                if (version !in 2..3) return null
                val tensorCount = readU64(input)
                val kvCount = readU64(input)
                if (tensorCount < 0L || tensorCount > MAX_TENSORS || kvCount < 0L || kvCount > MAX_KV_ENTRIES) {
                    return null
                }

                val map = LinkedHashMap<String, Any?>(minOf(maxKv, kvCount.toInt()))
                var alignment = DEFAULT_ALIGNMENT
                repeat(kvCount.toInt()) { index ->
                    val key = readString(input)
                    val type = readU32(input).toInt()
                    val value = readValue(input, type)
                    if (index < maxKv) map[key] = value
                    if (key == "general.alignment") {
                        val candidate = (value as? Number)?.toLong() ?: 0L
                        if (candidate <= 0L || candidate > (1L shl 20)) throw IOException("invalid GGUF alignment")
                        alignment = candidate
                    }
                }

                var maxTensorOffset = 0L
                var maxTensorEnd = 0L
                repeat(tensorCount.toInt()) {
                    val name = readString(input)
                    val dimensions = readU32(input).toInt()
                    if (dimensions !in 1..MAX_DIMS) throw IOException("invalid tensor dimensions for $name")
                    val shape = LongArray(dimensions)
                    for (i in 0 until dimensions) {
                        shape[i] = readU64(input)
                        if (shape[i] <= 0L) throw IOException("invalid tensor shape for $name")
                    }
                    val type = readU32(input).toInt()
                    val offset = readU64(input)
                    if (offset < 0L) throw IOException("invalid tensor offset for $name")
                    maxTensorOffset = maxOf(maxTensorOffset, offset)
                    val knownBytes = tensorByteCount(type, shape)
                    if (knownBytes == INVALID_BYTES) return null
                    if (knownBytes != null) {
                        val end = checkedAdd(offset, knownBytes) ?: return null
                        maxTensorEnd = maxOf(maxTensorEnd, end)
                    }
                }

                if (tensorCount == 0L) {
                    return Header(version, map, tensorCount, input.filePointer)
                }

                val dataOffset = alignUp(input.filePointer, alignment) ?: return null
                if (dataOffset > fileLength) return null
                val dataBytes = fileLength - dataOffset
                if (maxTensorOffset > dataBytes || maxTensorEnd > dataBytes) return null

                Header(version, map, tensorCount, dataOffset)
            }
        } catch (_: Exception) {
            null
        }
    }

    private const val INVALID_BYTES = -1L

    /** Returns null for a future/unknown type, so this preflight remains forward-compatible. */
    private fun tensorByteCount(type: Int, shape: LongArray): Long? {
        val spec = when (type) {
            0 -> 1 to 4       // F32
            1 -> 1 to 2       // F16
            2 -> 32 to 18     // Q4_0
            3 -> 32 to 20     // Q4_1
            6 -> 32 to 22     // Q5_0
            7 -> 32 to 24     // Q5_1
            8 -> 32 to 34     // Q8_0
            9 -> 32 to 36     // Q8_1
            10 -> 256 to 84   // Q2_K
            11 -> 256 to 110  // Q3_K
            12 -> 256 to 144  // Q4_K, used by Q4_K_M
            13 -> 256 to 176  // Q5_K
            14 -> 256 to 210  // Q6_K
            15 -> 256 to 292  // Q8_K
            24 -> 1 to 1      // I8
            25 -> 1 to 2      // I16
            26 -> 1 to 4      // I32
            27 -> 1 to 8      // I64
            28 -> 1 to 8      // F64
            30 -> 1 to 2      // BF16
            else -> return null
        }
        val elements = shape.fold(1L) { acc, dimension -> checkedMultiply(acc, dimension) ?: return INVALID_BYTES }
        val (blockSize, bytesPerBlock) = spec
        if (elements % blockSize != 0L) return INVALID_BYTES
        return checkedMultiply(elements / blockSize, bytesPerBlock.toLong()) ?: INVALID_BYTES
    }

    private fun checkedMultiply(a: Long, b: Long): Long? {
        if (a < 0L || b < 0L) return null
        if (a != 0L && b > Long.MAX_VALUE / a) return null
        return a * b
    }

    private fun checkedAdd(a: Long, b: Long): Long? {
        if (a < 0L || b < 0L || a > Long.MAX_VALUE - b) return null
        return a + b
    }

    private fun alignUp(value: Long, alignment: Long): Long? {
        if (value < 0L || alignment <= 0L) return null
        val remainder = value % alignment
        if (remainder == 0L) return value
        val delta = alignment - remainder
        return if (value <= Long.MAX_VALUE - delta) value + delta else null
    }

    private fun readU32(input: RandomAccessFile): Long {
        var value = 0L
        for (shift in 0 until 32 step 8) {
            value = value or (input.readUnsignedByte().toLong() shl shift)
        }
        return value
    }

    private fun readU64(input: RandomAccessFile): Long {
        var value = 0L
        for (shift in 0 until 64 step 8) {
            value = value or (input.readUnsignedByte().toLong() shl shift)
        }
        return value
    }

    private fun readString(input: RandomAccessFile): String {
        val length = readU64(input)
        if (length < 0L || length > MAX_STRING_BYTES) throw IOException("invalid GGUF string length")
        val bytes = ByteArray(length.toInt())
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readValue(input: RandomAccessFile, type: Int): Any? {
        return when (type) {
            0 -> input.readUnsignedByte()
            1 -> input.readByte()
            2 -> readU16(input)
            3 -> readI16(input)
            4 -> readU32(input)
            5 -> readU32(input).toInt()
            6 -> Float.fromBits(readU32(input).toInt())
            7 -> input.readUnsignedByte() != 0
            8 -> readString(input)
            9 -> {
                val arrayType = readU32(input).toInt()
                val count = readU64(input)
                if (count < 0L || count > MAX_ARRAY_ELEMENTS) throw IOException("oversized GGUF array")
                repeat(count.toInt()) { skipValue(input, arrayType) }
                null
            }
            10 -> readU64(input)
            11 -> readU64(input)
            12 -> Double.fromBits(readU64(input))
            else -> throw IOException("unknown GGUF value type $type")
        }
    }

    private fun skipValue(input: RandomAccessFile, type: Int) {
        when (type) {
            0, 1, 7 -> skipFully(input, 1L)
            2, 3 -> skipFully(input, 2L)
            4, 5, 6 -> skipFully(input, 4L)
            8 -> {
                val length = readU64(input)
                if (length < 0L || length > MAX_STRING_BYTES) throw IOException("invalid GGUF array string length")
                skipFully(input, length)
            }
            9 -> {
                val nestedType = readU32(input).toInt()
                val count = readU64(input)
                if (count < 0L || count > MAX_ARRAY_ELEMENTS) throw IOException("oversized nested GGUF array")
                repeat(count.toInt()) { skipValue(input, nestedType) }
            }
            10, 11, 12 -> skipFully(input, 8L)
            else -> throw IOException("unknown GGUF array value type $type")
        }
    }

    private fun skipFully(input: RandomAccessFile, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skipBytes(minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
            if (skipped <= 0) throw IOException("truncated GGUF array")
            remaining -= skipped.toLong()
        }
    }

    private fun readU16(input: RandomAccessFile): Int =
        input.readUnsignedByte() or (input.readUnsignedByte() shl 8)

    private fun readI16(input: RandomAccessFile): Int {
        val value = readU16(input)
        return if (value and 0x8000 != 0) value or -0x10000 else value
    }
}
