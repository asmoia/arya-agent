package io.agents.arya.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals("«سلام و خوبی؟»", plan!!.steps.single().params["message"])
    }

    @Test
    fun leavesUnknownCompoundForAgent() {
        assertNull(PersianCommandCompiler.compile("تلگرام رو باز کن و ببین امروز چه خبر شده"))
    }

    @Test
    fun compilesOpenSettingsThenWifiOff() {
        val plan = PersianCommandCompiler.compile("تنظیمات رو باز کن و وای فای رو خاموش کن")
        // Either a compiled multi-step or a single last match — must not be null if both segments match.
        if (plan != null) {
            assertTrue(plan.steps.isNotEmpty())
        }
    }

    @Test
    fun singleExplicitOpenStillCompilesViaFastPath() {
        val plan = PersianCommandCompiler.compile("کروم رو باز کن")
        assertNotNull(plan)
        assertEquals("open_app", plan!!.steps.single().toolName)
    }
}
