package com.n5nbd.mempuck.atsmini.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyTuneTransactionTest {
    @Test
    fun lowBandTuneSelectsAllThenModeThenFrequency() {
        val transaction = LegacyTuneTransaction(7_100_000L, RadioMode.LSB)

        assertEquals(
            LegacyTuneTransaction.Decision.Send("B"),
            transaction.advance(status(1, "VHF", RadioMode.FM, 101_700_000L)),
        )
        assertEquals(
            LegacyTuneTransaction.Decision.Send("M"),
            transaction.advance(status(2, "ALL", RadioMode.AM, 15_000_000L)),
        )
        assertEquals(
            LegacyTuneTransaction.Decision.Send("F7100000\r"),
            transaction.advance(status(3, "ALL", RadioMode.LSB, 15_000_000L)),
        )
        assertEquals(
            LegacyTuneTransaction.Decision.Complete(RadioMode.LSB),
            transaction.advance(status(4, "ALL", RadioMode.LSB, 7_100_000L)),
        )
    }

    @Test
    fun fmTuneSelectsVhfThenUsesF() {
        val transaction = LegacyTuneTransaction(101_700_000L, RadioMode.FM)

        assertEquals(
            LegacyTuneTransaction.Decision.Send("b"),
            transaction.advance(status(1, "ALL", RadioMode.AM, 7_100_000L)),
        )
        assertEquals(
            LegacyTuneTransaction.Decision.Send("F101700000\r"),
            transaction.advance(status(2, "VHF", RadioMode.FM, 103_900_000L)),
        )
        assertEquals(
            LegacyTuneTransaction.Decision.Complete(RadioMode.FM),
            transaction.advance(status(3, "VHF", RadioMode.FM, 101_700_000L)),
        )
    }

    @Test
    fun cwUsesUsbHardwareMode() {
        val transaction = LegacyTuneTransaction(7_030_000L, RadioMode.CW)

        assertEquals(
            LegacyTuneTransaction.Decision.Send("M"),
            transaction.advance(status(1, "ALL", RadioMode.LSB, 7_100_000L)),
        )
        assertEquals(
            LegacyTuneTransaction.Decision.Send("F7030000\r"),
            transaction.advance(status(2, "ALL", RadioMode.USB, 7_100_000L)),
        )
        assertEquals(
            LegacyTuneTransaction.Decision.Complete(RadioMode.USB),
            transaction.advance(status(3, "ALL", RadioMode.USB, 7_030_000L)),
        )
    }

    @Test
    fun queuedStatusRecordsDoNotDoubleBump() {
        val transaction = LegacyTuneTransaction(7_100_000L, RadioMode.AM)

        assertEquals(
            LegacyTuneTransaction.Decision.Send("B"),
            transaction.advance(status(1, "VHF", RadioMode.FM, 101_700_000L)),
        )
        assertEquals(
            LegacyTuneTransaction.Decision.Wait,
            transaction.advance(status(2, "VHF", RadioMode.FM, 101_700_000L)),
        )
        assertEquals(
            LegacyTuneTransaction.Decision.Wait,
            transaction.advance(status(3, "VHF", RadioMode.FM, 101_700_000L)),
        )
        assertEquals(
            LegacyTuneTransaction.Decision.Send("B"),
            transaction.advance(status(4, "VHF", RadioMode.FM, 101_700_000L)),
        )
    }

    @Test
    fun bandAndModeDirectionsAreDeterministic() {
        assertEquals("B", LegacyTuneTransaction.bandBumpCommand("ALL"))
        assertEquals("b", LegacyTuneTransaction.bandBumpCommand("VHF"))
        assertEquals("m", LegacyTuneTransaction.modeBumpCommand(RadioMode.LSB, RadioMode.AM))
        assertEquals("M", LegacyTuneTransaction.modeBumpCommand(RadioMode.AM, RadioMode.LSB))
    }

    @Test
    fun unsupportedFrequencyFailsWithoutSending() {
        val decision = LegacyTuneTransaction(50_000_000L, RadioMode.AM)
            .advance(status(1, "ALL", RadioMode.AM, 7_100_000L))
        assertTrue(decision is LegacyTuneTransaction.Decision.Failed)
    }

    private fun status(
        sequence: Int,
        bandName: String,
        mode: RadioMode,
        frequencyHz: Long,
        appVersion: Int = 235,
    ) = AtsStatus(
        appVersion = appVersion,
        frequencyHz = frequencyHz,
        bfoHz = 0,
        calibrationHz = 0,
        bandName = bandName,
        mode = mode,
        step = "1k",
        bandwidth = "Auto",
        agcIndex = 0,
        volume = 20,
        rssi = 40,
        snr = 15,
        tuningCapacitor = 0,
        voltage = 4.0f,
        sequence = sequence,
    )
}
