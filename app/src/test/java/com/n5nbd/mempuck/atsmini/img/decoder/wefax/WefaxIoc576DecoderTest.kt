package com.n5nbd.mempuck.atsmini.img.decoder.wefax

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val sink = decode(audio)

        assertEquals(1, sink.stages.count { it == WefaxIoc576Decoder.Stage.RECEIVING })
        assertTrue(sink.stages.contains(WefaxIoc576Decoder.Stage.COMPLETE))
        assertTrue("expected lines before and after fade, got ${sink.completedLines}", sink.completedLines >= 15)
        assertTrue(sink.complete)
        assertEquals(WefaxIoc576Decoder.IMAGE_WIDTH * sink.completedLines, sink.pixels.size)
        assertFalse(sink.modeNames.contains(WefaxIoc576Decoder.LATE_JOIN_MODE_NAME))

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

        assertAcquisitionDoesNotRestart(sink)
    }

    @Test
    fun lateJoinStartsInsideActiveImageAndSurvivesFade() {
        val audio = SyntheticWefax(SAMPLE_RATE).apply {
            repeat(8) { busyImageLine() }
            repeat(4) { silentLine() }
            repeat(10) { busyImageLine() }
        }.toShortArray()
        val sink = decode(audio)

        assertEquals(1, sink.stages.count { it == WefaxIoc576Decoder.Stage.RECEIVING })
        assertTrue(sink.modeNames.contains(WefaxIoc576Decoder.LATE_JOIN_MODE_NAME))
        assertTrue(sink.complete)
        assertTrue(
            "late join should retain lines before and after the fade, got ${sink.completedLines}",
            sink.completedLines >= 17,
        )
        assertAcquisitionDoesNotRestart(sink)
    }


    @Test
    fun lateJoinNeverSwitchesRasterTimingAwayFrom120Lpm() {
        val audio = SyntheticWefax(SAMPLE_RATE).apply {
            // Deliberately periodic content that previously tempted the autocorrelation path
            // to relabel and reconstruct the raster at another LPM.
            repeat(30) { busyImageLine(240) }
        }.toShortArray()
        val sink = decode(audio)

        assertTrue(sink.complete)
        assertTrue(sink.modeNames.contains(WefaxIoc576Decoder.LATE_JOIN_MODE_NAME))
        assertTrue(sink.modeNames.none { it.contains("60/576") })
        assertTrue(sink.modeNames.none { it.contains("90/576") })
        assertTrue(sink.modeNames.none { it.contains("180/576") })
        assertTrue(sink.modeNames.none { it.contains("240/576") })
    }

    @Test
    fun fixed120LineSpanFillsTheEntire1809PixelRaster() {
        val audio = SyntheticWefax(SAMPLE_RATE).apply {
            tone(1500.0, 0.20)
            repeat(10) { asymmetricPhasingLine() }
            repeat(12) { edgeMarkedLine() }
        }.toShortArray()
        val sink = decode(audio)

        assertTrue(sink.complete)
        assertTrue(sink.completedLines >= 8)
        val row = (sink.completedLines - 1) * WefaxIoc576Decoder.IMAGE_WIDTH
        val left = averageLuma(sink.pixels, row, row + 120)
        val right = averageLuma(
            sink.pixels,
            row + WefaxIoc576Decoder.IMAGE_WIDTH - 120,
            row + WefaxIoc576Decoder.IMAGE_WIDTH,
        )
        assertTrue("left edge marker missing: $left", left < 140.0)
        assertTrue("right edge marker missing: $right", right < 140.0)
    }

    @Test
    fun silenceDoesNotStartLateJoinPage() {
        val audio = SyntheticWefax(SAMPLE_RATE).apply {
            repeat(12) { silentLine() }
        }.toShortArray()
        val sink = decode(audio)

        assertFalse(sink.stages.contains(WefaxIoc576Decoder.Stage.RECEIVING))
        assertFalse(sink.complete)
        assertEquals(0, sink.completedLines)
    }


    private fun averageLuma(pixels: IntArray, start: Int, end: Int): Double {
        var sum = 0L
        for (index in start until end) sum += pixels[index] and 0xff
        return sum.toDouble() / (end - start)
    }

    private fun decode(audio: ShortArray): Sink {
        val sink = Sink()
        val decoder = WefaxIoc576Decoder(SAMPLE_RATE, sink)
        var offset = 0
        while (offset < audio.size) {
            val count = minOf(882, audio.size - offset)
            decoder.process(audio.copyOfRange(offset, offset + count), count)
            offset += count
        }
        decoder.finishCapture("TEST")
        return sink
    }

    private fun assertAcquisitionDoesNotRestart(sink: Sink) {
        val receivingIndex = sink.stages.indexOf(WefaxIoc576Decoder.Stage.RECEIVING)
        assertTrue(receivingIndex >= 0)
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
        val modeNames = mutableListOf<String>()
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
            modeNames += modeName
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

        fun busyImageLine(lpm: Int = 120) {
            val lineSamples = (sampleRate * 60.0 / lpm).toInt()
            repeat(lineSamples) { sample ->
                val frequencyHz = 1900.0 +
                    360.0 * sin(2.0 * PI * 7.0 * sample / lineSamples) +
                    90.0 * sin(2.0 * PI * 3.0 * sample / lineSamples)
                appendToneSample(frequencyHz)
            }
        }


        fun edgeMarkedLine() {
            val lineSamples = sampleRate / 2
            val edgeSamples = lineSamples / 8
            repeat(edgeSamples) { appendToneSample(1500.0) }
            repeat(lineSamples - edgeSamples * 2) { sample ->
                val frequencyHz = 1900.0 + 260.0 * sin(2.0 * PI * 5.0 * sample / lineSamples)
                appendToneSample(frequencyHz)
            }
            repeat(edgeSamples) { appendToneSample(1500.0) }
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
