package io.agents.arya.engine.budget

import android.content.Context
import com.tencent.mmkv.MMKV
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Random

class DeviceProfileManager(private val context: Context) {
    companion object {
        const val CURRENT_VERSION = 1
        private const val KEY_PROFILE = "device_profile_json"
    }

    private val mmkv: MMKV by lazy { MMKV.defaultMMKV() }

    fun getProfile(): MemoryBudget.DeviceProfile? {
        val jsonStr = mmkv.decodeString(KEY_PROFILE, null) ?: return null
        return try {
            val json = JSONObject(jsonStr)
            MemoryBudget.DeviceProfile(
                version = json.optInt("version", CURRENT_VERSION),
                bigCores = json.optInt("bigCores", 4),
                bestThreads = json.optInt("bestThreads", 4),
                memBwGbs = json.optDouble("memBwGbs", 10.0),
                flashMbps = json.optDouble("flashMbps", 200.0),
                ramClass = json.optString("ramClass", getRamClass())
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getRamClass(): String {
        val runtime = Runtime.getRuntime()
        val totalRamMb = (runtime.totalMemory() + runtime.freeMemory()) / (1024 * 1024)
        return when {
            totalRamMb <= 3500 -> "3GB"
            totalRamMb <= 5000 -> "4GB"
            totalRamMb <= 7000 -> "6GB"
            else -> "8GB+"
        }
    }

    fun runBenchIfNeeded(onProgress: (Int, String) -> Unit): MemoryBudget.DeviceProfile {
        val existing = getProfile()
        if (existing != null && existing.version == CURRENT_VERSION) {
            return existing
        }

        onProgress(10, "بررسی هسته‌های پردازنده...")
        val nProcs = Runtime.getRuntime().availableProcessors()
        val bigCores = detectBigCores(nProcs)
        val bestThreads = kotlin.math.min(4, kotlin.math.max(1, bigCores))

        onProgress(40, "تست سرعت حافظه فلش...")
        val flashMbps = benchFlashSpeed()

        onProgress(70, "تست پهنای باند حافظه...")
        val memBwGbs = benchMemoryBandwidth()

        val profile = MemoryBudget.DeviceProfile(
            version = CURRENT_VERSION,
            bigCores = bigCores,
            bestThreads = bestThreads,
            memBwGbs = memBwGbs,
            flashMbps = flashMbps,
            ramClass = getRamClass()
        )

        saveProfile(profile)
        onProgress(100, "تست سخت‌افزار تکمیل شد")
        return profile
    }

    private fun saveProfile(profile: MemoryBudget.DeviceProfile) {
        val json = JSONObject().apply {
            put("version", profile.version)
            put("bigCores", profile.bigCores)
            put("bestThreads", profile.bestThreads)
            put("memBwGbs", profile.memBwGbs)
            put("flashMbps", profile.flashMbps)
            put("ramClass", profile.ramClass)
        }
        mmkv.encode(KEY_PROFILE, json.toString())
    }

    private fun detectBigCores(nProcs: Int): Int {
        var bigCores = 0
        for (i in 0 until nProcs) {
            val freqFile = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (freqFile.exists()) {
                try {
                    val freq = freqFile.readText().trim().toLongOrNull() ?: 0L
                    if (freq > 1500000) bigCores++
                } catch (_: Exception) {}
            }
        }
        return if (bigCores > 0) bigCores else kotlin.math.max(1, nProcs / 2)
    }

    private fun benchFlashSpeed(): Double {
        return try {
            val testFile = File(context.cacheDir, "bench_test.tmp")
            val data = ByteArray(10 * 1024 * 1024) // 10MB
            Random().nextBytes(data)
            val t0 = System.currentTimeMillis()
            FileOutputStream(testFile).use { it.write(data) }
            val t1 = System.currentTimeMillis()
            testFile.delete()
            val durationSec = kotlin.math.max(0.001, (t1 - t0) / 1000.0)
            (10.0) / durationSec
        } catch (e: Exception) {
            100.0
        }
    }

    private fun benchMemoryBandwidth(): Double {
        val size = 16 * 1024 * 1024 // 16MB
        val a = ByteArray(size)
        val b = ByteArray(size)
        val t0 = System.currentTimeMillis()
        System.arraycopy(a, 0, b, 0, size)
        val t1 = System.currentTimeMillis()
        val durationSec = kotlin.math.max(0.001, (t1 - t0) / 1000.0)
        return (32.0 / (1024.0)) / durationSec // GB/s
    }
}
