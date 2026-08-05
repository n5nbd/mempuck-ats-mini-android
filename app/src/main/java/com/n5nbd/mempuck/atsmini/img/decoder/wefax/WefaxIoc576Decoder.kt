package com.n5nbd.mempuck.atsmini.img.decoder.wefax

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.ArrayDeque

/**
 * Application-local analogue weather-fax receiver for the common 120 LPM / IOC 576 format.
 *
 * The decoder deliberately separates acquisition from reception. Phasing establishes the line
 * period, horizontal phase, and black/white audio tones. Once reception begins those settings are
 * frozen: weak audio, silence, noise, or a returning signal can damage raster data, but cannot
 * restart the page or replace the established timing.
 */
class WefaxIoc576Decoder(
    private val sampleRateHz: Int,
    private val listener: Listener,
) {
    enum class Stage {
        SEARCHING,
        IOC_576,
        PHASING,
        RECEIVING,
        COMPLETE,
    }

    interface Listener {
        fun onStage(
            stage: Stage,
            modeName: String,
            correctionHz: Int,
            confidence: Int,
        )

        fun onFrame(
            width: Int,
            height: Int,
            argbPixels: IntArray,
            completedLines: Int,
            complete: Boolean,
        )

        fun onDiagnostic(message: String)
    }

    private val demodulator = FmSubcarrierDemodulator(sampleRateHz)
    private val nominalLinePeriod = sampleRateHz / LINES_PER_SECOND
    private val pixelSums = LongArray(IMAGE_WIDTH)
    private val pixelCounts = IntArray(IMAGE_WIDTH)
    private val currentLine = IntArray(IMAGE_WIDTH)
    private val phasingIntervals = ArrayDeque<Long>()

    private var stage = Stage.SEARCHING
    private var absoluteSample = 0L
    private var binaryWhite = false
    private var lastRisingSample = Long.MIN_VALUE
    private var lastFallingSample = Long.MIN_VALUE
    private var transitionsSinceRising = 0
    private var goodPhasingIntervals = 0
    private var iocHalfCycleStart = Long.MIN_VALUE
    private var iocHalfCycles = 0
    private var lineStartSample = 0.0
    private var nextLineBoundary = 0.0
    private var linePeriodSamples = nominalLinePeriod
    private var blackFrequencyHz = BLACK_FREQUENCY_HZ
    private var whiteFrequencyHz = WHITE_FREQUENCY_HZ
    private var candidateBlackSum = 0.0
    private var candidateBlackCount = 0L
    private var candidateWhiteSum = 0.0
    private var candidateWhiteCount = 0L
    private var imagePixels = IntArray(IMAGE_WIDTH * INITIAL_CAPACITY_LINES)
    private var completedLines = 0
    private var linesSinceFrame = 0
    private var finished = false

    init {
        require(sampleRateHz >= 8_000) { "WEFAX sample rate is too low: $sampleRateHz" }
        listener.onStage(Stage.SEARCHING, MODE_NAME, 0, 0)
        listener.onDiagnostic(
            "WEFAX init sample_rate_hz=$sampleRateHz width=$IMAGE_WIDTH " +
                "nominal_line_samples=${format(nominalLinePeriod)}",
        )
    }

    fun process(samples: ShortArray, count: Int) {
        if (finished) return
        val safeCount = count.coerceIn(0, samples.size)
        for (index in 0 until safeCount) {
            val frequencyHz = demodulator.process(samples[index])
            val sampleIndex = absoluteSample
            absoluteSample += 1

            if (sampleIndex < demodulator.warmupSamples) continue

            when (stage) {
                Stage.SEARCHING,
                Stage.IOC_576,
                -> processAcquisitionSample(sampleIndex, frequencyHz)

                Stage.PHASING,
                Stage.RECEIVING,
                -> processRasterSample(sampleIndex, frequencyHz)

                Stage.COMPLETE -> Unit
            }
        }
    }

    /** Finalizes the current page only because the user/session explicitly stopped. */
    fun finishCapture(reason: String) {
        if (finished) return
        finished = true
        if (stage == Stage.RECEIVING && completedLines > 0) {
            stage = Stage.COMPLETE
            emitFrame(complete = true)
            listener.onStage(
                Stage.COMPLETE,
                MODE_NAME,
                frequencyCorrectionHz(),
                100,
            )
            listener.onDiagnostic(
                "WEFAX complete reason=$reason lines=$completedLines " +
                    "line_samples=${format(linePeriodSamples)}",
            )
        } else {
            listener.onDiagnostic(
                "WEFAX stopped reason=$reason stage=$stage lines=$completedLines",
            )
        }
    }

    private fun processAcquisitionSample(sampleIndex: Long, frequencyHz: Double) {
        val newWhite = classifyWhite(frequencyHz)
        collectCandidateTone(frequencyHz, newWhite)
        if (newWhite == binaryWhite) return

        binaryWhite = newWhite
        processIocTransition(sampleIndex)
        if (newWhite) {
            processPhasingRisingEdge(sampleIndex)
        } else {
            lastFallingSample = sampleIndex
            transitionsSinceRising += 1
        }
    }

    private fun processIocTransition(sampleIndex: Long) {
        if (iocHalfCycleStart == Long.MIN_VALUE) {
            iocHalfCycleStart = sampleIndex
            iocHalfCycles = 0
            return
        }
        val duration = sampleIndex - iocHalfCycleStart
        iocHalfCycleStart = sampleIndex
        val expected = sampleRateHz.toDouble() / (IOC_SELECTION_HZ * 2.0)
        if (duration.toDouble() in expected * 0.58..expected * 1.55) {
            iocHalfCycles += 1
            if (iocHalfCycles == IOC_REQUIRED_HALF_CYCLES) {
                stage = Stage.IOC_576
                listener.onStage(Stage.IOC_576, MODE_NAME, 0, 55)
                listener.onDiagnostic(
                    "WEFAX IOC576 detected half_cycles=$iocHalfCycles",
                )
            }
        } else {
            iocHalfCycles = 0
        }
    }

    private fun processPhasingRisingEdge(sampleIndex: Long) {
        if (lastRisingSample == Long.MIN_VALUE) {
            lastRisingSample = sampleIndex
            transitionsSinceRising = 0
            return
        }

        val interval = sampleIndex - lastRisingSample
        val highWidth = if (lastFallingSample > lastRisingSample) {
            lastFallingSample - lastRisingSample
        } else {
            0L
        }
        val periodOkay = interval.toDouble() in nominalLinePeriod * 0.82..nominalLinePeriod * 1.18
        val brightFraction = if (interval > 0) highWidth.toDouble() / interval else 0.0
        val shapeOkay = brightFraction in ASYMMETRIC_WHITE_FRACTION ||
            brightFraction in SYMMETRIC_WHITE_FRACTION
        val edgeCountOkay = transitionsSinceRising in 1..3

        if (periodOkay && shapeOkay && edgeCountOkay) {
            phasingIntervals.addLast(interval)
            while (phasingIntervals.size > PHASING_INTERVAL_WINDOW) {
                phasingIntervals.removeFirst()
            }
            goodPhasingIntervals += 1
            val confidence = (goodPhasingIntervals * 100 / PHASING_REQUIRED_INTERVALS)
                .coerceIn(1, 99)
            listener.onStage(Stage.PHASING, MODE_NAME, 0, confidence)
            if (goodPhasingIntervals >= PHASING_REQUIRED_INTERVALS) {
                lockPhasing(sampleIndex)
                return
            }
        } else {
            resetPhasingCandidate()
        }

        lastRisingSample = sampleIndex
        transitionsSinceRising = 0
    }

    private fun resetPhasingCandidate() {
        phasingIntervals.clear()
        goodPhasingIntervals = 0
        candidateBlackSum = 0.0
        candidateBlackCount = 0
        candidateWhiteSum = 0.0
        candidateWhiteCount = 0
        if (stage == Stage.PHASING) {
            stage = Stage.SEARCHING
            listener.onStage(Stage.SEARCHING, MODE_NAME, 0, 0)
        }
    }

    private fun lockPhasing(risingSample: Long) {
        linePeriodSamples = median(phasingIntervals.map(Long::toDouble))
            .coerceIn(nominalLinePeriod * 0.82, nominalLinePeriod * 1.18)

        val measuredBlack = candidateBlackSum / candidateBlackCount.coerceAtLeast(1)
        val measuredWhite = candidateWhiteSum / candidateWhiteCount.coerceAtLeast(1)
        if (
            candidateBlackCount >= MIN_TONE_SAMPLES &&
            candidateWhiteCount >= MIN_TONE_SAMPLES &&
            measuredWhite - measuredBlack >= MIN_TONE_SEPARATION_HZ
        ) {
            blackFrequencyHz = measuredBlack
            whiteFrequencyHz = measuredWhite
        }

        stage = Stage.PHASING
        lineStartSample = risingSample.toDouble()
        nextLineBoundary = lineStartSample + linePeriodSamples
        clearLineAccumulator()
        listener.onStage(
            Stage.PHASING,
            MODE_NAME,
            frequencyCorrectionHz(),
            100,
        )
        listener.onDiagnostic(
            "WEFAX phasing_lock line_samples=${format(linePeriodSamples)} " +
                "black_hz=${format(blackFrequencyHz)} white_hz=${format(whiteFrequencyHz)} " +
                "correction_hz=${frequencyCorrectionHz()}",
        )
    }

    private fun processRasterSample(sampleIndex: Long, frequencyHz: Double) {
        while (sampleIndex.toDouble() >= nextLineBoundary) {
            finalizeRasterLine()
            lineStartSample = nextLineBoundary
            nextLineBoundary += linePeriodSamples
            clearLineAccumulator()
        }

        val position = (sampleIndex.toDouble() - lineStartSample) / linePeriodSamples
        val pixel = floor(position * IMAGE_WIDTH).toInt().coerceIn(0, IMAGE_WIDTH - 1)
        pixelSums[pixel] = pixelSums[pixel] + frequencyToLuma(frequencyHz).toLong()
        pixelCounts[pixel] += 1
    }

    private fun finalizeRasterLine() {
        var previous = 0
        for (pixel in 0 until IMAGE_WIDTH) {
            val count = pixelCounts[pixel]
            val value = if (count > 0) {
                (pixelSums[pixel] / count).toInt().coerceIn(0, 255)
            } else {
                previous
            }
            currentLine[pixel] = value
            previous = value
        }

        if (stage == Stage.PHASING && isPhasingLine(currentLine)) {
            return
        }

        if (stage == Stage.PHASING) {
            stage = Stage.RECEIVING
            listener.onStage(
                Stage.RECEIVING,
                MODE_NAME,
                frequencyCorrectionHz(),
                100,
            )
            listener.onDiagnostic(
                "WEFAX image_start sample=${lineStartSample.roundToInt()} " +
                    "line_samples=${format(linePeriodSamples)}",
            )
        }

        // Reception is intentionally open-loop after phasing lock. Every elapsed line is retained,
        // including noise or blank raster during a fade. Acquisition detectors are never consulted
        // again, so a returning signal resumes in the same page with the same timing and tone map.
        appendImageLine(currentLine)
    }

    private fun appendImageLine(line: IntArray) {
        if (completedLines >= MAX_IMAGE_LINES) {
            finished = true
            stage = Stage.COMPLETE
            emitFrame(complete = true)
            listener.onStage(Stage.COMPLETE, MODE_NAME, frequencyCorrectionHz(), 100)
            listener.onDiagnostic("WEFAX complete reason=max_lines lines=$completedLines")
            return
        }

        ensureImageCapacity(completedLines + 1)
        val row = completedLines * IMAGE_WIDTH
        for (pixel in 0 until IMAGE_WIDTH) {
            val value = line[pixel]
            imagePixels[row + pixel] =
                0xff000000.toInt() or (value shl 16) or (value shl 8) or value
        }
        completedLines += 1
        linesSinceFrame += 1
        if (linesSinceFrame >= FRAME_INTERVAL_LINES || completedLines <= 2) {
            emitFrame(complete = false)
        }
    }

    private fun emitFrame(complete: Boolean) {
        if (completedLines <= 0) return
        linesSinceFrame = 0
        listener.onFrame(
            width = IMAGE_WIDTH,
            height = completedLines,
            argbPixels = imagePixels.copyOf(completedLines * IMAGE_WIDTH),
            completedLines = completedLines,
            complete = complete,
        )
    }

    private fun ensureImageCapacity(wantedLines: Int) {
        val wantedPixels = wantedLines * IMAGE_WIDTH
        if (wantedPixels <= imagePixels.size) return
        val currentLines = imagePixels.size / IMAGE_WIDTH
        val newLines = (currentLines + CAPACITY_GROWTH_LINES)
            .coerceAtLeast(wantedLines)
            .coerceAtMost(MAX_IMAGE_LINES)
        imagePixels = imagePixels.copyOf(newLines * IMAGE_WIDTH)
    }

    private fun classifyWhite(frequencyHz: Double): Boolean {
        val center = (BLACK_FREQUENCY_HZ + WHITE_FREQUENCY_HZ) / 2.0
        return if (binaryWhite) {
            frequencyHz >= center - ACQUISITION_HYSTERESIS_HZ
        } else {
            frequencyHz > center + ACQUISITION_HYSTERESIS_HZ
        }
    }

    private fun collectCandidateTone(frequencyHz: Double, white: Boolean) {
        if (frequencyHz !in MIN_VALID_FREQUENCY_HZ..MAX_VALID_FREQUENCY_HZ) return
        if (white) {
            candidateWhiteSum += frequencyHz
            candidateWhiteCount += 1
        } else {
            candidateBlackSum += frequencyHz
            candidateBlackCount += 1
        }
    }

    private fun frequencyToLuma(frequencyHz: Double): Int {
        val span = (whiteFrequencyHz - blackFrequencyHz).coerceAtLeast(MIN_TONE_SEPARATION_HZ)
        return (((frequencyHz - blackFrequencyHz) * 255.0) / span)
            .roundToInt()
            .coerceIn(0, 255)
    }

    private fun isPhasingLine(line: IntArray): Boolean {
        var white = false
        var transitions = 0
        var brightPixels = 0
        var firstSum = 0L
        var tailSum = 0L
        val firstCount = (IMAGE_WIDTH * 0.02).roundToInt().coerceAtLeast(1)
        val tailStart = (IMAGE_WIDTH * 0.80).roundToInt()

        for (pixel in line.indices) {
            val value = line[pixel]
            if (value >= 160) brightPixels += 1
            if (pixel < firstCount) firstSum += value
            if (pixel >= tailStart) tailSum += value
            val nextWhite = if (white) value >= 96 else value > 160
            if (nextWhite != white) {
                transitions += 1
                white = nextWhite
            }
        }

        val brightFraction = brightPixels.toDouble() / IMAGE_WIDTH
        val firstAverage = firstSum.toDouble() / firstCount
        val tailAverage = tailSum.toDouble() / (IMAGE_WIDTH - tailStart)
        val shape = brightFraction in ASYMMETRIC_WHITE_FRACTION ||
            brightFraction in SYMMETRIC_WHITE_FRACTION
        return transitions in 1..4 && shape && firstAverage >= 150.0 && tailAverage <= 105.0
    }

    private fun clearLineAccumulator() {
        pixelSums.fill(0L)
        pixelCounts.fill(0)
    }

    private fun frequencyCorrectionHz(): Int =
        (((blackFrequencyHz + whiteFrequencyHz) / 2.0) - CENTER_FREQUENCY_HZ).roundToInt()

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return nominalLinePeriod
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2.0
        } else {
            sorted[middle]
        }
    }

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    private class FmSubcarrierDemodulator(sampleRateHz: Int) {
        private val sampleRate = sampleRateHz.toDouble()
        private val oscillatorStep = 2.0 * PI * CENTER_FREQUENCY_HZ / sampleRate
        private val lowPassAlpha = 1.0 - exp(-2.0 * PI * BASEBAND_LOW_PASS_HZ / sampleRate)
        private val smoothAlpha = 1.0 - exp(-1.0 / (sampleRate * FREQUENCY_SMOOTH_SECONDS))
        private val iStages = DoubleArray(LOW_PASS_STAGES)
        private val qStages = DoubleArray(LOW_PASS_STAGES)
        val warmupSamples: Long = (sampleRate * DEMODULATOR_WARMUP_SECONDS).roundToInt().toLong()

        private var oscillatorPhase = 0.0
        private var previousBasebandPhase = 0.0
        private var havePhase = false
        private var smoothedFrequency = CENTER_FREQUENCY_HZ

        fun process(sample: Short): Double {
            val normalized = sample.toDouble() / 32768.0
            var iValue = normalized * cos(oscillatorPhase)
            var qValue = -normalized * sin(oscillatorPhase)
            oscillatorPhase += oscillatorStep
            if (oscillatorPhase >= 2.0 * PI) oscillatorPhase -= 2.0 * PI

            for (stage in 0 until LOW_PASS_STAGES) {
                iStages[stage] += lowPassAlpha * (iValue - iStages[stage])
                qStages[stage] += lowPassAlpha * (qValue - qStages[stage])
                iValue = iStages[stage]
                qValue = qStages[stage]
            }

            val phase = atan2(qValue, iValue)
            if (!havePhase) {
                previousBasebandPhase = phase
                havePhase = true
                return smoothedFrequency
            }
            var delta = phase - previousBasebandPhase
            previousBasebandPhase = phase
            while (delta > PI) delta -= 2.0 * PI
            while (delta < -PI) delta += 2.0 * PI
            val frequency = CENTER_FREQUENCY_HZ + delta * sampleRate / (2.0 * PI)
            if (frequency in MIN_VALID_FREQUENCY_HZ..MAX_VALID_FREQUENCY_HZ) {
                smoothedFrequency += smoothAlpha * (frequency - smoothedFrequency)
            }
            return smoothedFrequency
        }
    }

    companion object {
        const val IMAGE_WIDTH = 1809
        const val MODE_NAME = "WEFAX 120/576"

        private const val LINES_PER_SECOND = 2.0
        private const val IOC_SELECTION_HZ = 300.0
        private const val IOC_REQUIRED_HALF_CYCLES = 240
        private const val PHASING_REQUIRED_INTERVALS = 6
        private const val PHASING_INTERVAL_WINDOW = 8
        private const val CENTER_FREQUENCY_HZ = 1900.0
        private const val BLACK_FREQUENCY_HZ = 1500.0
        private const val WHITE_FREQUENCY_HZ = 2300.0
        private const val ACQUISITION_HYSTERESIS_HZ = 75.0
        private const val MIN_VALID_FREQUENCY_HZ = 900.0
        private const val MAX_VALID_FREQUENCY_HZ = 2900.0
        private const val MIN_TONE_SEPARATION_HZ = 420.0
        private const val MIN_TONE_SAMPLES = 200L
        private const val BASEBAND_LOW_PASS_HZ = 720.0
        private const val LOW_PASS_STAGES = 4
        private const val FREQUENCY_SMOOTH_SECONDS = 0.0014
        private const val DEMODULATOR_WARMUP_SECONDS = 0.10
        private const val INITIAL_CAPACITY_LINES = 128
        private const val CAPACITY_GROWTH_LINES = 128
        private const val MAX_IMAGE_LINES = 4096
        private const val FRAME_INTERVAL_LINES = 4
        private val ASYMMETRIC_WHITE_FRACTION = 0.015..0.13
        private val SYMMETRIC_WHITE_FRACTION = 0.34..0.66
    }
}
