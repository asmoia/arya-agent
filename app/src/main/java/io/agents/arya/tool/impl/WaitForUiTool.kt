// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.tool.impl

import android.view.accessibility.AccessibilityNodeInfo
import io.agents.arya.service.ClawAccessibilityService
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolParameter
import io.agents.arya.tool.ToolResult
import io.agents.arya.tool.UiWaitEngine

/** Event-driven alternative to fixed sleeps after launch/navigation. */
class WaitForUiTool : BaseTool() {
    override fun getName() = "wait_for_ui"
    override fun getDescriptionEN() = "Wait until a package, visible text, or editable field appears. Prefer this to a fixed sleep when a UI condition is known."
    override fun getDescriptionCN() = getDescriptionEN()
    override fun getParameters() = listOf(
        ToolParameter("condition", "string", "package_active | text_visible | editable_visible", true),
        ToolParameter("value", "string", "Package name or text for the condition", false),
        ToolParameter("timeout_ms", "integer", "Timeout in milliseconds, default 1500, max 5000", false),
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val service = requireAccessibilityService(1_000L)
            ?: return ToolResult.error("Accessibility service is not running")
        val condition = params["condition"]?.toString()?.lowercase().orEmpty()
        val value = params["value"]?.toString().orEmpty()
        val timeout = (params["timeout_ms"] as? Number)?.toLong()
            ?: params["timeout_ms"]?.toString()?.toLongOrNull()
            ?: 1_500L
        val result = UiWaitEngine.until(timeout.coerceIn(100L, 5_000L)) {
            val root = service.rootInActiveWindow ?: return@until false
            when (condition) {
                "package_active" -> root.packageName?.toString() == value
                "text_visible" -> service.findNodesByText(value).any { it.isVisibleToUser }
                "editable_visible" -> hasEditable(root)
                else -> false
            }
        }
        return if (result.satisfied) ToolResult.success("UI condition '$condition' satisfied in ${result.elapsedMs}ms")
        else ToolResult.error("Timed out waiting for UI condition '$condition' after ${result.elapsedMs}ms")
    }

    private fun hasEditable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        if (node.isVisibleToUser && node.isEditable) return true
        for (i in 0 until node.childCount) if (hasEditable(node.getChild(i))) return true
        return false
    }
}
