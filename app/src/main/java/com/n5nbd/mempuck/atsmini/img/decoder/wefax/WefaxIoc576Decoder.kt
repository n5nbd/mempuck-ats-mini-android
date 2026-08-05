package com.n5nbd.mempuck.atsmini.img.decoder.wefax

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Application-local analogue weather-fax receiver for the common 120 LPM / IOC 576 format.
 *
 * The decoder is deliberately fixed to 120 LPM / IOC 576. Acquisition may establish the start
 * phase and black/white audio tones, but it never changes raster speed or width. Every fixed
 * half-second line span is buffered first and then resampled across exactly 1809 pixels. LISTEN may
 * also begin inside an active page with an arbitrary horizontal phase. Once reception begins, weak
 * audio, silence, noise, or a returning signal cannot restart the page or change its timing.
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
    private var lineLuma = IntArray(nominalLinePeriod.roundToInt().coerceAtLeast(1) + 8)
    private var lineLumaCount = 0

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
    private var lateJoin = false
    private var iocDetectedSample = Long.MIN_VALUE
    private var lateJoinWindowStart = Long.MIN_VALUE
    private var lateJoinObservations = 0
    private var lateJoinStrongValidObservations = 0
    private var lateJoinFrequencyMin = Double.POSITIVE_INFINITY
    private var lateJoinFrequencyMax = Double.NEGATIVE_INFINITY
    private var lateJoinFrequencyMean = 0.0
    private var lateJoinFrequencyM2 = 0.0

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
            val demodulated = demodulator.process(samples[index])
            val sampleIndex = absoluteSample
            absoluteSample += 1

            if (sampleIndex < demodulator.warmupSamples) continue

            when (stage) {
                Stage.SEARCHING,
                Stage.IOC_576,
                -> {
                    processAcquisitionSample(sampleIndex, demodulated.frequencyHz)
                    if (
                        stage != Stage.PHASING &&
                        observeLateJoinCandidate(sampleIndex, demodulated)
                    ) {
                        processRasterSample(sampleIndex, demodulated.frequencyHz)
                    }
                }

                Stage.PHASING,
                Stage.RECEIVING,
                -> processRasterSample(sampleIndex, demodulated.frequencyHz)

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
                activeModeName(),
                frequencyCorrectionHz(),
                100,
            )
            listener.onDiagnostic(
                "WEFAX complete reason=$reason lines=$completedLines " +
                    "line_samples=${format(nominalLinePeriod)} raster=span_to_1809",
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
                iocDetectedSample = sampleIndex
                resetLateJoinWindow()
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
        val periodOkay = interval.toDouble() in nominalLinePeriod * 0.88..nominalLinePeriod * 1.12
        val brightFraction = if (interval > 0) highWidth.toDouble() / interval else 0.0
        val shapeOkay = brightFraction in ASYMMETRIC_WHITE_FRACTION ||
            brightFraction in SYMMETRIC_WHITE_FRACTION
        val edgeCountOkay = transitionsSinceRising in 1..3

        if (periodOkay && shapeOkay && edgeCountOkay) {
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
        lateJoin = false
        resetLateJoinWindow()
        // 120 LPM is the format contract. Measured phasing intervals validate acquisition only;
        // they never alter the raster clock or horizontal construction.

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
        nextLineBoundary = lineStartSample + nominalLinePeriod
        clearLineAccumulator()
        listener.onStage(
            Stage.PHASING,
            MODE_NAME,
            frequencyCorrectionHz(),
            100,
        )
        listener.onDiagnostic(
            "WEFAX phasing_lock fixed_lpm=120 line_samples=${format(nominalLinePeriod)} " +
                "black_hz=${format(blackFrequencyHz)} white_hz=${format(whiteFrequencyHz)} " +
                "correction_hz=${frequencyCorrectionHz()}",
        )
    }

    private fun observeLateJoinCandidate(
        sampleIndex: Long,
        demodulated: DemodulatedSample,
    ): Boolean {
        if (stage == Stage.RECEIVING || stage == Stage.PHASING || finished) return false
        if (sampleIndex % LATE_JOIN_DECIMATION != 0L) return false

        if (lateJoinWindowStart == Long.MIN_VALUE) {
            lateJoinWindowStart = sampleIndex
        }

        lateJoinObservations += 1
        val strongAndValid = demodulated.signalLevel >= LATE_JOIN_MIN_SIGNAL_LEVEL &&
            demodulated.frequencyHz in LATE_JOIN_FREQUENCY_RANGE
        if (strongAndValid) {
            lateJoinStrongValidObservations += 1
            lateJoinFrequencyMin = minOf(lateJoinFrequencyMin, demodulated.frequencyHz)
            lateJoinFrequencyMax = maxOf(lateJoinFrequencyMax, demodulated.frequencyHz)
            val delta = demodulated.frequencyHz - lateJoinFrequencyMean
            lateJoinFrequencyMean += delta / lateJoinStrongValidObservations
            lateJoinFrequencyM2 += delta * (demodulated.frequencyHz - lateJoinFrequencyMean)
        }

        val elapsedSamples = sampleIndex - lateJoinWindowStart
        if (elapsedSamples < (sampleRateHz * LATE_JOIN_FAST_SECONDS).toLong()) return false

        val validFraction = lateJoinStrongValidObservations.toDouble() /
            lateJoinObservations.coerceAtLeast(1)
        val span = if (lateJoinStrongValidObservations > 0) {
            lateJoinFrequencyMax - lateJoinFrequencyMin
        } else {
            0.0
        }
        val deviation = if (lateJoinStrongValidObservations > 1) {
            kotlin.math.sqrt(lateJoinFrequencyM2 / (lateJoinStrongValidObservations - 1))
        } else {
            0.0
        }
        val headerWindowActive = goodPhasingIntervals > 0 ||
            (iocDetectedSample != Long.MIN_VALUE &&
                sampleIndex - iocDetectedSample <
                (sampleRateHz * HEADER_ACQUISITION_GRACE_SECONDS).toLong())
        val variedFax = validFraction >= LATE_JOIN_MIN_VALID_FRACTION &&
            (span >= LATE_JOIN_FAST_SPAN_HZ || deviation >= LATE_JOIN_FAST_DEVIATION_HZ)
        val sustainedFax = elapsedSamples >= (sampleRateHz * LATE_JOIN_SLOW_SECONDS).toLong() &&
            validFraction >= LATE_JOIN_SUSTAINED_VALID_FRACTION &&
            (span >= LATE_JOIN_SLOW_SPAN_HZ || deviation >= LATE_JOIN_SLOW_DEVIATION_HZ)

        if (!headerWindowActive && (variedFax || sustainedFax)) {
            startLateJoin(sampleIndex, validFraction, span, deviation)
            return true
        }

        if (elapsedSamples >= (sampleRateHz * LATE_JOIN_WINDOW_RESET_SECONDS).toLong()) {
            listener.onDiagnostic(
                "WEFAX late_join_window rejected valid=${format(validFraction * 100.0)}% " +
                    "span_hz=${format(span)} deviation_hz=${format(deviation)} " +
                    "header_active=$headerWindowActive",
            )
            resetLateJoinWindow()
        }
        return false
    }

    private fun startLateJoin(
        sampleIndex: Long,
        validFraction: Double,
        spanHz: Double,
        deviationHz: Double,
    ) {
        lateJoin = true
        stage = Stage.RECEIVING
        blackFrequencyHz = BLACK_FREQUENCY_HZ
        whiteFrequencyHz = WHITE_FREQUENCY_HZ
        lineStartSample = sampleIndex.toDouble()
        nextLineBoundary = lineStartSample + nominalLinePeriod
        clearLineAccumulator()
        listener.onStage(
            Stage.RECEIVING,
            LATE_JOIN_MODE_NAME,
            0,
            LATE_JOIN_CONFIDENCE,
        )
        listener.onDiagnostic(
            "WEFAX late_join_start sample=$sampleIndex " +
                "valid=${format(validFraction * 100.0)}% span_hz=${format(spanHz)} " +
                "deviation_hz=${format(deviationHz)} lpm=120 " +
                "line_samples=${format(nominalLinePeriod)} raster=span_to_1809",
        )
        resetLateJoinWindow()
    }

    private fun resetLateJoinWindow() {
        lateJoinWindowStart = Long.MIN_VALUE
        lateJoinObservations = 0
        lateJoinStrongValidObservations = 0
        lateJoinFrequencyMin = Double.POSITIVE_INFINITY
        lateJoinFrequencyMax = Double.NEGATIVE_INFINITY
        lateJoinFrequencyMean = 0.0
        lateJoinFrequencyM2 = 0.0
    }

    private fun processRasterSample(sampleIndex: Long, frequencyHz: Double) {
        while (sampleIndex.toDouble() >= nextLineBoundary) {
            finalizeRasterLine()
            lineStartSample = nextLineBoundary
            nextLineBoundary += nominalLinePeriod
            clearLineAccumulator()
        }

        appendLineLuma(frequencyToLuma(frequencyHz))
    }

    private fun appendLineLuma(value: Int) {
        if (lineLumaCount == lineLuma.size) {
            lineLuma = lineLuma.copyOf(lineLuma.size * 2)
        }
        lineLuma[lineLumaCount] = value
        lineLumaCount += 1
    }

    private fun finalizeRasterLine() {
        resampleCurrentSpanToRaster()

        if (stage == Stage.PHASING && isPhasingLine(currentLine)) {
            return
        }

        if (stage == Stage.PHASING) {
            stage = Stage.RECEIVING
            listener.onStage(
                Stage.RECEIVING,
                activeModeName(),
                frequencyCorrectionHz(),
                100,
            )
            listener.onDiagnostic(
                "WEFAX image_start sample=${lineStartSample.roundToInt()} " +
                    "line_samples=${format(nominalLinePeriod)}",
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
            listener.onStage(Stage.COMPLETE, activeModeName(), frequencyCorrectionHz(), 100)
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
        lineLumaCount = 0
    }

    /**
     * Horizontal construction has one input: every demodulated luma sample collected in this
     * completed 120-LPM line span. The actual span count is divided directly into 1809 bins.
     * No detected LPM, stale period, half-width probe, or corrective scaling participates here.
     */
    private fun resampleCurrentSpanToRaster() {
        pixelSums.fill(0L)
        pixelCounts.fill(0)

        if (lineLumaCount <= 0) {
            currentLine.fill(0)
            return
        }

        for (source in 0 until lineLumaCount) {
            val pixel = ((source.toLong() * IMAGE_WIDTH) / lineLumaCount)
                .toInt()
                .coerceIn(0, IMAGE_WIDTH - 1)
            pixelSums[pixel] += lineLuma[source].toLong()
            pixelCounts[pixel] += 1
        }

        var previous = lineLuma[0]
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
    }

    private fun activeModeName(): String =
        if (lateJoin) LATE_JOIN_MODE_NAME else MODE_NAME

    private fun frequencyCorrectionHz(): Int =
        (((blackFrequencyHz + whiteFrequencyHz) / 2.0) - CENTER_FREQUENCY_HZ).roundToInt()

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    private data class DemodulatedSample(
        val frequencyHz: Double,
        val signalLevel: Double,
    )

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

        fun process(sample: Short): DemodulatedSample {
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
                return DemodulatedSample(smoothedFrequency, hypot(iValue, qValue))
            }
            var delta = phase - previousBasebandPhase
            previousBasebandPhase = phase
            while (delta > PI) delta -= 2.0 * PI
            while (delta < -PI) delta += 2.0 * PI
            val frequency = CENTER_FREQUENCY_HZ + delta * sampleRate / (2.0 * PI)
            if (frequency in MIN_VALID_FREQUENCY_HZ..MAX_VALID_FREQUENCY_HZ) {
                smoothedFrequency += smoothAlpha * (frequency - smoothedFrequency)
            }
            return DemodulatedSample(smoothedFrequency, hypot(iValue, qValue))
        }
    }

    companion object {
        const val IMAGE_WIDTH = 1809
        const val MODE_NAME = "WEFAX 120/576"
        const val LATE_JOIN_MODE_NAME = "WEFAX 120/576 LATE JOIN"

        private const val LINES_PER_SECOND = 2.0
        private const val IOC_SELECTION_HZ = 300.0
        private const val IOC_REQUIRED_HALF_CYCLES = 240
        private const val PHASING_REQUIRED_INTERVALS = 6
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
        private const val LATE_JOIN_DECIMATION = 32L
        private const val LATE_JOIN_MIN_SIGNAL_LEVEL = 0.012
        private const val LATE_JOIN_FAST_SECONDS = 1.5
        private const val LATE_JOIN_SLOW_SECONDS = 3.5
        private const val LATE_JOIN_WINDOW_RESET_SECONDS = 4.5
        private const val HEADER_ACQUISITION_GRACE_SECONDS = 10.0
        private const val LATE_JOIN_MIN_VALID_FRACTION = 0.78
        private const val LATE_JOIN_SUSTAINED_VALID_FRACTION = 0.88
        private const val LATE_JOIN_FAST_SPAN_HZ = 140.0
        private const val LATE_JOIN_SLOW_SPAN_HZ = 45.0
        private const val LATE_JOIN_FAST_DEVIATION_HZ = 38.0
        private const val LATE_JOIN_SLOW_DEVIATION_HZ = 14.0
        private const val LATE_JOIN_CONFIDENCE = 70
        private val LATE_JOIN_FREQUENCY_RANGE = 1200.0..2600.0
        private val ASYMMETRIC_WHITE_FRACTION = 0.015..0.13
        private val SYMMETRIC_WHITE_FRACTION = 0.34..0.66
    }
}
