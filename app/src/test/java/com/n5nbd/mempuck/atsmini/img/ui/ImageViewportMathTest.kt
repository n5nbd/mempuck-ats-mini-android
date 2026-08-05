package com.n5nbd.mempuck.atsmini.img.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageViewportMathTest {
    @Test fun fitPlacementCanTopAnchorActiveFax() {
        val centered = fitPlacement(400f, 300f, 200, 100, topAligned = false)
        assertEquals(50f, centered.top, 0.0001f)
        val top = fitPlacement(400f, 300f, 200, 100, topAligned = true)
        assertEquals(0f, top.top, 0.0001f)
    }

    @Test fun tappedFitPointBecomesImageCoordinate() {
        val placement = fitPlacement(400f, 300f, 200, 100, topAligned = false)
        val point = imagePointAt(300f, 150f, placement, 200, 100)
        assertEquals(150f, point.x, 0.0001f)
        assertEquals(50f, point.y, 0.0001f)
    }

    @Test fun zoomCenterIsClampedToKeepImageCoveringViewport() {
        val clamped = clampZoomCenter(ImagePoint(0f, 0f), 300f, 200f, 600, 400, 2f)
        assertEquals(75f, clamped.x, 0.0001f)
        assertEquals(50f, clamped.y, 0.0001f)
    }
}
