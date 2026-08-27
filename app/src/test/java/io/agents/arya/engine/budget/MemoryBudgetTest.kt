package io.agents.arya.engine.budget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

class MemoryBudgetSingleTest {

    @Test
    fun ramClassBuckets() {
        assertEquals("3GB", MemoryBudget.ramClassOf(3L * GB))
        assertEquals("4GB", MemoryBudget.ramClassOf(4L * GB))
        assertEquals("6GB", MemoryBudget.ramClassOf(6L * GB))
        assertEquals("8GB+", MemoryBudget.ramClassOf(8L * GB))
        assertEquals("8GB+", MemoryBudget.ramClassOf(12L * GB))
    }

    @Test
    fun kvEstimateGrowsWithContext() {
        val small = MemoryBudget.kvBytes(512, null, 500L * MB)
        val large = MemoryBudget.kvBytes(4096, null, 500L * MB)
        assertTrue(large > small)
    }

    @Test
    fun mobilePrefersAtMost2048() {
        val inputs = MemoryBudget.Inputs(
            totalRamBytes = 12L * GB,
            availRamBytes = 4300L * MB,
            modelFileBytes = 1224L * MB,
            isLowRamDevice = false,
        )
        val plan = MemoryBudget.plan(inputs, MemoryBudget.DeviceProfile(bestThreads = 4))
        assertTrue(plan is MemoryBudget.Plan.Load)
        assertTrue((plan as MemoryBudget.Plan.Load).ctxSize <= 2048)
    }

    @Test
    fun lowAvailableRamCapsContextAt1024() {
        val plan = MemoryBudget.plan(
            MemoryBudget.Inputs(
                totalRamBytes = 12L * GB,
                availRamBytes = 4_700L * MB,
                modelFileBytes = 1_224L * MB,
                isLowRamDevice = false,
            ),
            MemoryBudget.DeviceProfile(bestThreads = 4),
        )
        assertTrue("low available RAM should still permit mmap load; got $plan", plan is MemoryBudget.Plan.Load)
        assertEquals(1024, (plan as MemoryBudget.Plan.Load).ctxSize)
    }

    @Test
    fun mmapModelIsNotRejectedByManagedHeapClassAlone() {
        val plan = MemoryBudget.plan(
            MemoryBudget.Inputs(
                totalRamBytes = 12L * GB,
                availRamBytes = 6L * GB,
                modelFileBytes = 1_224L * MB,
                isLowRamDevice = false,
                processMemoryLimitBytes = 512L * MB,
                modelMeta = MemoryBudget.ModelMeta(
                    nLayers = 28,
                    nKvHeads = 8,
                    headDim = 128,
                    nParams = 1_700_000_000,
                ),
            ),
            MemoryBudget.DeviceProfile(bestThreads = 4),
        )
        assertTrue("mmap-backed model should fit; got $plan", plan is MemoryBudget.Plan.Load)
    }

    @Test
    fun verySmallProcessBudgetStillRefusesTransientMemory() {
        val plan = MemoryBudget.plan(
            MemoryBudget.Inputs(
                totalRamBytes = 12L * GB,
                availRamBytes = 6L * GB,
                modelFileBytes = 1_224L * MB,
                isLowRamDevice = false,
                processMemoryLimitBytes = 256L * MB,
                modelMeta = MemoryBudget.ModelMeta(
                    nLayers = 28,
                    nKvHeads = 8,
                    headDim = 128,
                    nParams = 1_700_000_000,
                ),
            ),
            MemoryBudget.DeviceProfile(bestThreads = 4),
        )
        assertTrue("transient memory should be refused; got $plan", plan is MemoryBudget.Plan.Refuse)
    }

    @Test
    fun metaFormulaUsedWhenPresent() {
        val meta = MemoryBudget.ModelMeta(nLayers = 28, nKvHeads = 8, headDim = 128, nParams = 1_700_000_000)
        val kv = MemoryBudget.kvBytes(2048, meta, 1_200L * MB)
        val expected = 2L * 28 * 8 * 128 * 2L * 2048
        assertEquals(expected, kv)
    }

    companion object {
        const val MB = 1024L * 1024L
        const val GB = 1024L * MB
    }
}

@RunWith(Parameterized::class)
class MemoryBudgetTableTest(
    private val name: String,
    private val totalGb: Double,
    private val availMb: Long,
    private val modelMb: Long,
    private val lowRam: Boolean,
    private val expectLoad: Boolean,
    private val maxCtx: Int?,
) {
    @Test
    fun planMatchesTable() {
        val inputs = MemoryBudget.Inputs(
            totalRamBytes = (totalGb * GB).toLong(),
            availRamBytes = availMb * MB,
            modelFileBytes = modelMb * MB,
            isLowRamDevice = lowRam,
        )
        val plan = MemoryBudget.plan(inputs, MemoryBudget.DeviceProfile(bestThreads = 4))
        if (expectLoad) {
            assertTrue("$name should load, got $plan", plan is MemoryBudget.Plan.Load)
            val load = plan as MemoryBudget.Plan.Load
            if (maxCtx != null) {
                assertTrue("$name ctx ${load.ctxSize} > $maxCtx", load.ctxSize <= maxCtx)
            }
            assertEquals(false, load.useMlock)
        } else {
            assertTrue("$name should refuse, got $plan", plan is MemoryBudget.Plan.Refuse)
            assertTrue((plan as MemoryBudget.Plan.Refuse).suggestSmallerModel)
        }
    }

    companion object {
        const val MB = 1024L * 1024L
        const val GB = 1024L * MB

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any?>> = listOf(
            arrayOf("3GB small model", 3.0, 1400L, 500L, false, true, 4096),
            arrayOf("4GB 1.2GB model", 4.0, 2200L, 1200L, false, true, 4096),
            arrayOf("4GB oversized 2.5GB", 4.0, 3000L, 2500L, false, false, null),
            arrayOf("6GB low avail", 6.0, 1500L, 800L, false, true, 4096),
            arrayOf("8GB 1.2GB model 4096", 8.0, 4096L, 1200L, false, true, 4096),
            arrayOf("8GB only 700MB free", 8.0, 700L, 500L, false, false, null),
            arrayOf("12GB 2.5GB model", 12.0, 8192L, 2500L, false, true, 4096),
            arrayOf("low-ram device cap 1024", 2.0, 1200L, 500L, true, true, 1024),
        )
    }
}
