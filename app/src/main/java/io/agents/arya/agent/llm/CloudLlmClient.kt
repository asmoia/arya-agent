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

        val call = client.newCall(request)
        val assembler = StreamAssembler()

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                trySend(LlmEvent.Error(500, "خطای ارتباط با سرویس ابری: ${e.message}"))
                close()
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    trySend(LlmEvent.Error(response.code, "خطای سرویس ابری (${response.code}): ${errorBody.take(200)}"))
                    close()
                    return
                }

                val body = response.body
                if (body == null) {
                    trySend(LlmEvent.Error(500, "محتوایی از سرویس دریافت نشد"))
                    close()
                    return
                }

                try {
                    val reader = BufferedReader(InputStreamReader(body.byteStream()))
                    var line: String?

                    while (reader.readLine().also { line = it } != null) {
                        val currentLine = line!!.trim()
                        if (currentLine.isEmpty()) continue

                        if (config.dialect == CloudDialect.ANTHROPIC) {
                            handleAnthropicSseLine(currentLine, assembler, this@callbackFlow::trySend)
                        } else {
                            handleOpenAiSseLine(currentLine, assembler, this@callbackFlow::trySend)
                        }
                    }

                    val finalEvents = assembler.finish()
                    for (event in finalEvents) {
                        trySend(event)
                    }
                } catch (e: Exception) {
                    trySend(LlmEvent.Error(500, "خطای پردازش جریان پاسخ: ${e.message}"))
                } finally {
                    close()
                }
            }
        })

        awaitClose {
            call.cancel()
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
        }

        return Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
    }

    private fun handleOpenAiSseLine(
        line: String,
        assembler: StreamAssembler,
        emitter: (LlmEvent) -> Unit
    ) {
        if (!line.startsWith("data:")) return
        val data = line.substring(5).trim()
        if (data == "[DONE]") return

        try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices") ?: return
            if (choices.length() == 0) return
            val deltaObj = choices.getJSONObject(0).optJSONObject("delta") ?: return

            val content = deltaObj.optString("content", "")
            if (content.isNotEmpty()) {
                val events = assembler.feed(content)
                for (ev in events) emitter(ev)
            }

            val toolCalls = deltaObj.optJSONArray("tool_calls")
            if (toolCalls != null && toolCalls.length() > 0) {
                val tc = toolCalls.getJSONObject(0)
                val func = tc.optJSONObject("function")
                if (func != null) {
                    val name = func.optString("name", "")
                    val args = func.optString("arguments", "")
                    if (name.isNotEmpty()) {
                        emitter(LlmEvent.ToolCallStart(name))
                    }
                    if (args.isNotEmpty()) {
                        emitter(LlmEvent.ToolCallArgsDelta(args))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleAnthropicSseLine(
        line: String,
        assembler: StreamAssembler,
        emitter: (LlmEvent) -> Unit
    ) {
        if (!line.startsWith("data:")) return
        val data = line.substring(5).trim()

        try {
            val json = JSONObject(data)
            val type = json.optString("type")
            if (type == "content_block_delta") {
                val delta = json.optJSONObject("delta")
                if (delta != null && delta.optString("type") == "text_delta") {
                    val text = delta.optString("text", "")
                    if (text.isNotEmpty()) {
                        val events = assembler.feed(text)
                        for (ev in events) emitter(ev)
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
