// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Agent tool: listen to app voice/messages via notifications + open app UI playbook.

package io.agents.arya.tool.impl

import io.agents.arya.agent.hermes.voice.VoiceListenStore
import io.agents.arya.service.ClawNotificationListener
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolParameter
import io.agents.arya.tool.ToolResult
import org.json.JSONObject

/**
 * How Arya "listens" to Telegram / WhatsApp voice without a spy mic:
 *
 * 1) **Background listen jobs** — Notification Access captures message/voice
 *    notification text (e.g. "Voice message") + sender title.
 * 2) **Foreground play** — open the app, open chat, tap play on the voice bubble
 *    via normal phone tools (open_app / get_screen_info / tap). Android will not
 *    give raw voice bytes of other apps without Accessibility + UI.
 * 3) **Analyze** — after system STT or user-visible transcript, use hermes_voice.
 *
 * This tool manages listen jobs + reads captured events. It does NOT silently
 * record the microphone in the background (by design).
 */
class HermesListenVoiceTool : BaseTool() {

    override fun getName(): String = "listen_voice"

    override fun getDisplayName(): String = "Listen Voice / Messages"

    override fun getDescriptionEN(): String =
        "Background-listen for Telegram/WhatsApp (etc.) message or voice notifications, " +
            "list captured events, or get a playbook to open an app and play a voice message on screen. " +
            "Requires Notification Access. Does not spy with the mic."

    override fun getDescriptionCN(): String =
        "后台监听 Telegram/WhatsApp 等消息/语音通知，列出事件，或给出打开应用播放语音的步骤。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "action",
            "string",
            "start | stop | list_jobs | events | clear_events | playbook",
            true
        ),
        ToolParameter(
            "app",
            "string",
            "telegram | whatsapp | package name | empty=all messaging apps",
            false
        ),
        ToolParameter(
            "contact",
            "string",
            "Optional contact/title filter (e.g. Mom)",
            false
        ),
        ToolParameter(
            "note",
            "string",
            "Why we are listening (for the user)",
            false
        ),
        ToolParameter(
            "job_id",
            "string",
            "Job id for stop",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.lowercase()?.trim()
            ?: return ToolResult.error("action required")

        return try {
            when (action) {
                "start" -> {
                    if (!ClawNotificationListener.isConnected()) {
                        return ToolResult.error(
                            "Notification Access is OFF. Ask the user to enable it in Settings " +
                                "→ Notification Access → آریا. Background listen will not work without it."
                        )
                    }
                    val app = params["app"]?.toString()
                    val pkg = resolvePackage(app)
                    val contact = params["contact"]?.toString()
                    val note = params["note"]?.toString() ?: "listen"
                    val job = VoiceListenStore.addJob(pkg, contact, note)
                    ToolResult.success(
                        JSONObject()
                            .put("status", "listening")
                            .put("job_id", job.id)
                            .put("app_package", job.appPackage ?: "any_messaging")
                            .put("contact_hint", job.contactHint)
                            .put(
                                "how",
                                "Background: captures notifications (including voice-message labels). " +
                                    "To HEAR audio content: open the chat and tap play (use playbook action), " +
                                    "then system STT / hermes_voice on any visible transcript."
                            )
                            .toString(2)
                    )
                }
                "stop" -> {
                    val id = params["job_id"]?.toString()
                    if (id.isNullOrBlank()) {
                        VoiceListenStore.clearJobs()
                        ToolResult.success("All listen jobs stopped")
                    } else {
                        VoiceListenStore.removeJob(id)
                        ToolResult.success("Stopped job $id")
                    }
                }
                "list_jobs" -> {
                    val jobs = VoiceListenStore.listJobs()
                    if (jobs.isEmpty()) ToolResult.success("(no listen jobs)")
                    else ToolResult.success(
                        jobs.joinToString("\n") {
                            "${it.id} | pkg=${it.appPackage ?: "*"} | contact=${it.contactHint ?: "*"} | ${it.note}"
                        }
                    )
                }
                "events" -> {
                    val events = VoiceListenStore.recentEvents(40)
                    if (events.isEmpty()) {
                        ToolResult.success(
                            "No captured events yet. Keep Notification Access on and wait for messages/voice notifications."
                        )
                    } else {
                        ToolResult.success(events.joinToString("\n"))
                    }
                }
                "clear_events" -> {
                    VoiceListenStore.clearEvents()
                    ToolResult.success("Events cleared")
                }
                "playbook" -> {
                    val app = params["app"]?.toString() ?: "telegram"
                    val contact = params["contact"]?.toString() ?: "<contact>"
                    ToolResult.success(playbook(app, contact))
                }
                else -> ToolResult.error("Unknown action: $action")
            }
        } catch (e: Exception) {
            ToolResult.error("listen_voice failed: ${e.message}")
        }
    }

    private fun resolvePackage(app: String?): String? {
        if (app.isNullOrBlank()) return null
        val a = app.lowercase()
        return when {
            a.contains("telegram") || a.contains("تلگرام") -> "org.telegram.messenger"
            a.contains("whatsapp") || a.contains("واتس") -> "com.whatsapp"
            a.contains(".") -> app // already a package
            else -> app
        }
    }

    private fun playbook(app: String, contact: String): String = """
        # Play voice message on screen ($app / $contact)

        Android does not allow reading another app's raw voice file without root.
        Arya operates the UI:

        1. open_app(app_name="$app") wait_after=3000
        2. get_screen_info — find search / chats
        3. Find chat "$contact" (tap search if needed, input_text, tap result)
        4. get_screen_info — find the latest voice bubble (mic waveform / duration)
        5. tap the play control on that bubble
        6. If the app shows a transcript, read it with get_screen_info
        7. hermes_voice(action=analyze_transcript, transcript="...") if text exists
        8. finish(summary=what was said / what you did)

        For BACKGROUND awareness of new voice messages:
        listen_voice(action=start, app="$app", contact="$contact")
        then later listen_voice(action=events)

        Requires: Accessibility + Notification Access. Sensitive actions still need user confirm.
    """.trimIndent()
}
