// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Deterministic first actions so tasks don't die waiting on a slow first E4B token.

package io.agents.arya.agent.hermes.core

import io.agents.arya.tool.ToolRegistry
import io.agents.arya.tool.ToolResult
import io.agents.arya.utils.XLog

/**
 * Runs 1–3 tools **without** calling the LLM first when the goal is clear enough.
 * open_app uses [HermesDirectOpen] (no 20s Accessibility wait).
 */
object HermesBootstrapActions {

    private const val TAG = "HermesBootstrap"

    data class Step(val tool: String, val params: Map<String, Any>, val labelFa: String)

    data class Plan(val steps: List<Step>, val reason: String)

    fun plan(userTask: String): Plan? {
        val t = userTask.lowercase()
        val fa = userTask

        if (t.contains("telegram") || fa.contains("تلگرام") || fa.contains("سیو") ||
            t.contains("saved message") || fa.contains("پیام‌های ذخیره") ||
            fa.contains("اهنگ") || fa.contains("آهنگ") ||
            (fa.contains("پلی") && (fa.contains("تلگرام") || t.contains("telegram"))) ||
            (t.contains("play") && t.contains("telegram")) ||
            fa.contains("ویس") && fa.contains("تلگرام")
        ) {
            val steps = mutableListOf(
                Step("open_app", mapOf("package_name" to "telegram"), "باز کردن تلگرام"),
                Step("get_screen_info", emptyMap(), "خواندن صفحه تلگرام"),
            )
            // Soft UI probes (fail OK) — saves a whole LLM round when labels match.
            if (fa.contains("سیو") || t.contains("saved") || fa.contains("ذخیره")) {
                steps += Step(
                    "find_and_tap",
                    mapOf("text" to "Saved Messages", "max_scrolls" to 3),
                    "تلاش: Saved Messages"
                )
                steps += Step(
                    "find_and_tap",
                    mapOf("text" to "پیام‌های ذخیره‌شده", "max_scrolls" to 3),
                    "تلاش: پیام‌های ذخیره‌شده"
                )
            }
            if (fa.contains("جستجو") || t.contains("search") || fa.contains("سرچ")) {
                steps += Step("find_and_tap", mapOf("text" to "Search", "max_scrolls" to 2), "تلاش: Search")
            }
            return Plan(steps = steps, reason = "telegram")
        }

        if (t.contains("whatsapp") || fa.contains("واتس") || fa.contains("واتساپ")) {
            return Plan(
                steps = listOf(
                    Step("open_app", mapOf("package_name" to "whatsapp"), "باز کردن واتساپ"),
                    Step("get_screen_info", emptyMap(), "خواندن صفحه واتساپ"),
                ),
                reason = "whatsapp"
            )
        }

        if (t.contains("chrome") || t.contains("browser") || fa.contains("کروم") ||
            fa.contains("مرورگر") || t.contains("http") || t.contains("www.") ||
            (fa.contains("جستجو") && (fa.contains("گوگل") || fa.contains("اینترنت") || t.contains("google"))) ||
            fa.contains("سرچ")
        ) {
            return Plan(
                steps = listOf(
                    Step("open_app", mapOf("package_name" to "chrome"), "باز کردن مرورگر"),
                    Step("get_screen_info", emptyMap(), "خواندن صفحه مرورگر"),
                ),
                reason = "browser"
            )
        }

        if (t.contains("youtube") || fa.contains("یوتیوب")) {
            return Plan(
                steps = listOf(
                    Step("open_app", mapOf("package_name" to "youtube"), "باز کردن یوتیوب"),
                    Step("get_screen_info", emptyMap(), "خواندن صفحه یوتیوب"),
                ),
                reason = "youtube"
            )
        }

        if (t.contains("spotify") || fa.contains("اسپاتیفای") || fa.contains("اسپاتیفای")) {
            return Plan(
                steps = listOf(
                    Step("open_app", mapOf("package_name" to "spotify"), "باز کردن اسپاتیفای"),
                    Step("get_screen_info", emptyMap(), "خواندن صفحه"),
                ),
                reason = "spotify"
            )
        }

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

        // Generic phone task with no known app: still do get_screen_info so UI moves
        if (fa.contains("برو") || fa.contains("باز") || t.contains("open") || t.contains("go ")) {
            return Plan(
                steps = listOf(
                    Step("get_screen_info", emptyMap(), "خواندن صفحه فعلی"),
                ),
                reason = "screen-first"
            )
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
            "find_and_tap" -> {
                // Soft: never abort bootstrap if label missing on this screen.
                val r = ToolRegistry.getInstance().executeTool(step.tool, step.params)
                if (r.isSuccess) r else ToolResult.success("skip: ${r.error ?: "not found"}")
            }
            else -> ToolRegistry.getInstance().executeTool(step.tool, step.params)
        }
    }

    /** Soft steps must not abort the bootstrap chain. */
    fun isHardStep(step: Step): Boolean = step.tool == "open_app"
}
