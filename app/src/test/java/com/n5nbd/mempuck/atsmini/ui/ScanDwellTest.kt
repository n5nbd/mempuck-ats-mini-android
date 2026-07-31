package com.n5nbd.mempuck.atsmini.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScanDwellTest {
    @Test
    fun choicesCoverExpectedSharedDwellValues() {
        assertEquals(
            listOf(1_000L, 2_000L, 5_000L, 10_000L),
            ScanDwell.entries.map(ScanDwell::millis),
        )
    }

    @Test
    fun defaultChoiceUsesPracticalMinimum() {
        assertEquals(2_000L, ScanDwell.Seconds2.millis)
    }
}
