package com.n5nbd.mempuck.atsmini.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AtsFrequencyPlanTest {
    @Test
    fun coverageRegionsMatchReceiverPaths() {
        assertEquals(AtsFrequencyRegion.LowBand, AtsFrequencyPlan.regionFor(150_000L))
        assertEquals(AtsFrequencyRegion.LowBand, AtsFrequencyPlan.regionFor(30_000_000L))
        assertEquals(AtsFrequencyRegion.Unsupported, AtsFrequencyPlan.regionFor(30_000_001L))
        assertEquals(AtsFrequencyRegion.Unsupported, AtsFrequencyPlan.regionFor(63_999_999L))
        assertEquals(AtsFrequencyRegion.BroadcastFm, AtsFrequencyPlan.regionFor(64_000_000L))
        assertEquals(AtsFrequencyRegion.BroadcastFm, AtsFrequencyPlan.regionFor(108_000_000L))
    }

    @Test
    fun frequencySelectsFmAndRestoresLastLowBandMode() {
        assertEquals(
            RadioMode.FM,
            AtsFrequencyPlan.modeForFrequency(101_700_000L, RadioMode.LSB),
        )
        assertEquals(
            RadioMode.LSB,
            AtsFrequencyPlan.modeForFrequency(7_200_000L, RadioMode.LSB),
        )
        assertEquals(
            RadioMode.AM,
            AtsFrequencyPlan.modeForFrequency(1_000_000L, RadioMode.FM),
        )
        assertNull(AtsFrequencyPlan.modeForFrequency(50_000_000L, RadioMode.USB))
    }

    @Test
    fun immediateControlsSkipTheUnsupportedGap() {
        assertEquals(
            64_000_000L,
            AtsFrequencyPlan.normalizeInteractiveFrequency(29_900_000L, 39_900_000L),
        )
        assertEquals(
            30_000_000L,
            AtsFrequencyPlan.normalizeInteractiveFrequency(64_100_000L, 54_100_000L),
        )
    }

    @Test
    fun fmTargetsUseTheAtsTenKilohertzGrid() {
        assertEquals(
            101_700_000L,
            AtsFrequencyPlan.normalizeReceiverFrequency(101_700_001L),
        )
        assertEquals(
            101_710_000L,
            AtsFrequencyPlan.normalizeReceiverFrequency(101_705_000L),
        )
        assertEquals(
            7_074_123L,
            AtsFrequencyPlan.normalizeReceiverFrequency(7_074_123L),
        )
    }
}
