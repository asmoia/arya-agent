// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Fast open only — no scroll spam. Navigation continues via guided agent steps.

package io.agents.arya.agent.hermes.core

import io.agents.arya.tool.ToolRegistry
import io.agents.arya.tool.ToolResult
import io.agents.arya.utils.XLog

/**
 * Minimal bootstrap: open known apps + one screen read.
 * NEVER runs find_and_tap/scroll here (that caused useless double-scroll then hang).
 */
object HermesBootstrapActions {

    private const val TAG = "HermesBootstrap"

    data class Step(val tool: String, val params: Map<String, Any>, val labelFa: String)

    data class Plan(val steps: List<Step>, val reason: String)

    fun plan(userTask: String): Plan? {
        val t = userTask.lowercase()
        val fa = userTask

        fun openPlan(hint: String, label: String, reason: String) = Plan(
            steps = listOf(
                Step("open_app", mapOf("package_name" to hint), label),
                Step("get_screen_info", emptyMap(), "خواندن صفحه"),
            ),
            reason = reason
        )

        if (t.contains("telegram") || fa.contains("تلگرام") || fa.contains("سیو") ||
            t.contains("saved message") || fa.contains("پیام‌های ذخیره") ||
            fa.contains("اهنگ") || fa.contains("آهنگ") ||
            (fa.contains("پلی") && (fa.contains("تلگرام") || t.contains("telegram"))) ||
            (t.contains("play") && t.contains("telegram")) ||
            (fa.contains("ویس") && fa.contains("تلگرام"))
        ) {
            return openPlan("telegram", "باز کردن تلگرام", "telegram")
        }

        if (t.contains("whatsapp") || fa.contains("واتس") || fa.contains("واتساپ")) {
            return openPlan("whatsapp", "باز کردن واتساپ", "whatsapp")
        }

        if (t.contains("chrome") || t.contains("browser") || fa.contains("کروم") ||
            fa.contains("مرورگر") || t.contains("http") || t.contains("www.") ||
            fa.contains("سرچ") ||
            (fa.contains("جستجو") && (fa.contains("گوگل") || fa.contains("اینترنت") || t.contains("google")))
        ) {
            return openPlan("chrome", "باز کردن مرورگر", "browser")
        }

        if (t.contains("youtube") || fa.contains("یوتیوب")) {
            return openPlan("youtube", "باز کردن یوتیوب", "youtube")
        }

        if (t.contains("spotify") || fa.contains("اسپاتیفای")) {
            return openPlan("spotify", "باز کردن اسپاتیفای", "spotify")
        }

        val openMatch = Regex("""(?:باز کن|بازش کن|open)\s+([^\s،,]+)""", RegexOption.IGNORE_CASE)
            .find(userTask)
        if (openMatch != null) {
            val name = openMatch.groupValues[1]
            if (name.length in 2..40) {
                return openPlan(name, "باز کردن $name", "open:$name")
            }
        }

        return null
    }

    fun execute(step: Step): ToolResult {
        XLog.i(TAG, "bootstrap ${step.tool} ${step.params}")
        return when (step.tool) {
            "open_app" -> {
                val hint = step.params["package_name"]?.toString()
                    ?: step.params["app_name"]?.toString()
                    ?: return ToolResult.error("missing package_name")
                HermesDirectOpen.open(hint)
            }
            else -> {
                // get_screen_info: short fail if no a11y — don't hang
                ToolRegistry.getInstance().executeTool(step.tool, step.params)
            }
        }
    }

    fun isHardStep(step: Step): Boolean = step.tool == "open_app"
}
