package io.agents.arya.tool

import io.agents.arya.agent.llm.ToolSpec
import org.json.JSONObject

/**
 * Tiny on-device models cannot digest the full ToolRegistry.
 * Chat / FunctionGemma get this short list; the agent loop still sees everything.
 */
object PhoneToolset {
    private val CHAT_TOOLS = setOf(
        "open_app",
        "get_device_info",
        "get_notifications",
        "clipboard",
        "system_key",
        "take_screenshot",
        "send_message",
        "get_installed_apps",
        // Long-task messenger tools: read a whole Telegram/WhatsApp chat so the
        // model can summarise it. Previously missing from the chat path entirely.
        "telegram_read_chat",
        "open_messaging_chat",
    )

    fun compactSpecs(): List<ToolSpec> =
        ToolRegistry.getInstance().toToolSpecs().filter { it.name in CHAT_TOOLS }

    fun argsToMap(argsJson: String): Map<String, Any> {
        if (argsJson.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(argsJson)
            val out = mutableMapOf<String, Any>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = o.opt(k) ?: continue
                if (v != JSONObject.NULL) out[k] = v
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }
}
