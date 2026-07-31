package com.n5nbd.mempuck.atsmini.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FrequencyWheelTest {
    @Test
    fun fmDisplayStopsAtTenKilohertz() {
        val wheel = frequencyWheelSpec(101_700_000L)
        assertEquals("10170", wheel.digits)
        assertEquals(listOf(100_000_000L, 10_000_000L, 1_000_000L, 100_000L, 10_000L), wheel.placesHz)
        assertEquals(setOf(2), wheel.separatorAfter)
    }

    @Test
    fun lowerFmCoverageUsesTheSameCompactFormat() {
        val wheel = frequencyWheelSpec(64_000_000L)
        assertEquals("6400", wheel.digits)
        assertEquals(listOf(10_000_000L, 1_000_000L, 100_000L, 10_000L), wheel.placesHz)
        assertEquals(setOf(1), wheel.separatorAfter)
    }

    @Test
    fun lowBandKeepsFullHertzDigits() {
        val wheel = frequencyWheelSpec(14_230_123L)
        assertEquals("14230123", wheel.digits)
        assertEquals(setOf(1, 4), wheel.separatorAfter)
    }
}
