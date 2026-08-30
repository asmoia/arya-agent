package io.agents.arya.ui.chat

/**
 * One input box, two brains:
 * - greetings / short Q&A → local chat model (no tools)
 * - anything that looks like a phone action → PipelineRouter / TaskOrchestrator
 *
 * ChatRuntime previously sent EVERY string to the LLM with tools=0, so
 * "open Telegram X" became an essay. TaskOrchestrator already knows how to
 * run open_app with zero tokens.
 */
object ChatIntent {
    fun isChatOnly(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        val lower = t.lowercase()
        val taskHints = listOf(
            "open ", "launch ", "start ", "send ", "tap ", "install ", "call ",
            "message ", "whatsapp", "telegram", "chrome", "browser", "youtube",
            "play ", "saved", "screenshot", "wifi", "bluetooth", "battery",
            "باز کن", "بازش کن", "بفرست", "تماس", "پیام", "واتساپ", "تلگرام",
            "نصب", "تنظیمات", "اسکرین", "پخش", "پلی", "سیو", "آهنگ",
            "برو به", "برو تو", "برو خانه", "برگرد", "کروم", "مرورگر", "یوتیوب",
            "دوربین", "گالری", "ماشین حساب",
        )
        if (taskHints.any { lower.contains(it) || t.contains(it) }) return false
        if (t.length > 40) return false
        val chatHints = listOf(
            "سلام", "درود", "خداحافظ", "ممنون", "مرسی", "خوبی", "چطوری",
            "hello", "hi", "hey", "thanks", "thank you", "ok", "okay", "باشه",
        )
        if (chatHints.any { lower == it || t == it || lower.startsWith("$it ") || lower.startsWith("$it?") }) {
            return true
        }
        return t.length <= 16 && !t.contains("http")
    }
}
