// Copyright 2026 Arya Agent. Licensed under the Apache License, Version 2.0.
package io.agents.arya.agent

/** Extension point for bounded, testable flows for high-frequency apps. */
interface AppFlowAdapter {
    val cardId: String
    fun canCompile(task: String): Boolean
    fun compile(task: String): DeterministicPlan?
}

object AppFlowAdapterRegistry {
    private val adapters = listOf(
        TelegramFlowAdapter,
        BrowserFlowAdapter,
    )

    fun compile(task: String): DeterministicPlan? = adapters.firstNotNullOfOrNull { adapter ->
        if (adapter.canCompile(task)) adapter.compile(task) else null
    }
}

object TelegramFlowAdapter : AppFlowAdapter {
    override val cardId = "telegram"
    override fun canCompile(task: String): Boolean = PersianNormalizer.normalize(task).contains("تلگرام") || task.contains("telegram", true)
    override fun compile(task: String): DeterministicPlan? = PersianCommandCompiler.compile(task)
}

object BrowserFlowAdapter : AppFlowAdapter {
    override val cardId = "browser"
    override fun canCompile(task: String): Boolean {
        val normalized = PersianNormalizer.normalize(task)
        return normalized.contains("مرورگر") || normalized.contains("کروم") || normalized.contains("گوگل") || task.contains("browser", true) || task.contains("chrome", true)
    }
    override fun compile(task: String): DeterministicPlan? = PersianCommandCompiler.compile(task)
}
