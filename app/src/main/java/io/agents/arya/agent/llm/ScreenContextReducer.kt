// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent.llm

import io.agents.arya.agent.ScreenDslParser

/** Keeps relevant actionable UI nodes instead of rawResult.take(400). */
object ScreenContextReducer {
    private const val MAX_CHARS = 1_600
    fun reduceToolResult(raw: String, taskHint: String?): String {
        if (raw.length <= MAX_CHARS) return raw
        val marker = "Screen after action:"
        val index = raw.indexOf(marker)
        if (index < 0 && !raw.contains("[n")) return raw.take(MAX_CHARS)
        val prefix = if (index >= 0) raw.take(index + marker.length).take(260) else "[Reduced UI context]"
        val screen = if (index >= 0) raw.substring(index + marker.length) else raw
        return "$prefix\n${reduceScreen(screen, taskHint)}".take(MAX_CHARS)
    }
    fun reduceScreen(screen: String, taskHint: String?, maxChars: Int = MAX_CHARS): String {
        if (screen.length <= maxChars) return screen
        val dsl = ScreenDslParser.parse(screen, taskHint)
        if (dsl.nodes.isNotEmpty()) return dsl.toPrompt().take(maxChars)
        val tokens = taskHint.orEmpty().lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.length >= 2 }.take(12)
        data class Candidate(val index: Int, val score: Int, val text: String)
        val nodes = screen.lineSequence().mapIndexedNotNull { index, raw ->
            val line = raw.trim(); if (line.isBlank()) return@mapIndexedNotNull null
            val lower = line.lowercase(); var score = 0
            if (line.contains("[n")) score += 25
            if (" tap" in lower || " edit" in lower || " scroll" in lower) score += 35
            if ("play" in lower || "search" in lower || "send" in lower || "saved" in lower || "پخش" in line || "جستجو" in line || "ذخیره" in line || "ارسال" in line) score += 45
            score += tokens.count { lower.contains(it) } * 20
            if (score == 0 && index > 40) return@mapIndexedNotNull null
            Candidate(index, score, line.take(180))
        }.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.index }).take(18).sortedBy { it.index }.toList()
        if (nodes.isEmpty()) return screen.take(maxChars)
        return buildString { appendLine("[Relevant visible UI nodes]"); nodes.forEach { appendLine(it.text) } }.take(maxChars)
    }
}
