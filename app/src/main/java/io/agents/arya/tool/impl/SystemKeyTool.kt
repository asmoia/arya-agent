// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.tool.impl

import io.agents.arya.ClawApplication
import io.agents.arya.R
import io.agents.arya.service.ClawAccessibilityService
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolParameter
import io.agents.arya.tool.ToolResult
import io.agents.arya.utils.XLog

import java.util.Collections
import java.util.List

/**
 * System key tool with reliable fallback.
 *
 * Fixes the "key failed" / "system key failed" PM report:
 *  1. Waits briefly for accessibility-service rebind before giving up
 *     (ClawAccessibilityService.getConnectedInstance with REBIND_WAIT_MS).
 *  2. Calls the *Reliable* variants (performGlobalAction -> gesture fallback)
 *     defined in ClawAccessibilityService.
 *  3. Returns MODEL-ACTIONABLE, clearly tagged errors so the agent can either
 *     ask the user to re-enable Accessibility, or pick an alternative action
 *     instead of blind-retrying and looping.
 */
class SystemKeyTool : BaseTool() {

    companion object {
        private const val TAG = "SystemKeyTool"
        /** Wait up to 2.5s for a transient rebind (under RAM pressure) */
        private const val REBIND_WAIT_MS = 2500L
    }

    override fun getName(): String = "system_key"

    override fun getDisplayName(): String =
        ClawApplication.getInstance().getString(R.string.tool_name_system_key)

    override fun getDescriptionEN(): String =
        "Press a system key. Supported keys: back (navigate back), home (go to home screen), " +
        "recent_apps (open task switcher), notifications (expand notification shade), " +
        "collapse_notifications (collapse notification/quick settings), " +
        "lock_screen (lock screen, Android 9+), unlock_screen (wake up and unlock screen), " +
        "enter (press Enter/submit), tab (press Tab)."

    override fun getDescriptionCN(): String =
        "Press a system key. Supported keys: back (go back), home (go to home screen), " +
        "recent_apps (open recent tasks), notifications (expand notification bar), " +
        "collapse_notifications (collapse notification bar/quick settings), " +
        "lock_screen (lock screen, requires Android 9+), unlock_screen (wake and unlock screen), " +
        "enter (press Enter/confirm), tab (press Tab)."

    override fun getParameters(): List<ToolParameter> =
        Collections.singletonList(
            ToolParameter(
                "key",
                "string",
                "The system key to press. Must be one of: back, home, recent_apps, " +
                "notifications, collapse_notifications, lock_screen, unlock_screen, enter, tab.",
                true
            )
        )

    override fun execute(params: Map<String, Any?>): ToolResult {
        // 1) Try to obtain a CONNECTED service; wait briefly for rebind.
        val service = ClawAccessibilityService.getConnectedInstance(REBIND_WAIT_MS)
        if (service == null) {
            val enabled = ClawAccessibilityService.isEnabledInSettings(ClawApplication.getInstance())
            return if (enabled) {
                // Service is enabled in Settings but not attached yet -> transient.
                ToolResult.error(
                    "ACCESSIBILITY_REBINDING: Arya's accessibility service is enabled but not " +
                    "attached yet. Retry once after a moment; if it keeps failing, ask the user " +
                    "to toggle Arya off/on in Settings > Accessibility."
                )
            } else {
                // Hard failure: user must enable it.
                ToolResult.error(
                    "ACCESSIBILITY_DISABLED: Arya's accessibility service is OFF. Ask the user " +
                    "to open Settings > Accessibility > Arya and enable it, then retry. " +
                    "Do NOT loop on system_key."
                )
            }
        }

        val key = try {
            requireString(params, "key")
        } catch (e: IllegalArgumentException) {
            return ToolResult.error("Missing required parameter: key")
        }

        val success: Boolean
        val successMsg: String

        when (key) {
            "back" -> {
                success = service.pressBackReliable()
                successMsg = "Pressed Back button"
            }
            "home" -> {
                success = service.pressHomeReliable()
                successMsg = "Pressed Home button"
            }
            "recent_apps" -> {
                success = service.openRecentAppsReliable()
                successMsg = "Opened recent apps"
            }
            "notifications" -> {
                success = service.expandNotificationsReliable()
                successMsg = "Expanded notifications"
            }
            "collapse_notifications" -> {
                success = service.collapseNotificationsReliable()
                successMsg = "Collapsed notifications"
            }
            "lock_screen" -> {
                success = service.lockScreen()
                successMsg = "Screen locked"
            }
            "unlock_screen" -> {
                success = service.unlockScreen()
                successMsg = "Screen unlock requested"
            }
            "enter" -> {
                success = try {
                    Runtime.getRuntime()
                        .exec(arrayOf("input", "keyevent", android.view.KeyEvent.KEYCODE_ENTER.toString()))
                        .waitFor()
                    true
                } catch (e: Exception) {
                    false
                }
                successMsg = "Pressed Enter key"
            }
            "tab" -> {
                success = try {
                    Runtime.getRuntime()
                        .exec(arrayOf("input", "keyevent", android.view.KeyEvent.KEYCODE_TAB.toString()))
                        .waitFor()
                    true
                } catch (e: Exception) {
                    false
                }
                successMsg = "Pressed Tab key"
            }
            else -> return ToolResult.error(
                "Unknown system key: $key. Must be one of: back, home, recent_apps, notifications, " +
                "collapse_notifications, lock_screen, unlock_screen, enter, tab."
            )
        }

        return if (success) {
            ToolResult.success(successMsg)
        } else {
            // Tagged, model-actionable failure (this is what the PM saw as "key failed").
            XLog.w(TAG, "system_key($key) failed on device")
            ToolResult.error(
                "KEY_ACTION_FAILED: system_key($key) could not be executed on this device. " +
                "If this is 'back' or 'home', prefer an alternative action (e.g. open_app, or finish) " +
                "rather than retrying system_key."
            )
        }
    }
}
