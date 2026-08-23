// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Stronger post-turn learning (P1).

package io.agents.arya.agent.hermes.core

import io.agents.arya.agent.hermes.memory.HermesMemoryStore
import io.agents.arya.agent.hermes.skills.HermesSkillStore
import io.agents.arya.agent.llm.LlmSessionManager
import io.agents.arya.utils.XLog
import java.util.Locale

/**
 * After a successful task, optionally ask a cheap LLM pass to extract
 * durable user facts and a reusable skill draft.
 *
 * Falls back to heuristic extraction when no cloud/local single-shot works.
 */
object HermesLearning {

    private const val TAG = "HermesLearning"

    fun learnFromTurn(userTask: String, answer: String, usedTools: List<String>) {
        try {
            // Always keep episodic log (cheap, local)
            HermesMemoryStore.getInstance().appendEpisode(
                "Task: ${userTask.take(400)}\nTools: ${usedTools.joinToString()}\nResult: ${answer.take(600)}"
            )

            val complex = userTask.length > 40 || usedTools.size >= 2
            if (!complex) return

            val extraction = tryLlmExtract(userTask, answer, usedTools)
                ?: heuristicExtract(userTask, answer, usedTools)

            extraction.memoryNotes.forEach { note ->
                if (note.isNotBlank()) {
                    HermesMemoryStore.getInstance().appendProfileNote(note.take(400))
                }
            }

            val skillDraft = extraction.skill
            if (skillDraft != null && skillDraft.body.length > 40) {
                val existing = HermesSkillStore.getInstance().match(userTask)
                if (existing != null) {
                    HermesSkillStore.getInstance().improveSkill(
                        existing.id,
                        "Auto-learn: ${skillDraft.body.take(500)}"
                    )
                } else {
                    HermesSkillStore.getInstance().writeSkill(
                        id = skillDraft.id,
                        name = skillDraft.name,
                        triggers = skillDraft.triggers,
                        body = skillDraft.body
                    )
                }
            }
            XLog.i(TAG, "learnFromTurn done notes=${extraction.memoryNotes.size} skill=${skillDraft != null}")
        } catch (e: Exception) {
            XLog.w(TAG, "learnFromTurn failed", e)
        }
    }

    private data class SkillDraft(
        val id: String,
        val name: String,
        val triggers: List<String>,
        val body: String
    )

    private data class Extraction(
        val memoryNotes: List<String>,
        val skill: SkillDraft?
    )

    private fun tryLlmExtract(task: String, answer: String, tools: List<String>): Extraction? {
        return try {
            val prompt = """
                You extract durable learnings for a phone assistant.
                User task: $task
                Tools used: ${tools.joinToString()}
                Result: $answer

                Reply in EXACTLY this format (no markdown fences):
                MEMORY: <one short factual note about the user, or NONE>
                SKILL_ID: <kebab-id or NONE>
                SKILL_NAME: <short name or NONE>
                SKILL_TRIGGERS: <comma triggers or NONE>
                SKILL_BODY: <numbered steps or NONE>
            """.trimIndent()
            val text = LlmSessionManager.singleShot(prompt, temperature = 0.2) ?: return null
            parseExtraction(text, task)
        } catch (e: Exception) {
            XLog.d(TAG, "LLM extract skipped: ${e.message}")
            null
        }
    }

    private fun parseExtraction(text: String, task: String): Extraction {
        fun field(key: String): String {
            val re = Regex("^$key:\\s*(.+)$", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            return re.find(text)?.groupValues?.get(1)?.trim().orEmpty()
        }
        val mem = field("MEMORY")
        val notes = if (mem.isNotBlank() && !mem.equals("NONE", true)) listOf(mem) else emptyList()
        val sid = field("SKILL_ID")
        val skill = if (sid.isNotBlank() && !sid.equals("NONE", true)) {
            SkillDraft(
                id = sid.lowercase(Locale.US).replace(Regex("[^a-z0-9-]+"), "-").take(40),
                name = field("SKILL_NAME").ifBlank { sid },
                triggers = field("SKILL_TRIGGERS")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.equals("NONE", true) }
                    .ifEmpty { listOf(task.take(30)) },
                body = field("SKILL_BODY").let {
                    if (it.equals("NONE", true) || it.isBlank()) "" else it
                }
            ).takeIf { it.body.isNotBlank() }
        } else null
        return Extraction(notes, skill)
    }

    private fun heuristicExtract(task: String, answer: String, tools: List<String>): Extraction {
        val notes = mutableListOf<String>()
        val lower = task.lowercase(Locale.US)
        if (lower.contains("اسمم") || lower.contains("my name")) {
            notes += "User mentioned identity in: ${task.take(80)}"
        }
        if (tools.any { it in setOf("send_message", "make_call", "auto_reply") }) {
            notes += "User performs messaging/call automation on this device."
        }
        val skill = if (tools.size >= 2) {
            val id = "auto-" + tools.take(3).joinToString("-").take(30)
            SkillDraft(
                id = id,
                name = "Auto: ${task.take(40)}",
                triggers = listOf(task.take(24)),
                body = buildString {
                    appendLine("# Auto-learned flow")
                    appendLine("Task example: $task")
                    appendLine("Tools: ${tools.joinToString()}")
                    appendLine("Outcome: ${answer.take(200)}")
                    tools.forEachIndexed { i, t -> appendLine("${i + 1}. Use `$t` as appropriate") }
                }
            )
        } else null
        return Extraction(notes, skill)
    }
}
