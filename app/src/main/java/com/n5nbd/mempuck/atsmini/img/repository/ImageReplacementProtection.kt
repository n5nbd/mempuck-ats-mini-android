package com.n5nbd.mempuck.atsmini.img.repository

internal data class ProtectedImageReplacementMetadata(
    val detectedMode: String?,
    val frequencyCorrectionHz: Int?,
    val decoderConfidence: Int,
)

/**
 * Keeps a displayed decoded frame authoritative while a replacement decoder is
 * only armed or acquiring. The protected frame is released only after the new
 * decoder publishes at least one image line.
 */
internal class ImageReplacementProtection {
    var active: Boolean = false
        private set

    private var detectedMode: String? = null
    private var frequencyCorrectionHz: Int? = null
    private var decoderConfidence: Int = 0

    fun arm(completedLines: Int) {
        active = completedLines > 0
        detectedMode = null
        frequencyCorrectionHz = null
        decoderConfidence = 0
    }

    fun clear() {
        active = false
        detectedMode = null
        frequencyCorrectionHz = null
        decoderConfidence = 0
    }

    fun recordMode(modeName: String) {
        if (active) detectedMode = modeName
    }

    fun recordAdaptiveStatus(
        modeName: String,
        correctionHz: Int,
        confidence: Int,
    ) {
        if (!active) return
        detectedMode = modeName
        frequencyCorrectionHz = correctionHz
        decoderConfidence = confidence.coerceIn(0, 100)
    }

    fun holdsFrame(completedLines: Int): Boolean = active && completedLines <= 0

    fun releaseForFrame(completedLines: Int): ProtectedImageReplacementMetadata? {
        if (!active || completedLines <= 0) return null
        val metadata = ProtectedImageReplacementMetadata(
            detectedMode = detectedMode,
            frequencyCorrectionHz = frequencyCorrectionHz,
            decoderConfidence = decoderConfidence,
        )
        clear()
        return metadata
    }
}
