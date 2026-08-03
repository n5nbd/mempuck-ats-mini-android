package com.n5nbd.mempuck.atsmini.img.repository

import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageAutosavePolicyTest {
    @Test
    fun wefaxUnderFiftyLinesIsDiscarded() {
        assertFalse(shouldAutosaveFrame(frame(lines = 49, continuous = true)))
    }

    @Test
    fun wefaxAtFiftyLinesIsKept() {
        assertTrue(shouldAutosaveFrame(frame(lines = 50, continuous = true)))
    }

    @Test
    fun shortSstvPartialRemainsEligible() {
        assertTrue(shouldAutosaveFrame(frame(lines = 1, continuous = false)))
    }

    private fun frame(lines: Int, continuous: Boolean) = DecodedImageFrame(
        width = 2,
        height = lines.coerceAtLeast(1),
        argbPixels = IntArray(2 * lines.coerceAtLeast(1)),
        completedLines = lines,
        revision = 1L,
        continuous = continuous,
        captureId = 1L,
    )
}
