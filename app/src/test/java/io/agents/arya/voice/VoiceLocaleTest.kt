package io.agents.arya.voice

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceLocaleTest {
    @Test
    fun blankPreferenceDefaultsToPersian() {
        assertEquals(VoiceLocale.DEFAULT_TAG, VoiceLocale.resolveTag(""))
        assertEquals(VoiceLocale.DEFAULT_TAG, VoiceLocale.resolveTag(null))
    }

    @Test
    fun explicitPreferenceIsPreservedAsCanonicalTag() {
        assertEquals("en-US", VoiceLocale.resolveTag("en-us"))
        assertEquals("fa-IR", VoiceLocale.resolveTag("fa"))
    }

    @Test
    fun systemPreferenceUsesProvidedSystemLocale() {
        assertEquals("de-DE", VoiceLocale.resolveTag("system", Locale.GERMANY))
    }

    @Test
    fun malformedPreferenceFallsBackToPersian() {
        assertEquals(VoiceLocale.DEFAULT_TAG, VoiceLocale.resolveTag("not a locale"))
    }
}
