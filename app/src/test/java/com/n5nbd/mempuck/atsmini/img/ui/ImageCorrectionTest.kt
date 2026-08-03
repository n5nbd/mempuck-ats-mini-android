package com.n5nbd.mempuck.atsmini.img.ui

import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ImageCorrectionTest {
    @Test
    fun neutralCorrectionReturnsOriginalFrame() {
        val frame = frame(
            width = 2,
            height = 1,
            pixels = intArrayOf(black, white),
        )

        assertSame(frame, applyImageCorrection(frame, ImageCorrection()))
    }

    @Test
    fun offsetWrapsEachRowWithoutMutatingSource() {
        val source = intArrayOf(red, green, blue)
        val frame = frame(3, 1, source)

        val corrected = applyImageCorrection(
            frame,
            ImageCorrection(offsetPixels = 1f),
        )

        assertArrayEquals(intArrayOf(blue, red, green), corrected.argbPixels)
        assertArrayEquals(intArrayOf(red, green, blue), source)
        assertNotSame(source, corrected.argbPixels)
    }

    @Test
    fun skewProducesProgressiveBottomEdgeShift() {
        val frame = frame(
            width = 4,
            height = 3,
            pixels = intArrayOf(
                red, green, blue, white,
                red, green, blue, white,
                red, green, blue, white,
            ),
        )

        val corrected = applyImageCorrection(
            frame,
            ImageCorrection(skewPixels = 2f),
        )

        assertArrayEquals(
            intArrayOf(
                red, green, blue, white,
                white, red, green, blue,
                blue, white, red, green,
            ),
            corrected.argbPixels,
        )
    }

    @Test
    fun brightnessAndContrastAdjustChannelsAndPreserveAlpha() {
        val frame = frame(
            width = 1,
            height = 1,
            pixels = intArrayOf(0x80102030.toInt()),
        )

        val corrected = applyImageCorrection(
            frame,
            ImageCorrection(brightness = 20f, contrast = 50f),
        )

        assertEquals(0x80, corrected.argbPixels[0] ushr 24 and 0xff)
        assertEquals(11, corrected.argbPixels[0] ushr 16 and 0xff)
        assertEquals(35, corrected.argbPixels[0] ushr 8 and 0xff)
        assertEquals(59, corrected.argbPixels[0] and 0xff)
    }

    @Test
    fun continuousPreviewCropsUnusedBackingRows() {
        val frame = DecodedImageFrame(
            width = 2,
            height = 4,
            argbPixels = intArrayOf(
                red, green,
                blue, white,
                0, 0,
                0, 0,
            ),
            completedLines = 2,
            revision = 4L,
            continuous = true,
        )

        val corrected = applyImageCorrection(
            frame,
            ImageCorrection(offsetPixels = 1f),
        )

        assertEquals(2, corrected.height)
        assertEquals(2, corrected.completedLines)
        assertArrayEquals(
            intArrayOf(green, red, white, blue),
            corrected.argbPixels,
        )
    }

    @Test
    fun previewScalingReportsHorizontalScaleForSliderValues() {
        val frame = frame(
            width = 1000,
            height = 500,
            pixels = IntArray(500_000) { black },
        )

        val (preview, scale) = correctionPreviewFrame(frame, maxWidth = 500, maxHeight = 500)

        assertEquals(500, preview.width)
        assertEquals(250, preview.height)
        assertEquals(0.5f, scale, 0.0001f)
    }

    private fun frame(width: Int, height: Int, pixels: IntArray) = DecodedImageFrame(
        width = width,
        height = height,
        argbPixels = pixels,
        completedLines = height,
        revision = 1L,
    )

    private companion object {
        const val black = 0xff000000.toInt()
        const val white = 0xffffffff.toInt()
        const val red = 0xffff0000.toInt()
        const val green = 0xff00ff00.toInt()
        const val blue = 0xff0000ff.toInt()
    }
    @Test
    fun expandedSkewRangeReachesSixteenImageWidths() {
        assertEquals(28_944f, expandedSkewLimit(1_809), 0.01f)
    }

    @Test
    fun nonlinearSkewSliderRoundTripsLargeAndFineValues() {
        val limit = expandedSkewLimit(1_809)
        for (value in listOf(-25_000f, -400f, -10f, 0f, 10f, 400f, 25_000f)) {
            val position = skewToSliderPosition(value, limit)
            assertEquals(value, sliderPositionToSkew(position, limit), 0.1f)
        }
    }

}
