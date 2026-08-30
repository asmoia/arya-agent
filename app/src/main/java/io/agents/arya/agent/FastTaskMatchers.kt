// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.

package io.agents.arya.agent

/**
 * Narrow deterministic recognisers for commands whose parameters are explicit.
 *
 * They intentionally return null for ambiguous natural language. A wrong fast
 * action is worse than an LLM fallback; a clear "send X to Y on Telegram" or
 * "search X on Google" should not spend several local-model turns planning.
 */
object FastTaskMatchers {

    data class ToolMatch(
        val toolName: String,
        val params: Map<String, Any>,
        val description: String,
    )

    data class ChatAnalysisMatch(
        val chatName: String,
        val app: String,
    )

    const val FAST_READ_MARKER = "[ARYA_FAST_READ_CONTEXT]"

    private val persianSend = Regex(
        """^\s*به\s+(.+?)\s+(?:در|تو|روی)\s+(تلگرام(?:\s*x)?|telegram(?:\s*x)?|واتساپ|واتس\s*اپ|whatsapp)\s+(?:پیام\s*(?:بده|بفرست)|بگو|بنویس|ارسال\s*کن)\s*(?:که)?\s*[:،,]?\s*(.+?)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val persianSendAppFirst = Regex(
        """^\s*(?:در|تو|روی)\s+(تلگرام(?:\s*x)?|telegram(?:\s*x)?|واتساپ|واتس\s*اپ|whatsapp)\s+به\s+(.+?)\s+(?:پیام\s*(?:بده|بفرست)|بگو|بنویس|ارسال\s*کن)\s*(?:که)?\s*[:،,]?\s*(.+?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val persianGoogleSearchPrefix = Regex(
        """^\s*(?:در|تو)\s+گوگل\s+(.+?)\s+(?:سرچ|جستجو)\s*کن\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val persianGoogleSearchSuffix = Regex(
        """^\s*(.+?)\s+(?:رو|را)?\s*(?:در|تو)\s+گوگل\s+(?:سرچ|جستجو)\s*کن\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val englishGoogleSearch = Regex(
        """^\s*(?:search(?:\s+google)?\s+for|google)\s+(.+?)\s*$""",
        RegexOption.IGNORE_CASE,
    )

    private val persianChatAnalysis = Regex(
        """^\s*(?:پیام(?:‌| )های جدید|پیام جدید)\s+(?:(?:کانال|گروه)\s+)?(.+?)\s+(?:در|تو)\s+(تلگرام(?:\s*x)?|telegram(?:\s*x)?)\s+(?:رو|را)?\s*(?:تحلیل|خلاصه)\s*کن.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val englishChatAnalysis = Regex(
        """^\s*(?:analyze|summarize)\s+(?:new|latest|recent)\s+messages?\s+(?:in|from)\s+(?:the\s+)?(?:channel|group|chat)\s+(.+?)\s+(?:on|in)\s+(telegram(?:\s*x)?)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val persianOpenAppPrefix = Regex(
        """^\s*باز(?:ش)?\s*کن\s+(.+?)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val persianOpenAppSuffix = Regex(
        """^\s*(.+?)\s*(?:را|رو)?\s+باز(?:ش)?\s*کن\s*$""",
        RegexOption.IGNORE_CASE,
    )

    fun match(task: String): ToolMatch? {
        val normalized = PersianNormalizer.normalize(task)
        matchPersianSend(normalized)?.let { return it }
        matchBrowserSearch(normalized)?.let { return it }
        matchSimplePersianControl(normalized)?.let { return it }
        matchSimplePersianOpenApp(normalized)?.let { return it }
        return null
    }

    private fun matchSimplePersianControl(task: String): ToolMatch? {
        val compact = task.trim().lowercase().replace(Regex("""\s+"""), " ")
        return when (compact) {
            "برو خانه", "برو خونه", "صفحه اصلی", "برو صفحه اصلی" ->
                ToolMatch("system_key", mapOf("key" to "home"), "رفتن به صفحهٔ اصلی")
            "برگرد", "برگشت", "برو عقب", "یه مرحله برگرد" ->
                ToolMatch("system_key", mapOf("key" to "back"), "بازگشت")
            "اسکرین شات بگیر", "اسکرین‌شات بگیر", "عکس از صفحه بگیر" ->
                ToolMatch("take_screenshot", emptyMap(), "گرفتن اسکرین‌شات")
            "وای فای رو روشن کن", "وای‌فای رو روشن کن", "روشن کردن وای فای" ->
                ToolMatch("system_setting", mapOf("setting" to "wifi", "action" to "on"), "روشن کردن وای‌فای")
            "وای فای رو خاموش کن", "وای‌فای رو خاموش کن", "خاموش کردن وای فای" ->
                ToolMatch("system_setting", mapOf("setting" to "wifi", "action" to "off"), "خاموش کردن وای‌فای")
            "بلوتوث رو روشن کن", "روشن کردن بلوتوث" ->
                ToolMatch("system_setting", mapOf("setting" to "bluetooth", "action" to "on"), "روشن کردن بلوتوث")
            "بلوتوث رو خاموش کن", "خاموش کردن بلوتوث" ->
                ToolMatch("system_setting", mapOf("setting" to "bluetooth", "action" to "off"), "خاموش کردن بلوتوث")
            "چراغ قوه رو روشن کن", "فلاش رو روشن کن" ->
                ToolMatch("system_setting", mapOf("setting" to "flashlight", "action" to "on"), "روشن کردن چراغ‌قوه")
            "چراغ قوه رو خاموش کن", "فلاش رو خاموش کن" ->
                ToolMatch("system_setting", mapOf("setting" to "flashlight", "action" to "off"), "خاموش کردن چراغ‌قوه")
            "صدا رو زیاد کن", "ولوم رو زیاد کن" ->
                ToolMatch("system_setting", mapOf("setting" to "volume", "action" to "up"), "افزایش صدای زنگ")
            "صدا رو کم کن", "ولوم رو کم کن" ->
                ToolMatch("system_setting", mapOf("setting" to "volume", "action" to "down"), "کاهش صدای زنگ")
            "دوربین رو باز کن", "برنامه دوربین رو باز کن" ->
                ToolMatch("open_app", mapOf("app_name" to "Camera"), "باز کردن دوربین")
            "ماشین حساب رو باز کن" ->
                ToolMatch("open_app", mapOf("app_name" to "Calculator"), "باز کردن ماشین حساب")
            // Round-2 deterministic patterns (C3)
            "وای فای رو قطع کن", "اینترنت رو خاموش کن" ->
                ToolMatch("system_setting", mapOf("setting" to "wifi", "action" to "off"), "خاموش کردن وای‌فای")
            "حالت پرواز رو روشن کن", "airplane mode on" ->
                ToolMatch("system_setting", mapOf("setting" to "airplane", "action" to "on"), "روشن کردن حالت پرواز")
            "حالت پرواز رو خاموش کن", "airplane mode off" ->
                ToolMatch("system_setting", mapOf("setting" to "airplane", "action" to "off"), "خاموش کردن حالت پرواز")
            "تنظیمات رو باز کن", "open settings" ->
                ToolMatch("open_app", mapOf("app_name" to "Settings"), "باز کردن تنظیمات")
            "گالری رو باز کن", "open gallery", "photos رو باز کن" ->
                ToolMatch("open_app", mapOf("app_name" to "Gallery"), "باز کردن گالری")
            "نوتیفیکیشن‌ها رو نشون بده", "اعلان‌ها رو بخون", "show notifications" ->
                ToolMatch("get_notifications", emptyMap(), "خواندن اعلان‌ها")
            "باتری چقدره", "شارژ چقدره", "how much battery" ->
                ToolMatch("get_device_info", mapOf("category" to "battery"), "وضعیت باتری")
            "ساعت چنده", "what time is it" ->
                ToolMatch("get_device_info", mapOf("category" to "time"), "ساعت دستگاه")
            "کلیپ بورد رو بخون", "clipboard رو بخون", "read clipboard" ->
                ToolMatch("clipboard", mapOf("action" to "get"), "خواندن کلیپ‌بورد")
            "قفل صفحه", "lock screen" ->
                ToolMatch("system_key", mapOf("key" to "lock"), "قفل صفحه")
            "اسکرین شات بفرست", "take a screenshot" ->
                ToolMatch("take_screenshot", emptyMap(), "گرفتن اسکرین‌شات")
            "وای فای چطوره", "wifi status", "وضعیت وای فای" ->
                ToolMatch("get_device_info", mapOf("category" to "wifi"), "وضعیت وای‌فای")
            "بلوتوث چطوره", "bluetooth status" ->
                ToolMatch("get_device_info", mapOf("category" to "bluetooth"), "وضعیت بلوتوث")
            "حجم خالی چقدره", "storage left", "فضای ذخیره" ->
                ToolMatch("get_device_info", mapOf("category" to "storage"), "فضای ذخیره‌سازی")
            "نسخه اندروید", "android version" ->
                ToolMatch("get_device_info", mapOf("category" to "device"), "اطلاعات دستگاه")
            "اپ‌های نصب شده", "list apps", "installed apps" ->
                ToolMatch("get_installed_apps", emptyMap(), "فهرست برنامه‌ها")
            "برو هوم", "go home" ->
                ToolMatch("system_key", mapOf("key" to "home"), "رفتن به صفحهٔ اصلی")
            "صدا قطع کن", "mute", "سایلنت کن" ->
                ToolMatch("system_setting", mapOf("setting" to "volume", "action" to "mute"), "بی‌صدا کردن")
            "روشنایی زیاد کن", "brightness up" ->
                ToolMatch("system_setting", mapOf("setting" to "brightness", "action" to "up"), "افزایش روشنایی")
            "روشنایی کم کن", "brightness down" ->
                ToolMatch("system_setting", mapOf("setting" to "brightness", "action" to "down"), "کاهش روشنایی")
            "کروم رو باز کن", "open chrome" ->
                ToolMatch("open_app", mapOf("app_name" to "Chrome"), "باز کردن کروم")
            "turn on wifi", "enable wifi" ->
                ToolMatch("system_setting", mapOf("setting" to "wifi", "action" to "on"), "turn on wifi")
            "turn off wifi", "disable wifi" ->
                ToolMatch("system_setting", mapOf("setting" to "wifi", "action" to "off"), "turn off wifi")
            "turn on bluetooth", "enable bluetooth" ->
                ToolMatch("system_setting", mapOf("setting" to "bluetooth", "action" to "on"), "turn on bluetooth")
            "turn off bluetooth", "disable bluetooth" ->
                ToolMatch("system_setting", mapOf("setting" to "bluetooth", "action" to "off"), "turn off bluetooth")
            "open camera" ->
                ToolMatch("open_app", mapOf("app_name" to "Camera"), "open camera")
            "open calculator" ->
                ToolMatch("open_app", mapOf("app_name" to "Calculator"), "open calculator")
            "volume up" ->
                ToolMatch("system_setting", mapOf("setting" to "volume", "action" to "up"), "volume up")
            "volume down" ->
                ToolMatch("system_setting", mapOf("setting" to "volume", "action" to "down"), "volume down")
            "flashlight on", "torch on" ->
                ToolMatch("system_setting", mapOf("setting" to "flashlight", "action" to "on"), "flashlight on")
            "flashlight off", "torch off" ->
                ToolMatch("system_setting", mapOf("setting" to "flashlight", "action" to "off"), "flashlight off")
            "go back" ->
                ToolMatch("system_key", mapOf("key" to "back"), "go back")
            "open youtube" ->
                ToolMatch("open_app", mapOf("app_name" to "YouTube"), "open youtube")
            "open telegram", "open tg" ->
                ToolMatch("open_app", mapOf("app_name" to "Telegram"), "open telegram")
            "open telegram x", "open telegramx" ->
                ToolMatch("open_app", mapOf("app_name" to "Telegram X"), "open telegram x")
            "open whatsapp", "open wa" ->
                ToolMatch("open_app", mapOf("app_name" to "WhatsApp"), "open whatsapp")
            "open maps", "open google maps" ->
                ToolMatch("open_app", mapOf("app_name" to "Maps"), "open maps")
            "take screenshot" ->
                ToolMatch("take_screenshot", emptyMap(), "take screenshot")
            else -> null
        }
    }

    private fun matchSimplePersianOpenApp(task: String): ToolMatch? {
        // Only accept a single imperative. A comma, conjunction, URL or a second
        // action means this is an agent task, not a safe instant open.
        if (task.contains(Regex("[,،؛]")) || task.contains(" و ") || task.contains(" بعد ") ||
            task.contains(" سپس ") || task.contains("http", ignoreCase = true)) return null
        val raw = persianOpenAppPrefix.matchEntire(task)?.groupValues?.getOrNull(1)
            ?: persianOpenAppSuffix.matchEntire(task)?.groupValues?.getOrNull(1)
            ?: return null
        val app = raw.trim().removePrefix("اپ ").removePrefix("برنامه ").trim()
        if (app.isBlank() || app.length > 60) return null
        return ToolMatch(
            toolName = "open_app",
            params = mapOf("app_name" to app),
            description = "باز کردن $app",
        )
    }

    /** A named Telegram group/channel can be opened deterministically before one bounded summary turn. */
    fun matchChatAnalysis(task: String): ChatAnalysisMatch? {
        val normalized = PersianNormalizer.normalize(task)
        val match = persianChatAnalysis.matchEntire(normalized) ?: englishChatAnalysis.matchEntire(normalized) ?: return null
        val chat = match.groupValues[1].trim()
        val appRaw = match.groupValues[2]
        if (chat.isBlank() || chat.length > 100) return null
        val app = if (appRaw.contains("x", ignoreCase = true)) "Telegram X" else "Telegram"
        return ChatAnalysisMatch(chatName = chat, app = app)
    }

    private fun matchPersianSend(task: String): ToolMatch? {
        val first = persianSend.matchEntire(task)
        val appFirst = persianSendAppFirst.matchEntire(task)
        val contact: String
        val appRaw: String
        val message: String
        when {
            first != null -> {
                contact = first.groupValues[1].trim()
                appRaw = first.groupValues[2]
                message = first.groupValues[3].trim()
            }
            appFirst != null -> {
                appRaw = appFirst.groupValues[1]
                contact = appFirst.groupValues[2].trim()
                message = appFirst.groupValues[3].trim()
            }
            else -> return null
        }
        if (contact.isBlank() || message.isBlank() || contact.length > 80 || message.length > 2_000) return null
        val app = when {
            appRaw.contains("telegram", ignoreCase = true) || appRaw.contains("تلگرام") ->
                if (appRaw.contains("x", ignoreCase = true)) "Telegram X" else "Telegram"
            else -> "WhatsApp"
        }
        return ToolMatch(
            toolName = "send_message",
            params = mapOf("contact" to contact, "message" to message, "app" to app),
            description = "ارسال پیام به $contact در $app",
        )
    }

    private fun matchBrowserSearch(task: String): ToolMatch? {
        val query = persianGoogleSearchPrefix.matchEntire(task)?.groupValues?.getOrNull(1)
            ?: persianGoogleSearchSuffix.matchEntire(task)?.groupValues?.getOrNull(1)
            ?: englishGoogleSearch.matchEntire(task)?.groupValues?.getOrNull(1)
            ?: return null
        val clean = query.trim().removePrefix("برای ").trim()
        if (clean.isBlank() || clean.length > 500) return null
        return ToolMatch(
            toolName = "search_browser",
            params = mapOf("query" to clean),
            description = "جستجوی «$clean» در مرورگر",
        )
    }
}
