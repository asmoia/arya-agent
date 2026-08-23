package io.agents.arya.engine.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetTest {

    @Test
    fun test8GbDeviceNormalModelFits4096Ctx() {
        val totalRam = 8L * 1024 * 1024 * 1024
        val availRam = 4L * 1024 * 1024 * 1024
        val modelSize = 1200L * 1024 * 1024 // 1.2 GB model

        val inputs = MemoryBudget.Inputs(
            totalRamBytes = totalRam,
            availRamBytes = availRam,
            modelFileBytes = modelSize,
            isLowRamDevice = false
        )

        val plan = MemoryBudget.plan(inputs, null)
        assertTrue(plan is MemoryBudget.Plan.Load)
        val loadPlan = plan as MemoryBudget.Plan.Load
        assertEquals(4096, loadPlan.ctxSize)
    }

    @Test
    fun test4GbDeviceNormalModelFits2048Or1024Ctx() {
        val totalRam = 4L * 1024 * 1024 * 1024
        val availRam = 1800L * 1024 * 1024
        val modelSize = 1200L * 1024 * 1024

        val inputs = MemoryBudget.Inputs(
            totalRamBytes = totalRam,
            availRamBytes = availRam,
            modelFileBytes = modelSize,
            isLowRamDevice = false
        )

        val plan = MemoryBudget.plan(inputs, null)
        assertTrue(plan is MemoryBudget.Plan.Load)
    }

    @Test
    fun testOversizedModelExceeds45PercentRamRefused() {
        val totalRam = 4L * 1024 * 1024 * 1024
        val availRam = 3L * 1024 * 1024 * 1024
        val modelSize = 2500L * 1024 * 1024 // 2.5 GB model on 4GB RAM (62.5% of RAM)

        val inputs = MemoryBudget.Inputs(
            totalRamBytes = totalRam,
            availRamBytes = availRam,
            modelFileBytes = modelSize,
            isLowRamDevice = false
        )

        val plan = MemoryBudget.plan(inputs, null)
        assertTrue(plan is MemoryBudget.Plan.Refuse)
        val refuse = plan as MemoryBudget.Plan.Refuse
        assertTrue(refuse.suggestSmallerModel)
    }

    @Test
    fun testLowRamDeviceCapsAt1024Ctx() {
        val totalRam = 2L * 1024 * 1024 * 1024
        val availRam = 1200L * 1024 * 1024
        val modelSize = 500L * 1024 * 1024

        val inputs = MemoryBudget.Inputs(
            totalRamBytes = totalRam,
            availRamBytes = availRam,
            modelFileBytes = modelSize,
            isLowRamDevice = true
        )

        val plan = MemoryBudget.plan(inputs, null)
        assertTrue(plan is MemoryBudget.Plan.Load)
        val loadPlan = plan as MemoryBudget.Plan.Load
        assertTrue(loadPlan.ctxSize <= 1024)
    }

    @Test
    fun test6GbDeviceWithLowAvailRamFallbackCtx() {
        val totalRam = 6L * 1024 * 1024 * 1024
        val availRam = 1500L * 1024 * 1024
        val modelSize = 800L * 1024 * 1024

        val inputs = MemoryBudget.Inputs(
            totalRamBytes = totalRam,
            availRamBytes = availRam,
            modelFileBytes = modelSize,
            isLowRamDevice = false
        )

        val plan = MemoryBudget.plan(inputs, null)
        assertTrue(plan is MemoryBudget.Plan.Load)
    }

    @Test
    fun test12GbDeviceLargeModelLoadsSuccessfully() {
        val totalRam = 12L * 1024 * 1024 * 1024
        val availRam = 8L * 1024 * 1024 * 1024
        val modelSize = 2500L * 1024 * 1024

        val inputs = MemoryBudget.Inputs(
            totalRamBytes = totalRam,
            availRamBytes = availRam,
            modelFileBytes = modelSize,
            isLowRamDevice = false
        )

        val plan = MemoryBudget.plan(inputs, null)
        assertTrue(plan is MemoryBudget.Plan.Load)
        val loadPlan = plan as MemoryBudget.Plan.Load
        assertEquals(4096, loadPlan.ctxSize)
    }

    @Test
    fun test3GbDeviceSmallModelLoads() {
        val totalRam = 3L * 1024 * 1024 * 1024
        val availRam = 1400L * 1024 * 1024
        val modelSize = 500L * 1024 * 1024

        val inputs = MemoryBudget.Inputs(
            totalRamBytes = totalRam,
            availRamBytes = availRam,
            modelFileBytes = modelSize,
            isLowRamDevice = false
        )

        val plan = MemoryBudget.plan(inputs, null)
        assertTrue(plan is MemoryBudget.Plan.Load)
    }

    @Test
    fun testInsufficientAvailMemoryRefused() {
        val totalRam = 8L * 1024 * 1024 * 1024
        val availRam = 700L * 1024 * 1024 // Only 700 MB free
        val modelSize = 500L * 1024 * 1024

        val inputs = MemoryBudget.Inputs(
            totalRamBytes = totalRam,
            availRamBytes = availRam,
            modelFileBytes = modelSize,
            isLowRamDevice = false
        )

        val plan = MemoryBudget.plan(inputs, null)
        assertTrue(plan is MemoryBudget.Plan.Refuse)
    }
}
