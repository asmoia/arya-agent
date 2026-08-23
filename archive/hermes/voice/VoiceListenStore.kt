// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Background voice/message listen jobs for Hermes (notification-driven).

package io.agents.arya.agent.hermes.voice

import io.agents.arya.ClawApplication
import io.agents.arya.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists background "listen" jobs. When NotificationListener sees a matching
 * messaging notification (Telegram/WhatsApp/…), the event is appended here so
 * the agent can later read what was heard — without continuous microphone spy.
 *
 * True mic always-on is intentionally NOT used (privacy + OEM battery kill).
 */
object VoiceListenStore {

    private const val TAG = "VoiceListenStore"
    private const val FILE = "voice_listen_jobs.json"
    private const val EVENTS = "voice_listen_events.jsonl"
    private const val MAX_EVENTS = 200

    data class Job(
        val id: String,
        val appPackage: String?, // null = any messaging app
        val contactHint: String?,
        val note: String,
        val enabled: Boolean,
        val createdAt: Long
    )

    private fun root(): File =
        File(ClawApplication.instance.filesDir, "hermes").apply { mkdirs() }

    private fun jobsFile() = File(root(), FILE)
    private fun eventsFile() = File(root(), EVENTS)

    fun listJobs(): List<Job> {
        val f = jobsFile()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Job(
                    id = o.getString("id"),
                    appPackage = o.optString("app_package", null).takeIf { !it.isNullOrBlank() && it != "null" },
                    contactHint = o.optString("contact_hint", null).takeIf { !it.isNullOrBlank() && it != "null" },
                    note = o.optString("note", ""),
                    enabled = o.optBoolean("enabled", true),
                    createdAt = o.optLong("created_at", 0L)
                )
            }
        } catch (e: Exception) {
            XLog.w(TAG, "listJobs failed", e)
            emptyList()
        }
    }

    private fun saveJobs(jobs: List<Job>) {
        val arr = JSONArray()
        for (j in jobs) {
            arr.put(
                JSONObject()
                    .put("id", j.id)
                    .put("app_package", j.appPackage)
                    .put("contact_hint", j.contactHint)
                    .put("note", j.note)
                    .put("enabled", j.enabled)
                    .put("created_at", j.createdAt)
            )
        }
        jobsFile().writeText(arr.toString(2))
    }

    fun addJob(appPackage: String?, contactHint: String?, note: String): Job {
        val job = Job(
            id = java.util.UUID.randomUUID().toString().take(8),
            appPackage = appPackage?.ifBlank { null },
            contactHint = contactHint?.ifBlank { null },
            note = note.take(200),
            enabled = true,
            createdAt = System.currentTimeMillis()
        )
        saveJobs(listJobs() + job)
        XLog.i(TAG, "addJob ${job.id} pkg=${job.appPackage} contact=${job.contactHint}")
        return job
    }

    fun removeJob(id: String) {
        saveJobs(listJobs().filterNot { it.id == id })
    }

    fun clearJobs() = saveJobs(emptyList())

    /**
     * Called from NotificationListener when a messaging notification arrives.
     */
    fun onMessagingNotification(pkg: String, title: String, text: String) {
        val jobs = listJobs().filter { it.enabled }
        if (jobs.isEmpty()) return
        val matched = jobs.filter { job ->
            val pkgOk = job.appPackage == null ||
                pkg.contains(job.appPackage!!, ignoreCase = true) ||
                packageAliases(job.appPackage).any { pkg.equals(it, true) }
            val contactOk = job.contactHint == null ||
                title.contains(job.contactHint!!, ignoreCase = true) ||
                text.contains(job.contactHint!!, ignoreCase = true)
            pkgOk && contactOk
        }
        if (matched.isEmpty()) return

        val line = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("package", pkg)
            .put("title", title.take(200))
            .put("text", text.take(500))
            .put("matched_jobs", JSONArray(matched.map { it.id }))
            .put(
                "kind",
                when {
                    looksLikeVoice(text) -> "voice_or_media"
                    else -> "message"
                }
            )
            .toString()
        try {
            eventsFile().appendText(line + "\n")
            trimEvents()
            XLog.i(TAG, "captured notification pkg=$pkg kind=${if (looksLikeVoice(text)) "voice" else "msg"}")
        } catch (e: Exception) {
            XLog.w(TAG, "append event failed", e)
        }
    }

    fun recentEvents(limit: Int = 30): List<String> {
        val f = eventsFile()
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().takeLast(limit)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun clearEvents() {
        eventsFile().delete()
    }

    private fun looksLikeVoice(text: String): Boolean {
        val t = text.lowercase()
        return listOf(
            "voice message", "voice msg", "پیام صوتی", "ویس", "voice",
            "audio", "پیام صوتی ارسال", "video message", "sticker"
        ).any { t.contains(it) }
    }

    private fun packageAliases(hint: String): List<String> {
        val h = hint.lowercase()
        return when {
            h.contains("telegram") || h.contains("تلگرام") ->
                listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderdog.challegram")
            h.contains("whatsapp") || h.contains("واتساپ") || h.contains("واتساپ") ->
                listOf("com.whatsapp", "com.whatsapp.w4b")
            h.contains("chrome") || h.contains("browser") ->
                listOf("com.android.chrome", "com.chrome.beta")
            else -> listOf(hint)
        }
    }

    private fun trimEvents() {
        val f = eventsFile()
        if (!f.exists()) return
        val lines = f.readLines()
        if (lines.size <= MAX_EVENTS) return
        f.writeText(lines.takeLast(MAX_EVENTS).joinToString("\n") + "\n")
    }
}
