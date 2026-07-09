// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.hermes

import io.agents.arya.agent.AgentCallback
import io.agents.arya.agent.AgentConfig
import io.agents.arya.agent.AgentService
import io.agents.arya.agent.llm.LlmResponse
import io.agents.arya.tool.ToolRegistry
import io.agents.arya.tool.ToolResult
import io.agents.arya.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hermes Bridge — connects the Arya APK to a running Hermes Agent instance.
 *
 * Architecture:
 * ┌──────────────┐     HTTP API     ┌──────────────────┐
 * │  Arya APK    │ ←──────────────→ │  Hermes Agent     │
 * │  (UI/Screen) │   localhost      │  (Brain/Memory)   │
 * │  Accessibility│   :8642         │  Skills/Cron/MCP  │
 * │  Notifications│                 │  Web Search       │
 * │  Tools       │                  │  Multi-platform   │
 * └──────────────┘                  └──────────────────┘
 *
 * When Hermes is available, the agent loop runs on Hermes side.
 * When Hermes is not available, falls back to built-in LLM.
 *
 * Setup:
 * 1. Install Termux
 * 2. Install Hermes: curl -fsSL https://hermes-agent.nousresearch.com/install.sh | bash
 * 3. Start Hermes gateway: hermes gateway start --port 8642
 * 4. In Arya Settings, enable "Hermes Bridge" and set the URL
 */
class HermesBridgeService : AgentService {

    companion object {
        private const val TAG = "HermesBridge"
        private val GSON = Gson()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val DEFAULT_HERMES_URL = "http://127.0.0.1:8642"
        private const val HERMES_API_KEY_HEADER = "X-Hermes-API-Key"
    }

    private lateinit var config: AgentConfig
    private var hermesUrl: String = DEFAULT_HERMES_URL
    private var hermesApiKey: String = ""
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private var sessionScope: CoroutineScope? = null

    override fun initialize(config: AgentConfig) {
        this.config = config
        this.hermesUrl = config.hermesUrl ?: DEFAULT_HERMES_URL
        this.hermesApiKey = config.hermesApiKey ?: ""
        this.sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        XLog.i(TAG, "Hermes Bridge initialized: url=$hermesUrl")
    }

    override fun updateConfig(config: AgentConfig) {
        this.config = config
        this.hermesUrl = config.hermesUrl ?: DEFAULT_HERMES_URL
        this.hermesApiKey = config.hermesApiKey ?: ""
    }

    /**
     * Check if Hermes gateway is reachable.
     */
    suspend fun isAvailable(): Boolean {
        return try {
            val request = Request.Builder()
                .url("$hermesUrl/api/health")
                .apply { if (hermesApiKey.isNotBlank()) addHeader(HERMES_API_KEY_HEADER, hermesApiKey) }
                .get()
                .build()
            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            XLog.d(TAG, "Hermes not available: ${e.message}")
            false
        }
    }

