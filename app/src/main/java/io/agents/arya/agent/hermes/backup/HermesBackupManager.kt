// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Export / import Hermes memory, skills, and (optional) session metadata.

package io.agents.arya.agent.hermes.backup

import android.content.Context
import android.net.Uri
import io.agents.arya.ClawApplication
import io.agents.arya.agent.hermes.memory.HermesMemoryStore
import io.agents.arya.agent.hermes.session.HermesSessionStore
import io.agents.arya.agent.hermes.skills.HermesSkillStore
import io.agents.arya.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Creates and restores portable Hermes backups (ZIP).
 *
 * Contents:
 * - manifest.json
 * - MEMORY.md
 * - episodes/*.md
 * - skills/*.md
 * - sessions_index.json  (metadata only — not full message dump by default)
 *
 * Secrets (API keys) are **never** included.
 */
object HermesBackupManager {

    private const val TAG = "HermesBackup"
    const val FORMAT_VERSION = 1
    private const val MANIFEST = "manifest.json"

    data class Result(val ok: Boolean, val message: String, val file: File? = null)

    private fun hermesRoot(): File =
        File(ClawApplication.instance.filesDir, "hermes").apply { mkdirs() }

    /**
     * Write a ZIP into cacheDir and return it for sharing via FileProvider.
     */
    fun exportToCache(context: Context = ClawApplication.instance): Result {
        return try {
            // Ensure stores exist / seed skills if empty
            HermesSkillStore.getInstance().ensureSeedSkills()
            HermesMemoryStore.getInstance().readProfile()

            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val outFile = File(context.cacheDir, "arya_hermes_backup_$stamp.zip")
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outFile))).use { zip ->
                // Manifest
                val sessions = HermesSessionStore.getInstance().listRecent(50)
                val manifest = JSONObject()
                    .put("format_version", FORMAT_VERSION)
                    .put("app", "io.agents.arya")
                    .put("created_at", System.currentTimeMillis())
                    .put("created_at_iso", stamp)
                    .put("session_count", sessions.size)
                    .put("skill_count", HermesSkillStore.getInstance().listSkills().size)
                    .put("note", "No API keys or secrets included")
                putText(zip, MANIFEST, manifest.toString(2))

                // Memory profile
                val memFile = File(hermesRoot(), "MEMORY.md")
                if (memFile.exists()) putFile(zip, "MEMORY.md", memFile)
                else putText(zip, "MEMORY.md", HermesMemoryStore.getInstance().readProfile())

                // Episodes
                val episodes = File(hermesRoot(), "episodes")
                episodes.listFiles()?.filter { it.isFile && it.extension == "md" }?.forEach { f ->
                    putFile(zip, "episodes/${f.name}", f)
                }

                // Skills
                val skillsDir = File(hermesRoot(), "skills")
                skillsDir.listFiles()?.filter { it.isFile && it.extension.equals("md", true) }?.forEach { f ->
                    putFile(zip, "skills/${f.name}", f)
                }

                // Session index (safe metadata)
                val arr = JSONArray()
                for (s in sessions) {
                    arr.put(
                        JSONObject()
                            .put("id", s.id)
                            .put("title", s.title)
                            .put("source", s.source)
                            .put("created_at", s.createdAt)
                            .put("updated_at", s.updatedAt)
                            .put("ended_at", s.endedAt)
                            .put("metadata_json", s.metadataJson)
                    )
                }
                putText(zip, "sessions_index.json", arr.toString(2))
            }

            XLog.i(TAG, "export ok: ${outFile.absolutePath} (${outFile.length()} bytes)")
            Result(true, "Backup ready: ${outFile.name}", outFile)
        } catch (e: Exception) {
            XLog.e(TAG, "export failed", e)
            Result(false, "Export failed: ${e.message}")
        }
    }

    /**
     * Restore from a ZIP [Uri] (e.g. SAF picker). Merges files; does not wipe
     * unrelated hermes data unless [replaceAll] is true.
     */
    fun importFromUri(
        context: Context,
        uri: Uri,
        replaceAll: Boolean = false
    ): Result {
        return try {
            val root = hermesRoot()
            if (replaceAll) {
                // Keep sessions DB; only replace memory/skills/episodes trees
                File(root, "episodes").deleteRecursively()
                File(root, "skills").deleteRecursively()
                File(root, "MEMORY.md").delete()
            }
            File(root, "episodes").mkdirs()
            File(root, "skills").mkdirs()

            var formatOk = false
            var restoredFiles = 0
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.trimStart('/').replace("..", "")
                        if (entry.isDirectory) {
                            zis.closeEntry()
                            entry = zis.nextEntry
                            continue
                        }
                        when {
                            name == MANIFEST -> {
                                val text = zis.readBytes().toString(Charsets.UTF_8)
                                val ver = JSONObject(text).optInt("format_version", 0)
                                if (ver > FORMAT_VERSION) {
                                    return Result(
                                        false,
                                        "Backup format v$ver is newer than this app (v$FORMAT_VERSION). Update Arya first."
                                    )
                                }
                                formatOk = true
                            }
                            name == "MEMORY.md" -> {
                                File(root, "MEMORY.md").writeBytes(zis.readBytes())
                                restoredFiles++
                            }
                            name.startsWith("episodes/") && name.endsWith(".md") -> {
                                val f = File(root, name)
                                f.parentFile?.mkdirs()
                                f.writeBytes(zis.readBytes())
                                restoredFiles++
                            }
                            name.startsWith("skills/") && name.endsWith(".md") -> {
                                val f = File(root, name)
                                f.parentFile?.mkdirs()
                                f.writeBytes(zis.readBytes())
                                restoredFiles++
                            }
                            name == "sessions_index.json" -> {
                                // Index is informational; store a copy for forensics
                                File(root, "sessions_index.restored.json")
                                    .writeBytes(zis.readBytes())
                                restoredFiles++
                            }
                            else -> {
                                // skip unknown
                                zis.readBytes()
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } ?: return Result(false, "Cannot open backup file")

            // Refresh in-memory skill cache by re-reading from disk next call
            // (SkillStore has no global clear of singleton — listSkills re-reads files)
            HermesSkillStore.getInstance().listSkills()

            val msg = if (formatOk) {
                "Import OK — $restoredFiles files restored"
            } else {
                "Import finished ($restoredFiles files) — manifest missing (older/partial backup)"
            }
            XLog.i(TAG, msg)
            Result(true, msg)
        } catch (e: Exception) {
            XLog.e(TAG, "import failed", e)
            Result(false, "Import failed: ${e.message}")
        }
    }

    private fun putText(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun putFile(zip: ZipOutputStream, path: String, file: File) {
        zip.putNextEntry(ZipEntry(path))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
