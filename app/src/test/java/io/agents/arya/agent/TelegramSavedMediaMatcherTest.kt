package io.agents.arya.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramSavedMediaMatcherTest {

    @Test
    fun matchesPersianSavedMessagesPlaybackRequest() {
        assertTrue(
            TelegramSavedMediaMatcher.matches(
                "تلگرامو باز کن، برو تو سیو مسیجم، یه آهنگ رندوم پلی کن"
            )
        )
    }

    @Test
    fun matchesEnglishSavedMessagesPlaybackRequest() {
        assertTrue(
            TelegramSavedMediaMatcher.matches(
                "Open Telegram Saved Messages and play a random song"
            )
        )
    }

    @Test
    fun doesNotHijackOrdinaryTelegramMessageRequest() {
        assertFalse(TelegramSavedMediaMatcher.matches("به علی در تلگرام پیام بده"))
    }

    @Test
    fun doesNotHijackGenericMusicRequest() {
        assertFalse(TelegramSavedMediaMatcher.matches("یک آهنگ پخش کن"))
    }
}
