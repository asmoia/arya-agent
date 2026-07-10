// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent

/**
 * Compiles explicit, low-risk Persian command chains into bounded tool plans.
 * Unknown or partially understood segments deliberately return null so they can
 * use the normal agent route instead of executing a guessed plan.
 */
object PersianCommandCompiler {
    private val implicitMessage = Regex(
        """^به\s+(.+?)\s+(?:بگو|پیام\s*(?:بده|بفرست)|بنویس)\s+(.+?)$""",
        RegexOption.IGNORE_CASE,
    )

    fun compile(rawTask: String): DeterministicPlan? {
        val normalized = PersianNormalizer.normalize(rawTask)
        val segments = splitTopLevel(normalized)
        if (segments.size < 2) return FastTaskMatchers.match(normalized)?.let { singlePlan(rawTask, it) }

        val steps = mutableListOf<DeterministicPlanStep>()
        var currentApp: String? = null
        for (segment in segments) {
            val direct = FastTaskMatchers.match(segment)
            if (direct != null) {
                steps += DeterministicPlanStep(direct.toolName, direct.params, direct.description)
                if (direct.toolName == "open_app") {
                    currentApp = direct.params["app_name"]?.toString()?.let(PersianNormalizer::canonicalAppName)
                        ?: direct.params["app_name"]?.toString()
                }
                continue
            }

            val message = implicitMessage.matchEntire(segment)
            if (message != null && !currentApp.isNullOrBlank()) {
                val contact = message.groupValues[1].trim()
                val body = message.groupValues[2].trim()
                if (contact.isBlank() || body.isBlank()) return null
                steps += DeterministicPlanStep(
                    toolName = "send_message",
                    params = mapOf("contact" to contact, "message" to body, "app" to currentApp),
                    description = "ارسال پیام به $contact در $currentApp",
                )
                continue
            }
            return null
        }

        if (steps.size < 2) return null
        return DeterministicPlan(
            sourceText = rawTask,
            description = "اجرای ${steps.size} مرحلهٔ مشخص",
            steps = removeRedundantLaunches(steps),
        )
    }

    private fun singlePlan(source: String, match: FastTaskMatchers.ToolMatch): DeterministicPlan =
        DeterministicPlan(
            sourceText = source,
            description = match.description,
            steps = listOf(DeterministicPlanStep(match.toolName, match.params, match.description)),
        )

    private fun removeRedundantLaunches(steps: List<DeterministicPlanStep>): List<DeterministicPlanStep> {
        // send_message/search_browser already own their app launch. Avoid reset
        // when a compiled chain contains an immediately preceding open_app.
        return steps.filterIndexed { index, step ->
            !(step.toolName == "open_app" && steps.getOrNull(index + 1)?.toolName in setOf("send_message", "search_browser"))
        }
    }

    private fun splitTopLevel(text: String): List<String> {
        val separators = listOf(" و بعد ", " بعدش ", " سپس ", " then ", " and ", " و ")
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '"' || ch == '«' || ch == '»' || ch == '\'') {
                quote = if (quote == null) ch else null
                current.append(ch)
                i++
                continue
            }
            if (quote == null) {
                val separator = separators.firstOrNull { text.startsWith(it, i) }
                if (separator != null) {
                    current.toString().trim().takeIf { it.isNotBlank() }?.let(out::add)
                    current.clear()
                    i += separator.length
                    continue
                }
            }
            current.append(ch)
            i++
        }
        current.toString().trim().takeIf { it.isNotBlank() }?.let(out::add)
        return out
    }
}
