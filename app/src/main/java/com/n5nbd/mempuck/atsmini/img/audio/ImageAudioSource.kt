package com.n5nbd.mempuck.atsmini.img.audio

interface ImageAudioSource {
    fun start(
        onSamples: (sampleRateHz: Int, samples: ShortArray, count: Int) -> Unit,
        onError: (String) -> Unit,
    ): Result<Int>

    fun stop()
}
