package io.agents.arya.ui.chat

/** Hide raw tool JSON from bubbles (S5 / S9). */
object ChatNoise {
    private val toolTag = Regex("<tool_call>[\\s\\S]*?</tool_call>", RegexOption.IGNORE_CASE)
    private val thinkTag = Regex("<think>[\\s\\S]*?</think>", RegexOption.IGNORE_CASE)

    fun sanitizeAssistant(text: String): String {
        return text.replace(toolTag, "")
            .replace(thinkTag, "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun looksLikeRawToolJson(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("<tool_call") || (t.startsWith("{") && t.contains("\"name\"") && t.contains("arguments"))
    }
}
