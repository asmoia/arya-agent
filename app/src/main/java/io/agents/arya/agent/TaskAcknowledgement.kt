// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent

/** Immediate acknowledgement; never waits for local-model generation. */
object TaskAcknowledgement {
    fun forTask(task: String, route: PipelineRouter.Route): String {
        val lower = task.lowercase()
        return when {
            TelegramSavedMediaMatcher.matches(task) -> "فهمیدم؛ تلگرام را باز می‌کنم و در پیام‌های ذخیره‌شده دنبال کنترل پخش می‌گردم."
            lower.contains("telegram") || task.contains("تلگرام") -> "فهمیدم؛ task مربوط به تلگرام را انجام می‌دهم."
            lower.contains("browser") || lower.contains("google") || task.contains("مرورگر") || task.contains("گوگل") -> "فهمیدم؛ مرورگر را باز می‌کنم و درخواستت را انجام می‌دهم."
            route is PipelineRouter.Route.DirectTool -> "فهمیدم؛ ${route.description}"
            route is PipelineRouter.Route.DirectIntent -> "فهمیدم؛ ${route.description}"
            else -> "فهمیدم؛ task را مرحله‌به‌مرحله انجام می‌دهم."
        }
    }
}
