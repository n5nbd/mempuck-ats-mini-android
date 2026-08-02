package com.n5nbd.mempuck.atsmini.img.ui

import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame

internal fun renderedImageHeight(frame: DecodedImageFrame): Int =
    if (frame.continuous) {
        frame.completedLines.coerceIn(1, frame.height)
    } else {
        frame.height
    }

internal fun sourceColorPreviewPixels(
    frame: DecodedImageFrame,
    incompleteArgb: Int,
): IntArray {
    require(frame.argbPixels.size == frame.width * frame.height) {
        "Decoded image buffer does not match its dimensions"
    }

    val renderedHeight = renderedImageHeight(frame)
    val output = frame.argbPixels.copyOf(frame.width * renderedHeight)
    if (!frame.continuous) {
        val completedPixels = frame.completedLines.coerceIn(0, frame.height) * frame.width
        if (completedPixels < output.size) {
            output.fill(incompleteArgb, completedPixels, output.size)
        }
    }
    return output
}
