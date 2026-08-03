package com.n5nbd.mempuck.atsmini.img.ui

import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

data class ImageCorrection(
    val skewPixels: Float = 0f,
    val offsetPixels: Float = 0f,
    val brightness: Float = 0f,
    val contrast: Float = 0f,
) {
    val neutral: Boolean
        get() = skewPixels == 0f &&
            offsetPixels == 0f &&
            brightness == 0f &&
            contrast == 0f

    fun scaled(horizontalScale: Float): ImageCorrection = copy(
        skewPixels = skewPixels * horizontalScale,
        offsetPixels = offsetPixels * horizontalScale,
    )
}


internal fun expandedSkewLimit(imageWidth: Int): Float =
    (imageWidth.coerceAtLeast(1) * 16f).coerceIn(400f, 250_000f)

internal fun skewToSliderPosition(skewPixels: Float, skewLimit: Float): Float {
    require(skewLimit > 0f)
    val normalized = (abs(skewPixels).coerceAtMost(skewLimit) / skewLimit).pow(1f / 3f)
    return normalized * skewPixels.sign
}

internal fun sliderPositionToSkew(position: Float, skewLimit: Float): Float {
    require(skewLimit > 0f)
    val bounded = position.coerceIn(-1f, 1f)
    return bounded.sign * abs(bounded).pow(3f) * skewLimit
}

internal fun applyImageCorrection(
    frame: DecodedImageFrame,
    correction: ImageCorrection,
): DecodedImageFrame {
    if (correction.neutral) return frame

    val renderedHeight = renderedImageHeight(frame)
    val source = frame.argbPixels
    require(source.size == frame.width * frame.height) {
        "Decoded image buffer does not match its dimensions"
    }

    val output = IntArray(frame.width * renderedHeight)
    val contrastFactor = (1f + correction.contrast / 100f).coerceIn(0.2f, 2f)
    val brightnessOffset = correction.brightness.coerceIn(-100f, 100f) * 2.55f

    for (y in 0 until renderedHeight) {
        val fraction = if (renderedHeight <= 1) 0f else y.toFloat() / (renderedHeight - 1).toFloat()
        val rowShift = (correction.offsetPixels + correction.skewPixels * fraction).roundToInt()
        val row = y * frame.width
        for (x in 0 until frame.width) {
            val sourceX = Math.floorMod(x - rowShift, frame.width)
            output[row + x] = adjustArgb(
                source[row + sourceX],
                brightnessOffset,
                contrastFactor,
            )
        }
    }

    return frame.copy(
        height = renderedHeight,
        argbPixels = output,
        completedLines = min(frame.completedLines, renderedHeight),
    )
}

internal fun correctionPreviewFrame(
    frame: DecodedImageFrame,
    maxWidth: Int = 640,
    maxHeight: Int = 360,
): Pair<DecodedImageFrame, Float> {
    require(maxWidth > 0 && maxHeight > 0)
    val sourceHeight = renderedImageHeight(frame)
    val scale = min(
        1f,
        min(
            maxWidth.toFloat() / frame.width.toFloat(),
            maxHeight.toFloat() / sourceHeight.toFloat(),
        ),
    )
    val targetWidth = (frame.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
    val output = IntArray(targetWidth * targetHeight)

    for (y in 0 until targetHeight) {
        val sourceY = ((y + 0.5f) / scale).toInt().coerceIn(0, sourceHeight - 1)
        val sourceRow = sourceY * frame.width
        val targetRow = y * targetWidth
        for (x in 0 until targetWidth) {
            val sourceX = ((x + 0.5f) / scale).toInt().coerceIn(0, frame.width - 1)
            output[targetRow + x] = frame.argbPixels[sourceRow + sourceX]
        }
    }

    return DecodedImageFrame(
        width = targetWidth,
        height = targetHeight,
        argbPixels = output,
        completedLines = targetHeight,
        revision = frame.revision,
        continuous = frame.continuous,
    ) to scale
}

private fun adjustArgb(
    argb: Int,
    brightnessOffset: Float,
    contrastFactor: Float,
): Int {
    val alpha = argb ushr 24 and 0xff
    val red = adjustChannel(argb ushr 16 and 0xff, brightnessOffset, contrastFactor)
    val green = adjustChannel(argb ushr 8 and 0xff, brightnessOffset, contrastFactor)
    val blue = adjustChannel(argb and 0xff, brightnessOffset, contrastFactor)
    return alpha shl 24 or (red shl 16) or (green shl 8) or blue
}

private fun adjustChannel(
    channel: Int,
    brightnessOffset: Float,
    contrastFactor: Float,
): Int = (((channel - 128f) * contrastFactor) + 128f + brightnessOffset)
    .roundToInt()
    .coerceIn(0, 255)
