package io.agents.arya.engine

import org.json.JSONObject

/**
 * Path helpers shared by EngineService / EngineCore / EngineClient.
 * Pure JVM — no Android types — so unit tests can compile this file.
 */
object ModelPaths {
    fun fileName(path: String): String {
        val cut = path.replace('\\', '/')
        return cut.substringAfterLast('/')
    }

    fun sameModel(loadedPath: String?, requestedPath: String): Boolean {
        if (loadedPath.isNullOrBlank() || requestedPath.isBlank()) return false
        if (loadedPath == requestedPath) return true
        val a = fileName(loadedPath)
        val b = fileName(requestedPath)
        return a.isNotEmpty() && a.equals(b, ignoreCase = true)
    }

    fun statsLooksLike(statsJson: String, requestedPath: String): Boolean {
        if (statsJson.isBlank() || requestedPath.isBlank()) return false
        if (statsJson.contains(requestedPath)) return true
        val name = fileName(requestedPath)
        return name.isNotEmpty() && statsJson.contains(name)
    }

    /**
     * True only when the engine has a real GGUF resident in RAM.
     *
     * Huawei fake-ready was `loaded=true`, `model_size_mb≈1223` (file size from
     * stat) and ~70 MB RSS. The 80 MB file-size gate never caught it.
     */
    fun isResident(statsJson: String): Boolean {
        if (statsJson.isBlank()) return false
        return try {
            val o = JSONObject(statsJson)
            if (!o.optBoolean("loaded", false)) return false
            val info = o.optJSONObject("model_info")
            val sizeMb = info?.optDouble("model_size_mb", 0.0) ?: 0.0
            if (sizeMb < 80.0) return false
            val rssMb = info?.optDouble("rss_mb", -1.0) ?: -1.0
            // rss_mb < 0 → field missing (old binary); file size is all we have.
            if (rssMb >= 0.0 && sizeMb >= 200.0 && rssMb < 200.0) return false
            true
        } catch (_: Exception) {
            false
        }
    }
}
