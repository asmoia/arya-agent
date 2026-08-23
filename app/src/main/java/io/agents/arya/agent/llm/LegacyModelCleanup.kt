package io.agents.arya.agent.llm

import java.io.File

/**
 * S8 migration: never silently delete user .litertlm/.task files.
 * UI should show a one-time "Delete old model files (X MB)" button.
 */
object LegacyModelCleanup {
    fun findLegacyFiles(modelsDir: File): List<File> {
        if (!modelsDir.exists()) return emptyList()
        return modelsDir.walkTopDown().filter { f ->
            f.isFile && (f.extension.equals("litertlm", true) || f.extension.equals("task", true))
        }.toList()
    }

    fun totalBytes(files: List<File>): Long = files.sumOf { it.length() }

    fun noticeMb(files: List<File>): Int = ((totalBytes(files) + 512 * 1024) / (1024 * 1024)).toInt()
}
