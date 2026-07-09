// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Deterministic first actions so tasks don't die waiting on a slow first E4B token.

package io.agents.arya.agent.hermes.core

import io.agents.arya.tool.ToolRegistry
import io.agents.arya.tool.ToolResult
import io.agents.arya.utils.XLog

/**
 * Runs 1–3 tools **without** calling the LLM first when the goal is clear enough.
 * Keeps the agent general: only bootstraps navigation; later steps still use the model.
 */
object HermesBootstrapActions {

    private const val TAG = "HermesBootstrap"

    data class Step(val tool: String, val params: Map<String, Any>, val labelFa: String)

    data class Plan(val steps: List<Step>, val reason: String)

    fun plan(userTask: String): Plan? {
        val t = userTask.lowercase()
        val fa = userTask

        // Telegram family
        if (t.contains("telegram") || fa.contains("تلگرام") || fa.contains("سیو") ||
            t.contains("saved message") || fa.contains("پیام‌های ذخیره")
        ) {
            return Plan(
                steps = listOf(
                    Step("open_app", mapOf("package_name" to "org.telegram.messenger"), "باز کردن تلگرام"),
                    Step("get_screen_info", emptyMap(), "خواندن صفحه تلگرام"),
                ),
                reason = "telegram"
            )
        }

        // WhatsApp
        if (t.contains("whatsapp") || fa.contains("واتس") || fa.contains("واتساپ")) {
            return Plan(
                steps = listOf(
                    Step("open_app", mapOf("package_name" to "com.whatsapp"), "باز کردن واتساپ"),
                    Step("get_screen_info", emptyMap(), "خواندن صفحه واتساپ"),
                ),
                reason = "whatsapp"
            )
        }

        // Browser / search
        if (t.contains("chrome") || t.contains("browser") || fa.contains("کروم") ||
            fa.contains("مرورگر") || fa.contains("سرچ") || t.contains("http") ||
            fa.contains("جستجو") && (fa.contains("گوگل") || fa.contains("اینترنت") || t.contains("google"))
        ) {
            return Plan(
                steps = listOf(
                    Step("open_app", mapOf("package_name" to "com.android.chrome"), "باز کردن کروم"),
                    Step("get_screen_info", emptyMap(), "خواندن صفحه مرورگر"),
                ),
                reason = "browser"
            )
        }

        // YouTube
        if (t.contains("youtube") || fa.contains("یوتیوب")) {
            return Plan(
                steps = listOf(
                    Step("open_app", mapOf("package_name" to "com.google.android.youtube"), "باز کردن یوتیوب"),
                    Step("get_screen_info", emptyMap(), "خواندن صفحه یوتیوب"),
                ),
                reason = "youtube"
            )
        }

        // Generic "open X" — only if a clear app name is present
        val openMatch = Regex(
            """(?:باز کن|بازش کن|open)\s+([^\s،,]+)""",
            RegexOption.IGNORE_CASE
        ).find(userTask)
        if (openMatch != null) {
            val name = openMatch.groupValues[1]
            if (name.length in 2..40) {
                return Plan(
                    steps = listOf(
                        Step("open_app", mapOf("package_name" to name), "باز کردن $name"),
                        Step("get_screen_info", emptyMap(), "خواندن صفحه"),
                    ),
                    reason = "open:$name"
                )
            }
        }

        return null
    }

    fun execute(step: Step): ToolResult {
        XLog.i(TAG, "bootstrap ${step.tool} ${step.params}")
        return ToolRegistry.getInstance().executeTool(step.tool, step.params)
    }
}
