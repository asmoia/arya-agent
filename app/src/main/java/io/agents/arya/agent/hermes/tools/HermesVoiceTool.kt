// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
// Offline-first voice analysis tool for Arya Hermes core.

package io.agents.arya.agent.hermes.tools

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.speech.RecognizerIntent
import io.agents.arya.ClawApplication
import io.agents.arya.agent.llm.LlmSessionManager
import io.agents.arya.tool.BaseTool
import io.agents.arya.tool.ToolParameter
import io.agents.arya.tool.ToolResult
import io.agents.arya.utils.XLog
import org.json.JSONObject
import java.io.File

/**
 * Voice analysis tool.
 *
 * Capabilities today:
 * - metadata / duration / mime for a local audio file
 * - launch system speech recognizer for live dictation (returns guidance + optional transcript if already provided)
 * - summarize / analyze a provided transcript with the active LLM (local Gemma or cloud)
 *
 * To listen for Telegram/WhatsApp voice in background use tool listen_voice. Full offline waveform→text (Whisper-class) is not bundled yet; when a transcript
 * is available (system STT, messaging app caption, or user paste), analysis runs
 * through the offline/local LLM path the user configured.
 */
class HermesVoiceTool : BaseTool() {

    override fun getName(): String = "hermes_voice"

    override fun getDescriptionEN(): String =
        "Analyze voice/audio: metadata of a file, live speech-to-text via system UI, or LLM analysis of a transcript (works with offline Gemma)."

    override fun getDescriptionCN(): String =
        "语音/音频分析：文件元数据、系统语音转文字、或对转写文本做 LLM 分析（可用离线模型）"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter(
            "action",
            "string",
            "metadata | analyze_transcript | stt_info | summarize",
            true
        ),
        ToolParameter(
            "path",
            "string",
            "Absolute path to a local audio file (for metadata)",
            false
        ),
        ToolParameter(
            "transcript",
            "string",
            "Speech transcript text to analyze/summarize",
            false
        ),
        ToolParameter(
            "language",
            "string",
            "BCP-47 language hint, e.g. fa-IR or en-US",
            false
        ),
        ToolParameter(
            "question",
            "string",
            "Optional question about the transcript (default: summarize + key points)",
            false
        )
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val action = params["action"]?.toString()?.lowercase()?.trim()
            ?: return ToolResult.error("Missing action")
        return try {
            when (action) {
                "metadata" -> metadata(params["path"]?.toString())
                "stt_info" -> sttInfo(params["language"]?.toString())
                "analyze_transcript", "summarize" -> analyzeTranscript(params)
                else -> ToolResult.error("Unknown action: $action")
            }
        } catch (e: Exception) {
            XLog.e(TAG, "hermes_voice failed", e)
            ToolResult.error("hermes_voice failed: ${e.message}")
        }
    }

    private fun metadata(path: String?): ToolResult {
        if (path.isNullOrBlank()) return ToolResult.error("path required for metadata")
        val file = File(path)
        if (!file.exists() || !file.isFile) {
            return ToolResult.error("File not found: $path")
        }
        if (file.length() > MAX_FILE_BYTES) {
            return ToolResult.error("File too large (>${MAX_FILE_BYTES / 1024 / 1024}MB)")
        }
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
            val mime = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "unknown"
            val bitrate = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
            val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val json = JSONObject()
                .put("path", file.absolutePath)
                .put("size_bytes", file.length())
                .put("duration_ms", durationMs)
                .put("duration_sec", if (durationMs >= 0) durationMs / 1000.0 else JSONObject.NULL)
                .put("mime", mime)
                .put("bitrate", bitrate)
                .put("title", title)
                .put(
                    "note",
                    "Offline waveform→text (Whisper) is not embedded yet. " +
                        "Use system STT (mic in chat) or provide transcript= for analyze_transcript. " +
                        "Analysis uses your active model (prefer local Gemma 4 E4B 3.6GB)."
                )
            ToolResult.success(json.toString(2))
        } catch (e: Exception) {
            ToolResult.error("Cannot read audio metadata: ${e.message}")
        } finally {
            try {
                mmr.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun sttInfo(language: String?): ToolResult {
        val lang = language?.ifBlank { null } ?: "fa-IR"
        val ctx = ClawApplication.instance
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        val can = intent.resolveActivity(ctx.packageManager) != null
        return ToolResult.success(
            JSONObject()
                .put("system_stt_available", can)
                .put("language", lang)
                .put(
                    "how_to_use",
                    "In Arya chat, tap the mic button (RecognizerIntent). " +
                        "Or pass the resulting text to hermes_voice action=analyze_transcript with transcript=..."
                )
                .put(
                    "offline_note",
                    "On-device STT quality depends on the phone's offline speech pack. " +
                        "Full Whisper offline ASR is planned; until then use system STT + local Gemma for understanding."
                )
                .toString(2)
        )
    }

    private fun analyzeTranscript(params: Map<String, Any>): ToolResult {
        val transcript = params["transcript"]?.toString()?.trim().orEmpty()
        if (transcript.isBlank()) {
            return ToolResult.error(
                "transcript required. Capture speech via chat mic (system STT), then call " +
                    "hermes_voice(action=analyze_transcript, transcript=\"...\")."
            )
        }
        if (transcript.length > MAX_TRANSCRIPT_CHARS) {
            return ToolResult.error("transcript too long (max $MAX_TRANSCRIPT_CHARS chars)")
        }
        val lang = params["language"]?.toString()?.ifBlank { "fa" } ?: "fa"
        val question = params["question"]?.toString()?.ifBlank { null }
            ?: "خلاصه کن، نکات کلیدی، لحن، و کارهای پیشنهادی را بگو."

        val prompt = buildString {
            appendLine("You are آریا voice analyst. Prefer the user's language ($lang).")
            appendLine("Task: $question")
            appendLine()
            appendLine("TRANSCRIPT:")
            appendLine(transcript.take(MAX_TRANSCRIPT_CHARS))
        }

        // Prefer whatever is configured (local Gemma E4B if selected).
        val answer = LlmSessionManager.singleShot(prompt, temperature = 0.2)
            ?: LlmSessionManager.singleShotLocal(prompt, temperature = 0.2)
            ?: LlmSessionManager.singleShotCloud(prompt, temperature = 0.2)

        return if (answer.isNullOrBlank()) {
            ToolResult.error(
                "No LLM available to analyze transcript. " +
                    "Download/select local Gemma 4 E4B (3.6GB) in LLM Config, or configure cloud."
            )
        } else {
            ToolResult.success(answer)
        }
    }

    companion object {
        private const val TAG = "HermesVoice"
        private const val MAX_FILE_BYTES = 80L * 1024L * 1024L
        private const val MAX_TRANSCRIPT_CHARS = 20_000
    }
}
