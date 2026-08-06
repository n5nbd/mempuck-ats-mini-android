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
    private var lineFrequencyHz = DoubleArray(lineLuma.size)
    private var lineSignalLevel = DoubleArray(lineLuma.size)
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
    private var lockedFrequencyOffsetHz = 0.0
    private var candidateBlackSum = 0.0
    private var candidateBlackCount = 0L
    private var candidateWhiteSum = 0.0
    private var candidateWhiteCount = 0L
    private var acquisitionCenterHz = CENTER_FREQUENCY_HZ
    private val afcHistogram = IntArray(AFC_HISTOGRAM_BIN_COUNT)
    private var afcWindowStartSample = Long.MIN_VALUE
    private var afcWindowObservations = 0
    private var afcDecimationCounter = 0
    private var afcEstimateCount = 0
    private var lastAfcEstimateSample = Long.MIN_VALUE
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
    private val trackingPhaseRows =
        Array(TRACKING_WINDOW_LINES) { DoubleArray(TRACKING_PHASE_BINS) { Double.NaN } }
    private var trackingRowCount = 0
    private var trackingNextRow = 0
    private var trackingAcceptedRows = 0
    private var trackingStableCandidateHz = Double.NaN
    private var trackingStableCandidateCount = 0
    private var trackingLastReportedCorrection = Int.MIN_VALUE

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
                    processAcquisitionSample(sampleIndex, demodulated)
                    if (
                        stage != Stage.PHASING &&
                        observeLateJoinCandidate(sampleIndex, demodulated)
                    ) {
                        processRasterSample(sampleIndex, demodulated)
                    }
                }

                Stage.PHASING,
                Stage.RECEIVING,
                -> processRasterSample(sampleIndex, demodulated)

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

    private fun processAcquisitionSample(
        sampleIndex: Long,
        demodulated: DemodulatedSample,
    ) {
        observeCoarseAfc(sampleIndex, demodulated)
        val frequencyHz = demodulated.frequencyHz
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
        val measuredTonesValid =
            candidateBlackCount >= MIN_TONE_SAMPLES &&
                candidateWhiteCount >= MIN_TONE_SAMPLES &&
                measuredWhite - measuredBlack >= MIN_TONE_SEPARATION_HZ
        val coarseAfcFresh = lastAfcEstimateSample != Long.MIN_VALUE &&
            risingSample - lastAfcEstimateSample <=
            (sampleRateHz * AFC_ESTIMATE_MAX_AGE_SECONDS).toLong()
        val lockCenterHz = when {
            coarseAfcFresh -> acquisitionCenterHz
            measuredTonesValid -> (measuredBlack + measuredWhite) / 2.0
            else -> acquisitionCenterHz
        }
        lockAfcCenter(lockCenterHz)

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
                "measured_black_hz=${format(measuredBlack)} " +
                "measured_white_hz=${format(measuredWhite)} " +
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
        val lateJoinCenter = lateJoinCenterEstimate(sampleIndex, spanHz)
        lockAfcCenter(lateJoinCenter)
        lineStartSample = sampleIndex + sampleRateHz * AFC_RETUNE_SETTLE_SECONDS
        nextLineBoundary = lineStartSample + nominalLinePeriod
        clearLineAccumulator()
        listener.onStage(
            Stage.RECEIVING,
            LATE_JOIN_MODE_NAME,
            frequencyCorrectionHz(),
            LATE_JOIN_CONFIDENCE,
        )
        listener.onDiagnostic(
            "WEFAX late_join_start sample=$sampleIndex " +
                "valid=${format(validFraction * 100.0)}% span_hz=${format(spanHz)} " +
                "deviation_hz=${format(deviationHz)} " +
                "afc_center_hz=${format(lateJoinCenter)} " +
                "correction_hz=${frequencyCorrectionHz()} lpm=120 " +
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

    private fun processRasterSample(sampleIndex: Long, demodulated: DemodulatedSample) {
        if (sampleIndex.toDouble() < lineStartSample) return
        while (sampleIndex.toDouble() >= nextLineBoundary) {
            finalizeRasterLine()
            lineStartSample = nextLineBoundary
            nextLineBoundary += nominalLinePeriod
            clearLineAccumulator()
        }

        appendLineSample(demodulated)
    }

    private fun appendLineSample(demodulated: DemodulatedSample) {
        if (lineLumaCount == lineLuma.size) {
            lineLuma = lineLuma.copyOf(lineLuma.size * 2)
            lineFrequencyHz = lineFrequencyHz.copyOf(lineFrequencyHz.size * 2)
            lineSignalLevel = lineSignalLevel.copyOf(lineSignalLevel.size * 2)
        }
        lineLuma[lineLumaCount] = frequencyToLuma(demodulated.frequencyHz)
        lineFrequencyHz[lineLumaCount] = demodulated.frequencyHz
        lineSignalLevel[lineLumaCount] = demodulated.signalLevel
        lineLumaCount += 1
    }

    private fun finalizeRasterLine() {
        resampleCurrentSpanToRaster()

        if (stage == Stage.RECEIVING) {
            updateTrackingAfcFromCompletedLine()
        }

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


    /**
     * WEFAX-only coarse AFC. The audio receiver may shift both fax tones together, so acquisition
     * cannot assume that the black/white midpoint is always 1900 Hz. A short decimated histogram
     * finds the two persistent tone clusters whose separation still matches the 800 Hz WEFAX
     * deviation. The selected midpoint is bounded to a ±1000 Hz correction and is used only by
     * this decoder; raw PCM and every SSTV decoder remain untouched.
     */
    private fun observeCoarseAfc(
        sampleIndex: Long,
        demodulated: DemodulatedSample,
    ) {
        if (afcWindowStartSample == Long.MIN_VALUE) {
            afcWindowStartSample = sampleIndex
        }

        afcDecimationCounter += 1
        if (afcDecimationCounter >= AFC_DECIMATION) {
            afcDecimationCounter = 0
            if (
                demodulated.signalLevel >= AFC_MIN_SIGNAL_LEVEL &&
                demodulated.frequencyHz in AFC_HISTOGRAM_MIN_HZ..AFC_HISTOGRAM_MAX_HZ
            ) {
                val bin = ((demodulated.frequencyHz - AFC_HISTOGRAM_MIN_HZ) /
                    AFC_HISTOGRAM_BIN_HZ).toInt()
                    .coerceIn(0, afcHistogram.lastIndex)
                afcHistogram[bin] += 1
                afcWindowObservations += 1
            }
        }

        val elapsed = sampleIndex - afcWindowStartSample
        if (elapsed < (sampleRateHz * AFC_WINDOW_SECONDS).toLong()) return

        estimateAfcCenter()?.let { estimate ->
            val previousCenter = acquisitionCenterHz
            acquisitionCenterHz = estimate.centerHz
            afcEstimateCount += 1
            lastAfcEstimateSample = sampleIndex
            if (kotlin.math.abs(previousCenter - acquisitionCenterHz) >=
                AFC_RESTART_THRESHOLD_HZ
            ) {
                resetAcquisitionForAfcChange()
            }
            listener.onDiagnostic(
                "WEFAX afc_estimate center_hz=${format(acquisitionCenterHz)} " +
                    "correction_hz=${frequencyCorrectionFromCenter(acquisitionCenterHz)} " +
                    "separation_hz=${format(estimate.separationHz)} " +
                    "weak_peak=${estimate.weakPeakCount} observations=$afcWindowObservations " +
                    "estimate=$afcEstimateCount",
            )
        }

        afcHistogram.fill(0)
        afcWindowObservations = 0
        afcWindowStartSample = sampleIndex
    }

    private fun estimateAfcCenter(): AfcEstimate? {
        if (afcWindowObservations < AFC_MIN_OBSERVATIONS) return null

        val smoothed = IntArray(afcHistogram.size)
        for (index in afcHistogram.indices) {
            val left = afcHistogram.getOrElse(index - 1) { 0 }
            val center = afcHistogram[index]
            val right = afcHistogram.getOrElse(index + 1) { 0 }
            smoothed[index] = left + center * 2 + right
        }

        var bestLow = -1
        var bestHigh = -1
        var bestWeak = 0
        var bestStrong = 0
        var bestScore = Long.MIN_VALUE
        for (low in smoothed.indices) {
            val lowHz = histogramBinCenter(low)
            val firstHigh = histogramIndex(lowHz + AFC_MIN_TONE_SEPARATION_HZ)
            val lastHigh = histogramIndex(lowHz + AFC_MAX_TONE_SEPARATION_HZ)
            for (high in firstHigh.coerceAtLeast(low + 1)..lastHigh.coerceAtMost(smoothed.lastIndex)) {
                val lowCount = smoothed[low]
                val highCount = smoothed[high]
                val weak = minOf(lowCount, highCount)
                val strong = maxOf(lowCount, highCount)
                val score = weak.toLong() * AFC_WEAK_PEAK_WEIGHT + strong
                if (score > bestScore) {
                    bestScore = score
                    bestLow = low
                    bestHigh = high
                    bestWeak = weak
                    bestStrong = strong
                }
            }
        }

        val minimumWeakPeak = maxOf(
            AFC_MIN_WEAK_PEAK_COUNT,
            (afcWindowObservations * AFC_MIN_WEAK_PEAK_FRACTION).roundToInt(),
        )
        val minimumStrongPeak = maxOf(
            AFC_MIN_STRONG_PEAK_COUNT,
            (afcWindowObservations * AFC_MIN_STRONG_PEAK_FRACTION).roundToInt(),
        )
        if (
            bestLow < 0 ||
            bestHigh < 0 ||
            bestWeak < minimumWeakPeak ||
            bestStrong < minimumStrongPeak
        ) {
            return null
        }

        val lowHz = histogramPeakCentroid(bestLow)
        val highHz = histogramPeakCentroid(bestHigh)
        val separation = highHz - lowHz
        if (separation !in AFC_MIN_TONE_SEPARATION_HZ..AFC_MAX_TONE_SEPARATION_HZ) {
            return null
        }

        val center = ((lowHz + highHz) / 2.0).coerceIn(
            CENTER_FREQUENCY_HZ - AFC_MAX_CORRECTION_HZ,
            CENTER_FREQUENCY_HZ + AFC_MAX_CORRECTION_HZ,
        )
        return AfcEstimate(center, separation, bestWeak)
    }

    private fun histogramPeakCentroid(index: Int): Double {
        var weighted = 0.0
        var count = 0
        for (candidate in (index - 1).coerceAtLeast(0)..(index + 1).coerceAtMost(afcHistogram.lastIndex)) {
            val binCount = afcHistogram[candidate]
            weighted += histogramBinCenter(candidate) * binCount
            count += binCount
        }
        return if (count > 0) weighted / count else histogramBinCenter(index)
    }

    private fun histogramBinCenter(index: Int): Double =
        AFC_HISTOGRAM_MIN_HZ + (index + 0.5) * AFC_HISTOGRAM_BIN_HZ

    private fun histogramIndex(frequencyHz: Double): Int =
        ((frequencyHz - AFC_HISTOGRAM_MIN_HZ) / AFC_HISTOGRAM_BIN_HZ).toInt()

    private fun resetAcquisitionForAfcChange() {
        binaryWhite = false
        lastRisingSample = Long.MIN_VALUE
        lastFallingSample = Long.MIN_VALUE
        transitionsSinceRising = 0
        goodPhasingIntervals = 0
        iocHalfCycleStart = Long.MIN_VALUE
        iocHalfCycles = 0
        candidateBlackSum = 0.0
        candidateBlackCount = 0L
        candidateWhiteSum = 0.0
        candidateWhiteCount = 0L
        if (stage != Stage.RECEIVING && stage != Stage.COMPLETE) {
            stage = Stage.SEARCHING
            listener.onStage(
                Stage.SEARCHING,
                MODE_NAME,
                frequencyCorrectionFromCenter(acquisitionCenterHz),
                20,
            )
        }
    }

    private fun lateJoinCenterEstimate(sampleIndex: Long, spanHz: Double): Double {
        val afcEstimateFresh = lastAfcEstimateSample != Long.MIN_VALUE &&
            sampleIndex - lastAfcEstimateSample <=
            (sampleRateHz * AFC_ESTIMATE_MAX_AGE_SECONDS).toLong()
        val observedCenter = when {
            lateJoinStrongValidObservations <= 0 -> acquisitionCenterHz
            spanHz >= LATE_JOIN_AFC_MIN_SPAN_HZ ->
                (lateJoinFrequencyMin + lateJoinFrequencyMax) / 2.0
            afcEstimateFresh -> acquisitionCenterHz
            else -> lateJoinFrequencyMean
        }
        return observedCenter.coerceIn(
            CENTER_FREQUENCY_HZ - AFC_MAX_CORRECTION_HZ,
            CENTER_FREQUENCY_HZ + AFC_MAX_CORRECTION_HZ,
        )
    }

    /**
     * Freeze one WEFAX correction for the page and retune the FM detector around the shifted
     * 1900 Hz subcarrier. Raster conversion still uses the fixed 1500 Hz black / 2300 Hz white
     * contract after subtracting this correction. This is intentionally page-stable: fades and
     * changing picture content cannot drag the grayscale map after acquisition.
     */
    private fun lockAfcCenter(centerHz: Double) {
        val boundedCenter = centerHz.coerceIn(
            CENTER_FREQUENCY_HZ - AFC_MAX_CORRECTION_HZ,
            CENTER_FREQUENCY_HZ + AFC_MAX_CORRECTION_HZ,
        )
        acquisitionCenterHz = boundedCenter
        lockedFrequencyOffsetHz = boundedCenter - CENTER_FREQUENCY_HZ
        blackFrequencyHz = BLACK_FREQUENCY_HZ + lockedFrequencyOffsetHz
        whiteFrequencyHz = WHITE_FREQUENCY_HZ + lockedFrequencyOffsetHz
        demodulator.tuneCenter(boundedCenter)
        resetTrackingAfc()
    }

    private fun frequencyCorrectionFromCenter(centerHz: Double): Int =
        (CENTER_FREQUENCY_HZ - centerHz).roundToInt()

    private fun classifyWhite(frequencyHz: Double): Boolean {
        val center = acquisitionCenterHz
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
        val correctedFrequencyHz = frequencyHz - lockedFrequencyOffsetHz
        return (((correctedFrequencyHz - BLACK_FREQUENCY_HZ) * 255.0) /
            (WHITE_FREQUENCY_HZ - BLACK_FREQUENCY_HZ))
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

    /**
     * Late join remains completely independent from AFC refinement: it starts on the existing
     * signal-presence detector and fixed 120-LPM clock. After reception is already running, each
     * completed half-second line is reduced to a small phase profile of uncorrected frequencies.
     *
     * Standard 120/576 picture lines contain a 25 ms white reference pulse once per line. Because
     * late join may begin at any horizontal phase, the pulse can appear anywhere inside our local
     * line span, but it repeats at the same phase every 0.5 seconds. Across the rolling window, the
     * phase whose lower-percentile frequency remains highest is therefore an invariant white-tone
     * anchor. Ordinary picture content may be bright on one line, but it cannot bias the estimate
     * unless it remains white at the same phase across nearly every line.
     *
     * The measured reference is absolute raw frequency. It never includes the currently applied
     * correction, so there is no feedback loop. Correction changes happen only after a complete
     * line and only as a scalar subtraction in frequencyToLuma(); the FM detector is not retuned or
     * reset while the page is being received.
     */
    private fun updateTrackingAfcFromCompletedLine() {
        val phaseProfile = buildTrackingPhaseProfile() ?: return
        trackingPhaseRows[trackingNextRow] = phaseProfile
        trackingNextRow = (trackingNextRow + 1) % TRACKING_WINDOW_LINES
        if (trackingRowCount < TRACKING_WINDOW_LINES) trackingRowCount += 1
        trackingAcceptedRows += 1

        if (
            trackingRowCount < TRACKING_MIN_WINDOW_LINES ||
            trackingAcceptedRows % TRACKING_EVALUATION_INTERVAL_LINES != 0
        ) {
            return
        }

        val estimate = estimateTrackingOffsetFromWhiteReference()
        if (estimate == null) {
            trackingStableCandidateHz = Double.NaN
            trackingStableCandidateCount = 0
            return
        }

        if (
            trackingStableCandidateHz.isNaN() ||
            kotlin.math.abs(estimate.offsetHz - trackingStableCandidateHz) >
            TRACKING_STABLE_AGREEMENT_HZ
        ) {
            trackingStableCandidateHz = estimate.offsetHz
            trackingStableCandidateCount = 1
            return
        }

        trackingStableCandidateHz += TRACKING_CANDIDATE_ALPHA *
            (estimate.offsetHz - trackingStableCandidateHz)
        trackingStableCandidateCount += 1
        if (trackingStableCandidateCount < TRACKING_REQUIRED_STABLE_WINDOWS) return

        val error = trackingStableCandidateHz - lockedFrequencyOffsetHz
        if (kotlin.math.abs(error) < TRACKING_DEADBAND_HZ) return
        val step = (error * TRACKING_CORRECTION_ALPHA).coerceIn(
            -TRACKING_MAX_STEP_HZ,
            TRACKING_MAX_STEP_HZ,
        )
        lockedFrequencyOffsetHz = (lockedFrequencyOffsetHz + step).coerceIn(
            -AFC_MAX_CORRECTION_HZ,
            AFC_MAX_CORRECTION_HZ,
        )
        blackFrequencyHz = BLACK_FREQUENCY_HZ + lockedFrequencyOffsetHz
        whiteFrequencyHz = WHITE_FREQUENCY_HZ + lockedFrequencyOffsetHz

        val correction = frequencyCorrectionHz()
        if (
            trackingLastReportedCorrection == Int.MIN_VALUE ||
            kotlin.math.abs(correction - trackingLastReportedCorrection) >=
            TRACKING_REPORT_DELTA_HZ
        ) {
            trackingLastReportedCorrection = correction
            listener.onStage(
                Stage.RECEIVING,
                activeModeName(),
                correction,
                if (lateJoin) LATE_JOIN_CONFIDENCE else 100,
            )
            listener.onDiagnostic(
                "WEFAX afc_white_reference phase_bin=${estimate.phaseBin} " +
                    "white_hz=${format(estimate.whiteFrequencyHz)} " +
                    "phase_support=${estimate.supportingRows}/${trackingRowCount} " +
                    "phase_spread_hz=${format(estimate.spreadHz)} " +
                    "measured_offset_hz=${format(trackingStableCandidateHz)} " +
                    "applied_correction_hz=$correction",
            )
        }
    }

    private fun buildTrackingPhaseProfile(): DoubleArray? {
        if (lineLumaCount < TRACKING_MIN_LINE_SAMPLES) return null
        val sums = DoubleArray(TRACKING_PHASE_BINS)
        val counts = IntArray(TRACKING_PHASE_BINS)
        for (sample in 0 until lineLumaCount) {
            val frequencyHz = lineFrequencyHz[sample]
            if (
                lineSignalLevel[sample] < TRACKING_MIN_SIGNAL_LEVEL ||
                frequencyHz !in TRACKING_FREQUENCY_RANGE
            ) {
                continue
            }
            val bin = ((sample.toLong() * TRACKING_PHASE_BINS) / lineLumaCount)
                .toInt()
                .coerceIn(0, TRACKING_PHASE_BINS - 1)
            sums[bin] += frequencyHz
            counts[bin] += 1
        }
        val minimumPerBin = (lineLumaCount / TRACKING_PHASE_BINS / 3).coerceAtLeast(4)
        val profile = DoubleArray(TRACKING_PHASE_BINS) { Double.NaN }
        var validBins = 0
        for (bin in profile.indices) {
            if (counts[bin] >= minimumPerBin) {
                profile[bin] = sums[bin] / counts[bin]
                validBins += 1
            }
        }
        return if (validBins >= TRACKING_MIN_VALID_PHASE_BINS) profile else null
    }

    private fun estimateTrackingOffsetFromWhiteReference(): TrackingEstimate? {
        val robustPhase = DoubleArray(TRACKING_PHASE_BINS) { Double.NaN }
        val phaseSupport = IntArray(TRACKING_PHASE_BINS)
        val phaseSpread = DoubleArray(TRACKING_PHASE_BINS) { Double.POSITIVE_INFINITY }
        val values = DoubleArray(trackingRowCount)

        for (phase in 0 until TRACKING_PHASE_BINS) {
            var count = 0
            for (rowOffset in 0 until trackingRowCount) {
                val row = trackingPhaseRows[rowOffset]
                val value = row[phase]
                if (!value.isNaN()) values[count++] = value
            }
            if (count < TRACKING_MIN_PHASE_SUPPORT_ROWS) continue
            java.util.Arrays.sort(values, 0, count)
            val quantileIndex = ((count - 1) * TRACKING_PHASE_LOWER_QUANTILE)
                .roundToInt()
                .coerceIn(0, count - 1)
            robustPhase[phase] = values[quantileIndex]
            phaseSupport[phase] = count
            phaseSpread[phase] = values[count - 1] - values[0]
        }

        var bestStart = -1
        var bestScore = Double.NEGATIVE_INFINITY
        var bestSpread = Double.POSITIVE_INFINITY
        var bestSupport = 0
        for (start in 0 until TRACKING_PHASE_BINS) {
            var sum = 0.0
            var count = 0
            var spreadSum = 0.0
            var minimumSupport = Int.MAX_VALUE
            for (offset in 0 until TRACKING_REFERENCE_WINDOW_BINS) {
                val phase = (start + offset) % TRACKING_PHASE_BINS
                val value = robustPhase[phase]
                if (value.isNaN()) continue
                sum += value
                spreadSum += phaseSpread[phase]
                minimumSupport = minOf(minimumSupport, phaseSupport[phase])
                count += 1
            }
            if (count < TRACKING_MIN_REFERENCE_WINDOW_BINS) continue
            val mean = sum / count
            val spread = spreadSum / count
            // Prefer the highest phase-stable plateau. A small spread penalty prevents a single
            // noisy high-frequency pixel from outranking the repeated 25 ms reference pulse.
            val score = mean - TRACKING_SPREAD_SCORE_WEIGHT * spread
            if (score > bestScore) {
                bestScore = score
                bestStart = start
                bestSpread = spread
                bestSupport = minimumSupport
            }
        }
        if (bestStart < 0 || bestSpread > TRACKING_MAX_PHASE_SPREAD_HZ) return null

        var whiteSum = 0.0
        var whiteCount = 0
        val whiteValues = DoubleArray(TRACKING_REFERENCE_WINDOW_BINS)
        for (offset in 0 until TRACKING_REFERENCE_WINDOW_BINS) {
            val phase = (bestStart + offset) % TRACKING_PHASE_BINS
            val value = robustPhase[phase]
            if (!value.isNaN()) whiteValues[whiteCount++] = value
        }
        if (whiteCount < TRACKING_MIN_REFERENCE_WINDOW_BINS) return null
        java.util.Arrays.sort(whiteValues, 0, whiteCount)
        val trim = (whiteCount * TRACKING_REFERENCE_TRIM_FRACTION).toInt()
            .coerceAtMost((whiteCount - 1) / 2)
        for (index in trim until whiteCount - trim) whiteSum += whiteValues[index]
        val used = whiteCount - 2 * trim
        if (used <= 0) return null
        val measuredWhiteHz = whiteSum / used
        if (kotlin.math.abs(measuredWhiteHz - whiteFrequencyHz) >
            TRACKING_MAX_REFERENCE_JUMP_HZ
        ) {
            return null
        }
        val offsetHz = (measuredWhiteHz - WHITE_FREQUENCY_HZ).coerceIn(
            -AFC_MAX_CORRECTION_HZ,
            AFC_MAX_CORRECTION_HZ,
        )
        return TrackingEstimate(
            offsetHz = offsetHz,
            whiteFrequencyHz = measuredWhiteHz,
            phaseBin = bestStart,
            supportingRows = bestSupport,
            spreadHz = bestSpread,
        )
    }

    private fun resetTrackingAfc() {
        for (row in trackingPhaseRows) row.fill(Double.NaN)
        trackingRowCount = 0
        trackingNextRow = 0
        trackingAcceptedRows = 0
        trackingStableCandidateHz = Double.NaN
        trackingStableCandidateCount = 0
        trackingLastReportedCorrection = Int.MIN_VALUE
    }

    private fun activeModeName(): String =
        if (lateJoin) LATE_JOIN_MODE_NAME else MODE_NAME

    /** Correction applied to raw audio frequency; opposite sign from measured receiver offset. */
    private fun frequencyCorrectionHz(): Int = (-lockedFrequencyOffsetHz).roundToInt()

    private fun format(value: Double): String = String.format(java.util.Locale.US, "%.3f", value)

    private data class AfcEstimate(
        val centerHz: Double,
        val separationHz: Double,
        val weakPeakCount: Int,
    )

    private data class TrackingEstimate(
        val offsetHz: Double,
        val whiteFrequencyHz: Double,
        val phaseBin: Int,
        val supportingRows: Int,
        val spreadHz: Double,
    )

    private data class DemodulatedSample(
        val frequencyHz: Double,
        val signalLevel: Double,
    )

    private class FmSubcarrierDemodulator(sampleRateHz: Int) {
        private val sampleRate = sampleRateHz.toDouble()
        private var tunedCenterHz = CENTER_FREQUENCY_HZ
        private var oscillatorStep = 2.0 * PI * tunedCenterHz / sampleRate
        private val lowPassAlpha = 1.0 - exp(-2.0 * PI * BASEBAND_LOW_PASS_HZ / sampleRate)
        private val smoothAlpha = 1.0 - exp(-1.0 / (sampleRate * FREQUENCY_SMOOTH_SECONDS))
        private val iStages = DoubleArray(LOW_PASS_STAGES)
        private val qStages = DoubleArray(LOW_PASS_STAGES)
        val warmupSamples: Long = (sampleRate * DEMODULATOR_WARMUP_SECONDS).roundToInt().toLong()

        private var oscillatorPhase = 0.0
        private var previousBasebandPhase = 0.0
        private var havePhase = false
        private var smoothedFrequency = CENTER_FREQUENCY_HZ


        fun tuneCenter(centerHz: Double) {
            if (kotlin.math.abs(centerHz - tunedCenterHz) < 0.5) return
            tunedCenterHz = centerHz
            oscillatorStep = 2.0 * PI * tunedCenterHz / sampleRate
            iStages.fill(0.0)
            qStages.fill(0.0)
            havePhase = false
            smoothedFrequency = tunedCenterHz
        }

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
            val frequency = tunedCenterHz + delta * sampleRate / (2.0 * PI)
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
        private const val MIN_VALID_FREQUENCY_HZ = 400.0
        private const val MAX_VALID_FREQUENCY_HZ = 3400.0
        private const val MIN_TONE_SEPARATION_HZ = 420.0
        private const val MIN_TONE_SAMPLES = 200L
        private const val BASEBAND_LOW_PASS_HZ = 1600.0
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
        private const val LATE_JOIN_AFC_MIN_SPAN_HZ = 180.0
        private const val TRACKING_WINDOW_LINES = 24
        private const val TRACKING_MIN_WINDOW_LINES = 12
        private const val TRACKING_EVALUATION_INTERVAL_LINES = 3
        private const val TRACKING_REQUIRED_STABLE_WINDOWS = 2
        private const val TRACKING_PHASE_BINS = 200
        private const val TRACKING_REFERENCE_WINDOW_BINS = 10
        private const val TRACKING_MIN_REFERENCE_WINDOW_BINS = 8
        private const val TRACKING_MIN_PHASE_SUPPORT_ROWS = 9
        private const val TRACKING_MIN_VALID_PHASE_BINS = 160
        private const val TRACKING_MIN_LINE_SAMPLES = 8_000
        private const val TRACKING_MIN_SIGNAL_LEVEL = 0.006
        private const val TRACKING_FREQUENCY_MIN_HZ = 400.0
        private const val TRACKING_FREQUENCY_MAX_HZ = 3400.0
        private val TRACKING_FREQUENCY_RANGE =
            TRACKING_FREQUENCY_MIN_HZ..TRACKING_FREQUENCY_MAX_HZ
        private const val TRACKING_PHASE_LOWER_QUANTILE = 0.20
        private const val TRACKING_MAX_PHASE_SPREAD_HZ = 220.0
        private const val TRACKING_MAX_REFERENCE_JUMP_HZ = 500.0
        private const val TRACKING_SPREAD_SCORE_WEIGHT = 0.20
        private const val TRACKING_REFERENCE_TRIM_FRACTION = 0.10
        private const val TRACKING_STABLE_AGREEMENT_HZ = 45.0
        private const val TRACKING_CANDIDATE_ALPHA = 0.40
        private const val TRACKING_CORRECTION_ALPHA = 0.45
        private const val TRACKING_MAX_STEP_HZ = 80.0
        private const val TRACKING_DEADBAND_HZ = 3.0
        private const val TRACKING_REPORT_DELTA_HZ = 5
        private const val AFC_MAX_CORRECTION_HZ = 1000.0
        private const val AFC_WINDOW_SECONDS = 0.35
        private const val AFC_DECIMATION = 16
        private const val AFC_MIN_SIGNAL_LEVEL = 0.008
        private const val AFC_HISTOGRAM_MIN_HZ = 400.0
        private const val AFC_HISTOGRAM_MAX_HZ = 3400.0
        private const val AFC_HISTOGRAM_BIN_HZ = 25.0
        private const val AFC_HISTOGRAM_BIN_COUNT =
            ((AFC_HISTOGRAM_MAX_HZ - AFC_HISTOGRAM_MIN_HZ) /
                AFC_HISTOGRAM_BIN_HZ).toInt() + 1
        private const val AFC_MIN_TONE_SEPARATION_HZ = 620.0
        private const val AFC_MAX_TONE_SEPARATION_HZ = 980.0
        private const val AFC_MIN_OBSERVATIONS = 120
        private const val AFC_MIN_WEAK_PEAK_COUNT = 8
        private const val AFC_MIN_STRONG_PEAK_COUNT = 20
        private const val AFC_MIN_WEAK_PEAK_FRACTION = 0.008
        private const val AFC_MIN_STRONG_PEAK_FRACTION = 0.025
        private const val AFC_WEAK_PEAK_WEIGHT = 8L
        private const val AFC_RESTART_THRESHOLD_HZ = 35.0
        private const val AFC_ESTIMATE_MAX_AGE_SECONDS = 1.0
        private const val AFC_RETUNE_SETTLE_SECONDS = 0.06
        private val LATE_JOIN_FREQUENCY_RANGE =
            (MIN_VALID_FREQUENCY_HZ + 80.0)..(MAX_VALID_FREQUENCY_HZ - 80.0)
        private val ASYMMETRIC_WHITE_FRACTION = 0.015..0.13
        private val SYMMETRIC_WHITE_FRACTION = 0.34..0.66
    }
}
