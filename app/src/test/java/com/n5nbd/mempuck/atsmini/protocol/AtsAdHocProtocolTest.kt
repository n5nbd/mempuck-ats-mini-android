package com.n5nbd.mempuck.atsmini.protocol

import com.n5nbd.mempuck.atsmini.model.RadioMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AtsAdHocProtocolTest {
    @Test
    fun capabilityCanArriveAcrossNotifications() {
        val parser = AtsAdHocProtocol()
        assertTrue(parser.feed("Z?\r\nOK,".toByteArray()).none {
            it is AtsAdHocProtocol.Event.AbsoluteTuneCapability
        })

        val events = parser.feed("Z,1\r\n".toByteArray())
        assertTrue(events.contains(AtsAdHocProtocol.Event.AbsoluteTuneCapability(1)))
    }

    @Test
    fun absoluteTuneConfirmationIsParsed() {
        val parser = AtsAdHocProtocol()
        val events = parser.feed("Z14230000,USB\r\nOK,Z,14230000,USB\r\n".toByteArray())
        assertTrue(
            events.contains(
                AtsAdHocProtocol.Event.AbsoluteTuneConfirmed(14_230_000L, RadioMode.USB),
            ),
        )
    }

    @Test
    fun monitorStatusConvertsSsbFrequencyAndBfoToHertz() {
        val parser = AtsAdHocProtocol()
        val line = "235,14230,123,0,20M,USB,1k,1.2k,0,31,44,18,0,4.08,7\r\n"
        val status = parser.feed(line.toByteArray())
            .filterIsInstance<AtsAdHocProtocol.Event.Status>()
            .single()
            .value

        assertEquals(14_230_123L, status.frequencyHz)
        assertEquals(RadioMode.USB, status.mode)
        assertEquals("1.2k", status.bandwidth)
        assertEquals(44, status.rssi)
        assertEquals(18, status.snr)
    }

    @Test
    fun monitorStatusConvertsFmUnitsToHertz() {
        val parser = AtsAdHocProtocol()
        val line = "235,10170,0,0,VHF,FM,100k,Auto,0,20,51,35,0,4.01,8\r\n"
        val status = parser.feed(line.toByteArray())
            .filterIsInstance<AtsAdHocProtocol.Event.Status>()
            .single()
            .value

        assertEquals(101_700_000L, status.frequencyHz)
        assertEquals(RadioMode.FM, status.mode)
    }

    @Test
    fun cwTuneCommandMapsToUsb() {
        assertEquals("Z7030000,USB\r", AtsAdHocProtocol.absoluteTuneCommand(7_030_000L, RadioMode.CW))
    }
}
