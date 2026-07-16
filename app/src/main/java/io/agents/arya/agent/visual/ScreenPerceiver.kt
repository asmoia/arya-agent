// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.visual

/**
 * PHASE 2 — Perception module for the visual control agent.
 *
 * Builds a structured view of the current screen from the existing Accessibility
 * UI tree (ClawAccessibilityService.getScreenTree), which yields:
 *   [n1] "text" tap edit (cx,cy)
 *
 * This is better than screenshot+OCR for normal apps and avoids the OEM
 * fragility of pixel-based parsing.
 * Optional: add an OmniParser-style visual detector later for canvas/game UIs
 * where the a11y tree is sparse.
 *
 * The produced [ScreenState] is what the LLM "sees" instead of raw pixels.
 */

/**
 * A single UI element on the screen.
 */
data class UiElement(
    val id: String,        // e.g. "n3"
    val text: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val cx: Int,
    val cy: Int
)

/**
 * Structured representation of the current screen state.
 */
data class ScreenState(
    val elements: List<UiElement>,
    val rawTree: String,
    val screenshotPath: String? = null
) {
    /** Compact, model-friendly description. */
    fun describe(): String = buildString {
        for (e in elements) {
            append("[${e.id}] \"${e.text.take(40)}\"")
            if (e.clickable) append(" tap")
            if (e.editable) append(" edit")
            if (e.scrollable) append(" scroll")
            append(" (${e.cx},${e.cy})\n")
        }
    }

    /** Stable signature used by [ActionReflector] to detect change. */
    fun signature(): String = elements.joinToString("|") { "${it.id}:${it.text}" }
}

/**
 * Reads the current screen via the Accessibility service.
 */
class ScreenPerceiver {

    companion object {
        private const val TAG = "ScreenPerceiver"
    }

    /**
     * Perceive the current screen. Returns null if the accessibility service
     * is not connected or the screen cannot be read (e.g. locked/off).
     */
    fun perceive(): ScreenState? {
        val service = io.agents.arya.service.ClawAccessibilityService
            .getConnectedInstance(1500)
            ?: run {
                io.agents.arya.utils.XLog.w(TAG, "No accessibility service connected")
                return null
            }

        // Access Java getter as Kotlin property
        @Suppress("USELESS_ELVIS")
        val tree: String? = service.screenTree ?: run {
            io.agents.arya.utils.XLog.w(TAG, "screenTree null (screen may be off / locked)")
            return null
        }

        val elements = parseTree(tree!!)
        return ScreenState(elements, tree)
    }

    /**
     * Parse the screen tree string into structured UiElement objects.
     * Format: [n1] "text" tap edit (cx,cy)
     */
    private fun parseTree(tree: String): List<UiElement> {
        val out = mutableListOf<UiElement>()
        // Format: [n1] "text" tap edit (cx,cy)
        val regex = Regex("""\[(\w+)\]\s*"([^"]*)"\s*([a-z ]*?)\s*\((\d+),(\d+)\)""")
        for (m in regex.findAll(tree)) {
            val (id, text, flags, cx, cy) = m.destructured
            out.add(
                UiElement(
                    id = id,
                    text = text,
                    clickable = "tap" in flags,
                    editable = "edit" in flags,
                    scrollable = "scroll" in flags,
                    cx = cx.toIntOrNull() ?: 0,
                    cy = cy.toIntOrNull() ?: 0
                )
            )
        }
        return out
    }
}
