package com.n5nbd.mempuck.atsmini.img.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageViewportMathTest {
    @Test
    fun fitPlacementShowsWholeImageAndCanBottomAlignFax() {
        val centered = fitPlacement(
            viewportWidth = 400f,
            viewportHeight = 300f,
            imageWidth = 200,
            imageHeight = 100,
            bottomAligned = false,
        )
        assertEquals(2f, centered.scale, 0.0001f)
        assertEquals(0f, centered.left, 0.0001f)
        assertEquals(50f, centered.top, 0.0001f)

        val bottom = fitPlacement(
            viewportWidth = 400f,
            viewportHeight = 300f,
            imageWidth = 200,
            imageHeight = 100,
            bottomAligned = true,
        )
        assertEquals(100f, bottom.top, 0.0001f)
    }

    @Test
    fun tappedFitPointBecomesImageCoordinate() {
        val placement = fitPlacement(
            viewportWidth = 400f,
            viewportHeight = 300f,
            imageWidth = 200,
            imageHeight = 100,
            bottomAligned = false,
        )

        val point = imagePointAt(
            tapX = 300f,
            tapY = 150f,
            placement = placement,
            imageWidth = 200,
            imageHeight = 100,
        )

        assertEquals(150f, point.x, 0.0001f)
        assertEquals(50f, point.y, 0.0001f)
    }

    @Test
    fun zoomCenterIsClampedToKeepImageCoveringViewport() {
        val clamped = clampZoomCenter(
            requested = ImagePoint(0f, 0f),
            viewportWidth = 300f,
            viewportHeight = 200f,
            imageWidth = 600,
            imageHeight = 400,
            scale = 2f,
        )

        assertEquals(75f, clamped.x, 0.0001f)
        assertEquals(50f, clamped.y, 0.0001f)
    }

    @Test
    fun growingFaxKeepsAbsoluteZoomCenterStable() {
        val before = clampZoomCenter(
            requested = ImagePoint(250f, 180f),
            viewportWidth = 300f,
            viewportHeight = 200f,
            imageWidth = 600,
            imageHeight = 300,
            scale = 2f,
        )
        val after = clampZoomCenter(
            requested = before,
            viewportWidth = 300f,
            viewportHeight = 200f,
            imageWidth = 600,
            imageHeight = 700,
            scale = 2f,
        )

        assertEquals(before.x, after.x, 0.0001f)
        assertEquals(before.y, after.y, 0.0001f)
    }
}
