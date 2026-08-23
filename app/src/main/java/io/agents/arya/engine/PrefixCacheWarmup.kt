package io.agents.arya.engine

import android.content.Context
import android.content.Intent
import android.os.BatteryManager

/**
 * Overflow #3 — opportunistic prefix warmup after load.
 * Full WorkManager idle+charging scheduling is documented; v1 triggers from EngineClient
 * after ensureLoaded when the device is charging or the caller asks.
 */
object PrefixCacheWarmup {
    const val PROMPT_TEMPLATE_VERSION = PrefixCache.PROMPT_TEMPLATE_VERSION

    fun shouldWarmNow(context: Context): Boolean {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val charging = bm?.isCharging == true
        val intent = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return charging || plugged != 0
    }

    fun warmupRequest(systemPrompt: String, key: String): EngineRequest =
        EngineRequest(
            prompt = systemPrompt,
            promptMode = "full",
            prefixKey = key,
            warmupKey = key,
            maxTokens = 0,
            deadlineMs = 20_000L,
        )
}
