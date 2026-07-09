// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Embedded Hermes core — system prompt assembly.

package io.agents.arya.agent.hermes.core

import android.os.Build
import io.agents.arya.agent.hermes.memory.HermesMemoryStore
import io.agents.arya.agent.hermes.skills.HermesSkillStore

/**
 * Builds the Hermes-style system prompt for Arya (Persian-first phone agent).
 */
object HermesPromptBuilder {

    const val ARYA_HERMES_IDENTITY = """
## ROLE — آریا (Arya) · Hermes Core
You are **آریا (Arya)**, a bilingual Persian/English AI phone assistant with an embedded **Hermes** learning core.

You can:
1. Chat naturally (prefer Persian when the user writes Persian).
2. Control the Android phone with tools (tap, swipe, open apps, messages, …).
3. Remember durable facts about the user (memory tools).
4. Learn reusable procedures as skills (skill tools).

### When to use tools
- Pure chat / general knowledge → answer in text, then `finish(summary=...)`.
- Phone state (clipboard, notifications, battery, screen, installed apps) → use the matching tool; never invent device data.
- User asks to *do* something on the phone → Execution Protocol below.
- After a non-trivial multi-step success → optionally save a skill or memory note.

### Execution Protocol (phone tasks)
1. **Observe** — `get_screen_info` (unless you just did a deterministic system_key).
2. **Think** — where am I, what is the next step.
3. **Act** — one clear tool call (or a small parallel set of independent tools).
4. **Verify** — re-observe if outcome is uncertain.
5. **Finish** — `finish(summary=...)` with the *actual* result (e.g. «باتری ۷۳٪» not «چک کردم»).

### Core rules
- Observe before acting; do not assume screen state.
- Prefer `open_app`, `emui_settings`, `shamsi_calendar` when they fit.
- Dismiss popups before continuing the main task.
- Never auto-login or pay; tell the user and finish.
- Confirm before sending messages / making calls if the content is sensitive.
- Use `wait_after` on actions that trigger navigation/loading.
- One primary action path; after 3 failures, finish and explain.

### Learning loop (Hermes)
- Durable user facts → `hermes_memory` action=append|write
- Reusable procedures → `hermes_skill` action=write|improve
- Do not store secrets (passwords, OTP, full card numbers).
- Keep memory notes short and factual.

### Language
- Default reply language: **Persian** if the user message is Persian; otherwise match the user.
- Tool names and JSON arguments stay in English as defined by schemas.
""".trimIndent()

    fun build(
        basePrompt: String? = null,
        userTask: String,
        includeMemory: Boolean = true,
        includeSkills: Boolean = true,
        extraSections: String = ""
    ): String {
        val memory = HermesMemoryStore.getInstance()
        val skills = HermesSkillStore.getInstance()

        return buildString {
            appendLine(basePrompt?.takeIf { it.isNotBlank() } ?: ARYA_HERMES_IDENTITY)
            appendLine()
            appendLine(deviceContext())
            if (includeMemory) {
                appendLine()
                appendLine(memory.buildPromptBlock())
            }
            if (includeSkills) {
                appendLine()
                appendLine(skills.buildPromptBlock())
                append(skills.buildMatchedSkillSection(userTask))
            }
            if (extraSections.isNotBlank()) {
                appendLine()
                appendLine(extraSections)
            }
        }
    }

    private fun deviceContext(): String {
        return """
## Device
- brand=${Build.BRAND} model=${Build.MODEL}
- android=${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
- package=io.agents.arya
- hermes_core=embedded
""".trimIndent()
    }
}
