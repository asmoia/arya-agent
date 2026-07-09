// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.arya.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.agents.arya.utils.XLog

/**
 * Boot broadcast receiver retained for future restart hooks.
 * PokeClaw no longer starts a persistent foreground notification on boot.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            XLog.i(TAG, "Boot broadcast received — rescheduling Hermes cron, no sticky FG service")
            try {
                io.agents.arya.agent.hermes.cron.HermesCronStore.rescheduleAll(context.applicationContext)
                // Accessibility may need user re-enable on some OEMs after reboot — log only.
                XLog.i(TAG, "Hermes cron rescheduled after boot")
            } catch (e: Exception) {
                XLog.w(TAG, "cron reschedule on boot failed", e)
            }
        }
    }
}
