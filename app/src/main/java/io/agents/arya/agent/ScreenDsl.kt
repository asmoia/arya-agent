// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent

/** Compact, deterministic representation of the accessibility tree for local LLM prompts. */
data class ScreenDslNode(
    val nodeId: String,
    val label: String,
    val role: String,
    val flags: Set<String>,
    val x: Int,
    val y: Int,
    val score: Int,
)

data class ScreenDsl(
    val nodes: List<ScreenDslNode>,
    val hasEditable: Boolean,
    val hasScrollable: Boolean,
) {
    fun toPrompt(maxNodes: Int = 10): String = buildString {
        appendLine("[UI]")
        append("editable=").append(hasEditable).append(" scrollable=").append(hasScrollable).appendLine()
        nodes.take(maxNodes).forEachIndexed { index, node ->
            append(index + 1).append(". ").append(node.nodeId)
                .append(" role=").append(node.role)
                .append(" label=").append(node.label.take(70))
                .append(" flags=").append(node.flags.joinToString(","))
                .append(" @").append(node.x).append(',').append(node.y)
                .appendLine()
        }
    }
}

object ScreenDslParser {
    private val nodePattern = Regex("""\[(n\d+)]\s*(?:\"([^\"]*)\")?\s*([^()]*)\((\d+),(\d+)\)""")

    fun parse(tree: String, task: String?): ScreenDsl {
        val tokens = PersianNormalizer.normalize(task.orEmpty()).split(Regex("[^\\p{L}\\p{Nd}]+"))
            .filter { it.length >= 2 }.take(12)
        val nodes = tree.lineSequence().mapNotNull { raw ->
            val match = nodePattern.find(raw.trim()) ?: return@mapNotNull null
            val id = match.groupValues[1]
            val label = match.groupValues[2].ifBlank { "(unlabeled)" }
            val flagsText = match.groupValues[3].trim().lowercase()
            val flags = buildSet {
                if (flagsText.contains("tap")) add("tap")
                if (flagsText.contains("edit")) add("edit")
                if (flagsText.contains("scroll")) add("scroll")
                if (flagsText.contains("on") || flagsText.contains("off")) add("toggle")
            }
            var score = flags.size * 30
            if (flags.contains("edit")) score += 50
            if (flags.contains("tap")) score += 20
            val lowerLabel = label.lowercase()
            if (listOf("play", "search", "send", "saved", "پخش", "جستجو", "ارسال", "ذخیره").any { lowerLabel.contains(it) }) score += 60
            score += tokens.count { lowerLabel.contains(it) } * 20
            ScreenDslNode(id, label, when {
                flags.contains("edit") -> "input"
                flags.contains("scroll") -> "scroll"
                flags.contains("tap") -> "action"
                else -> "text"
            }, flags, match.groupValues[4].toInt(), match.groupValues[5].toInt(), score)
        }.sortedByDescending { it.score }.take(18).toList()
        return ScreenDsl(nodes, nodes.any { "edit" in it.flags }, nodes.any { "scroll" in it.flags })
    }
}
