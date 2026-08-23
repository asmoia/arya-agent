package io.agents.arya.engine.budget

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.tencent.mmkv.MMKV
import org.json.JSONObject

/**
 * Persist / restore DeviceProfile. Shared by engine (bench) and main (catalog gating).
 */
object DeviceProfileStore {
    const val CURRENT_VERSION = 1
    private const val KEY = "device_profile_json"

    fun mmkv(): MMKV = MMKV.defaultMMKV()

    fun read(@Suppress("UNUSED_PARAMETER") context: Context? = null): MemoryBudget.DeviceProfile? {
        val raw = try {
            mmkv().decodeString(KEY, null)
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            val o = JSONObject(raw)
            val version = o.optInt("version", 0)
            if (version != CURRENT_VERSION) return null
            MemoryBudget.DeviceProfile(
                version = version,
                bigCores = o.optInt("bigCores", 4),
                bestThreads = o.optInt("bestThreads", 4),
                memBwGbs = o.optDouble("memBwGbs", 10.0),
                flashMbps = o.optDouble("flashMbps", 200.0),
                ramClass = o.optString("ramClass", "4GB"),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun write(profile: MemoryBudget.DeviceProfile) {
        val json = JSONObject().apply {
            put("version", profile.version)
            put("bigCores", profile.bigCores)
            put("bestThreads", profile.bestThreads)
            put("memBwGbs", profile.memBwGbs)
            put("flashMbps", profile.flashMbps)
            put("ramClass", profile.ramClass)
        }.toString()
        mmkv().encode(KEY, json)
    }

    fun readDeviceRam(context: Context): Triple<Long, Long, Boolean> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        if (am != null) {
            am.getMemoryInfo(info)
            val low = if (Build.VERSION.SDK_INT >= 19) am.isLowRamDevice else info.totalMem < 3L * 1024 * 1024 * 1024
            return Triple(info.totalMem, info.availMem, low)
        }
        return Triple(4L * 1024 * 1024 * 1024, 2L * 1024 * 1024 * 1024, false)
    }
}
