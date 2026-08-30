package io.agents.arya.agent.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMlPromptTest {
    @Test
    fun nonThinkingPrefillsOfficialEmptyThinkBlock() {
        val prompt = ChatMlPrompt.build(
            listOf(ChatMsg(Role.USER, "سلام")),
            emptyList(),
            enableThinking = false,
        )
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertFalse(prompt.contains("/no_think"))
        assertTrue(prompt.contains("<|im_start|>user\nسلام<|im_end|>"))
    }

    @Test
    fun functionGemmaUsesOfficialDeclarationAndModelTurn() {
        val tools = listOf(
            ToolSpec(
                name = "get_current_temperature",
                descriptionFa = "Gets the current temperature",
                paramsJsonSchema = """{"type":"object","properties":{"location":{"type":"string","description":"City"}},"required":["location"]}""",
            ),
        )
        val prompt = FunctionGemmaPrompt.build(listOf(ChatMsg(Role.USER, "دمای تهران چنده؟")), tools)
        assertTrue(prompt.contains("<start_of_turn>developer"))
        assertTrue(prompt.contains("<start_function_declaration>declaration:get_current_temperature"))
        assertTrue(prompt.contains("<start_of_turn>user"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
        assertFalse(prompt.startsWith("<bos>"))
        assertFalse(prompt.contains("<|im_start|>"))
    }

    @Test
    fun thinkingModeDoesNotPrefillEmptyThink() {
        val prompt = ChatMlPrompt.build(
            listOf(ChatMsg(Role.USER, "hi")),
            emptyList(),
            enableThinking = true,
        )
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
        assertFalse(prompt.contains("<think>"))
        assertFalse(prompt.contains("/no_think"))
    }
}
