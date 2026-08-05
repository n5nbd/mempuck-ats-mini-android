package com.n5nbd.mempuck.atsmini.img.ui

import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame
import kotlin.math.min
import kotlin.math.roundToInt

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

internal fun skewControlLimit(imageWidth: Int): Float =
    imageWidth * 0.50f

internal fun applyImageCorrection(
    frame: DecodedImageFrame,
    correction: ImageCorrection,
): DecodedImageFrame {
    if (correction.neutral) return frame

    val source = frame.argbPixels
    require(source.size == frame.width * frame.height) {
        "Decoded image buffer does not match its dimensions"
    }

    val output = IntArray(source.size)
    val contrastFactor = (1f + correction.contrast / 100f).coerceIn(0.2f, 2f)
    val brightnessOffset = correction.brightness.coerceIn(-100f, 100f) * 2.55f

    for (y in 0 until frame.height) {
        val fraction = if (frame.height <= 1) 0f else y.toFloat() / (frame.height - 1).toFloat()
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

    return frame.copy(argbPixels = output)
}

internal fun correctionPreviewFrame(
    frame: DecodedImageFrame,
    maxWidth: Int = 640,
    maxHeight: Int = 360,
): Pair<DecodedImageFrame, Float> {
    require(maxWidth > 0 && maxHeight > 0)
    val scale = min(
        1f,
        min(
            maxWidth.toFloat() / frame.width.toFloat(),
            maxHeight.toFloat() / frame.height.toFloat(),
        ),
    )
    val targetWidth = (frame.width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (frame.height * scale).roundToInt().coerceAtLeast(1)
    val output = IntArray(targetWidth * targetHeight)

    for (y in 0 until targetHeight) {
        val sourceY = ((y + 0.5f) / scale).toInt().coerceIn(0, frame.height - 1)
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
        complete = frame.complete,
    ) to scale
}

private fun adjustArgb(argb: Int, brightnessOffset: Float, contrastFactor: Float): Int {
    val alpha = argb ushr 24 and 0xff
    val red = adjustChannel(argb ushr 16 and 0xff, brightnessOffset, contrastFactor)
    val green = adjustChannel(argb ushr 8 and 0xff, brightnessOffset, contrastFactor)
    val blue = adjustChannel(argb and 0xff, brightnessOffset, contrastFactor)
    return alpha shl 24 or (red shl 16) or (green shl 8) or blue
}

private fun adjustChannel(channel: Int, brightnessOffset: Float, contrastFactor: Float): Int =
    (((channel - 128f) * contrastFactor) + 128f + brightnessOffset)
        .roundToInt()
        .coerceIn(0, 255)
