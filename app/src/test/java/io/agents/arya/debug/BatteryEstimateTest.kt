package io.agents.arya.debug

import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryEstimateTest {
    @Test
    fun localCostsMoreThanCloud() {
        val local = BatteryEstimate.estimateMah(60_000, true, 3)
        val cloud = BatteryEstimate.estimateMah(60_000, false, 3)
        assertTrue(local > cloud)
        assertTrue(BatteryEstimate.format(local).contains("mAh"))
    }
}
