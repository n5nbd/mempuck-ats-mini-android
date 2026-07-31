package com.n5nbd.mempuck.atsmini.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VhfVfoStepTest {
    @Test
    fun broadcastFmUsesConfiguredVhfStep() {
        assertEquals(
            50_000L,
            vfoSingleArrowStepHz(
                frequencyHz = 101_700_000L,
                receiverStepHz = 10_000L,
                vhfVfoStep = VhfVfoStep.KHz50,
                hfVfoSmallStep = HfVfoSmallStep.KHz1,
            ),
        )
        assertEquals(
            200_000L,
            vfoSingleArrowStepHz(
                frequencyHz = 101_700_000L,
                receiverStepHz = 10_000L,
                vhfVfoStep = VhfVfoStep.KHz200,
                hfVfoSmallStep = HfVfoSmallStep.KHz1,
            ),
        )
    }

    @Test
    fun lowBandUsesConfiguredSmallStep() {
        assertEquals(
            10L,
            vfoSingleArrowStepHz(
                frequencyHz = 7_100_000L,
                receiverStepHz = 1_000L,
                vhfVfoStep = VhfVfoStep.KHz200,
                hfVfoSmallStep = HfVfoSmallStep.Hz10,
            ),
        )
    }

    @Test
    fun lowBandUsesConfiguredLargeStep() {
        assertEquals(
            1_000_000L,
            vfoLargeArrowStepHz(
                frequencyHz = 14_230_000L,
                receiverStepHz = 1_000L,
                hfVfoLargeStep = HfVfoLargeStep.MHz1,
            ),
        )
    }

    @Test
    fun broadcastFmLargeArrowKeepsReceiverMultiple() {
        assertEquals(
            100_000L,
            vfoLargeArrowStepHz(
                frequencyHz = 101_700_000L,
                receiverStepHz = 10_000L,
                hfVfoLargeStep = HfVfoLargeStep.MHz1,
            ),
        )
    }
}
