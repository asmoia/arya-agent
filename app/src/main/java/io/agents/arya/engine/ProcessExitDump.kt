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
        // Numeric constants: some OEM compile SDKs omit later ApplicationExitInfo fields.
        return when (reason) {
            1 -> "EXIT_SELF"
            2 -> "SIGNALED"
            3 -> "LOW_MEMORY"
            4 -> "CRASH_JAVA"
            5 -> "CRASH_NATIVE"
            6 -> "ANR"
            7 -> "INITIALIZATION"
            8 -> "PERMISSION_CHANGE"
            9 -> "EXCESSIVE_RESOURCE"
            10 -> "USER_REQUESTED"
            11 -> "USER_STOPPED"
            12 -> "DEPENDENCY_DIED"
            13 -> "OTHER_OEM"
            14 -> "FREEZER"
            else -> "UNKNOWN_$reason"
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
            appendLine(
                "status=$status ${if (reason == ApplicationExitInfo.REASON_SIGNALED) signalName(status) else ""}".trimEnd(),
            )
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
