// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Embedded Hermes core — persistent memory (profile + episodic notes).

package io.agents.arya.agent.hermes.memory

import io.agents.arya.ClawApplication
import io.agents.arya.utils.XLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * File-backed memory store inspired by Hermes MEMORY.md + daily notes.
 *
 * Layout under app files:
 *   hermes/MEMORY.md
 *   hermes/episodes/yyyy-MM-dd.md
 */
class HermesMemoryStore private constructor() {

    private val lock = ReentrantReadWriteLock()

    private fun rootDir(): File {
        val dir = File(ClawApplication.instance.filesDir, "hermes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun memoryFile(): File = File(rootDir(), "MEMORY.md")

    private fun episodesDir(): File {
        val dir = File(rootDir(), "episodes")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun todayEpisodeFile(): File {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return File(episodesDir(), "$day.md")
    }

    fun readProfile(): String = lock.read {
        val f = memoryFile()
        if (!f.exists()) {
            val seed = DEFAULT_MEMORY
            f.writeText(seed)
            return@read seed
        }
        f.readText()
    }

    fun writeProfile(content: String) = lock.write {
        val capped = content.take(MAX_PROFILE_CHARS)
        memoryFile().writeText(capped)
        XLog.i(TAG, "writeProfile chars=${capped.length}")
    }

    fun appendProfileNote(note: String) = lock.write {
        val f = memoryFile()
        if (!f.exists()) f.writeText(DEFAULT_MEMORY)
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        f.appendText("\n- [$stamp] ${note.trim().take(500)}\n")
        trimFile(f, MAX_PROFILE_CHARS)
        XLog.i(TAG, "appendProfileNote")
    }

    fun appendEpisode(text: String) = lock.write {
        val f = todayEpisodeFile()
        if (!f.exists()) {
            val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            f.writeText("# Episodes — $day\n\n")
        }
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        f.appendText("## $stamp\n${text.trim().take(2000)}\n\n")
        trimFile(f, MAX_EPISODE_CHARS)
        XLog.d(TAG, "appendEpisode")
    }

    fun readRecentEpisodes(maxDays: Int = 3, maxChars: Int = 4000): String = lock.read {
        val files = episodesDir().listFiles { file -> file.extension == "md" }
            ?.sortedByDescending { it.name }
            ?.take(maxDays)
            ?: emptyList()
        val sb = StringBuilder()
        for (f in files) {
            val body = f.readText()
            if (sb.length + body.length > maxChars) {
                sb.append(body.take(maxChars - sb.length))
                break
            }
            sb.append(body).append('\n')
        }
        sb.toString()
    }

    /**
     * Build the memory block injected into the system prompt each turn.
     */
    fun buildPromptBlock(maxChars: Int = 2500): String {
        val profile = readProfile().take(maxChars * 2 / 3)
        val episodes = readRecentEpisodes(maxDays = 2, maxChars = maxChars / 3)
        return buildString {
            appendLine("## Persistent Memory (Hermes)")
            appendLine("Use this to personalize answers. Update via memory tools when you learn durable facts.")
            appendLine()
            appendLine("### Profile")
            appendLine(profile.ifBlank { "(empty)" })
            if (episodes.isNotBlank()) {
                appendLine()
                appendLine("### Recent episodes")
                appendLine(episodes)
            }
        }.take(maxChars + 400)
    }

    /**
     * Simple keyword search across profile + episode files.
     */
    fun search(query: String, limit: Int = 8): List<SearchHit> = lock.read {
        val q = query.trim().lowercase(Locale.US)
        if (q.isEmpty()) return@read emptyList()
        val hits = mutableListOf<SearchHit>()
        fun scan(path: String, text: String) {
            val lines = text.lines()
            lines.forEachIndexed { idx, line ->
                if (line.lowercase(Locale.US).contains(q)) {
                    hits += SearchHit(path, idx + 1, line.trim().take(240))
                }
            }
        }
        scan("MEMORY.md", readProfile())
        episodesDir().listFiles { f -> f.extension == "md" }?.forEach { f ->
            scan("episodes/${f.name}", f.readText())
        }
        hits.take(limit)
    }

    data class SearchHit(val path: String, val line: Int, val snippet: String)

    private fun trimFile(file: File, maxChars: Int) {
        val text = file.readText()
        if (text.length <= maxChars) return
        // Keep head header + tail
        val keepHead = (maxChars * 0.25).toInt().coerceAtLeast(200)
        val keepTail = maxChars - keepHead - 40
        val trimmed = text.take(keepHead) + "\n\n…[truncated]…\n\n" + text.takeLast(keepTail)
        file.writeText(trimmed)
    }

    companion object {
        private const val TAG = "HermesMemoryStore"
        private const val MAX_PROFILE_CHARS = 20_000
        private const val MAX_EPISODE_CHARS = 40_000

        private val DEFAULT_MEMORY = """
            # MEMORY — آریا / Arya user model

            ## Identity
            - Assistant name: آریا (Arya)
            - Languages: Persian (primary), English
            - Device focus: Android phone control (EMUI/Huawei friendly)

            ## User
            - (facts the agent learns about the user go here)

            ## Preferences
            - Prefer concise Persian replies unless the user writes in English
            - Confirm before sending messages or making calls

            ## Notes
            """.trimIndent() + "\n"

        @Volatile
        private var instance: HermesMemoryStore? = null

        fun getInstance(): HermesMemoryStore {
            return instance ?: synchronized(this) {
                instance ?: HermesMemoryStore().also { instance = it }
            }
        }
    }
}
