package com.n5nbd.mempuck.atsmini.img.ui

import kotlin.math.min

internal data class FitPlacement(
    val scale: Float,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

internal data class ImagePoint(
    val x: Float,
    val y: Float,
)

internal fun fitPlacement(
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    bottomAligned: Boolean,
): FitPlacement {
    require(viewportWidth > 0f)
    require(viewportHeight > 0f)
    require(imageWidth > 0)
    require(imageHeight > 0)

    val scale = min(
        viewportWidth / imageWidth.toFloat(),
        viewportHeight / imageHeight.toFloat(),
    )
    val width = imageWidth * scale
    val height = imageHeight * scale
    val left = (viewportWidth - width) / 2f
    val top = if (bottomAligned) {
        viewportHeight - height
    } else {
        (viewportHeight - height) / 2f
    }
    return FitPlacement(scale, left, top, width, height)
}

internal fun imagePointAt(
    tapX: Float,
    tapY: Float,
    placement: FitPlacement,
    imageWidth: Int,
    imageHeight: Int,
): ImagePoint = ImagePoint(
    x = ((tapX - placement.left) / placement.scale)
        .coerceIn(0f, imageWidth.toFloat()),
    y = ((tapY - placement.top) / placement.scale)
        .coerceIn(0f, imageHeight.toFloat()),
)

internal fun clampZoomCenter(
    requested: ImagePoint,
    viewportWidth: Float,
    viewportHeight: Float,
    imageWidth: Int,
    imageHeight: Int,
    scale: Float,
): ImagePoint {
    require(viewportWidth > 0f)
    require(viewportHeight > 0f)
    require(imageWidth > 0)
    require(imageHeight > 0)
    require(scale > 0f)

    fun clampAxis(requestedValue: Float, viewport: Float, image: Int): Float {
        val imageSize = image.toFloat()
        val visibleHalf = viewport / (2f * scale)
        return if (visibleHalf * 2f >= imageSize) {
            imageSize / 2f
        } else {
            requestedValue.coerceIn(visibleHalf, imageSize - visibleHalf)
        }
    }

    return ImagePoint(
        x = clampAxis(requested.x, viewportWidth, imageWidth),
        y = clampAxis(requested.y, viewportHeight, imageHeight),
    )
}

internal fun zoomTopLeft(
    center: ImagePoint,
    viewportWidth: Float,
    viewportHeight: Float,
    scale: Float,
): ImagePoint = ImagePoint(
    x = viewportWidth / 2f - center.x * scale,
    y = viewportHeight / 2f - center.y * scale,
)
