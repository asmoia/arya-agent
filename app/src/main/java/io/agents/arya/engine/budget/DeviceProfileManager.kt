package io.agents.arya.engine.budget

import android.content.Context
import io.agents.arya.engine.EngineNative
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * First-run ~15s micro-bench (S3). Runs in the `:engine` process.
 */
class DeviceProfileManager(private val context: Context) {

    fun getProfile(): MemoryBudget.DeviceProfile? = DeviceProfileStore.read(context)

    fun runBenchIfNeeded(onProgress: (Int, String) -> Unit): MemoryBudget.DeviceProfile {
        val existing = getProfile()
        if (existing != null && existing.version == DeviceProfileStore.CURRENT_VERSION) {
            return existing
        }
        return runBench(onProgress)
    }

    fun runBench(onProgress: (Int, String) -> Unit): MemoryBudget.DeviceProfile {
        onProgress(5, "Detecting CPU cores")
        val bigCores = try {
            val n = EngineNative.nativeDetectBigCores()
            if (n > 0) n else detectBigCoresFallback()
        } catch (_: Throwable) {
            detectBigCoresFallback()
        }

        onProgress(25, "Measuring compute")
        var bestThreads = minOf(4, maxOf(1, bigCores))
        var memBw = 8.0
        try {
            val candidates = listOf(2, 4, 6).filter { it <= maxOf(2, bigCores + 2) }
            var bestScore = -1.0
            for (t in candidates) {
                val json = EngineNative.nativeBench(t)
                val o = JSONObject(json)
                val gflops = o.optDouble("gflops", 0.0)
                val bw = o.optDouble("mem_bw_gbs", 0.0)
                if (bw > memBw) memBw = bw
                val score = gflops / t
                if (score > bestScore) {
                    bestScore = score
                    bestThreads = t
                }
            }
        } catch (_: Throwable) {
            memBw = benchMemoryBandwidthFallback()
        }

        onProgress(70, "Measuring flash speed")
        val flash = benchFlashSpeed()

        onProgress(90, "Classifying RAM")
        val (total, _, _) = DeviceProfileStore.readDeviceRam(context)
        val ramClass = MemoryBudget.ramClassOf(total)

        val profile = MemoryBudget.DeviceProfile(
            version = DeviceProfileStore.CURRENT_VERSION,
            bigCores = bigCores,
            bestThreads = bestThreads.coerceIn(1, 6),
            memBwGbs = memBw,
            flashMbps = flash,
            ramClass = ramClass,
        )
        DeviceProfileStore.write(profile)
        onProgress(100, "Device profile ready")
        return profile
    }

    private fun detectBigCoresFallback(): Int {
        var big = 0
        val n = Runtime.getRuntime().availableProcessors()
        for (i in 0 until n) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (f.exists()) {
                val freq = f.readText().trim().toLongOrNull() ?: 0L
                if (freq > 1_500_000) big++
            }
        }
        return if (big > 0) big else maxOf(1, n / 2)
    }

    private fun benchFlashSpeed(): Double {
        return try {
            val test = File(context.filesDir, "bench_flash.bin")
            val chunk = ByteArray(1024 * 1024)
            java.util.Random(1).nextBytes(chunk)
            val t0 = System.nanoTime()
            RandomAccessFile(test, "rw").use { raf ->
                repeat(64) { raf.write(chunk) }
                raf.fd.sync()
            }
            val t1 = System.nanoTime()
            test.delete()
            val sec = ((t1 - t0) / 1e9).coerceAtLeast(0.001)
            64.0 / sec
        } catch (_: Exception) {
            150.0
        }
    }

    private fun benchMemoryBandwidthFallback(): Double {
        val size = 32 * 1024 * 1024
        val a = ByteArray(size)
        val b = ByteArray(size)
        val t0 = System.nanoTime()
        System.arraycopy(a, 0, b, 0, size)
        val t1 = System.nanoTime()
        val sec = ((t1 - t0) / 1e9).coerceAtLeast(1e-6)
        return (size.toDouble() / (1024 * 1024 * 1024)) / sec
    }
}
