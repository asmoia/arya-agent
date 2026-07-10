package io.agents.arya.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PersianCommandCompilerTest {
    @Test
    fun compilesExplicitOpenAndMessageChain() {
        val plan = PersianCommandCompiler.compile("تلگرام رو باز کن و به علی بگو دیر میام")
        assertNotNull(plan)
        assertEquals("send_message", plan!!.steps.single().toolName)
        assertEquals("Telegram", plan.steps.single().params["app"])
        assertEquals("علی", plan.steps.single().params["contact"])
    }

    @Test
    fun doesNotSplitQuotedMessageContent() {
        val plan = PersianCommandCompiler.compile("تلگرام رو باز کن و به علی بگو «سلام و خوبی؟»")
        assertNotNull(plan)
        assertEquals("سلام و خوبی؟", plan!!.steps.single().params["message"])
    }

    @Test
    fun leavesUnknownCompoundForAgent() {
        assertNull(PersianCommandCompiler.compile("تلگرام رو باز کن و ببین امروز چه خبر شده"))
    }
}
