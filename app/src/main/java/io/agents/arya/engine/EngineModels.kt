package io.agents.arya.engine

import org.json.JSONArray
import org.json.JSONObject

/** AIDL / EngineCore error codes (S1). */
object EngineError {
    const val ERR_BUSY = 1
    const val ERR_NO_MODEL = 2
    const val ERR_LOAD_FAILED = 3
    const val ERR_CANCELLED = 4
    const val ERR_DEADLINE = 5
    const val ERR_NATIVE = 6
    const val ERR_OOM_PREVENTED = 7

    fun message(code: Int, detail: String? = null): String {
        val base = when (code) {
            ERR_BUSY -> "Engine is busy with another generation"
            ERR_NO_MODEL -> "No model is loaded"
            ERR_LOAD_FAILED -> "Model failed to load"
            ERR_CANCELLED -> "Generation cancelled"
            ERR_DEADLINE -> "Generation hit a deadline"
            ERR_NATIVE -> "Engine process crashed or native error"
            ERR_OOM_PREVENTED -> "Refused to load: not enough RAM"
            else -> "Unknown engine error ($code)"
        }
        return if (detail.isNullOrBlank()) base else "$base: $detail"
    }
}

sealed interface EngineState {
    data object Disconnected : EngineState
    data object Connecting : EngineState
    data class Loading(val pct: Int, val phase: String) : EngineState
    data class Ready(val modelPath: String) : EngineState
    data object Busy : EngineState
    data class Crashed(val reason: String) : EngineState
    data class Quarantined(val modelPath: String) : EngineState
}

data class EngineLoadProgress(
    val pct: Int = 0,
    val phase: String = "",
)

sealed interface EngineEvent {
    data class Delta(val text: String) : EngineEvent
    data class Done(val statsJson: String) : EngineEvent
    data class Failed(val code: Int, val message: String) : EngineEvent
    data class LoadProgress(val pct: Int, val phase: String) : EngineEvent
}

data class EngineRequest(
    val prompt: String,
    val promptMode: String = "full",
    val prefixKey: String? = null,
    val maxTokens: Int = 512,
    val temperature: Double = 0.3,
    val topP: Double = 0.9,
    val topK: Int = 32,
    val repeatPenalty: Double = 1.12,
    val stop: List<String> = listOf("<|im_end|>", "</tool_call>"),
    val deadlineMs: Long = 45_000L,
    val tokenDeadlineMs: Long = 4_000L,
    val warmupKey: String? = null,
) {
    fun toJson(): String = JSONObject().apply {
        put("prompt", prompt)
        put("promptMode", promptMode)
        if (!prefixKey.isNullOrBlank()) put("prefixKey", prefixKey)
        if (!warmupKey.isNullOrBlank()) put("warmupKey", warmupKey)
        put("maxTokens", maxTokens)
        put("temperature", temperature)
        put("topP", topP)
        put("topK", topK)
        put("repeatPenalty", repeatPenalty)
        put("stop", JSONArray(stop))
        put("deadlineMs", deadlineMs)
        put("tokenDeadlineMs", tokenDeadlineMs)
    }.toString()

    companion object {
        fun parse(json: String): EngineRequest {
            val o = JSONObject(json)
            val stops = mutableListOf<String>()
            val arr = o.optJSONArray("stop")
            if (arr != null) {
                for (i in 0 until arr.length()) stops += arr.optString(i)
            }
            return EngineRequest(
                prompt = o.optString("prompt"),
                promptMode = o.optString("promptMode", "full"),
                prefixKey = o.optString("prefixKey").ifBlank { null },
                maxTokens = o.optInt("maxTokens", 512),
                temperature = o.optDouble("temperature", 0.3),
                topP = o.optDouble("topP", 0.9),
                topK = o.optInt("topK", 32),
                repeatPenalty = o.optDouble("repeatPenalty", 1.12),
                stop = if (stops.isEmpty()) listOf("<|im_end|>", "</tool_call>") else stops,
                deadlineMs = o.optLong("deadlineMs", 45_000L),
                tokenDeadlineMs = o.optLong("tokenDeadlineMs", 4_000L),
                warmupKey = o.optString("warmupKey").ifBlank { null },
            )
        }
    }
}

data class EngineInfo(
    val loaded: Boolean,
    val modelPath: String?,
    val ctxSize: Int,
    val nThreads: Int,
    val rawJson: String,
) {
    companion object {
        fun parse(json: String): EngineInfo {
            return try {
                val o = org.json.JSONObject(json)
                EngineInfo(
                    loaded = o.optBoolean("loaded", json.isNotBlank()),
                    modelPath = o.optString("model_path").ifBlank { null },
                    ctxSize = o.optInt("ctx", 0),
                    nThreads = o.optInt("n_threads", 0),
                    rawJson = json,
                )
            } catch (_: Exception) {
                EngineInfo(loaded = json.isNotBlank(), modelPath = null, ctxSize = 0, nThreads = 0, rawJson = json)
            }
        }
    }
}

data class EngineStats(
    val rawJson: String,
    val loaded: Boolean,
)
