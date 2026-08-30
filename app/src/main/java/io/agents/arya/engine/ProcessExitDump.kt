package io.agents.arya.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes Android 11+ historical process deaths. This is the only way to
 * distinguish SIGKILL / LMK / OEM "other" from a catchable SIGILL when
 * native-crash.txt is empty.
 */
object ProcessExitDump {
    private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)

    fun writeToEngineLogs(context: Context) {
        val dir = File(context.cacheDir, "engine_logs").apply { mkdirs() }
        val text = dumpText(context)
        runCatching {
            File(dir, "exit-info.txt").writeText(text)
        }
        writeTombstones(context, dir)
        EngineLog.i("ProcessExitDump", text.take(800).replace("\n", " | "))
    }

    fun dumpText(context: Context): String {
        if (Build.VERSION.SDK_INT < 30) {
            return "ApplicationExitInfo requires API 30. sdk=${Build.VERSION.SDK_INT}\n"
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 20)
            buildString {
                appendLine("count=${exits.size} now=${timeFmt.format(Date())} pid=${android.os.Process.myPid()}")
                if (exits.isEmpty()) appendLine("(none recorded yet)")
                exits.forEachIndexed { i, info ->
                    appendLine("----- exit[$i] -----")
                    appendLine(formatOne(info))
                }
            }
        } catch (e: Exception) {
            "getHistoricalProcessExitReasons failed: ${e.javaClass.simpleName}: ${e.message}\n"
        }
    }

    fun reasonName(reason: Int): String {
        if (Build.VERSION.SDK_INT < 30) return "reason=$reason"
        return when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
            ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
            ApplicationExitInfo.REASON_CRASH -> "CRASH_JAVA"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION -> "INITIALIZATION"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
            ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
            ApplicationExitInfo.REASON_OTHER -> "OTHER_OEM"
            else -> {
                if (Build.VERSION.SDK_INT >= 31 && reason == ApplicationExitInfo.REASON_FREEZER) {
                    "FREEZER"
                } else {
                    "UNKNOWN_$reason"
                }
            }
        }
    }

    fun signalName(status: Int): String = when (status) {
        4 -> "SIGILL"
        5 -> "SIGTRAP"
        6 -> "SIGABRT"
        7 -> "SIGBUS"
        8 -> "SIGFPE"
        9 -> "SIGKILL"
        11 -> "SIGSEGV"
        13 -> "SIGPIPE"
        31 -> "SIGSYS"
        else -> "sig=$status"
    }

    private fun formatOne(info: ApplicationExitInfo): String {
        if (Build.VERSION.SDK_INT < 30) return ""
        val reason = info.reason
        val status = info.status
        return buildString {
            appendLine("timestamp=${timeFmt.format(Date(info.timestamp))}")
            appendLine("process=${info.processName}")
            appendLine("pid=${info.pid} realUid=${info.realUid} packageUid=${info.packageUid}")
            appendLine("reason=${reasonName(reason)} ($reason)")
            appendLine("status=$status ${if (reason == ApplicationExitInfo.REASON_SIGNALED) signalName(status) else ""}".trimEnd())
            appendLine("importance=${info.importance} pss_kb=${info.pss} rss_kb=${info.rss}")
            appendLine("description=${info.description ?: "(none)"}")
        }
    }

    private fun writeTombstones(context: Context, dir: File) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            exits.forEachIndexed { i, info ->
                val stream = try {
                    info.traceInputStream
                } catch (_: Exception) {
                    null
                } ?: return@forEachIndexed
                stream.use { input ->
                    val out = File(dir, "exit-tombstone-$i.txt")
                    FileOutputStream(out).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }
}
