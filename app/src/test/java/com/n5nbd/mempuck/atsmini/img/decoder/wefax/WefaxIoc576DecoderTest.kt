package com.n5nbd.mempuck.atsmini.img.decoder.wefax

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class WefaxIoc576DecoderTest {
    @Test
    fun decodesOne120LpmLineAt44100HzWithCorrectPolarity() {
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(
            SAMPLE_RATE,
            listener,
            false,
        )
        val samples = splitToneLine(
            firstFrequencyHz = WefaxIoc576Decoder.BLACK_FREQUENCY_HZ,
            secondFrequencyHz = WefaxIoc576Decoder.WHITE_FREQUENCY_HZ,
        )

        feedInIrregularChunks(decoder, samples)

        assertEquals(1, listener.completedLines)
        assertEquals(WefaxIoc576Decoder.IMAGE_WIDTH, listener.width)
        assertEquals(WefaxIoc576Decoder.INITIAL_CAPACITY_LINES, listener.height)
        assertTrue(listener.averageLevel(0, listener.width / 2) < 32)
        assertTrue(listener.averageLevel(listener.width / 2, listener.width) > 220)
    }

    @Test
    fun lineClockDoesNotDependOnAudioCallbackBoundaries() {
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(SAMPLE_RATE, listener, false)
        val samples = continuousTone(
            sampleCount = SAMPLE_RATE,
            frequencyHz = WefaxIoc576Decoder.CENTER_FREQUENCY_HZ,
        )

        feedInIrregularChunks(decoder, samples)

        assertEquals(2, decoder.completedLines)
        decoder.finishCapture("TEST_STOP")
        assertEquals(2, listener.completedLines)
        assertTrue(listener.averageLevel(0, listener.width) in 112..144)
    }

    @Test
    fun acquisitionDoesNotTimeoutWhileInputIsSilent() {
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(SAMPLE_RATE, listener, true)

        feedInIrregularChunks(decoder, ShortArray(SAMPLE_RATE * 6))

        assertEquals("UNLOCKED", decoder.phaseSource)
        assertEquals(0, decoder.completedLines)
        assertTrue(listener.diagnostics.none { it.contains("phase_lock") })
    }

    @Test
    fun activeMidImageAudioUsesDelayedManualFallback() {
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(SAMPLE_RATE, listener, true)

        feedInIrregularChunks(
            decoder,
            continuousTone(
                sampleCount = SAMPLE_RATE * 5,
                frequencyHz = WefaxIoc576Decoder.CENTER_FREQUENCY_HZ,
            ),
        )

        assertEquals("MANUAL_TIMEOUT", decoder.phaseSource)
        assertTrue(decoder.completedLines >= 1)
        assertTrue(listener.diagnostics.any { it.contains("carrier_acquired") })
    }

    @Test
    fun repeatedPhasingEdgesSetLineOriginAndMeasuredClock() {
        val sampleRate = 12_000
        val transmittedSamplesPerLine = 6_001
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(sampleRate, listener, true)
        val generator = ToneGenerator(sampleRate)

        val phasing = generator.lines(
            lineCount = 40,
            samplesPerLine = transmittedSamplesPerLine,
        ) { position, lineLength ->
            if (position < lineLength * 5 / 100) {
                WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
            } else {
                WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
            }
        }
        feedInIrregularChunks(decoder, phasing)

        assertEquals("PHASING", decoder.phaseSource)
        assertTrue(abs(decoder.samplesPerLine - transmittedSamplesPerLine) < 1.0)
        assertTrue(listener.diagnostics.any {
            it.contains("phase_lock source=PHASING") &&
                it.contains("samples_per_line=6001")
        })

        val picture = generator.lines(
            lineCount = 80,
            samplesPerLine = transmittedSamplesPerLine,
        ) { position, lineLength ->
            when {
                position < lineLength * 5 / 100 -> WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                position < lineLength * 8 / 100 -> WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
                else -> WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
            }
        }
        feedInIrregularChunks(decoder, picture)
        decoder.finishCapture("TEST_STOP")

        val firstPictureRow = listener.completedLines - 75
        val lastPictureRow = listener.completedLines - 2
        val firstMarker = listener.darkRunStart(firstPictureRow)
        val lastMarker = listener.darkRunStart(lastPictureRow)
        assertTrue(firstMarker in 75..120)
        assertTrue(lastMarker in 75..120)
        assertTrue(abs(firstMarker - lastMarker) <= 2)
    }

    @Test
    fun robustPhasingFitRejectsEndpointJitter() {
        val sampleRate = 12_000
        val transmittedSamplesPerLine = 5_999
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(sampleRate, listener, true)
        val generator = ToneGenerator(sampleRate)
        val edgeJitter = intArrayOf(
            140, -12, 8, -9, 5, 0, -6, 7,
            -5, 6, -4, 4, -3, 3, -2, 2,
            -1, 1, 0, 0, 0, 0, 0, -130,
        )

        val phasing = generator.linesWithIndex(
            lineCount = edgeJitter.size,
            samplesPerLine = transmittedSamplesPerLine,
        ) { line, position, lineLength ->
            val edge = 200 + edgeJitter[line]
            if (position in edge until edge + lineLength * 5 / 100) {
                WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
            } else {
                WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
            }
        }
        feedInIrregularChunks(decoder, phasing)
        feedInIrregularChunks(
            decoder,
            generator.lines(
                lineCount = 6,
                samplesPerLine = transmittedSamplesPerLine,
            ) { position, lineLength ->
                if (position < lineLength / 10) {
                    WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                } else {
                    WefaxIoc576Decoder.CENTER_FREQUENCY_HZ
                }
            },
        )

        assertEquals("PHASING", decoder.phaseSource)
        assertTrue(abs(decoder.samplesPerLine - transmittedSamplesPerLine) < 1.0)
        assertTrue(listener.diagnostics.any {
            it.contains("phase_calibration_complete") &&
                it.contains("interval_count=23")
        })
    }

    @Test
    fun repeatedVerticalStructureCorrectsLateEntryClockAndExistingRows() {
        val sampleRate = 12_000
        val transmittedSamplesPerLine = 5_999
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(sampleRate, listener, true)
        val generator = ToneGenerator(sampleRate)

        val picture = generator.linesWithIndex(
            lineCount = 330,
            samplesPerLine = transmittedSamplesPerLine,
        ) { line, position, lineLength ->
            val pixel = position * WefaxIoc576Decoder.IMAGE_WIDTH / lineLength
            val stripe = pixel % 173 < 10 ||
                pixel in 350 until 370 ||
                pixel in 900 until 925 ||
                pixel in 1420 until 1445
            val changingBlock = (line / 28) % 2 == 0 && pixel in 600 until 680
            if (stripe || changingBlock) {
                WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
            } else {
                WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
            }
        }
        feedInIrregularChunks(decoder, picture)
        decoder.finishCapture("TEST_STOP")

        assertEquals("MANUAL_TIMEOUT", decoder.phaseSource)
        assertTrue(decoder.isMidImageClockCorrected)
        assertTrue(abs(decoder.samplesPerLine - transmittedSamplesPerLine) < 0.6)
        assertTrue(listener.diagnostics.any { it.contains("mid_clock_corrected") })
        val earlyMarker = listener.darkRunStart(15)
        val lateMarker = listener.darkRunStart(listener.completedLines - 10)
        assertTrue(abs(earlyMarker - lateMarker) < 5)
    }

    @Test
    fun featurelessLateEntryImageDoesNotInventClockCorrection() {
        val sampleRate = 12_000
        val transmittedSamplesPerLine = 5_999
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(sampleRate, listener, true)
        val generator = ToneGenerator(sampleRate)

        feedInIrregularChunks(
            decoder,
            generator.lines(
                lineCount = 280,
                samplesPerLine = transmittedSamplesPerLine,
            ) { _, _ -> WefaxIoc576Decoder.WHITE_FREQUENCY_HZ },
        )
        decoder.finishCapture("TEST_STOP")

        assertEquals("MANUAL_TIMEOUT", decoder.phaseSource)
        assertFalse(decoder.isMidImageClockCorrected)
        assertEquals(6_000.0, decoder.samplesPerLine, 0.001)
    }

    @Test
    fun oneDiagonalFeatureDoesNotMasqueradeAsClockDrift() {
        val sampleRate = 12_000
        val samplesPerLine = 6_000
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(sampleRate, listener, true)
        val generator = ToneGenerator(sampleRate)

        feedInIrregularChunks(
            decoder,
            generator.linesWithIndex(
                lineCount = 300,
                samplesPerLine = samplesPerLine,
            ) { line, position, lineLength ->
                val pixel = position * WefaxIoc576Decoder.IMAGE_WIDTH / lineLength
                val center = 1200 - line * 3 / 10
                if (pixel in center until center + 12) {
                    WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
                } else {
                    WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                }
            },
        )
        decoder.finishCapture("TEST_STOP")

        assertEquals("MANUAL_TIMEOUT", decoder.phaseSource)
        assertFalse(decoder.isMidImageClockCorrected)
        assertEquals(6_000.0, decoder.samplesPerLine, 0.001)
    }


    @Test
    fun standardAptStartAndStopRollPagesWhileDecoderStaysArmed() {
        val sampleRate = 12_000
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(sampleRate, listener, true)
        val generator = ToneGenerator(sampleRate)

        feedInIrregularChunks(decoder, generator.aptTone(seconds = 5, rateHz = 300.0))
        feedInIrregularChunks(
            decoder,
            generator.lines(lineCount = 20, samplesPerLine = sampleRate / 2) { position, length ->
                if (position < length / 20) {
                    WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                } else {
                    WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
                }
            },
        )
        feedInIrregularChunks(
            decoder,
            generator.lines(lineCount = 12, samplesPerLine = sampleRate / 2) { position, length ->
                if (position < length / 2) {
                    WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
                } else {
                    WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                }
            },
        )
        assertTrue(listener.completedLines > 0)

        feedInIrregularChunks(decoder, generator.aptTone(seconds = 5, rateHz = 450.0))

        assertEquals(1, listener.pageStarts)
        assertEquals(1, listener.stopSignals)
        assertEquals(1, listener.completeFrames)
        assertTrue(listener.complete)

        feedInIrregularChunks(decoder, generator.aptTone(seconds = 5, rateHz = 300.0))
        feedInIrregularChunks(
            decoder,
            generator.lines(lineCount = 12, samplesPerLine = sampleRate / 2) { position, length ->
                if (position < length / 20) {
                    WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                } else {
                    WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
                }
            },
        )

        assertEquals(2, listener.pageStarts)
        assertTrue(listener.completedLines > 0)
        assertFalse(listener.complete)
    }

    @Test
    fun repeatedStandardPhasingRollsAnOpenEndedStripOnlyAfterConservativeMatch() {
        val sampleRate = 12_000
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(sampleRate, listener, false)
        val generator = ToneGenerator(sampleRate)

        feedInIrregularChunks(
            decoder,
            generator.lines(lineCount = 20, samplesPerLine = sampleRate / 2) { position, length ->
                if (position < length / 2) {
                    WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
                } else {
                    WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                }
            },
        )
        assertEquals(0, listener.pageStarts)

        feedInIrregularChunks(
            decoder,
            generator.lines(lineCount = 12, samplesPerLine = sampleRate / 2) { position, length ->
                if (position < length / 20) {
                    WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                } else {
                    WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
                }
            },
        )

        assertEquals(1, listener.pageStarts)
        assertTrue(listener.diagnostics.any { it.contains("new_page_phasing") })
    }

    @Test
    fun rolloverContinuesRobustPhasingRefinementBeforeDrawingNextPage() {
        val sampleRate = 12_000
        val transmittedSamplesPerLine = 5_999
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(sampleRate, listener, false)
        val generator = ToneGenerator(sampleRate)

        // Establish a useful first page so the repeated phasing train is treated
        // as a rollover rather than initial acquisition.
        feedInIrregularChunks(
            decoder,
            generator.lines(lineCount = 24, samplesPerLine = sampleRate / 2) { position, length ->
                if (position < length / 2) {
                    WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
                } else {
                    WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                }
            },
        )

        val edgeJitter = intArrayOf(
            130, -10, 8, -7, 5, -4, 3, -2,
            2, -1, 1, 0, 0, 0, 0, -110,
        )
        feedInIrregularChunks(
            decoder,
            generator.linesWithIndex(
                lineCount = edgeJitter.size,
                samplesPerLine = transmittedSamplesPerLine,
            ) { line, position, lineLength ->
                val edge = 180 + edgeJitter[line]
                if (position in edge until edge + lineLength * 5 / 100) {
                    WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                } else {
                    WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
                }
            },
        )

        val picture = generator.lines(
            lineCount = 90,
            samplesPerLine = transmittedSamplesPerLine,
        ) { position, lineLength ->
            val pixel = position * WefaxIoc576Decoder.IMAGE_WIDTH / lineLength
            if (pixel in 280 until 310 || pixel in 1_180 until 1_220) {
                WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
            } else {
                WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
            }
        }
        feedInIrregularChunks(decoder, picture)
        decoder.finishCapture("TEST_STOP")

        assertEquals(1, listener.pageStarts)
        assertEquals("PHASING_RESTART", decoder.phaseSource)
        assertTrue(abs(decoder.samplesPerLine - transmittedSamplesPerLine) < 1.0)
        assertTrue(listener.diagnostics.any {
            it.contains("new_page_phasing") && it.contains("refining=true")
        })
        assertTrue(listener.diagnostics.any {
            it.contains("phase_calibration_complete")
        })
        val firstMarker = listener.darkRunStart(10)
        val lastMarker = listener.darkRunStart(listener.completedLines - 3)
        assertTrue(firstMarker >= 0)
        assertTrue(lastMarker >= 0)
        assertTrue(abs(firstMarker - lastMarker) <= 16)
    }

    @Test
    fun finishPublishesManualStopAsComplete() {
        val listener = RecordingListener()
        val decoder = WefaxIoc576Decoder(SAMPLE_RATE, listener, false)
        decoder.process(
            continuousTone(
                sampleCount = SAMPLE_RATE / 2,
                frequencyHz = WefaxIoc576Decoder.CENTER_FREQUENCY_HZ,
            ),
            SAMPLE_RATE / 2,
        )

        decoder.finishCapture("TEST_STOP")

        assertEquals(1, listener.completedLines)
        assertTrue(listener.complete)
    }

    private class RecordingListener : WefaxIoc576Decoder.Listener {
        var width = 0
        var height = 0
        var completedLines = 0
        var pixels = IntArray(0)
        var complete = false
        var pageStarts = 0
        var stopSignals = 0
        var completeFrames = 0
        val diagnostics = mutableListOf<String>()

        override fun onModeDetected(modeName: String) = Unit

        override fun onFrame(
            width: Int,
            height: Int,
            argbPixels: IntArray,
            completedLines: Int,
            complete: Boolean,
        ) {
            this.width = width
            this.height = height
            this.completedLines = completedLines
            this.pixels = argbPixels
            this.complete = complete
            if (complete) completeFrames++
        }

        override fun onPageStarted(reason: String) {
            pageStarts++
        }

        override fun onStopSignal(reason: String) {
            stopSignals++
        }

        override fun onDiagnostic(message: String) {
            diagnostics += message
        }

        fun averageLevel(startX: Int, endX: Int): Int {
            var total = 0L
            for (x in startX until endX) {
                total += pixels[x] and 0xff
            }
            return (total / (endX - startX)).toInt()
        }

        fun darkRunStart(row: Int): Int {
            val rowOffset = row * width
            for (x in 25 until width) {
                if ((pixels[rowOffset + x] and 0xff) < 96) {
                    return x
                }
            }
            return -1
        }
    }

    private class ToneGenerator(private val sampleRate: Int) {
        private var phase = 0.0

        fun aptTone(seconds: Int, rateHz: Double): ShortArray {
            val output = ShortArray(sampleRate * seconds)
            output.indices.forEach { index ->
                val frequency = if (sin(2.0 * PI * rateHz * index / sampleRate) >= 0.0) {
                    WefaxIoc576Decoder.WHITE_FREQUENCY_HZ
                } else {
                    WefaxIoc576Decoder.BLACK_FREQUENCY_HZ
                }
                phase += 2.0 * PI * frequency / sampleRate
                if (phase >= 2.0 * PI) phase -= 2.0 * PI
                output[index] = (16_000.0 * sin(phase)).roundToInt().toShort()
            }
            return output
        }

        fun lines(
            lineCount: Int,
            samplesPerLine: Int,
            frequencyAt: (position: Int, lineLength: Int) -> Double,
        ): ShortArray = linesWithIndex(
            lineCount = lineCount,
            samplesPerLine = samplesPerLine,
        ) { _, position, lineLength -> frequencyAt(position, lineLength) }

        fun linesWithIndex(
            lineCount: Int,
            samplesPerLine: Int,
            frequencyAt: (line: Int, position: Int, lineLength: Int) -> Double,
        ): ShortArray {
            val output = ShortArray(lineCount * samplesPerLine)
            output.indices.forEach { index ->
                val line = index / samplesPerLine
                val position = index % samplesPerLine
                val frequency = frequencyAt(line, position, samplesPerLine)
                phase += 2.0 * PI * frequency / sampleRate
                if (phase >= 2.0 * PI) phase -= 2.0 * PI
                output[index] = (16_000.0 * sin(phase)).roundToInt().toShort()
            }
            return output
        }
    }

    private fun splitToneLine(
        firstFrequencyHz: Double,
        secondFrequencyHz: Double,
    ): ShortArray {
        val samples = ShortArray(SAMPLE_RATE / 2)
        var phase = 0.0
        samples.indices.forEach { index ->
            val frequency = if (index < samples.size / 2) {
                firstFrequencyHz
            } else {
                secondFrequencyHz
            }
            phase += 2.0 * PI * frequency / SAMPLE_RATE
            samples[index] = (16_000.0 * sin(phase)).roundToInt().toShort()
        }
        return samples
    }

    private fun continuousTone(
        sampleCount: Int,
        frequencyHz: Double,
    ): ShortArray = ShortArray(sampleCount) { index ->
        (16_000.0 * sin(2.0 * PI * frequencyHz * index / SAMPLE_RATE))
            .roundToInt()
            .toShort()
    }

    private fun feedInIrregularChunks(
        decoder: WefaxIoc576Decoder,
        samples: ShortArray,
    ) {
        val chunkSizes = intArrayOf(73, 881, 17, 2_048, 511)
        var position = 0
        var chunk = 0
        while (position < samples.size) {
            val count = minOf(chunkSizes[chunk % chunkSizes.size], samples.size - position)
            decoder.process(samples.copyOfRange(position, position + count), count)
            position += count
            chunk++
        }
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
    }
}
