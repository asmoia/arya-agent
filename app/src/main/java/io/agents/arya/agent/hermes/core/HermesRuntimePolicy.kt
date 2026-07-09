// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Adaptive runtime policy: Instant / Thinking / High — action-first, E4B-oriented.

package io.agents.arya.agent.hermes.core

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import io.agents.arya.ClawApplication
import io.agents.arya.utils.KVUtils
import io.agents.arya.utils.XLog
import org.json.JSONObject

/**
 * Thinking depth for the phone agent (like instant vs thinking modes).
 * Default [ADAPTIVE] picks Instant/Thinking from task + free RAM.
 */
enum class HermesThinkingMode {
    /** Minimum rounds, tight sampler, max action bias. */
    INSTANT,
    /** Default balanced for most phone tasks. */
    THINKING,
    /** More rounds for messy multi-app UI (still action-first). */
    HIGH,
    /** Auto: simple → Instant, hard → Thinking/High; low RAM → Instant. */
    ADAPTIVE;

    companion object {
        fun fromStorage(raw: String?): HermesThinkingMode =
            entries.find { it.name.equals(raw, ignoreCase = true) } ?: ADAPTIVE
    }
}

data class HermesRuntimeSnapshot(
    val mode: HermesThinkingMode,
    val resolvedMode: HermesThinkingMode,
    val maxIterations: Int,
    val screenSettleMs: Long,
    val topK: Int,
    val topP: Double,
    val temperature: Double,
    val forceToolOnEssay: Boolean,
    val autoScreenAfterAction: Boolean,
    val maxSameActionRepeats: Int,
    val availMemMb: Long,
    val totalMemMb: Long,
    val lowMemory: Boolean,
    val notes: String
)

/**
 * Central knobs for speed vs thoroughness. Prefer **more action, less essay**.
 */
object HermesRuntimePolicy {

    private const val TAG = "HermesRuntime"

    fun currentMode(): HermesThinkingMode =
        HermesThinkingMode.fromStorage(KVUtils.getHermesThinkingMode())

    fun setMode(mode: HermesThinkingMode) {
        KVUtils.setHermesThinkingMode(mode.name)
        XLog.i(TAG, "thinking mode → ${mode.name}")
    }

    fun memorySnapshot(context: Context = ClawApplication.instance): Triple<Long, Long, Boolean> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val avail = info.availMem / (1024L * 1024L)
        val total = info.totalMem / (1024L * 1024L)
        return Triple(avail, total, info.lowMemory)
    }

    /**
     * Resolve effective policy for this user task.
     */
    fun resolve(userTask: String, providerIsLocal: Boolean): HermesRuntimeSnapshot {
        val stored = currentMode()
        val (avail, total, low) = memorySnapshot()
        val complexity = estimateComplexity(userTask)

        val resolved = when (stored) {
            HermesThinkingMode.ADAPTIVE -> when {
                // Prefer Instant for speed; only HIGH when RAM is healthy AND task is hard.
                low || avail < 1000 -> HermesThinkingMode.INSTANT
                complexity >= 3 && avail > 2000 -> HermesThinkingMode.HIGH
                complexity >= 2 -> HermesThinkingMode.THINKING
                else -> HermesThinkingMode.INSTANT
            }
            else -> stored
        }

        // Local E4B: keep caps tight so the phone stays usable.
        // Instant = 3–4 rounds (action-first, minimal dithering).
        // HARD CAPS for local E4B — never show 1/10 style bloat.
        // Instant = 3 rounds max. Thinking = 5. High = 7.
        val baseMax = when (resolved) {
            HermesThinkingMode.INSTANT -> 3
            HermesThinkingMode.THINKING -> 5
            HermesThinkingMode.HIGH -> 7
            HermesThinkingMode.ADAPTIVE -> 5
        }
        val maxIter = if (providerIsLocal) {
            when (resolved) {
                HermesThinkingMode.INSTANT -> if (low || avail < 800) 3 else 3
                HermesThinkingMode.THINKING -> if (low || avail < 900) 4 else 5
                HermesThinkingMode.HIGH -> if (low || avail < 900) 5 else 7
                HermesThinkingMode.ADAPTIVE -> if (low || avail < 900) 3 else 5
            }
        } else {
            when (resolved) {
                HermesThinkingMode.INSTANT -> 4
                else -> baseMax + 2
            }
        }

        val settle = when (resolved) {
            HermesThinkingMode.INSTANT -> 120L
            HermesThinkingMode.THINKING -> 280L
            HermesThinkingMode.HIGH -> 350L
            HermesThinkingMode.ADAPTIVE -> 280L
        }.let { if (low) it + 60L else it }

        val topK = when (resolved) {
            HermesThinkingMode.INSTANT -> 20
            HermesThinkingMode.THINKING -> 32
            HermesThinkingMode.HIGH -> 40
            HermesThinkingMode.ADAPTIVE -> 32
        }.let { if (avail < 900) minOf(it, 20) else it }

        val temp = when (resolved) {
            HermesThinkingMode.INSTANT -> 0.03
            HermesThinkingMode.THINKING -> 0.1
            HermesThinkingMode.HIGH -> 0.15
            HermesThinkingMode.ADAPTIVE -> 0.1
        }

        val notes = buildString {
            append("mode=${stored.name}→${resolved.name} complexity=$complexity ")
            append("mem=${avail}MB/${total}Mb low=$low")
            if (low) append(" | low-RAM: tighter caps")
        }

        XLog.i(TAG, "resolve $notes maxIter=$maxIter topK=$topK settle=${settle}ms")

        return HermesRuntimeSnapshot(
            mode = stored,
            resolvedMode = resolved,
            maxIterations = maxIter,
            screenSettleMs = settle,
            topK = topK,
            topP = if (resolved == HermesThinkingMode.INSTANT) 0.85 else 0.9,
            temperature = temp,
            forceToolOnEssay = true,
            autoScreenAfterAction = true,
            maxSameActionRepeats = if (resolved == HermesThinkingMode.INSTANT) 2 else 3,
            availMemMb = avail,
            totalMemMb = total,
            lowMemory = low,
            notes = notes
        )
    }

    /**
     * 0 = chat-like / trivial, 1 = single app, 2 = multi-step, 3 = hard multi-app / search+play.
     */
    fun estimateComplexity(task: String): Int {
        val t = task.lowercase()
        var score = 0
        val hard = listOf(
            "telegram", "whatsapp", "chrome", "browser", "youtube", "spotify",
            "تلگرام", "واتس", "کروم", "مرورگر", "یوتیوب", "سیو", "saved",
            "search", "جستجو", "سرچ", "play", "پخش", "پلی", "random", "رندوم"
        )
        val multi = listOf(" and ", " then ", " after ", " و ", " بعد ", " سپس ")
        if (hard.any { t.contains(it) || task.contains(it) }) score += 2
        if (multi.any { t.contains(it) }) score += 1
        if (task.length > 80) score += 1
        if (task.length > 160) score += 1
        return score.coerceIn(0, 3)
    }

    fun statusJson(snap: HermesRuntimeSnapshot, phase: String, detail: String = ""): String {
        return JSONObject()
            .put("phase", phase)
            .put("mode", snap.resolvedMode.name)
            .put("user_mode", snap.mode.name)
            .put("round_cap", snap.maxIterations)
            .put("avail_mem_mb", snap.availMemMb)
            .put("low_memory", snap.lowMemory)
            .put("detail", detail)
            .toString()
    }
}
