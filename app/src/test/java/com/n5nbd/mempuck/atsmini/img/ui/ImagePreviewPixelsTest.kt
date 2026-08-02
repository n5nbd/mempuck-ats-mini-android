package com.n5nbd.mempuck.atsmini.img.ui

import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ImagePreviewPixelsTest {
    @Test
    fun preservesCompletedColorRowsAndUsesPreviewBackgroundBelowThem() {
        val source = intArrayOf(
            0xffff0000.toInt(), 0xff00ff00.toInt(),
            0xff0000ff.toInt(), 0xffffffff.toInt(),
        )
        val frame = DecodedImageFrame(
            width = 2,
            height = 2,
            argbPixels = source,
            completedLines = 1,
            revision = 1L,
        )

        val output = sourceColorPreviewPixels(frame, 0xffffffff.toInt())

        assertArrayEquals(
            intArrayOf(
                0xffff0000.toInt(), 0xff00ff00.toInt(),
                0xffffffff.toInt(), 0xffffffff.toInt(),
            ),
            output,
        )
        assertArrayEquals(
            intArrayOf(
                0xffff0000.toInt(), 0xff00ff00.toInt(),
                0xff0000ff.toInt(), 0xffffffff.toInt(),
            ),
            source,
        )
    }

    @Test
    fun completeFrameKeepsEverySourceColorPixel() {
        val source = intArrayOf(
            0xff102030.toInt(),
            0xff405060.toInt(),
        )
        val frame = DecodedImageFrame(
            width = 1,
            height = 2,
            argbPixels = source,
            completedLines = 2,
            revision = 2L,
        )

        assertArrayEquals(
            source,
            sourceColorPreviewPixels(frame, 0xffffffff.toInt()),
        )
    }
}
