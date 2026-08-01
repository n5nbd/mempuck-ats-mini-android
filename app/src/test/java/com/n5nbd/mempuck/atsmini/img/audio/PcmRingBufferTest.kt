package com.n5nbd.mempuck.atsmini.img.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmRingBufferTest {
    @Test
    fun keepsNewestSamplesInChronologicalOrder() {
        val buffer = PcmRingBuffer(capacitySamples = 5)

        buffer.append(shortArrayOf(1, 2, 3), count = 3)
        buffer.append(shortArrayOf(4, 5, 6, 7), count = 4)

        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7), buffer.snapshot())
    }

    @Test
    fun oversizedAppendKeepsOnlyNewestCapacity() {
        val buffer = PcmRingBuffer(capacitySamples = 5)

        buffer.append(shortArrayOf(8, 9, 10, 11, 12, 13), count = 6)

        assertArrayEquals(shortArrayOf(9, 10, 11, 12, 13), buffer.snapshot())
    }
}