    /**
     * Get Hermes gateway info.
     */
    suspend fun getInfo(): String? {
        return try {
            val request = Request.Builder()
                .url("$hermesUrl/api/info")
                .apply { if (hermesApiKey.isNotBlank()) addHeader(HERMES_API_KEY_HEADER, hermesApiKey) }
                .get()
                .build()
            val response = client.newCall(request).execute()
            if (response.isSuccessful) response.body?.string() else null
        } catch (e: Exception) {
            null
        }
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)
        sessionScope?.launch {
            try {
                runHermesTask(userPrompt, callback)
            } catch (e: Exception) {
                if (!cancelled.get()) {
                    XLog.e(TAG, "Hermes task error", e)
                    withContext(Dispatchers.Main) {
                        callback.onError(0, e, 0)
                    }
                }
            } finally {
                running.set(false)
            }
        }
    }

    private suspend fun runHermesTask(userPrompt: String, callback: AgentCallback) {
        withContext(Dispatchers.Main) { callback.onLoopStart(1) }

        // Build the request body
        val requestBody = mapOf(
            "message" to userPrompt,
            "platform" to "arya_android",
            "device_info" to mapOf(
                "model" to android.os.Build.MODEL,
                "brand" to android.os.Build.BRAND,
                "android_version" to android.os.Build.VERSION.RELEASE,
                "language" to "fa"
            )
        )
        val jsonBody = GSON.toJson(requestBody)

        val request = Request.Builder()
            .url("$hermesUrl/api/chat")
            .apply { if (hermesApiKey.isNotBlank()) addHeader(HERMES_API_KEY_HEADER, hermesApiKey) }
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val errorMsg = "Hermes API error: ${response.code} — ${responseBody?.take(200)}"
                XLog.e(TAG, errorMsg)
                withContext(Dispatchers.Main) {
                    callback.onError(1, RuntimeException(errorMsg), 0)
                }
                return
            }

            // Parse Hermes response
            val responseType = object : TypeToken<Map<String, Any?>>() {}.type
            val responseMap: Map<String, Any?> = GSON.fromJson(responseBody, responseType)

            val content = responseMap["content"]?.toString() ?: ""
            val toolCalls = responseMap["tool_calls"] as? List<Map<String, Any?>> ?: emptyList()

            // Process tool calls from Hermes
            for (toolCall in toolCalls) {
                if (cancelled.get()) break

                val toolName = toolCall["name"]?.toString() ?: continue
                val toolParams = toolCall["parameters"] as? Map<String, Any> ?: emptyMap()
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)

                withContext(Dispatchers.Main) {
                    callback.onToolCall(1, toolName, displayName, GSON.toJson(toolParams))
                }

                // Execute tool locally on device
                val result = ToolRegistry.getInstance().executeTool(toolName, toolParams)
                withContext(Dispatchers.Main) {
                    callback.onToolResult(1, toolName, displayName, toolParams.toString(), result)
                }

                // Send result back to Hermes for next step
                val feedbackBody = mapOf(
                    "tool_name" to toolName,
                    "tool_result" to mapOf(
                        "success" to result.isSuccess,
                        "data" to result.data,
                        "error" to result.error
                    ),
                    "session_id" to (responseMap["session_id"] ?: "")
                )
                val feedbackJson = GSON.toJson(feedbackBody)
                val feedbackRequest = Request.Builder()
                    .url("$hermesUrl/api/tool-result")
                    .apply { if (hermesApiKey.isNotBlank()) addHeader(HERMES_API_KEY_HEADER, hermesApiKey) }
                    .post(feedbackJson.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val feedbackResponse = client.newCall(feedbackRequest).execute()
                if (feedbackResponse.isSuccessful) {
                    val feedbackBody = feedbackResponse.body?.string()
                    val feedbackMap: Map<String, Any?> = GSON.fromJson(feedbackBody, responseType)
                    val nextContent = feedbackMap["content"]?.toString() ?: ""
                    if (nextContent.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            callback.onContent(1, nextContent)
                        }
                    }
                }
            }

            // Final response
            withContext(Dispatchers.Main) {
                callback.onContent(1, content)
                callback.onComplete(1, content.ifEmpty { "وظیفه انجام شد" }, 0, "hermes-bridge")
            }

        } catch (e: Exception) {
            if (!cancelled.get()) {
                XLog.e(TAG, "Hermes connection failed", e)
                withContext(Dispatchers.Main) {
                    callback.onError(1, RuntimeException(
                        "ارتباط با هرمس ممکن نیست. مطمئن شو Hermes gateway اجراست: ${e.message}"
                    ), 0)
                }
            }
        }
    }

    override fun cancel() {
        cancelled.set(true)
        XLog.i(TAG, "Hermes task cancelled")
    }

    override fun shutdown() {
        cancel()
        sessionScope?.cancel()
    }

    override fun isRunning(): Boolean = running.get()
}

/**
 * Extended AgentConfig to support Hermes Bridge settings.
 */
data class HermesBridgeConfig(
    val enabled: Boolean = false,
    val url: String = "http://127.0.0.1:8642",
    val apiKey: String = "",
    val fallbackToLocal: Boolean = true,
)
