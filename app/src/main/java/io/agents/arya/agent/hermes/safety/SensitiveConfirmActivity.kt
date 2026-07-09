// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent.hermes.safety

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import io.agents.arya.utils.XLog
import io.agents.arya.widget.ConfirmDialog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Transparent activity that hosts [ConfirmDialog] for sensitive tool approval.
 * Can be launched from a background agent thread with NEW_TASK.
 */
class SensitiveConfirmActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep screen on while waiting for user decision
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "تأیید کار حساس"
        val message = intent.getStringExtra(EXTRA_MESSAGE) ?: ""
        val pending = pendingRequests[requestId]
        if (pending == null) {
            XLog.w(TAG, "No pending request for id=$requestId")
            finish()
            return
        }

        ConfirmDialog.showWarm(
            context = this,
            title = title,
            message = message,
            actionTitle = "اجازه بده",
            cancelTitle = "رد کردن",
            isDismissible = false,
            onAction = {
                pending.complete(true)
                finish()
            },
            onCancel = {
                pending.complete(false)
                finish()
            }
        )
    }

    override fun onBackPressed() {
        // Back = deny
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        pendingRequests[requestId]?.complete(false)
        super.onBackPressed()
    }

    companion object {
        private const val TAG = "SensitiveConfirmAct"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_MESSAGE = "message"

        private val pendingRequests = ConcurrentHashMap<String, Pending>()

        data class Pending(
            val latch: CountDownLatch = CountDownLatch(1),
            private val allowed: AtomicBoolean = AtomicBoolean(false),
            private val completed: AtomicBoolean = AtomicBoolean(false)
        ) {
            fun complete(allow: Boolean) {
                if (completed.compareAndSet(false, true)) {
                    allowed.set(allow)
                    latch.countDown()
                }
            }

            fun await(timeoutSec: Long): Boolean? {
                val ok = latch.await(timeoutSec, TimeUnit.SECONDS)
                if (!ok) return null
                return allowed.get()
            }
        }

        /**
         * Launch confirm UI and block until user decides.
         * @return true allow, false deny, null timeout / failure
         */
        fun request(
            context: Context,
            title: String,
            message: String,
            timeoutSec: Long = 90L
        ): Boolean? {
            val id = java.util.UUID.randomUUID().toString()
            val pending = Pending()
            pendingRequests[id] = pending
            return try {
                val intent = Intent(context, SensitiveConfirmActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_REQUEST_ID, id)
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_MESSAGE, message)
                }
                context.startActivity(intent)
                pending.await(timeoutSec)
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to launch confirm activity", e)
                null
            } finally {
                pendingRequests.remove(id)
            }
        }
    }
}
