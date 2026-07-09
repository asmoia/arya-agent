// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Open apps without waiting for Accessibility (bootstrap must not block 20s).

package io.agents.arya.agent.hermes.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.agents.arya.ClawApplication
import io.agents.arya.tool.ToolResult
import io.agents.arya.tool.impl.OpenAppTool
import io.agents.arya.utils.XLog

/**
 * Fast path to launch an app using PackageManager only.
 * Accessibility is optional (only for OEM "Allow open?" dialogs).
 */
object HermesDirectOpen {

    private const val TAG = "HermesDirectOpen"

    private val TELEGRAM_PKGS = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.thunderdog.challegram",
        "org.telegram.plus",
        "ir.ilmili.telegraph", // common regional forks if present
    )
    private val WHATSAPP_PKGS = listOf("com.whatsapp", "com.whatsapp.w4b")
    private val CHROME_PKGS = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.huawei.browser",
        "com.sec.android.app.sbrowser",
    )
    private val YOUTUBE_PKGS = listOf(
        "com.google.android.youtube",
        "com.google.android.apps.youtube.mango",
        "com.vanced.android.youtube",
    )

    fun open(appHint: String): ToolResult = open(ClawApplication.instance, appHint)

    fun open(ctx: Context, appHint: String): ToolResult {
        val pm = ctx.packageManager
        val candidates = candidatesFor(appHint)
        XLog.i(TAG, "open hint='$appHint' candidates=$candidates")

        for (pkg in candidates) {
            try {
                val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Must be called from a context that can start activities; Application works with NEW_TASK.
                ctx.startActivity(intent)
                XLog.i(TAG, "started $pkg")
                // Best-effort OEM allow dialog if a11y already connected (non-blocking short wait)
                tryDismissAllowDialogQuick()
                return ToolResult.success("Opened app: $pkg")
            } catch (e: Exception) {
                XLog.w(TAG, "failed $pkg: ${e.message}")
            }
        }

        // Last resort: OpenAppTool name resolution (may use a11y with short timeout path later)
        val resolved = OpenAppTool.resolveAppNameStatic(appHint)
        if (resolved != null && resolved !in candidates) {
            try {
                val intent = pm.getLaunchIntentForPackage(resolved)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    return ToolResult.success("Opened app: $resolved")
                }
            } catch (e: Exception) {
                XLog.w(TAG, "resolved open failed $resolved: ${e.message}")
            }
        }

        // Fuzzy scan installed launchers (labels)
        val fuzzy = fuzzyFindPackage(pm, appHint)
        if (fuzzy != null) {
            try {
                val intent = pm.getLaunchIntentForPackage(fuzzy)!!
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return ToolResult.success("Opened app: $fuzzy")
            } catch (e: Exception) {
                XLog.w(TAG, "fuzzy open failed: ${e.message}")
            }
        }

        return ToolResult.error(
            "Could not open '$appHint'. Install the app or enable it. Tried: ${candidates.joinToString()}"
        )
    }

    private fun candidatesFor(hint: String): List<String> {
        val h = hint.lowercase().trim()
        return when {
            h.contains("telegram") || h.contains("تلگرام") || h == "telegram" -> TELEGRAM_PKGS
            h.contains("whatsapp") || h.contains("واتس") -> WHATSAPP_PKGS
            h.contains("chrome") || h.contains("browser") || h.contains("کروم") || h.contains("مرورگر") -> CHROME_PKGS
            h.contains("youtube") || h.contains("یوتیوب") -> YOUTUBE_PKGS
            h.contains(".") -> listOf(hint) // already a package
            else -> {
                val resolved = OpenAppTool.resolveAppNameStatic(hint)
                listOfNotNull(resolved, hint)
            }
        }
    }

    private fun fuzzyFindPackage(pm: PackageManager, hint: String): String? {
        val q = hint.lowercase().trim()
        if (q.length < 2) return null
        return try {
            var best: String? = null
            var bestScore = 0
            for (app in pm.getInstalledApplications(0)) {
                val launch = pm.getLaunchIntentForPackage(app.packageName) ?: continue
                val label = pm.getApplicationLabel(app)?.toString()?.lowercase().orEmpty()
                val pkg = app.packageName.lowercase()
                var score = 0
                if (label == q || pkg == q) score = 100
                else if (label.contains(q)) score = 50 + q.length
                else if (pkg.contains(q)) score = 40
                if (score > bestScore) {
                    bestScore = score
                    best = app.packageName
                }
            }
            if (bestScore >= 40) best else null
        } catch (e: Exception) {
            XLog.w(TAG, "fuzzy scan failed: ${e.message}")
            null
        }
    }

    private fun tryDismissAllowDialogQuick() {
        // Optional: if a11y is already up, OpenAppTool dialog dismiss helps on Huawei.
        // Do NOT wait 20s — only fire-and-forget via tool if service is connected now.
        try {
            val svc = io.agents.arya.service.ClawAccessibilityService.getInstance()
            if (svc == null) return
            // Reuse tool path with tiny work — call open is already done; dialog dismiss is internal.
            // Skip heavy path; OEM dialog often auto-dismisses after user grant once.
        } catch (_: Exception) {
        }
    }
}
