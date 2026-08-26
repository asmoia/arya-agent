// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.voice

import android.content.Context
import io.agents.arya.ClawApplication
import io.agents.arya.agent.llm.ChatMsg
import io.agents.arya.agent.llm.LlmClient
import io.agents.arya.agent.llm.LlmClientFactory
import io.agents.arya.agent.llm.LlmEvent
import io.agents.arya.agent.llm.ModelReadiness
import io.agents.arya.agent.llm.ModelSession
import io.agents.arya.agent.llm.Role
import io.agents.arya.engine.EngineClient
import io.agents.arya.utils.XLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Streaming voice loop: platform STT -> the currently configured Arya LLM -> TTS.
 *
 * The loop deliberately uses the same model repository as typed chat. It does not
 * claim offline STT: [SpeechPipelineFactory] currently wraps Android SpeechRecognizer,
 * whose backend is device/provider dependent. LLM work runs off the main thread and
 * an in-flight local generation is cancelled before the loop is released.
 */
class VoiceAssistantLoop(private val context: Context) {

    companion object {
        private const val TAG = "VoiceLoop"
        private const val MAX_REPLY_CHARS = 4_000
    }

    private val speech: SpeechPipeline = SpeechPipelineFactory.create(context)
    private val tts: TtsEngine = AndroidTtsEngine(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var responseJob: Job? = null

    @Volatile
    private var activeClient: LlmClient? = null

    @Volatile
    private var localGenerationInFlight = false

    /**
     * Start the voice loop. The callback fires with final recognized text before
     * model dispatch, allowing a host UI to display the transcript immediately.
     */
    fun start(onFinalUserText: (String) -> Unit = {}) {
        XLog.i(TAG, "Voice loop started")
        speech.startListening(
            partial = { /* host UI may render live captions */ },
            onFinal = { userText ->
                if (userText.isBlank()) return@startListening
                onFinalUserText(userText)
                respond(userText)
            },
        )
    }

    private fun respond(userText: String) {
        responseJob?.cancel()
        activeClient?.close()

        responseJob = scope.launch(Dispatchers.IO) {
            val engine = applicationEngineClient()
            val readiness = try {
                ModelSession.resolve(context)
            } catch (e: Exception) {
                XLog.e(TAG, "Unable to resolve voice model readiness", e)
                speakReply("وضعیت مدل صوتی قابل تشخیص نیست. تنظیمات مدل را بررسی کنید.")
                return@launch
            }
            val config = when (readiness) {
                is ModelReadiness.Local -> readiness.config
                is ModelReadiness.Cloud -> readiness.config
                is ModelReadiness.NeedsSetup -> {
                    speakReply(readiness.reason.ifBlank { "ابتدا یک مدل یا سرویس زبانی را تنظیم کنید." })
                    return@launch
                }
            }.copy(
                temperature = 0.2,
                maxIterations = 1,
                streaming = true,
            )
            val client = try {
                LlmClientFactory.create(context, config, engine)
            } catch (e: Exception) {
                XLog.e(TAG, "Unable to create voice LLM client", e)
                speakReply("مدل صوتی در دسترس نیست. ابتدا مدل یا سرویس زبانی را تنظیم کنید.")
                return@launch
            }
            activeClient = client
            localGenerationInFlight = config.isLocalModel

            try {
                val reply = StringBuilder()
                var errorMessage: String? = null
                client.chatStream(
                    messages = listOf(ChatMsg(Role.USER, userText)),
                ).collect { event ->
                    when (event) {
                        is LlmEvent.Text -> if (!event.isReasoning && reply.length < MAX_REPLY_CHARS) {
                            val remaining = MAX_REPLY_CHARS - reply.length
                            reply.append(event.delta.take(remaining))
                        }
                        is LlmEvent.Error -> errorMessage = event.message
                        else -> Unit
                    }
                }

                val text = reply.toString().trim().ifBlank {
                    XLog.w(TAG, "Voice response contained no text: ${errorMessage.orEmpty()}")
                    "مدل پاسخی تولید نکرد. لطفاً تنظیم مدل را بررسی کنید."
                }
                XLog.i(TAG, "Reply: ${text.take(80)}")
                speakReply(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                XLog.e(TAG, "Voice response failed", e)
                speakReply("پاسخ صوتی آماده نشد. لطفاً دوباره تلاش کنید.")
            } finally {
                activeClient = null
                client.close()
                localGenerationInFlight = false
                if (config.isLocalModel) {
                    try {
                        engine?.cancelActive()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    private suspend fun speakReply(text: String) {
        withContext(Dispatchers.Main.immediate) {
            tts.speak(text) { /* playback completed */ }
        }
    }

    private fun applicationEngineClient(): EngineClient? = runCatching {
        val application = context.applicationContext as? ClawApplication
        application?.engineClient ?: ClawApplication.instance.engineClient
    }.getOrNull()

    fun stop() {
        speech.stop()
        responseJob?.cancel()
        responseJob = null
        activeClient?.close()
        activeClient = null
        val shouldCancelLocal = localGenerationInFlight
        localGenerationInFlight = false
        tts.stop()
        if (shouldCancelLocal) {
            scope.launch(Dispatchers.IO) {
                try {
                    applicationEngineClient()?.cancelActive()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun release() {
        stop()
        scope.cancel()
        speech.release()
        tts.release()
    }
}
