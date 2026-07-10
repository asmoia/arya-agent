// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent

/** Shared Persian/Arabic normalization for deterministic routing and slots. */
object PersianNormalizer {
    private val digitMap = mapOf(
        '۰' to '0', '۱' to '1', '۲' to '2', '۳' to '3', '۴' to '4',
        '۵' to '5', '۶' to '6', '۷' to '7', '۸' to '8', '۹' to '9',
        '٠' to '0', '١' to '1', '٢' to '2', '٣' to '3', '٤' to '4',
        '٥' to '5', '٦' to '6', '٧' to '7', '٨' to '8', '٩' to '9',
    )

    fun normalize(input: String): String {
        var out = buildString(input.length) {
            input.forEach { char -> append(digitMap[char] ?: char) }
        }
        out = out
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ك', 'ک')
            .replace('\u200c', ' ')
            .replace(Regex("[ًٌٍَُِّْ]"), "")
            // Keep punctuation intact: it can be part of a message body. Matchers
            // already accept Persian/English separators where command grammar needs it.
            .replace(Regex("\\s+"), " ")
            .trim()
            .lowercase()
        // Common colloquial object suffixes are separated for intent patterns.
        out = out.replace(Regex("(تلگرام|واتساپ|کروم|مرورگر|یوتیوب|اینستاگرام)و\\b"), "$1 رو")
        return out
    }

    fun canonicalAppName(value: String): String? = when (normalize(value)) {
        "تلگرام", "telegram", "تل" -> "Telegram"
        "تلگرام x", "telegram x", "telegramx" -> "Telegram X"
        "واتساپ", "واتس اپ", "whatsapp", "wa" -> "WhatsApp"
        "کروم", "chrome" -> "Chrome"
        "مرورگر", "browser" -> "Browser"
        "یوتیوب", "youtube" -> "YouTube"
        "اینستاگرام", "instagram", "اینستا" -> "Instagram"
        "تنظیمات", "settings" -> "Settings"
        else -> null
    }
}
