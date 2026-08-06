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
    fun phasingLockAcquiresPositive850HzAudioOffset() {
        val sink = decodeOffsetPage(850.0)

        assertTrue(sink.complete)
        assertTrue(sink.stages.contains(WefaxIoc576Decoder.Stage.RECEIVING))
        assertTrue(
            "expected AFC correction near -850 Hz, got ${sink.corrections}",
            sink.corrections.any { it in -910..-790 },
        )
        assertTrue("offset ramp should retain contrast", sink.lastRowContrast() > 120)
    }

    @Test
    fun phasingLockAcquiresNegative850HzAudioOffset() {
        val sink = decodeOffsetPage(-850.0)

        assertTrue(sink.complete)
        assertTrue(sink.stages.contains(WefaxIoc576Decoder.Stage.RECEIVING))
        assertTrue(
            "expected AFC correction near +850 Hz, got ${sink.corrections}",
            sink.corrections.any { it in 790..910 },
        )
        assertTrue("offset ramp should retain contrast", sink.lastRowContrast() > 120)
    }

    @Test
    fun lateJoinNormalizesPositive400HzAudioOffset() {
        val audio = SyntheticWefax(SAMPLE_RATE, 400.0).apply {
            repeat(18) { busyImageLine() }
        }.toShortArray()
        val sink = decode(audio)

        assertTrue(sink.complete)
        assertTrue(sink.modeNames.contains(WefaxIoc576Decoder.LATE_JOIN_MODE_NAME))
        assertTrue(
            "expected late-join correction near -400 Hz, got ${sink.corrections}",
            sink.corrections.any { it in -500..-300 },
        )
        assertTrue("late-join offset image should retain contrast", sink.lastRowContrast() > 100)
    }

    @Test
    fun lateJoinNormalizesNegative400HzAudioOffset() {
        val audio = SyntheticWefax(SAMPLE_RATE, -400.0).apply {
            repeat(18) { busyImageLine() }
        }.toShortArray()
        val sink = decode(audio)

        assertTrue(sink.complete)
        assertTrue(sink.modeNames.contains(WefaxIoc576Decoder.LATE_JOIN_MODE_NAME))
        assertTrue(
            "expected late-join correction near +400 Hz, got ${sink.corrections}",
            sink.corrections.any { it in 300..500 },
        )
        assertTrue("late-join offset image should retain contrast", sink.lastRowContrast() > 100)
    }

    private fun decodeOffsetPage(offsetHz: Double): Sink {
        val audio = SyntheticWefax(SAMPLE_RATE, offsetHz).apply {
            iocSelection(0.70)
            repeat(12) { asymmetricPhasingLine() }
            repeat(12) { rampLine() }
        }.toShortArray()
        return decode(audio)
    }


    @Test
    fun fullStartAfcKeepsFixedGrayscaleAcrossWideOffsets() {
        val control = decodeOffsetPage(0.0)
        val controlMean = control.lastRowMean()

        for (offsetHz in listOf(-750.0, -500.0, -250.0, 250.0, 500.0, 750.0)) {
            val sink = decodeOffsetPage(offsetHz)
            assertTrue("offset $offsetHz should complete", sink.complete)
            assertTrue(
                "full-start luma shifted at $offsetHz Hz: ${sink.lastRowMean()} vs $controlMean",
                kotlin.math.abs(sink.lastRowMean() - controlMean) <= 5.0,
            )
            assertTrue("offset $offsetHz should retain contrast", sink.lastRowContrast() > 200)
        }
    }

    @Test
    fun lateJoinAfcKeepsFixedGrayscaleAcrossWideOffsets() {
        fun decodeLateJoin(offsetHz: Double): Sink {
            val audio = SyntheticWefax(SAMPLE_RATE, offsetHz).apply {
                repeat(18) { busyImageLine() }
            }.toShortArray()
            return decode(audio)
        }

        val control = decodeLateJoin(0.0)
        val controlMean = control.lastRowMean()
        for (offsetHz in listOf(-750.0, -500.0, -250.0, 250.0, 500.0, 750.0)) {
            val sink = decodeLateJoin(offsetHz)
            assertTrue("offset $offsetHz should complete", sink.complete)
            assertTrue(sink.modeNames.contains(WefaxIoc576Decoder.LATE_JOIN_MODE_NAME))
            assertTrue(
                "late-join luma shifted at $offsetHz Hz: ${sink.lastRowMean()} vs $controlMean",
                kotlin.math.abs(sink.lastRowMean() - controlMean) <= 5.0,
            )
            assertTrue("offset $offsetHz should retain contrast", sink.lastRowContrast() > 200)
        }
    }

    @Test
    fun lateJoinTrackingAfcConvergesWithoutPositiveFeedback() {
        for (offsetHz in listOf(-500.0, 500.0, 750.0)) {
            val audio = SyntheticWefax(SAMPLE_RATE, offsetHz).apply {
                // Force the one-shot late-join estimate to depend on a narrow gray field.
                repeat(10) { syncedGrayLine(0.55) }
                repeat(72) { syncedRampLine() }
            }.toShortArray()
            val sink = decode(audio)
            val receivingCorrections = sink.stageEvents
                .filter { it.stage == WefaxIoc576Decoder.Stage.RECEIVING }
                .map { it.correctionHz }
            val expectedCorrection = -offsetHz

            assertTrue("expected rolling AFC reports at $offsetHz: $receivingCorrections",
                receivingCorrections.distinct().size >= 3)
            assertTrue("rolling AFC did not converge at $offsetHz: $receivingCorrections",
                kotlin.math.abs(receivingCorrections.last() - expectedCorrection) <= 70.0)
            assertTrue("rolling AFC ran away at $offsetHz: $receivingCorrections",
                receivingCorrections.all { kotlin.math.abs(it) <= 1000 })
            assertTrue("final ramp lost contrast at $offsetHz", sink.lastRowContrast() > 200)
        }
    }


    @Test
    fun lateJoinWhiteReferenceConvergesFromArbitraryHorizontalPhase() {
        for (offsetHz in listOf(-750.0, -500.0, -250.0, 0.0, 250.0, 500.0, 750.0)) {
            val audio = SyntheticWefax(SAMPLE_RATE, offsetHz).apply {
                // Capture starts 37% into a picture line. The recurring white reference therefore
                // lands at an arbitrary local phase, exactly as it does during a real mid-image join.
                partialSyncedBusyLine(0.37)
                repeat(8) { syncedBusyLine() }
                repeat(76) { syncedRampLine() }
            }.toShortArray()
            val sink = decode(audio)
            val receivingCorrections = sink.stageEvents
                .filter { it.stage == WefaxIoc576Decoder.Stage.RECEIVING }
                .map { it.correctionHz }
            val expectedCorrection = -offsetHz

            assertTrue("late join failed at $offsetHz Hz", sink.complete)
            assertTrue(sink.modeNames.contains(WefaxIoc576Decoder.LATE_JOIN_MODE_NAME))
            assertTrue("no correction reports at $offsetHz: $receivingCorrections",
                receivingCorrections.isNotEmpty())
            assertTrue("white-reference AFC missed $offsetHz: $receivingCorrections",
                kotlin.math.abs(receivingCorrections.last() - expectedCorrection) <= 45.0)
            assertTrue("late-start ramp lost contrast at $offsetHz", sink.lastRowContrast() > 200)
            assertAcquisitionDoesNotRestart(sink)
        }
    }

    @Test
    fun lateJoinWhiteReferenceSurvivesAttenuatedUpperAudio() {
        for (offsetHz in listOf(500.0, 750.0)) {
            val audio = SyntheticWefax(
                sampleRate = SAMPLE_RATE,
                frequencyOffsetHz = offsetHz,
                referenceGain = 0.35,
            ).apply {
                partialSyncedBusyLine(0.61)
                repeat(10) { syncedBusyLine() }
                repeat(78) { syncedRampLine() }
            }.toShortArray()
            val sink = decode(audio)
            val receivingCorrections = sink.stageEvents
                .filter { it.stage == WefaxIoc576Decoder.Stage.RECEIVING }
                .map { it.correctionHz }

            assertTrue("attenuated late join failed at $offsetHz", sink.complete)
            assertTrue(sink.modeNames.contains(WefaxIoc576Decoder.LATE_JOIN_MODE_NAME))
            assertTrue("attenuated white reference missed $offsetHz: $receivingCorrections",
                kotlin.math.abs(receivingCorrections.last() + offsetHz) <= 55.0)
            assertAcquisitionDoesNotRestart(sink)
        }
    }

    @Test
    fun zeroOffsetRollingAfcDoesNotChasePictureBrightness() {
        val audio = SyntheticWefax(SAMPLE_RATE).apply {
            repeat(10) { syncedGrayLine(0.80) }
            repeat(80) { syncedRampLine() }
        }.toShortArray()
        val sink = decode(audio)
        val receivingCorrections = sink.stageEvents
            .filter { it.stage == WefaxIoc576Decoder.Stage.RECEIVING }
            .map { it.correctionHz }

        assertTrue("zero-offset AFC did not settle: $receivingCorrections",
            kotlin.math.abs(receivingCorrections.last()) <= 35)
        assertTrue("zero-offset AFC ran away: $receivingCorrections",
            receivingCorrections.all { kotlin.math.abs(it) <= 300 })
        assertTrue("zero-offset ramp lost contrast", sink.lastRowContrast() > 200)
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
        val corrections = mutableListOf<Int>()
        val stageEvents = mutableListOf<StageEvent>()
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
            corrections += correctionHz
            stageEvents += StageEvent(stage, correctionHz, confidence)
        }

        fun lastRowMean(): Double {
            if (completedLines <= 0 || pixels.isEmpty()) return 0.0
            val start = (completedLines - 1) * WefaxIoc576Decoder.IMAGE_WIDTH
            var sum = 0L
            for (index in start until start + WefaxIoc576Decoder.IMAGE_WIDTH) {
                sum += pixels[index] and 0xff
            }
            return sum.toDouble() / WefaxIoc576Decoder.IMAGE_WIDTH
        }

        fun lastRowContrast(): Int {
            if (completedLines <= 0 || pixels.isEmpty()) return 0
            val start = (completedLines - 1) * WefaxIoc576Decoder.IMAGE_WIDTH
            var darkest = 255
            var brightest = 0
            for (index in start until start + WefaxIoc576Decoder.IMAGE_WIDTH) {
                val value = pixels[index] and 0xff
                darkest = minOf(darkest, value)
                brightest = maxOf(brightest, value)
            }
            return brightest - darkest
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

    private data class StageEvent(
        val stage: WefaxIoc576Decoder.Stage,
        val correctionHz: Int,
        val confidence: Int,
    )

    private class SyntheticWefax(
        private val sampleRate: Int,
        private val frequencyOffsetHz: Double = 0.0,
        private val referenceGain: Double = 1.0,
    ) {
        private var phase = 0.0
        private var samples = ShortArray(sampleRate * 4)
        private var size = 0

        fun tone(frequencyHz: Double, seconds: Double) {
            repeat((seconds * sampleRate).toInt()) {
                appendToneSample(frequencyHz)
            }
        }

        fun iocSelection(seconds: Double) {
            val halfCycleSamples = sampleRate / (300.0 * 2.0)
            val totalHalfCycles = (seconds * 300.0 * 2.0).toInt()
            var emitted = 0
            for (halfCycle in 0 until totalHalfCycles) {
                val next = ((halfCycle + 1) * halfCycleSamples).toInt()
                val count = next - emitted
                emitted = next
                val frequencyHz = if (halfCycle % 2 == 0) 2300.0 else 1500.0
                repeat(count) { appendToneSample(frequencyHz) }
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

        fun syncedGrayLine(level: Double) {
            val lineSamples = sampleRate / 2
            val referenceSamples = (lineSamples * 0.05).toInt()
            repeat(referenceSamples) { appendToneSample(2300.0, referenceGain) }
            val frequencyHz = 1500.0 + 800.0 * level.coerceIn(0.0, 1.0)
            repeat(lineSamples - referenceSamples) { appendToneSample(frequencyHz) }
        }

        fun syncedRampLine() {
            emitSyncedRampLine(startSample = 0)
        }

        fun partialSyncedRampLine(startFraction: Double) {
            val lineSamples = sampleRate / 2
            val startSample = (lineSamples * startFraction.coerceIn(0.0, 0.99)).toInt()
            emitSyncedRampLine(startSample)
        }

        private fun emitSyncedRampLine(startSample: Int) {
            val lineSamples = sampleRate / 2
            val referenceSamples = (lineSamples * 0.05).toInt()
            val imageSamples = lineSamples - referenceSamples
            for (sample in startSample until lineSamples) {
                if (sample < referenceSamples) {
                    appendToneSample(2300.0, referenceGain)
                } else {
                    val imageSample = sample - referenceSamples
                    val level = imageSample.toDouble() / (imageSamples - 1).coerceAtLeast(1)
                    appendToneSample(1500.0 + 800.0 * level)
                }
            }
        }


        fun syncedBusyLine() {
            emitSyncedBusyLine(startSample = 0)
        }

        fun partialSyncedBusyLine(startFraction: Double) {
            val lineSamples = sampleRate / 2
            val startSample = (lineSamples * startFraction.coerceIn(0.0, 0.99)).toInt()
            emitSyncedBusyLine(startSample)
        }

        private fun emitSyncedBusyLine(startSample: Int) {
            val lineSamples = sampleRate / 2
            val referenceSamples = (lineSamples * 0.05).toInt()
            val imageSamples = lineSamples - referenceSamples
            for (sample in startSample until lineSamples) {
                if (sample < referenceSamples) {
                    appendToneSample(2300.0, referenceGain)
                } else {
                    val imageSample = sample - referenceSamples
                    val frequencyHz = 1900.0 +
                        320.0 * sin(2.0 * PI * 7.0 * imageSample / imageSamples) +
                        70.0 * sin(2.0 * PI * 19.0 * imageSample / imageSamples)
                    appendToneSample(frequencyHz)
                }
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

        private fun appendToneSample(frequencyHz: Double, amplitudeScale: Double = 1.0) {
            val boundedAmplitude = amplitudeScale.coerceIn(0.0, 1.0)
            append((sin(phase) * 0.8 * boundedAmplitude * Short.MAX_VALUE).toInt().toShort())
            phase += 2.0 * PI * (frequencyHz + frequencyOffsetHz) / sampleRate
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
