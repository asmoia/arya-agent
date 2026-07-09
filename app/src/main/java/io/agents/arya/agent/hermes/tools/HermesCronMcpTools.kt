// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.hermes.tools

import io.agents.arya.agent.hermes.cron.HermesCronStore
import io.agents.arya.agent.hermes.mcp.HermesMcpClient
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolParameter
import io.agents.arya.tool.ToolRegistry
import io.agents.arya.tool.ToolResult
import io.agents.arya.utils.XLog
import org.json.JSONObject

/** Registers cron + MCP tools once. */
object HermesExtendedTools {
    private const val TAG = "HermesExtTools"
    @Volatile private var registered = false

    fun registerAll() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val reg = ToolRegistry.getInstance()
            reg.register(HermesCronTool())
            reg.register(HermesMcpTool())
            reg.register(HermesVoiceTool())
            registered = true
            XLog.i(TAG, "Registered hermes_cron + hermes_mcp + hermes_voice")
        }
    }
}

class HermesCronTool : BaseTool() {
    override fun getName() = "hermes_cron"
    override fun getDescriptionEN() =
        "Schedule recurring Hermes jobs on-device (list/add/remove). interval_minutes min 15."
    override fun getDescriptionCN() = "安排/管理设备上的 Hermes 定时任务"
    override fun getParameters() = listOf(
        ToolParameter("action", "string", "list | add | remove", true),
        ToolParameter("title", "string", "Job title for add", false),
        ToolParameter("prompt", "string", "What to do when job fires", false),
        ToolParameter("interval_minutes", "integer", "Interval in minutes (min 15)", false),
        ToolParameter("id", "string", "Job id for remove", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.lowercase() ?: return ToolResult.error("action required")
        return try {
            when (action) {
                "list" -> {
                    val jobs = HermesCronStore.listJobs()
                    if (jobs.isEmpty()) ToolResult.success("(no cron jobs)")
                    else ToolResult.success(
                        jobs.joinToString("\n") {
                            "${it.id} | ${it.title} | every ${it.intervalMinutes}m | enabled=${it.enabled}"
                        }
                    )
                }
                "add" -> {
                    val title = params["title"]?.toString() ?: "job"
                    val prompt = params["prompt"]?.toString()
                        ?: return ToolResult.error("prompt required")
                    val interval = (params["interval_minutes"] as? Number)?.toLong()
                        ?: params["interval_minutes"]?.toString()?.toLongOrNull()
                        ?: 60L
                    val job = HermesCronStore.addJob(title, prompt, interval)
                    ToolResult.success("Scheduled ${job.id} every ${job.intervalMinutes} minutes")
                }
                "remove" -> {
                    val id = params["id"]?.toString() ?: return ToolResult.error("id required")
                    HermesCronStore.removeJob(id)
                    ToolResult.success("Removed $id")
                }
                else -> ToolResult.error("Unknown action $action")
            }
        } catch (e: Exception) {
            ToolResult.error("hermes_cron: ${e.message}")
        }
    }
}

class HermesMcpTool : BaseTool() {
    override fun getName() = "hermes_mcp"
    override fun getDescriptionEN() =
        "Call external MCP server tools (list | call). Requires MCP enabled in settings."
    override fun getDescriptionCN() = "调用外部 MCP 工具服务器"
    override fun getParameters() = listOf(
        ToolParameter("action", "string", "list | call", true),
        ToolParameter("name", "string", "Tool name for call", false),
        ToolParameter("arguments_json", "string", "JSON object of arguments", false)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.lowercase() ?: return ToolResult.error("action required")
        return try {
            when (action) {
                "list" -> {
                    val tools = HermesMcpClient.listTools().getOrElse {
                        return ToolResult.error(it.message ?: "list failed")
                    }
                    if (tools.isEmpty()) ToolResult.success("(no MCP tools)")
                    else ToolResult.success(
                        tools.joinToString("\n") { "${it.name}: ${it.description}" }
                    )
                }
                "call" -> {
                    val name = params["name"]?.toString() ?: return ToolResult.error("name required")
                    val argsRaw = params["arguments_json"]?.toString().orEmpty()
                    val args = if (argsRaw.isBlank()) JSONObject() else JSONObject(argsRaw)
                    val out = HermesMcpClient.callTool(name, args).getOrElse {
                        return ToolResult.error(it.message ?: "call failed")
                    }
                    ToolResult.success(out)
                }
                else -> ToolResult.error("Unknown action $action")
            }
        } catch (e: Exception) {
            ToolResult.error("hermes_mcp: ${e.message}")
        }
    }
}
