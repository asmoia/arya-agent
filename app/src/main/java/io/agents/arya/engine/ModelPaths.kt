package io.agents.arya.engine

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
}
