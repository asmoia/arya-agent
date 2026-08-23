package io.agents.arya.agent.llm

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

enum class CloudDialect {
    OPENAI,
    ANTHROPIC
}

data class CloudConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val dialect: CloudDialect = CloudDialect.OPENAI,
    val temperature: Float = 0.3f,
    val maxTokens: Int = 1024
)

class CloudLlmClient(private val config: CloudConfig) : LlmClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    override fun chatStream(messages: List<ChatMsg>, tools: List<ToolSpec>): Flow<LlmEvent> = callbackFlow {
        val request = try {
            if (config.dialect == CloudDialect.ANTHROPIC) {
                buildAnthropicRequest(messages, tools)
            } else {
                buildOpenAiRequest(messages, tools)
            }
        } catch (e: Exception) {
            trySend(LlmEvent.Error(400, "Failed to build cloud request: ${e.message}"))
            close()
            return@callbackFlow
        }

        var active: Call = client.newCall(request)
        val assembler = StreamAssembler()
        val toolDelta = ToolDeltaAssembler()
        var receivedDelta = false
        var attempt = 0

        fun enqueue(c: Call) {
            active = c
            c.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (CloudRetry.shouldRetry(receivedDelta, attempt, true)) {
                        attempt++
                        enqueue(client.newCall(request))
                        return
                    }
                    trySend(LlmEvent.Error(500, "Cloud service error: ${e.message}"))
                    close()
                }

                override fun onResponse(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: ""
                        trySend(LlmEvent.Error(response.code, "Cloud service error (${response.code}): ${errorBody.take(200)}"))
                        close()
                        return
                    }
                    val body = response.body
                    if (body == null) {
                        trySend(LlmEvent.Error(500, "Empty cloud response"))
                        close()
                        return
                    }
                    try {
                        val reader = BufferedReader(InputStreamReader(body.byteStream()))
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val currentLine = line!!.trim()
                            if (currentLine.isEmpty()) continue
                            if (currentLine.startsWith("data:")) {
                                val data = currentLine.substring(5).trim()
                                val events = if (config.dialect == CloudDialect.ANTHROPIC) {
                                    SseParser.parseAnthropicDataLine(data, assembler, toolDelta)
                                } else {
                                    SseParser.parseOpenAiDataLine(data, assembler, toolDelta)
                                }
                                for (event in events) {
                                    if (event is LlmEvent.Text || event is LlmEvent.ToolCallStart || event is LlmEvent.ToolCall) {
                                        receivedDelta = true
                                    }
                                    trySend(event)
                                }
                            }
                        }
                        toolDelta.finish()?.let { trySend(it) }
                        for (event in assembler.finish()) trySend(event)
                    } catch (e: Exception) {
                        trySend(LlmEvent.Error(500, "Stream parse error: ${e.message}"))
                    } finally {
                        close()
                    }
                }
            })
        }
        enqueue(active)

        awaitClose {
            active.cancel()
        }
    }

    override fun chatSync(messages: List<ChatMsg>, tools: List<ToolSpec>): LlmResponse {
        var textResult = ""
        val toolCallsResult = mutableListOf<ToolCallSpec>()

        // Simple sync wrapper calling stream internally
        val events = mutableListOf<LlmEvent>()
        kotlinx.coroutines.runBlocking {
            chatStream(messages, tools).collect { event ->
                events.add(event)
            }
        }

        for (event in events) {
            when (event) {
                is LlmEvent.Text -> textResult += event.delta
                is LlmEvent.ToolCall -> toolCallsResult.add(
                    ToolCallSpec(id = "cloud_${System.currentTimeMillis()}", name = event.name, argumentsJson = event.argsJson)
                )
                is LlmEvent.Error -> throw IOException(event.message)
                else -> {}
            }
        }

        return LlmResponse(
            text = textResult.ifEmpty { null },
            toolCalls = toolCallsResult,
            modelName = config.model
        )
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
    }

    private fun buildOpenAiRequest(messages: List<ChatMsg>, tools: List<ToolSpec>): Request {
        val url = if (config.baseUrl.endsWith("/chat/completions")) {
            config.baseUrl
        } else {
            "${config.baseUrl.trimEnd('/')}/chat/completions"
        }

        val json = JSONObject().apply {
            put("model", config.model)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("stream", true)

            val msgsArray = JSONArray()
            for (msg in messages) {
                val mObj = JSONObject().apply {
                    put("role", msg.role.name.lowercase())
                    put("content", msg.content)
                    if (msg.toolCallId != null) {
                        put("tool_call_id", msg.toolCallId)
                    }
                }
                msgsArray.put(mObj)
            }
            put("messages", msgsArray)

            if (tools.isNotEmpty()) {
                val toolsArray = JSONArray()
                for (tool in tools) {
                    val tObj = JSONObject().apply {
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tool.name)
                            put("description", tool.descriptionFa)
                            put("parameters", JSONObject(tool.paramsJsonSchema))
                        })
                    }
                    toolsArray.put(tObj)
                }
                put("tools", toolsArray)
            }
        }

        return Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun buildAnthropicRequest(messages: List<ChatMsg>, tools: List<ToolSpec>): Request {
        val url = if (config.baseUrl.endsWith("/messages")) {
            config.baseUrl
        } else {
            "${config.baseUrl.trimEnd('/')}/v1/messages"
        }

        var systemPrompt = ""
        val nonSystemMsgs = mutableListOf<ChatMsg>()
        for (m in messages) {
            if (m.role == Role.SYSTEM) {
                systemPrompt += m.content + "\n"
            } else {
                nonSystemMsgs.add(m)
            }
        }

        val json = JSONObject().apply {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("temperature", config.temperature)
            put("stream", true)
            if (systemPrompt.isNotEmpty()) {
                put("system", systemPrompt.trim())
            }

            val msgsArray = JSONArray()
            for (msg in nonSystemMsgs) {
                val roleStr = if (msg.role == Role.USER) "user" else "assistant"
                msgsArray.put(JSONObject().apply {
                    put("role", roleStr)
                    put("content", msg.content)
                })
            }
            put("messages", msgsArray)

            if (tools.isNotEmpty()) {
                val toolsArray = JSONArray()
                for (tool in tools) {
                    toolsArray.put(JSONObject().apply {
                        put("name", tool.name)
                        put("description", tool.descriptionFa)
                        put("input_schema", JSONObject(tool.paramsJsonSchema))
                    })
                }
                put("tools", toolsArray)
            }
        }

        return Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }
}
