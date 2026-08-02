package com.n5nbd.mempuck.atsmini.img.model

enum class ImageDecoderSelection(val label: String) {
    AUTO("??"),
    SSTV("R36"),
    MARTIN_M1("M1"),
    MARTIN_M2("M2"),
    SCOTTIE_S1("S1"),
    SCOTTIE_S2("S2"),
    WEFAX("WX"),
}

enum class ImageAudioInput(
    val label: String,
    val available: Boolean,
) {
    MIC("MIC", true),
    USB("USB", false),
}

enum class ImageDecoderSession {
    IDLE,
    STARTING,
    LISTENING,
    ERROR,
}

enum class ImageSignalState {
    WAITING,
    DECODING,
    COMPLETE,
}

data class DecodedImageFrame(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
    val completedLines: Int,
    val revision: Long,
    val continuous: Boolean = false,
)

data class ImageDecoderState(
    val decoder: ImageDecoderSelection = ImageDecoderSelection.AUTO,
    val input: ImageAudioInput = ImageAudioInput.MIC,
    val session: ImageDecoderSession = ImageDecoderSession.IDLE,
    val signal: ImageSignalState = ImageSignalState.WAITING,
    val sampleRateHz: Int? = null,
    val receivedSamples: Long = 0L,
    val bufferedSamples: Int = 0,
    val detectedMode: String? = null,
    val frequencyCorrectionHz: Int? = null,
    val decoderConfidence: Int = 0,
    val image: DecodedImageFrame? = null,
    val error: String? = null,
) {
    val listening: Boolean
        get() = session == ImageDecoderSession.STARTING ||
            session == ImageDecoderSession.LISTENING

    val bufferedSeconds: Double
        get() = sampleRateHz
            ?.takeIf { it > 0 }
            ?.let { bufferedSamples.toDouble() / it }
            ?: 0.0
}

sealed interface ImageListenResult {
    data object Started : ImageListenResult
    data object PermissionRequired : ImageListenResult
    data class Failed(val message: String) : ImageListenResult
}
