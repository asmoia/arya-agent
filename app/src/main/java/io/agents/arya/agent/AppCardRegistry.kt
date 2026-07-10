// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent

/**
 * Small data-driven app knowledge layer. Cards describe package aliases and
 * supported deterministic actions; they are not prompt fragments.
 */
data class AppCard(
    val id: String,
    val aliases: Set<String>,
    val packages: List<String>,
    val actions: Set<String>,
)

object AppCardRegistry {
    private val cards = listOf(
        AppCard("telegram", setOf("telegram", "تلگرام", "تل"), listOf("org.telegram.messenger", "org.thunderdog.challegram", "org.telegram.plus", "ir.ilmili.telegraph"), setOf("open_chat", "read_visible", "draft_message", "play_media")),
        AppCard("telegram_x", setOf("telegram x", "telegramx", "تلگرام ایکس"), listOf("org.thunderdog.challegram", "org.telegram.messenger"), setOf("open_chat", "read_visible", "draft_message", "play_media")),
        AppCard("whatsapp", setOf("whatsapp", "واتساپ", "واتس اپ"), listOf("com.whatsapp", "com.whatsapp.w4b"), setOf("open_chat", "read_visible", "draft_message")),
        AppCard("browser", setOf("chrome", "کروم", "browser", "مرورگر"), listOf("com.android.chrome", "com.chrome.beta", "com.huawei.browser", "com.sec.android.app.sbrowser"), setOf("search", "open_url", "read_visible")),
        AppCard("youtube", setOf("youtube", "یوتیوب"), listOf("com.google.android.youtube", "com.google.android.apps.youtube.mango"), setOf("search", "play_media")),
        AppCard("settings", setOf("settings", "تنظیمات"), listOf("com.android.settings"), setOf("open_settings")),
    )

    fun match(hint: String): AppCard? {
        val normalized = PersianNormalizer.normalize(hint)
        return cards.firstOrNull { card -> card.aliases.any { alias -> normalized == alias || normalized.contains(alias) } }
    }

    fun packagesFor(hint: String): List<String> = match(hint)?.packages ?: emptyList()
    fun supports(hint: String, action: String): Boolean = match(hint)?.actions?.contains(action) == true
}
