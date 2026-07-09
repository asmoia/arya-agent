// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Minimal MCP (Model Context Protocol) JSON-RPC over HTTP client (P3).

package io.agents.arya.agent.hermes.mcp

import io.agents.arya.utils.KVUtils
import io.agents.arya.utils.XLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Lightweight MCP client: tools/list + tools/call against an HTTP MCP endpoint.
 *
 * Configure via KV:
 * - hermes.mcp.url  e.g. http://127.0.0.1:8765/mcp
 * - hermes.mcp.enabled
 *
 * Not a full multi-transport MCP stack — enough for power users to attach
 * external tool servers without Termux/Hermes desktop.
 */
object HermesMcpClient {

    private const val TAG = "HermesMcp"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val idGen = AtomicLong(1)
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class McpTool(val name: String, val description: String, val inputSchema: JSONObject?)

    fun isEnabled(): Boolean = KVUtils.getBoolean("KEY_HERMES_MCP_ENABLED", false)

    fun setEnabled(v: Boolean) = KVUtils.putBoolean("KEY_HERMES_MCP_ENABLED", v)

    fun getUrl(): String = KVUtils.getString("KEY_HERMES_MCP_URL", "")

    fun setUrl(url: String) = KVUtils.putString("KEY_HERMES_MCP_URL", url.trim())

    fun listTools(): Result<List<McpTool>> {
        if (!isEnabled()) return Result.failure(IllegalStateException("MCP disabled"))
        val url = getUrl()
        if (url.isBlank()) return Result.failure(IllegalStateException("MCP URL empty"))
        return try {
            val resp = rpc(url, "tools/list", JSONObject())
            val tools = resp.optJSONObject("result")?.optJSONArray("tools")
                ?: resp.optJSONArray("tools")
                ?: JSONArray()
            val out = mutableListOf<McpTool>()
            for (i in 0 until tools.length()) {
                val t = tools.getJSONObject(i)
                out += McpTool(
                    name = t.optString("name"),
                    description = t.optString("description"),
                    inputSchema = t.optJSONObject("inputSchema")
                )
            }
            Result.success(out)
        } catch (e: Exception) {
            XLog.w(TAG, "listTools failed", e)
            Result.failure(e)
        }
    }

    fun callTool(name: String, arguments: JSONObject = JSONObject()): Result<String> {
        if (!isEnabled()) return Result.failure(IllegalStateException("MCP disabled"))
        val url = getUrl()
        if (url.isBlank()) return Result.failure(IllegalStateException("MCP URL empty"))
        return try {
            val params = JSONObject()
                .put("name", name)
                .put("arguments", arguments)
            val resp = rpc(url, "tools/call", params)
            val result = resp.optJSONObject("result") ?: resp
            Result.success(result.toString())
        } catch (e: Exception) {
            XLog.w(TAG, "callTool $name failed", e)
            Result.failure(e)
        }
    }

    fun buildPromptSection(): String {
        if (!isEnabled() || getUrl().isBlank()) return ""
        val tools = listTools().getOrNull().orEmpty()
        if (tools.isEmpty()) {
            return "\n## MCP\nMCP enabled at ${getUrl()} but tools/list returned empty or failed.\n"
        }
        return buildString {
            appendLine()
            appendLine("## MCP external tools")
            appendLine("Call via hermes_mcp tool. Available:")
            for (t in tools.take(20)) {
                appendLine("- ${t.name}: ${t.description.take(120)}")
            }
        }
    }

    private fun rpc(url: String, method: String, params: JSONObject): JSONObject {
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idGen.getAndIncrement())
            .put("method", method)
            .put("params", params)
            .toString()
        val req = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON))
            .header("Content-Type", "application/json")
            .build()
        http.newCall(req).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: ${text.take(200)}")
            }
            return JSONObject(text)
        }
    }
}
