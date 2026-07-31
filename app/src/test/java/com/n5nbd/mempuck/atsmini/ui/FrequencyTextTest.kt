package com.n5nbd.mempuck.atsmini.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FrequencyTextTest {
    @Test
    fun groupedDisplayFormatRoundTrips() {
        assertEquals("14.230.123", formatFrequencyHz(14_230_123L))
        assertEquals("07.074.000", formatFrequencyHz(7_074_000L))
        assertEquals(14_230_123L, parseFrequencyText("14.230.123"))
    }

    @Test
    fun singleDotIsMhzShorthand() {
        assertEquals(7_074_000L, parseFrequencyText("7.074"))
    }

    @Test
    fun plainIntegerIsHertz() {
        assertEquals(101_700_000L, parseFrequencyText("101700000"))
    }
}
