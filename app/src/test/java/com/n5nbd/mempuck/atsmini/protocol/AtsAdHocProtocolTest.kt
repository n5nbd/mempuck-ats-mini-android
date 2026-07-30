package com.n5nbd.mempuck.atsmini.protocol

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
    fun multipleLinesInOneNotificationAreParsed() {
        val parser = AtsAdHocProtocol()
        val events = parser.feed("Z?\r\nOK,Z,1\r\n".toByteArray())
        assertEquals(3, events.size)
        assertTrue(events.contains(AtsAdHocProtocol.Event.Line("Z?")))
        assertTrue(events.contains(AtsAdHocProtocol.Event.Line("OK,Z,1")))
        assertTrue(events.contains(AtsAdHocProtocol.Event.AbsoluteTuneCapability(1)))
    }
}
