package io.agents.arya.engine

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * On-disk llama.cpp state snapshots keyed by model+prompt (S4).
 * Lives in the engine process' own filesDir.
 */
class PrefixCache(private val root: File) {
    fun ensureRoot(): Boolean = root.mkdirs() || root.isDirectory

    data class Sidecar(
        val nPast: Int,
        val promptHash: String,
        val modelHash: String,
        val createdAt: Long,
        val sizeBytes: Long,
        val key: String,
    )

    init {
        root.mkdirs()
    }

    fun stateFile(key: String): File = File(root, "$key.state")
    fun sidecarFile(key: String): File = File(root, "$key.json")

    fun readSidecar(key: String): Sidecar? {
        val f = sidecarFile(key)
        if (!f.exists()) return null
        return try {
            val o = JSONObject(f.readText())
            Sidecar(
                nPast = o.optInt("nPast"),
                promptHash = o.optString("promptHash"),
                modelHash = o.optString("modelHash"),
                createdAt = o.optLong("createdAt"),
                sizeBytes = o.optLong("sizeBytes"),
                key = key,
            )
        } catch (_: Exception) {
            null
        }
    }

    fun writeSidecar(sidecar: Sidecar) {
        sidecarFile(sidecar.key).writeText(
            JSONObject().apply {
                put("nPast", sidecar.nPast)
                put("promptHash", sidecar.promptHash)
                put("modelHash", sidecar.modelHash)
                put("createdAt", sidecar.createdAt)
                put("sizeBytes", sidecar.sizeBytes)
            }.toString(),
        )
    }

    fun existsAndValid(key: String, modelHash: String): Boolean {
        val sc = readSidecar(key) ?: return false
        if (sc.modelHash != modelHash) return false
        return stateFile(key).exists() && stateFile(key).length() > 0
    }

    fun evictLru(maxFiles: Int = 3) {
        val states = root.listFiles { f -> f.extension == "state" }?.toList().orEmpty()
        if (states.size <= maxFiles) return
        states.sortedBy { it.lastModified() }
            .take(states.size - maxFiles)
            .forEach { stale ->
                val key = stale.nameWithoutExtension
                stale.delete()
                sidecarFile(key).delete()
            }
    }

    fun deleteIfOversized(key: String, maxBytes: Long = 200L * 1024 * 1024): Boolean {
        val f = stateFile(key)
        if (f.exists() && f.length() > maxBytes) {
            f.delete()
            sidecarFile(key).delete()
            return true
        }
        return false
    }

    fun deleteStale(currentModelHash: String) {
        root.listFiles { f -> f.extension == "json" }?.forEach { json ->
            val key = json.nameWithoutExtension
            val sc = readSidecar(key)
            if (sc == null || sc.modelHash != currentModelHash) {
                json.delete()
                stateFile(key).delete()
            }
        }
    }

    companion object {
        const val PROMPT_TEMPLATE_VERSION = "sys-v3"

        fun key(modelFileHash8: String, promptTemplateVersion: String, systemPromptText: String): String {
            val promptHash = sha256Hex(systemPromptText)
            return sha256Hex("$modelFileHash8|$promptTemplateVersion|$promptHash")
        }

        fun sha256Hex(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun fileHash8(file: File): String {
            if (!file.exists()) return "missing"
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }.take(8)
        }
    }
}
