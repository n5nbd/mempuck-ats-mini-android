package com.n5nbd.mempuck.atsmini.img.decoder.wefax

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WefaxIoc576DecoderTest {
    @Test
    fun phasingLockSurvivesFadeAndContinuesSamePage() {
        val audio = SyntheticWefax(SAMPLE_RATE).apply {
            tone(1500.0, 0.20)
            repeat(10) { asymmetricPhasingLine() }
            repeat(5) { rampLine() }
            repeat(4) { silentLine() }
            repeat(8) { rampLine() }
        }.toShortArray()
        val sink = Sink()
        val decoder = WefaxIoc576Decoder(SAMPLE_RATE, sink)

        var offset = 0
        while (offset < audio.size) {
            val count = minOf(882, audio.size - offset)
            decoder.process(audio.copyOfRange(offset, offset + count), count)
            offset += count
        }
        decoder.finishCapture("TEST")

        assertEquals(1, sink.stages.count { it == WefaxIoc576Decoder.Stage.RECEIVING })
        assertTrue(sink.stages.contains(WefaxIoc576Decoder.Stage.COMPLETE))
        assertTrue("expected lines before and after fade, got ${sink.completedLines}", sink.completedLines >= 15)
        assertTrue(sink.complete)
        assertEquals(WefaxIoc576Decoder.IMAGE_WIDTH * sink.completedLines, sink.pixels.size)

        val lastRow = (sink.completedLines - 1) * WefaxIoc576Decoder.IMAGE_WIDTH
        var darkest = 255
        var brightest = 0
        for (index in lastRow until lastRow + WefaxIoc576Decoder.IMAGE_WIDTH) {
            val value = sink.pixels[index] and 0xff
            darkest = minOf(darkest, value)
            brightest = maxOf(brightest, value)
        }
        assertTrue(
            "returning ramp should remain visible after fade: $darkest..$brightest",
            brightest - darkest > 120,
        )

        val receivingIndex = sink.stages.indexOf(WefaxIoc576Decoder.Stage.RECEIVING)
        assertTrue(
            "acquisition must never restart after page lock",
            sink.stages.drop(receivingIndex + 1).none {
                it == WefaxIoc576Decoder.Stage.SEARCHING ||
                    it == WefaxIoc576Decoder.Stage.IOC_576 ||
                    it == WefaxIoc576Decoder.Stage.PHASING
            },
        )
    }

    private class Sink : WefaxIoc576Decoder.Listener {
        val stages = mutableListOf<WefaxIoc576Decoder.Stage>()
        var completedLines = 0
        var pixels = IntArray(0)
        var complete = false

        override fun onStage(
            stage: WefaxIoc576Decoder.Stage,
            modeName: String,
            correctionHz: Int,
            confidence: Int,
        ) {
            if (stages.lastOrNull() != stage) stages += stage
        }

        override fun onFrame(
            width: Int,
            height: Int,
            argbPixels: IntArray,
            completedLines: Int,
            complete: Boolean,
        ) {
            this.completedLines = completedLines
            this.pixels = argbPixels
            this.complete = complete
        }

        override fun onDiagnostic(message: String) = Unit
    }

    private class SyntheticWefax(private val sampleRate: Int) {
        private var phase = 0.0
        private var samples = ShortArray(sampleRate * 4)
        private var size = 0

        fun tone(frequencyHz: Double, seconds: Double) {
            repeat((seconds * sampleRate).toInt()) {
                appendToneSample(frequencyHz)
            }
        }

        fun asymmetricPhasingLine() {
            tone(2300.0, 0.025)
            tone(1500.0, 0.475)
        }

        fun rampLine() {
            val lineSamples = sampleRate / 2
            var used = 0
            for (pixel in 0 until WefaxIoc576Decoder.IMAGE_WIDTH) {
                val next = (((pixel + 1).toLong() * lineSamples) /
                    WefaxIoc576Decoder.IMAGE_WIDTH).toInt()
                val count = next - used
                used = next
                val level = pixel.toDouble() / (WefaxIoc576Decoder.IMAGE_WIDTH - 1)
                val frequencyHz = 1500.0 + 800.0 * level
                repeat(count) { appendToneSample(frequencyHz) }
            }
        }

        fun silentLine() {
            repeat(sampleRate / 2) { append(0) }
        }

        fun toShortArray(): ShortArray = samples.copyOf(size)

        private fun appendToneSample(frequencyHz: Double) {
            append((sin(phase) * 0.8 * Short.MAX_VALUE).toInt().toShort())
            phase += 2.0 * PI * frequencyHz / sampleRate
            if (phase >= 2.0 * PI) phase -= 2.0 * PI
        }

        private fun append(value: Short) {
            if (size == samples.size) samples = samples.copyOf(samples.size * 2)
            samples[size] = value
            size += 1
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
