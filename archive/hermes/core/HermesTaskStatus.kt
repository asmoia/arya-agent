// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Live task status for UI (action-first agent transparency).

package io.agents.arya.agent.hermes.core

/**
 * Phases shown while a task runs. Not a timeout — continuous visibility.
 */
enum class HermesPhase {
    QUEUED,
    PLANNING,
    OBSERVING,
    ACTING,
    VERIFYING,
    CONTINUING,
    DONE,
    CANCELLED,
    FAILED,
    NEED_USER
}

data class HermesStatusUpdate(
    val phase: HermesPhase,
    val round: Int,
    val maxRounds: Int,
    val toolName: String? = null,
    val messageFa: String,
    val messageEn: String = messageFa,
    val memNote: String? = null
) {
    fun displayFa(): String = buildString {
        if (round > 0 && maxRounds > 0) append("[$round/$maxRounds] ")
        append(messageFa)
        memNote?.let { append(" · ").append(it) }
    }
}

object HermesStatusMessages {
    fun planning(round: Int, max: Int) = HermesStatusUpdate(
        HermesPhase.PLANNING, round, max,
        messageFa = "برنامه‌ریزی اقدام…"
    )

    fun observing(round: Int, max: Int) = HermesStatusUpdate(
        HermesPhase.OBSERVING, round, max, "get_screen_info",
        messageFa = "خواندن صفحه…"
    )

    fun acting(round: Int, max: Int, tool: String, detail: String = "") = HermesStatusUpdate(
        HermesPhase.ACTING, round, max, tool,
        messageFa = "اقدام: ${toolLabelFa(tool)}" + if (detail.isNotBlank()) " ($detail)" else ""
    )

    fun verifying(round: Int, max: Int) = HermesStatusUpdate(
        HermesPhase.VERIFYING, round, max,
        messageFa = "بررسی نتیجه روی صفحه…"
    )

    fun continuing(round: Int, max: Int, reason: String) = HermesStatusUpdate(
        HermesPhase.CONTINUING, round, max,
        messageFa = "ادامه: $reason"
    )

    fun done(summary: String) = HermesStatusUpdate(
        HermesPhase.DONE, 0, 0,
        messageFa = summary.take(200)
    )

    fun toolLabelFa(tool: String): String = when (tool) {
        "open_app" -> "باز کردن اپ"
        "get_screen_info" -> "خواندن صفحه"
        "find_and_tap", "tap", "tap_node" -> "لمس"
        "input_text" -> "تایپ"
        "swipe", "scroll_to_find" -> "اسکرول"
        "system_key" -> "کلید سیستم"
        "finish" -> "پایان"
        "send_message" -> "ارسال پیام"
        "wait" -> "صبر"
        else -> tool
    }
}
