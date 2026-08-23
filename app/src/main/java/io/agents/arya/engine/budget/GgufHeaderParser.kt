package io.agents.arya.engine.budget

import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Tiny GGUF header reader (magic + typed KV). Pure JVM. Used when JNI meta
 * is unavailable (tests, pre-load gating).
 */
object GgufHeaderParser {
    data class Header(
        val version: Int,
        val kv: Map<String, Any?>,
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

    fun parse(file: File, maxKv: Int = 256): Header? {
        if (!file.exists() || file.length() < 24) return null
        return try {
            FileInputStream(file).use { fis ->
                val input = DataInputStream(fis)
                val magic = ByteArray(4)
                input.readFully(magic)
                if (String(magic, Charsets.US_ASCII) != "GGUF") return null
                val version = readU32(input)
                /* nTensors */ readU64(input)
                val nKv = readU64(input).toInt().coerceAtMost(maxKv)
                val map = LinkedHashMap<String, Any?>()
                repeat(nKv) {
                    val key = readString(input) ?: return@repeat
                    val typ = readU32(input)
                    map[key] = readValue(input, typ)
                }
                Header(version, map)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readU32(input: DataInputStream): Int {
        val b = ByteArray(4)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readU64(input: DataInputStream): Long {
        val b = ByteArray(8)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun readString(input: DataInputStream): String? {
        val len = readU64(input).toInt()
        if (len < 0 || len > 1_000_000) return null
        val bytes = ByteArray(len)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readValue(input: DataInputStream, typ: Int): Any? {
        return when (typ) {
            0 -> input.readUnsignedByte() // UINT8
            1 -> input.readByte() // INT8
            2 -> readU16(input)
            3 -> readI16(input)
            4 -> readU32(input)
            5 -> readU32(input)
            6 -> readF32(input)
            7 -> input.readBoolean()
            8 -> readString(input)
            9 -> { // array — skip
                val at = readU32(input)
                val n = readU64(input).toInt().coerceAtMost(10_000)
                repeat(n) { readValue(input, at) }
                null
            }
            10 -> readU64(input)
            11 -> readU64(input)
            12 -> readF64(input)
            else -> null
        }
    }

    private fun readU16(input: DataInputStream): Int {
        val b = ByteArray(2)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun readI16(input: DataInputStream): Int {
        val b = ByteArray(2)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
    }

    private fun readF32(input: DataInputStream): Float {
        val b = ByteArray(4)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).float
    }

    private fun readF64(input: DataInputStream): Double {
        val b = ByteArray(8)
        input.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).double
    }
}
