// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Lightweight scheduled tasks for embedded Hermes (P3).

package io.agents.arya.agent.hermes.cron

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import io.agents.arya.ClawApplication
import io.agents.arya.agent.hermes.memory.HermesMemoryStore
import io.agents.arya.utils.XLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * File-backed cron jobs + AlarmManager scheduling.
 * Jobs fire [HermesCronReceiver] which logs an episode and can later trigger tasks.
 */
object HermesCronStore {

    private const val TAG = "HermesCron"
    private const val FILE_NAME = "cron_jobs.json"

    data class Job(
        val id: String,
        val title: String,
        val prompt: String,
        val intervalMinutes: Long,
        val enabled: Boolean,
        val lastRunAt: Long = 0L,
        val createdAt: Long = System.currentTimeMillis()
    )

    private fun file(): File =
        File(ClawApplication.instance.filesDir, "hermes/$FILE_NAME").also {
            it.parentFile?.mkdirs()
        }

    fun listJobs(): List<Job> {
        val f = file()
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Job(
                    id = o.getString("id"),
                    title = o.optString("title"),
                    prompt = o.optString("prompt"),
                    intervalMinutes = o.optLong("interval_minutes", 60),
                    enabled = o.optBoolean("enabled", true),
                    lastRunAt = o.optLong("last_run_at", 0),
                    createdAt = o.optLong("created_at", 0)
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
                    .put("title", j.title)
                    .put("prompt", j.prompt)
                    .put("interval_minutes", j.intervalMinutes)
                    .put("enabled", j.enabled)
                    .put("last_run_at", j.lastRunAt)
                    .put("created_at", j.createdAt)
            )
        }
        file().writeText(arr.toString(2))
    }

    fun addJob(title: String, prompt: String, intervalMinutes: Long): Job {
        val job = Job(
            id = UUID.randomUUID().toString(),
            title = title.take(80),
            prompt = prompt.take(2000),
            intervalMinutes = intervalMinutes.coerceIn(15, 60 * 24 * 7),
            enabled = true
        )
        val jobs = listJobs().toMutableList()
        jobs += job
        saveJobs(jobs)
        schedule(ClawApplication.instance, job)
        XLog.i(TAG, "addJob ${job.id} every ${job.intervalMinutes}m")
        return job
    }

    fun removeJob(id: String) {
        val jobs = listJobs().filterNot { it.id == id }
        saveJobs(jobs)
        cancelAlarm(ClawApplication.instance, id)
        XLog.i(TAG, "removeJob $id")
    }

    fun markRan(id: String) {
        val jobs = listJobs().map {
            if (it.id == id) it.copy(lastRunAt = System.currentTimeMillis()) else it
        }
        saveJobs(jobs)
    }

    fun rescheduleAll(context: Context) {
        for (j in listJobs().filter { it.enabled }) {
            schedule(context, j)
        }
    }

    private fun schedule(context: Context, job: Job) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(context, job.id)
        val intervalMs = job.intervalMinutes * 60_000L
        val trigger = SystemClock.elapsedRealtime() + intervalMs
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        } catch (e: Exception) {
            XLog.w(TAG, "schedule failed ${job.id}", e)
        }
    }

    private fun cancelAlarm(context: Context, jobId: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pendingIntent(context, jobId))
    }

    private fun pendingIntent(context: Context, jobId: String): PendingIntent {
        val intent = Intent(context, HermesCronReceiver::class.java).apply {
            action = HermesCronReceiver.ACTION_FIRE
            putExtra(HermesCronReceiver.EXTRA_JOB_ID, jobId)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, jobId.hashCode(), intent, flags)
    }
}

class HermesCronReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_FIRE) return
        val jobId = intent.getStringExtra(EXTRA_JOB_ID) ?: return
        val job = HermesCronStore.listJobs().find { it.id == jobId }
        if (job == null || !job.enabled) return
        XLog.i(TAG, "cron fire ${job.id}: ${job.title}")
        HermesCronStore.markRan(job.id)
        // Persist a memory episode so Hermes "knows" the schedule fired.
        HermesMemoryStore.getInstance().appendEpisode(
            "Cron fired: ${job.title}\nPrompt: ${job.prompt.take(300)}"
        )
        // Reschedule next occurrence (setAndAllowWhileIdle is one-shot).
        HermesCronStore.rescheduleAll(context)
        // Optional: broadcast for UI / future task trigger
        context.sendBroadcast(
            Intent(ACTION_FIRED).setPackage(context.packageName)
                .putExtra(EXTRA_JOB_ID, job.id)
                .putExtra(EXTRA_PROMPT, job.prompt)
        )
    }

    companion object {
        private const val TAG = "HermesCronRx"
        const val ACTION_FIRE = "io.agents.arya.HERMES_CRON_FIRE"
        const val ACTION_FIRED = "io.agents.arya.HERMES_CRON_FIRED"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_PROMPT = "prompt"
    }
}
