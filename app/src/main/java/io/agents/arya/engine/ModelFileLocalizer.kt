package io.agents.arya.engine

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * llama.cpp mmaps the GGUF. On Huawei/EMUI the file often lives on
 * /storage/emulated/0 (FUSE). mmap "succeeds" instantly, RSS stays ~70 MB,
 * then the first generate page-faults 1 GB through FUSE and the watchdog
 * kills it. Copy onto ext4 (app filesDir) once, then mmap that.
 */
object ModelFileLocalizer {

    fun isFusePath(path: String): Boolean {
        val p = path.lowercase()
        return p.startsWith("/storage/emulated") ||
            p.startsWith("/sdcard") ||
            p.startsWith("/mnt/sdcard") ||
            p.contains("/android/data/")
    }

    /**
     * Returns a path that is safe to mmap. Copies [src] into
     * filesDir/models/fast/ when needed. [onProgress] is 0–100 of the copy.
     */
    fun ensureFastPath(
        context: Context,
        src: File,
        onProgress: (Int, String) -> Unit,
    ): File {
        if (!src.exists() || src.length() < 1_048_576L) {
            throw EngineLoadException(
                EngineError.ERR_LOAD_FAILED,
                "Model file missing or tiny: ${src.absolutePath}",
            )
        }
        if (!isFusePath(src.absolutePath)) {
            EngineLog.i("ModelFileLocalizer", "already fast path ${src.absolutePath}")
            return src
        }
        val destDir = File(context.filesDir, "models/fast").apply { mkdirs() }
        val dest = File(destDir, src.name)
        if (dest.exists() && dest.length() == src.length()) {
            EngineLog.i("ModelFileLocalizer", "reuse internal ${dest.absolutePath} bytes=${dest.length()}")
            onProgress(100, "Using fast local copy")
            return dest
        }
        val tmp = File(destDir, src.name + ".copying")
        if (tmp.exists()) tmp.delete()
        EngineLog.i("ModelFileLocalizer", "copy ${src.length()} bytes ${src.absolutePath} -> ${dest.absolutePath}")
        onProgress(0, "Copying model off slow storage…")
        val total = src.length().coerceAtLeast(1L)
        FileInputStream(src).use { input ->
            FileOutputStream(tmp).use { output ->
                val buf = ByteArray(1024 * 1024)
                var copied = 0L
                var lastPct = -1
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    output.write(buf, 0, n)
                    copied += n
                    val pct = ((copied * 100) / total).toInt().coerceIn(0, 99)
                    if (pct != lastPct && pct % 5 == 0) {
                        lastPct = pct
                        onProgress(pct, "Copying model… $pct%")
                    }
                }
                output.fd.sync()
            }
        }
        if (tmp.length() != src.length()) {
            tmp.delete()
            throw EngineLoadException(
                EngineError.ERR_LOAD_FAILED,
                "Copy incomplete (${tmp.length()} != ${src.length()})",
            )
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            tmp.copyTo(dest, overwrite = true)
            tmp.delete()
        }
        onProgress(100, "Copy done")
        EngineLog.i("ModelFileLocalizer", "copy ok ${dest.length()} bytes")
        return dest
    }
}
