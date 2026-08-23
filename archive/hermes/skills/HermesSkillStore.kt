// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Embedded Hermes core — procedural skill library (agentskills-style markdown).

package io.agents.arya.agent.hermes.skills

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
 * Markdown skill library inspired by Hermes skills hub / agentskills.io.
 *
 * Each skill is a single .md file with optional YAML frontmatter:
 *
 * ---
 * id: reply-whatsapp-fa
 * name: پاسخ واتساپ
 * triggers:
 *   - "به * پیام بده"
 *   - "whatsapp *"
 * ---
 * # Steps
 * 1. ...
 */
class HermesSkillStore private constructor() {

    private val lock = ReentrantReadWriteLock()

    data class Skill(
        val id: String,
        val name: String,
        val triggers: List<String>,
        val body: String,
        val path: String,
        val improvedCount: Int = 0
    )

    private fun skillsDir(): File {
        val dir = File(ClawApplication.instance.filesDir, "hermes/skills")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun ensureSeedSkills() = lock.write {
        val dir = skillsDir()
        if (dir.listFiles()?.isNotEmpty() == true) return@write
        writeSkillUnlocked(
            id = "phone-observe-act",
            name = "Observe → Act on phone",
            triggers = listOf("باز کن", "open ", "بفرست", "send ", "تنظیمات", "settings"),
            body = """
                # Observe → Act

                1. Call `get_screen_info` before acting.
                2. Prefer `open_app` / `emui_settings` over blind taps when possible.
                3. After each action, re-observe if the outcome is uncertain.
                4. Call `finish(summary=...)` with the actual result data.
            """.trimIndent()
        )
        writeSkillUnlocked(
            id = "shamsi-date",
            name = "تاریخ شمسی",
            triggers = listOf("تاریخ", "امروز چندمه", "shamsi", "شمسی"),
            body = """
                # تاریخ شمسی

                1. Use tool `shamsi_calendar` with action `today` or `now`.
                2. Reply in Persian with weekday + date.
            """.trimIndent()
        )
        writeSkillUnlocked(
            id = "huawei-emui",
            name = "EMUI / Huawei",
            triggers = listOf("هواوی", "emui", "اپ گالری", "appgallery", "مدیریت گوشی"),
            body = """
                # EMUI

                1. Prefer `emui_settings` for AppGallery, Phone Manager, WiFi, Bluetooth, battery.
                2. If an OEM screen blocks automation, explain in Persian what the user must tap.
            """.trimIndent()
        )
        writeSkillUnlocked(
            id = "telegram-saved-media",
            name = "تلگرام — پیام‌های ذخیره‌شده / پخش مدیا",
            triggers = listOf(
                "سیو مسیج", "saved messages", "پیام‌های ذخیره", "سیو مسیج تلگرام",
                "تلگرام", "telegram", "ویس", "آهنگ", "پخش", "پلی"
            ),
            body = """
                # Telegram Saved Messages → play media

                NEVER say you cannot access Telegram.

                1. open_app(app_name="Telegram") or package org.telegram.messenger — wait_after=3000
                2. get_screen_info
                3. Open Saved Messages: search icon → input_text "Saved Messages" or "پیام‌های ذخیره‌شده" → tap result
                   (or profile / menu shortcut if visible)
                4. get_screen_info — find an audio/voice/music bubble (waveform, duration, play triangle)
                5. If user asked random: pick any media item on screen (not the same failed node thrice)
                6. tap play on that bubble
                7. finish(summary=what played / where you are)

                If Telegram package differs, get_installed_apps and search "telegram".
            """.trimIndent()
        )
        writeSkillUnlocked(
            id = "browser-web",
            name = "مرورگر / جستجوی وب",
            triggers = listOf(
                "کروم", "chrome", "مرورگر", "browser", "جستجو کن", "سرچ",
                "google", "اینترنت", "سایت", "http"
            ),
            body = """
                # Browser

                1. open_app Chrome (or browser from get_installed_apps)
                2. get_screen_info — find URL/search bar
                3. find_and_tap bar → input_text(query or URL) → system_key enter / tap Go
                4. wait_after load → get_screen_info → tap relevant result
                5. finish with what you opened / answer from screen text
                Captcha/login → finish explaining user must continue.
            """.trimIndent()
        )
        writeSkillUnlocked(
            id = "whatsapp-chat",
            name = "واتساپ",
            triggers = listOf("whatsapp", "واتساپ", "واتس"),
            body = """
                # WhatsApp
                open_app WhatsApp → search contact → open chat → input_text / media → finish
            """.trimIndent()
        )
        XLog.i(TAG, "Seeded default Hermes skills")
    }

    fun listSkills(): List<Skill> = lock.read {
        ensureDir()
        skillsDir().listFiles { f -> f.extension.equals("md", true) }
            ?.mapNotNull { parseFile(it) }
            ?.sortedBy { it.id }
            ?: emptyList()
    }

    fun getSkill(id: String): Skill? = lock.read {
        val safe = sanitizeId(id)
        val f = File(skillsDir(), "$safe.md")
        if (!f.exists()) return@read null
        parseFile(f)
    }

    fun match(task: String): Skill? = lock.read {
        val lower = task.lowercase(Locale.US)
        // Skip compound tasks — let the agent loop handle them.
        if (listOf(" and ", " then ", " after ", " و بعد ", " سپس ").any { lower.contains(it) }) {
            return@read null
        }
        listSkills().firstOrNull { skill ->
            skill.triggers.any { trigger ->
                val pattern = trigger.lowercase(Locale.US)
                    .replace(Regex("\\*+"), ".*")
                    .replace(Regex("\\{[^}]+\\}"), ".+")
                try {
                    Regex(pattern).containsMatchIn(lower)
                } catch (_: Exception) {
                    lower.contains(pattern.replace(".*", ""))
                }
            }
        }
    }

    fun writeSkill(
        id: String,
        name: String,
        triggers: List<String>,
        body: String
    ): Skill = lock.write {
        writeSkillUnlocked(id, name, triggers, body)
    }

    fun improveSkill(id: String, note: String): Skill? = lock.write {
        val existing = getSkillUnlocked(id) ?: return@write null
        val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val improvedBody = buildString {
            append(existing.body.trim())
            append("\n\n## Improvement ($stamp)\n")
            append(note.trim().take(1500))
            append('\n')
        }.take(MAX_BODY_CHARS)
        writeSkillUnlocked(
            id = existing.id,
            name = existing.name,
            triggers = existing.triggers,
            body = improvedBody,
            improvedCount = existing.improvedCount + 1
        )
    }

    fun deleteSkill(id: String): Boolean = lock.write {
        val f = File(skillsDir(), "${sanitizeId(id)}.md")
        val ok = f.exists() && f.delete()
        XLog.i(TAG, "deleteSkill id=$id ok=$ok")
        ok
    }

    fun buildPromptBlock(maxChars: Int = 1800): String {
        val skills = listSkills()
        if (skills.isEmpty()) return ""
        return buildString {
            appendLine("## Skill library (Hermes)")
            appendLine("If a skill matches the task, follow it. You may create/improve skills with skill tools.")
            for (s in skills.take(12)) {
                appendLine("- **${s.id}** (${s.name}): triggers=${s.triggers.take(3).joinToString()}")
            }
        }.take(maxChars)
    }

    fun buildMatchedSkillSection(task: String): String {
        val skill = match(task) ?: return ""
        XLog.i(TAG, "Matched skill ${skill.id} for task='$task'")
        return buildString {
            appendLine()
            appendLine("## Active skill: ${skill.name} (`${skill.id}`)")
            appendLine("Follow these steps unless the user overrides:")
            appendLine(skill.body)
        }
    }

    private fun ensureDir() {
        skillsDir()
    }

    private fun getSkillUnlocked(id: String): Skill? {
        val f = File(skillsDir(), "${sanitizeId(id)}.md")
        if (!f.exists()) return null
        return parseFile(f)
    }

    private fun writeSkillUnlocked(
        id: String,
        name: String,
        triggers: List<String>,
        body: String,
        improvedCount: Int = 0
    ): Skill {
        val safe = sanitizeId(id)
        val f = File(skillsDir(), "$safe.md")
        val triggerYaml = triggers.joinToString("\n") { "  - \"${it.replace("\"", "'")}\"" }
        val content = buildString {
            appendLine("---")
            appendLine("id: $safe")
            appendLine("name: ${name.replace("\n", " ").take(80)}")
            appendLine("improved_count: $improvedCount")
            appendLine("triggers:")
            appendLine(triggerYaml.ifBlank { "  - \"$safe\"" })
            appendLine("---")
            appendLine()
            append(body.trim().take(MAX_BODY_CHARS))
            appendLine()
        }
        f.writeText(content)
        XLog.i(TAG, "writeSkill id=$safe")
        return Skill(safe, name, triggers, body, f.absolutePath, improvedCount)
    }

    private fun parseFile(file: File): Skill? {
        return try {
            val text = file.readText()
            if (text.startsWith("---")) {
                val end = text.indexOf("---", 3)
                if (end > 0) {
                    val fm = text.substring(3, end).trim()
                    val body = text.substring(end + 3).trim()
                    val id = yamlValue(fm, "id") ?: file.nameWithoutExtension
                    val name = yamlValue(fm, "name") ?: id
                    val improved = yamlValue(fm, "improved_count")?.toIntOrNull() ?: 0
                    val triggers = yamlList(fm, "triggers")
                    return Skill(id, name, triggers, body, file.absolutePath, improved)
                }
            }
            Skill(
                id = file.nameWithoutExtension,
                name = file.nameWithoutExtension,
                triggers = listOf(file.nameWithoutExtension),
                body = text,
                path = file.absolutePath
            )
        } catch (e: Exception) {
            XLog.w(TAG, "parseFile failed: ${file.name}", e)
            null
        }
    }

    private fun yamlValue(fm: String, key: String): String? {
        val re = Regex("^$key:\\s*(.+)$", RegexOption.MULTILINE)
        return re.find(fm)?.groupValues?.get(1)?.trim()?.trim('"')
    }

    private fun yamlList(fm: String, key: String): List<String> {
        val lines = fm.lines()
        val idx = lines.indexOfFirst { it.trim().startsWith("$key:") }
        if (idx < 0) return emptyList()
        val out = mutableListOf<String>()
        for (i in (idx + 1) until lines.size) {
            val line = lines[i]
            if (line.matches(Regex("^[A-Za-z_].*"))) break
            val m = Regex("^\\s*-\\s*\"?(.+?)\"?\\s*$").find(line) ?: continue
            out += m.groupValues[1].trim().trim('"')
        }
        return out
    }

    private fun sanitizeId(id: String): String =
        id.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .ifBlank { "skill" }
            .take(64)

    companion object {
        private const val TAG = "HermesSkillStore"
        private const val MAX_BODY_CHARS = 12_000

        @Volatile
        private var instance: HermesSkillStore? = null

        fun getInstance(): HermesSkillStore {
            return instance ?: synchronized(this) {
                instance ?: HermesSkillStore().also {
                    instance = it
                    it.ensureSeedSkills()
                }
            }
        }
    }
}
