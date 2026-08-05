package com.n5nbd.mempuck.atsmini.img.ui

import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ImageCorrectionTest {
    @Test fun neutralCorrectionReturnsOriginalFrame() {
        val frame = frame(2, 1, intArrayOf(black, white))
        assertSame(frame, applyImageCorrection(frame, ImageCorrection()))
    }

    @Test fun offsetWrapsEachRowWithoutMutatingSource() {
        val source = intArrayOf(red, green, blue)
        val corrected = applyImageCorrection(frame(3, 1, source), ImageCorrection(offsetPixels = 1f))
        assertArrayEquals(intArrayOf(blue, red, green), corrected.argbPixels)
        assertArrayEquals(intArrayOf(red, green, blue), source)
        assertNotSame(source, corrected.argbPixels)
    }

    @Test fun skewProducesProgressiveBottomEdgeShift() {
        val corrected = applyImageCorrection(
            frame(4, 3, intArrayOf(
                red, green, blue, white,
                red, green, blue, white,
                red, green, blue, white,
            )),
            ImageCorrection(skewPixels = 2f),
        )
        assertArrayEquals(intArrayOf(
            red, green, blue, white,
            white, red, green, blue,
            blue, white, red, green,
        ), corrected.argbPixels)
    }

    @Test fun correctionRangeIsUsefulButNotExtreme() {
        assertEquals(160f, skewControlLimit(320), 0.001f)
        assertEquals(904.5f, skewControlLimit(1809), 0.001f)
        assertEquals(2000f, skewControlLimit(4000), 0.001f)
    }

    @Test fun previewScalingReportsHorizontalScale() {
        val frame = frame(1000, 500, IntArray(500_000) { black })
        val (preview, scale) = correctionPreviewFrame(frame, 500, 500)
        assertEquals(500, preview.width)
        assertEquals(250, preview.height)
        assertEquals(0.5f, scale, 0.0001f)
    }

    private fun frame(width: Int, height: Int, pixels: IntArray) = DecodedImageFrame(
        width, height, pixels, height, 1L, complete = true,
    )

    private companion object {
        const val black = 0xff000000.toInt()
        const val white = 0xffffffff.toInt()
        const val red = 0xffff0000.toInt()
        const val green = 0xff00ff00.toInt()
        const val blue = 0xff0000ff.toInt()
    }
}
