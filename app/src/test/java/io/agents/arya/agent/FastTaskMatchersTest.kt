package io.agents.arya.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastTaskMatchersTest {

    @Test
    fun parsesExplicitPersianTelegramMessage() {
        val match = requireNotNull(FastTaskMatchers.match("به علی در تلگرام بگو سلام، حالت چطوره؟"))
        assertEquals("send_message", match.toolName)
        assertEquals("علی", match.params["contact"])
        assertEquals("Telegram", match.params["app"])
        assertEquals("سلام، حالت چطوره؟", match.params["message"])
    }

    @Test
    fun parsesExplicitPersianTelegramXMessage() {
        val match = requireNotNull(FastTaskMatchers.match("به تیم تو تلگرام X پیام بده انتشار نسخه انجام شد"))
        assertEquals("send_message", match.toolName)
        assertEquals("Telegram X", match.params["app"])
    }

    @Test
    fun parsesGoogleSearchWithoutCallingModel() {
        val match = requireNotNull(FastTaskMatchers.match("در گوگل قیمت بیت کوین رو سرچ کن"))
        assertEquals("search_browser", match.toolName)
        assertEquals("قیمت بیت کوین رو", match.params["query"])
    }

    @Test
    fun parsesNamedTelegramChannelAnalysisForPrimedRead() {
        val match = requireNotNull(
            FastTaskMatchers.matchChatAnalysis("پیام های جدید کانال خبر فوری در تلگرام رو تحلیل کن")
        )
        assertEquals("خبر فوری", match.chatName)
        assertEquals("Telegram", match.app)
    }

    @Test
    fun opensSinglePersianAppCommandWithoutModel() {
        val match = requireNotNull(FastTaskMatchers.match("تلگرامو باز کن"))
        assertEquals("open_app", match.toolName)
        assertEquals("تلگرام", match.params["app_name"])
    }

    @Test
    fun doesNotTreatMultiStepOpenAsInstantOpen() {
        assertNull(FastTaskMatchers.match("تلگرامو باز کن، برو تو سیو مسیجم"))
    }

    @Test
    fun keepsAmbiguousMessageOutOfFastPath() {
        assertNull(FastTaskMatchers.match("به علی یه پیام خوب بده"))
    }

    @Test
    fun round2BatteryAndClipboard() {
        assertEquals("get_device_info", FastTaskMatchers.match("how much battery")?.toolName)
        assertEquals("clipboard", FastTaskMatchers.match("read clipboard")?.toolName)
        assertEquals("get_notifications", FastTaskMatchers.match("show notifications")?.toolName)
        assertEquals("open_app", FastTaskMatchers.match("open settings")?.toolName)
        assertEquals("system_setting", FastTaskMatchers.match("airplane mode on")?.toolName)
        assertEquals("get_device_info", FastTaskMatchers.match("wifi status")?.toolName)
        assertEquals("get_installed_apps", FastTaskMatchers.match("list apps")?.toolName)
        assertEquals("open_app", FastTaskMatchers.match("open chrome")?.toolName)
    }

    @Test
    fun englishOverflowControls() {
        assertEquals("system_setting", FastTaskMatchers.match("turn on wifi")?.toolName)
        assertEquals("on", FastTaskMatchers.match("turn on wifi")?.params?.get("action"))
        assertEquals("off", FastTaskMatchers.match("turn off bluetooth")?.params?.get("action"))
        assertEquals("Camera", FastTaskMatchers.match("open camera")?.params?.get("app_name"))
        assertEquals("YouTube", FastTaskMatchers.match("open youtube")?.params?.get("app_name"))
        assertEquals("take_screenshot", FastTaskMatchers.match("take screenshot")?.toolName)
        assertEquals("back", FastTaskMatchers.match("go back")?.params?.get("key"))
        assertEquals("up", FastTaskMatchers.match("volume up")?.params?.get("action"))
    }
}
