package io.agents.arya.voice

import java.util.Locale

/**
 * Resolves the language requested from Android's speech recognizer.
 * Persian is the product default; an explicit persisted tag may opt into another language.
 */
object VoiceLocale {
    const val DEFAULT_TAG = "fa-IR"
    const val SYSTEM_TAG = "system"

    fun resolveTag(preference: String?, systemLocale: Locale = Locale.getDefault()): String {
        val requested = preference?.trim().orEmpty()
        if (requested.isBlank()) return DEFAULT_TAG
        if (requested.equals(SYSTEM_TAG, ignoreCase = true)) {
            return systemLocale.toLanguageTag().ifBlank { DEFAULT_TAG }
        }

        val normalized = requested.replace('_', '-')
        val validTag = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z]{4})?(?:-[A-Za-z]{2}|-[0-9]{3})?$")
        if (!validTag.matches(normalized)) return DEFAULT_TAG

        val parsed = Locale.forLanguageTag(normalized)
        if (parsed.language.isBlank()) return DEFAULT_TAG
        if (parsed.language.equals("fa", ignoreCase = true)) return DEFAULT_TAG
        return parsed.toLanguageTag().ifBlank { DEFAULT_TAG }
    }
}
