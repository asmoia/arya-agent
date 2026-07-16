// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent

import io.agents.arya.utils.XLog

/**
 * PHASE 3 — Classification + escalation policy.
 *
 * Classification is keyword/structure-based (fast, on-device, no extra model call).
 * Escalation triggers decide when to bump a struggling LOCAL task to CLOUD
 * (only if the user opted in).
 */
object RoutingPolicy {

    // Routine: explicit device/app intents -> fast deterministic/local path.
    private val ROUTINE = listOf(
        "باز کن", "باز کن", "تلگرام", "واتس", "اینستا", "یوتیوب", "مرورگر", "کروم",
        "باتری", "وای‌فای", "بلوتوث", "حافظه", "اعلان", "کلیپبورد", "تنظیمات",
        "open", "telegram", "whatsapp", "youtube", "browser", "battery", "wifi",
        "bluetooth", "notification", "clipboard", "settings", "home", "back"
    )

    // Cloud: heavy reasoning / cross-app synthesis.
    private val CLOUD = listOf(
        "مقایسه", "خلاصه کن", "تحلیل", "بنویس",
        "translate", "summarize", "compare", "analyze", "write", "research",
        "طولانی", "چند مرحله"
    )

    // Visual: ambiguous GUI navigation (no explicit app/element named).
    private val VISUAL = listOf(
        "پیدا کن", "برو تو", "باز کن و", "پیدا کردن", "توی تنظیمات",
        "find", "navigate", "open and", "go to", "inside"
    )

    /**
     * Classify a task into a routing tier.
     * - ROUTINE: deterministic phone tasks with explicit targets
     * - VISUAL: ambiguous GUI tasks that need screen-aware navigation
     * - CLOUD: heavy reasoning tasks that benefit from larger models
     */
    fun classify(task: String): Tier {
        val t = task.lowercase()
        return when {
            CLOUD.any { t.contains(it) } -> Tier.CLOUD
            VISUAL.any { t.contains(it) } -> Tier.VISUAL
            ROUTINE.any { t.contains(it) } -> Tier.ROUTINE
            else -> Tier.VISUAL // default: ambiguous -> visual agent is safest
        }
    }

    /**
     * Decide whether a struggling local task should escalate to cloud.
     * Triggers: repeated tool-parse failures / schema violations / no tool call
     * when one was clearly needed.
     */
    fun shouldEscalate(
        cloudOptIn: Boolean,
        consecutiveToolFailures: Int,
        noToolCallStreak: Int
    ): Boolean {
        if (!cloudOptIn) return false
        val escalate = consecutiveToolFailures >= 2 || noToolCallStreak >= 3
        if (escalate) {
            io.agents.arya.utils.XLog.i("RoutingPolicy", "escalate local->cloud (fails=$consecutiveToolFailures, noTool=$noToolCallStreak)")
        }
        return escalate
    }
}
