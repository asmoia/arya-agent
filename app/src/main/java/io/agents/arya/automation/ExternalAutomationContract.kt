// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.automation

import android.content.Context
import android.content.Intent
import io.agents.arya.utils.XLog
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Public contract for automation apps such as Tasker and MacroDroid.
 *
 * The receiver still requires the user to enable External Automation in
 * Settings before any request is executed.
 */
object ExternalAutomationContract {
    const val ACTION_RUN_TASK = "io.agents.arya.RUN_TASK"
    const val ACTION_RUN_CHAT = "io.agents.arya.RUN_CHAT"

    const val EXTRA_TASK = "task"
    const val EXTRA_CHAT = "chat"
    const val EXTRA_TASK_B64 = "task_b64"
    const val EXTRA_CHAT_B64 = "chat_b64"
    const val EXTRA_REQUEST_ID = "request_id"
    const val EXTRA_RETURN_ACTION = "return_action"
    const val EXTRA_RETURN_PACKAGE = "return_package"

    const val EXTRA_STATUS = "status"
    const val EXTRA_RESULT = "result"
    const val EXTRA_ERROR = "error"
    const val EXTRA_MODE = "mode"

    const val STATUS_ACCEPTED = "accepted"
    const val STATUS_COMPLETED = "completed"
    const val STATUS_FAILED = "failed"
    const val STATUS_CANCELLED = "cancelled"
    const val STATUS_BLOCKED = "blocked"
    const val STATUS_REJECTED = "rejected"

    private const val TAG = "ExternalAutomation"
    const val MAX_TEXT_CHARS = 16_000
    private const val MAX_BASE64_CHARS = 24_000
    private const val MAX_REQUEST_ID_CHARS = 128
    private const val MAX_RETURN_ACTION_CHARS = 128
    private const val MAX_RETURN_PACKAGE_CHARS = 256
    private const val MAX_CALLBACK_ERROR_CHARS = 2_000
    private val ACTION_PATTERN = Regex("^[A-Za-z0-9_.-]{1,$MAX_RETURN_ACTION_CHARS}$")
    private val PACKAGE_PATTERN = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")

    enum class Mode {
        TASK,
        CHAT,
    }

    data class Request(
        val mode: Mode,
        val text: String,
        val requestId: String?,
        val returnAction: String?,
        val returnPackage: String?,
    )

    fun parse(action: String?, extra: (String) -> String?): Request? {
        val mode = when (action) {
            ACTION_RUN_TASK -> Mode.TASK
            ACTION_RUN_CHAT -> Mode.CHAT
            else -> return null
        }

        val task = firstNonBlank(decodeBase64(extra(EXTRA_TASK_B64)), extra(EXTRA_TASK))
        val chat = firstNonBlank(decodeBase64(extra(EXTRA_CHAT_B64)), extra(EXTRA_CHAT))
        val text = when (mode) {
            Mode.TASK -> task ?: chat
            Mode.CHAT -> chat ?: task
        } ?: return null

        return Request(
            mode = mode,
            text = text,
            requestId = boundedMetadata(extra(EXTRA_REQUEST_ID), MAX_REQUEST_ID_CHARS),
            returnAction = boundedMetadata(extra(EXTRA_RETURN_ACTION), MAX_RETURN_ACTION_CHARS)
                ?.takeIf { ACTION_PATTERN.matches(it) },
            returnPackage = boundedMetadata(extra(EXTRA_RETURN_PACKAGE), MAX_RETURN_PACKAGE_CHARS)
                ?.takeIf { PACKAGE_PATTERN.matches(it) },
        )
    }

    fun sendCallback(
        context: Context,
        returnAction: String?,
        requestId: String?,
        status: String,
        result: String? = null,
        error: String? = null,
        returnPackage: String? = null,
        mode: Mode? = null,
    ) {
        val action = returnAction?.trim()
        val packageName = returnPackage?.trim()
        if (action.isNullOrBlank() || packageName.isNullOrBlank()) return
        if (!ACTION_PATTERN.matches(action) || !PACKAGE_PATTERN.matches(packageName)) {
            XLog.w(TAG, "Skipped callback with invalid destination")
            return
        }
        try {
            val callback = Intent(action).apply {
                setPackage(packageName)
                requestId?.take(MAX_REQUEST_ID_CHARS)?.let { putExtra(EXTRA_REQUEST_ID, it) }
                putExtra(EXTRA_STATUS, status)
                mode?.let { putExtra(EXTRA_MODE, it.name.lowercase()) }
                result?.take(MAX_TEXT_CHARS)?.let { putExtra(EXTRA_RESULT, it) }
                error?.take(MAX_CALLBACK_ERROR_CHARS)?.let { putExtra(EXTRA_ERROR, it) }
            }
            context.sendBroadcast(callback)
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to send automation callback", e)
        }
    }

    private fun decodeBase64(value: String?): String? {
        val encoded = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (encoded.length > MAX_BASE64_CHARS) return null
        return try {
            String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
                .trim()
                .takeIf { it.isNotBlank() && it.length <= MAX_TEXT_CHARS }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun firstNonBlank(first: String?, second: String?): String? {
        return listOf(first, second)
            .asSequence()
            .mapNotNull { boundedPayload(it) }
            .firstOrNull()
    }

    private fun boundedPayload(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= MAX_TEXT_CHARS }

    private fun boundedMetadata(value: String?, maxChars: Int): String? = value
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it.length <= maxChars }
}
