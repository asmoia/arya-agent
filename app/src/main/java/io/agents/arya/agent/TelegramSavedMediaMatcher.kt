// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent

/**
 * Recognises the narrow, repeatable Telegram Saved Messages media-play flow.
 *
 * This intentionally requires all three concepts (Telegram, Saved Messages and
 * playback/media) so ordinary Telegram chat requests still use the general
 * agent path. The matching is kept independent of Android classes so it can be
 * unit-tested and reused by the router and the runtime policy.
 */
object TelegramSavedMediaMatcher {

    fun matches(task: String): Boolean {
        val normalized = task.lowercase()
        val telegram = normalized.contains("telegram") || task.contains("تلگرام")
        val saved = normalized.contains("saved message") ||
            normalized.contains("saved messages") ||
            normalized.contains("saved") ||
            task.contains("سیو") ||
            task.contains("ذخیره")
        val playback = normalized.contains("play") ||
            normalized.contains("music") ||
            normalized.contains("song") ||
            normalized.contains("audio") ||
            normalized.contains("voice") ||
            task.contains("پلی") ||
            task.contains("پخش") ||
            task.contains("آهنگ") ||
            task.contains("موسیقی") ||
            task.contains("ویس")
        return telegram && saved && playback
    }
}
