// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Embedded Hermes core — memory & skill tools registered into ToolRegistry.

package io.agents.arya.agent.hermes.tools

import io.agents.arya.agent.hermes.memory.HermesMemoryStore
import io.agents.arya.agent.hermes.skills.HermesSkillStore
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolParameter
import io.agents.arya.tool.ToolRegistry
import io.agents.arya.tool.ToolResult
import io.agents.arya.utils.XLog

/**
 * Meta-tools that expose Hermes memory/skill stores to the LLM tool loop.
 * Registered alongside phone tools when the embedded Hermes core is active.
 */
object HermesMetaTools {

    private const val TAG = "HermesMetaTools"

    @Volatile
    private var registered = false

    fun registerAll() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val reg = ToolRegistry.getInstance()
            reg.register(HermesMemoryTool())
            reg.register(HermesSkillTool())
            registered = true
            XLog.i(TAG, "Registered hermes_memory + hermes_skill tools")
        }
    }
}

class HermesMemoryTool : BaseTool() {
    override fun getName(): String = "hermes_memory"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "action",
            "string",
            "read | append | write | search | episode",
            true
        ),
        ToolParameter(
            "text",
            "string",
            "Content for append/write/episode, or query for search",
            false
        )
    )

    override fun getDescriptionEN(): String =
        "Hermes long-term memory: read/write profile, append notes, search, log episodes"

    override fun getDescriptionCN(): String =
        "Hermes 长期记忆：读写用户档案、追加笔记、搜索、记录事件"

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.lowercase()?.trim()
            ?: return ToolResult.error("Missing action")
        val text = params["text"]?.toString()?.trim().orEmpty()
        val store = HermesMemoryStore.getInstance()
        return try {
            when (action) {
                "read" -> ToolResult.success(store.readProfile())
                "append" -> {
                    if (text.isBlank()) return ToolResult.error("text required for append")
                    store.appendProfileNote(text)
                    ToolResult.success("Memory note appended")
                }
                "write" -> {
                    if (text.isBlank()) return ToolResult.error("text required for write")
                    store.writeProfile(text)
                    ToolResult.success("MEMORY.md overwritten")
                }
                "episode" -> {
                    if (text.isBlank()) return ToolResult.error("text required for episode")
                    store.appendEpisode(text)
                    ToolResult.success("Episode logged")
                }
                "search" -> {
                    if (text.isBlank()) return ToolResult.error("text required for search")
                    val hits = store.search(text)
                    if (hits.isEmpty()) ToolResult.success("No hits for '$text'")
                    else ToolResult.success(
                        hits.joinToString("\n") { "${it.path}:${it.line} ${it.snippet}" }
                    )
                }
                else -> ToolResult.error("Unknown action: $action")
            }
        } catch (e: Exception) {
            ToolResult.error("hermes_memory failed: ${e.message}")
        }
    }
}

class HermesSkillTool : BaseTool() {
    override fun getName(): String = "hermes_skill"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "action",
            "string",
            "list | get | write | improve | delete | match",
            true
        ),
        ToolParameter("id", "string", "Skill id (required for get/improve/delete)", false),
        ToolParameter("name", "string", "Human name for write", false),
        ToolParameter(
            "triggers",
            "string",
            "Comma-separated trigger patterns for write",
            false
        ),
        ToolParameter("body", "string", "Markdown body for write, or improvement note", false),
        ToolParameter("task", "string", "Task text for match action", false)
    )

    override fun getDescriptionEN(): String =
        "Hermes skill library: list/get/write/improve/delete/match procedural skills"

    override fun getDescriptionCN(): String =
        "Hermes 技能库：列出/读取/写入/改进/删除/匹配流程技能"

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.lowercase()?.trim()
            ?: return ToolResult.error("Missing action")
        val store = HermesSkillStore.getInstance()
        return try {
            when (action) {
                "list" -> {
                    val all = store.listSkills()
                    if (all.isEmpty()) ToolResult.success("(no skills)")
                    else ToolResult.success(
                        all.joinToString("\n") {
                            "${it.id} | ${it.name} | triggers=${it.triggers.joinToString()} | improved=${it.improvedCount}"
                        }
                    )
                }
                "get" -> {
                    val id = params["id"]?.toString() ?: return ToolResult.error("id required")
                    val s = store.getSkill(id) ?: return ToolResult.error("Skill not found: $id")
                    ToolResult.success("# ${s.name} (${s.id})\nTriggers: ${s.triggers}\n\n${s.body}")
                }
                "write" -> {
                    val id = params["id"]?.toString()?.ifBlank { null }
                        ?: return ToolResult.error("id required")
                    val name = params["name"]?.toString() ?: id
                    val triggers = params["triggers"]?.toString()
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: listOf(id)
                    val body = params["body"]?.toString()
                        ?: return ToolResult.error("body required")
                    val s = store.writeSkill(id, name, triggers, body)
                    ToolResult.success("Skill written: ${s.id}")
                }
                "improve" -> {
                    val id = params["id"]?.toString() ?: return ToolResult.error("id required")
                    val note = params["body"]?.toString()
                        ?: return ToolResult.error("body (improvement note) required")
                    val s = store.improveSkill(id, note)
                        ?: return ToolResult.error("Skill not found: $id")
                    ToolResult.success("Skill improved: ${s.id} (count=${s.improvedCount})")
                }
                "delete" -> {
                    val id = params["id"]?.toString() ?: return ToolResult.error("id required")
                    if (store.deleteSkill(id)) ToolResult.success("Deleted $id")
                    else ToolResult.error("Not found: $id")
                }
                "match" -> {
                    val task = params["task"]?.toString() ?: return ToolResult.error("task required")
                    val s = store.match(task)
                    if (s == null) ToolResult.success("No matching skill")
                    else ToolResult.success("Matched ${s.id}: ${s.name}\n${s.body}")
                }
                else -> ToolResult.error("Unknown action: $action")
            }
        } catch (e: Exception) {
            ToolResult.error("hermes_skill failed: ${e.message}")
        }
    }
}
