// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Blocks dangerous phone actions until the user explicitly confirms in UI.

package io.agents.arya.agent.hermes.safety

import io.agents.arya.ClawApplication
import io.agents.arya.utils.KVUtils
import io.agents.arya.utils.XLog
import org.json.JSONObject

/**
 * Policy + interactive confirmation for high-risk tools.
 *
 * When a sensitive tool is about to run, [awaitApproval] launches
 * [SensitiveConfirmActivity] and **blocks the agent worker thread** until the
 * user taps Allow / Deny (or timeout).
 *
 * Fail-closed: if the dialog cannot be shown, the tool is denied.
 */
object SensitiveActionGate {

    private const val TAG = "SensitiveGate"
    private const val DEFAULT_TIMEOUT_SEC = 90L

    const val DENIED_MARKER = "needs_confirmation_denied"
    const val TIMEOUT_MARKER = "needs_confirmation_timeout"

    data class Policy(
        val toolName: String,
        val titleFa: String,
        val risk: Risk,
        val alwaysAsk: Boolean = true
    )

    enum class Risk { LOW, MEDIUM, HIGH, CRITICAL }

    private val policies: Map<String, Policy> = listOf(
        Policy("send_message", "ارسال پیام", Risk.HIGH, alwaysAsk = true),
        Policy("make_call", "برقراری تماس", Risk.CRITICAL, alwaysAsk = true),
        Policy("auto_reply", "پاسخ خودکار / مانیتور پیام", Risk.HIGH, alwaysAsk = true),
        Policy("send_file", "ارسال فایل", Risk.HIGH, alwaysAsk = true),
        Policy("clipboard", "نوشتن کلیپ‌بورد", Risk.MEDIUM, alwaysAsk = false),
        Policy("emui_settings", "تغییر تنظیمات گوشی", Risk.MEDIUM, alwaysAsk = false),
        Policy("system_key", "کلید سیستم (Power)", Risk.MEDIUM, alwaysAsk = false),
        Policy("input_text", "تایپ متن در صفحه", Risk.LOW, alwaysAsk = false),
        Policy("hermes_memory", "نوشتن حافظه Hermes", Risk.LOW, alwaysAsk = false),
        Policy("hermes_skill", "تغییر مهارت Hermes", Risk.LOW, alwaysAsk = false),
    ).associateBy { it.toolName }

    fun isSensitive(toolName: String): Boolean = policies.containsKey(toolName)

    /**
     * @return null if allowed to proceed, otherwise a deny reason for ToolResult.error
     */
    fun awaitApproval(toolName: String, params: Map<String, Any>): String? {
        if (!KVUtils.isSensitiveConfirmEnabled()) {
            XLog.d(TAG, "confirm disabled — allowing $toolName")
            return null
        }
        val policy = policies[toolName] ?: return null

        if (!policy.alwaysAsk &&
            policy.risk == Risk.LOW &&
            KVUtils.isSensitiveAutoAllowLowRisk()
        ) {
            XLog.d(TAG, "auto-allow low-risk $toolName")
            return null
        }

        if (toolName == "clipboard") {
            val action = params["action"]?.toString()?.lowercase().orEmpty()
            if (action != "set" && action != "write" && action != "copy") return null
        }
        if (toolName == "hermes_memory") {
            val action = params["action"]?.toString()?.lowercase().orEmpty()
            if (action in setOf("read", "search", "")) return null
        }
        if (toolName == "hermes_skill") {
            val action = params["action"]?.toString()?.lowercase().orEmpty()
            if (action in setOf("list", "get", "match", "")) return null
        }
        if (toolName == "system_key") {
            val key = params["key"]?.toString()?.lowercase().orEmpty()
            if (key != "power" && key != "power_long") return null
        }

        val summary = humanSummary(toolName, params)
        val message = buildString {
            appendLine("آریا می‌خواهد این کار را انجام دهد:")
            appendLine()
            appendLine("• ${policy.titleFa} (`$toolName`)")
            appendLine("• سطح خطر: ${policy.risk.name}")
            appendLine()
            appendLine(summary)
            appendLine()
            append("اگر مطمئن نیستی، «رد کردن» را بزن.")
        }

        XLog.i(TAG, "awaitApproval tool=$toolName")
        // Must NOT run on main thread (would deadlock with activity UI).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            XLog.e(TAG, "awaitApproval called on main thread — denying $toolName")
            return "$DENIED_MARKER: internal error (main thread)"
        }

        val result = SensitiveConfirmActivity.request(
            context = ClawApplication.instance,
            title = "تأیید کار حساس",
            message = message,
            timeoutSec = DEFAULT_TIMEOUT_SEC
        )

        return when (result) {
            true -> {
                XLog.i(TAG, "user ALLOW $toolName")
                null
            }
            false -> {
                XLog.i(TAG, "user DENY $toolName")
                "$DENIED_MARKER: کاربر اجازه نداد — $toolName اجرا نشد."
            }
            null -> {
                XLog.w(TAG, "timeout/fail $toolName")
                "$TIMEOUT_MARKER: کاربر در ${DEFAULT_TIMEOUT_SEC}s تأیید نکرد — $toolName لغو شد."
            }
        }
    }

    private fun humanSummary(toolName: String, params: Map<String, Any>): String {
        fun p(key: String): String = params[key]?.toString()?.take(200).orEmpty()
        return when (toolName) {
            "send_message" ->
                "مخاطب: ${p("contact").ifBlank { "؟" }}\nاپ: ${p("app").ifBlank { "؟" }}\nمتن: ${p("message").ifBlank { p("text") }}"
            "make_call" -> "شماره/مخاطب: ${p("number").ifBlank { p("contact") }}"
            "auto_reply" -> "action=${p("action")} contact=${p("contact")}"
            "send_file" -> "path=${p("path")} contact=${p("contact")}"
            "clipboard" -> "action=${p("action")} text=${p("text").take(80)}"
            "emui_settings" -> "action=${p("action")} level=${p("level")}"
            "system_key" -> "key=${p("key")}"
            "input_text" -> "text=${p("text").take(120)}"
            "hermes_memory" -> "action=${p("action")} text=${p("text").take(120)}"
            "hermes_skill" -> "action=${p("action")} id=${p("id")}"
            else -> try {
                val obj = JSONObject()
                for ((k, v) in params) obj.put(k, v?.toString() ?: "")
                obj.toString().take(300)
            } catch (_: Exception) {
                params.toString().take(300)
            }
        }
    }
}
