package io.agents.arya.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class PersianFormatTest {
    @Test
    fun roundTripDigits() {
        val src = "battery 73% at 12:05"
        val fa = PersianFormat.toPersianDigits(src)
        assertEquals("battery ۷۳% at ۱۲:۰۵", fa)
        assertEquals(src, PersianFormat.toLatinDigits(fa))
    }

    @Test
    fun percent() {
        assertEquals("۸۰%", PersianFormat.formatPercent(80, true))
        assertEquals("80%", PersianFormat.formatPercent(80, false))
    }
}
