package io.agents.arya.agent.hermes.voice

/** Hermes voice listen store archived. No-op so NotificationListener still compiles. */
object VoiceListenStore {
    fun onMessagingNotification(
        @Suppress("UNUSED_PARAMETER") pkg: String?,
        @Suppress("UNUSED_PARAMETER") title: String?,
        @Suppress("UNUSED_PARAMETER") text: String?,
    ) {
        // Archived — NotificationListener still delivers to AutoReplyManager.
    }
}
