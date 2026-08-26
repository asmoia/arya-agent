package io.agents.arya.engine

import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rolling log that lives in the `:engine` process (and can also be written
 * from the main process for bind/load breadcrumbs). Same UID as the UI
 * process, so DebugReportManager can zip these files.
 */
object EngineLog {
    private const val TAG = "AryaEngineLog"
    private const val DIR = "engine_logs"
    private const val ACTIVE = "arya-engine.log"
    private const val PREV = "arya-engine.prev.log"
    private const val NATIVE_CRASH = "native-crash.txt"
    private const val NATIVE_LOAD_STAGE = "native-load-stage.txt"
    private const val MAX_BYTES = 768L * 1024L

    @Volatile
    private var appContext: Context? = null
    private val lock = Any()

    @JvmStatic
    fun init(context: Context) {
        appContext = context.applicationContext
        resolveDir(context)
        i("boot", "EngineLog init pid=${Process.myPid()} process=${processName(context)}")
    }

    @JvmStatic
    fun i(tag: String, message: String) = write("I", tag, message, null)

    @JvmStatic
    fun w(tag: String, message: String, err: Throwable? = null) = write("W", tag, message, err)

    @JvmStatic
    fun e(tag: String, message: String, err: Throwable? = null) = write("E", tag, message, err)

    @JvmStatic
    fun listFiles(context: Context): List<File> {
        val dir = resolveDir(context) ?: return emptyList()
        return listOf(
            File(dir, PREV),
            File(dir, ACTIVE),
            File(dir, NATIVE_CRASH),
            File(dir, NATIVE_LOAD_STAGE),
        ).filter { it.exists() && it.isFile && it.length() > 0L }
    }

    private fun write(level: String, tag: String, message: String, err: Throwable?) {
        val line = buildString {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date())
            append(ts).append(' ').append(level).append('/').append(tag)
            append(" pid=").append(Process.myPid())
            append(" thread=").append(Thread.currentThread().name)
            append(' ').append(message)
            if (err != null) {
                append('\n').append(Log.getStackTraceString(err).trimEnd())
            }
            append('\n')
        }
        when (level) {
            "E" -> Log.e(TAG, "$tag $message", err)
            "W" -> Log.w(TAG, "$tag $message", err)
            else -> Log.i(TAG, "$tag $message")
        }
        val dir = resolveDir() ?: return
        val bytes = line.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            val active = File(dir, ACTIVE)
            if (active.exists() && active.length() + bytes.size > MAX_BYTES) {
                val prev = File(dir, PREV)
                if (prev.exists()) prev.delete()
                active.renameTo(prev)
            }
            runCatching {
                FileOutputStream(active, true).use {
                    it.write(bytes)
                    it.flush()
                }
            }
        }
    }

    private fun resolveDir(context: Context? = appContext): File? {
        val cache = context?.cacheDir ?: return null
        return File(cache, DIR).apply { mkdirs() }
    }

    private fun processName(context: Context): String {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                context.applicationInfo.processName + "/" + android.app.Application.getProcessName()
            } else {
                "pid=${Process.myPid()}"
            }
        } catch (_: Exception) {
            "unknown"
        }
    }
}
