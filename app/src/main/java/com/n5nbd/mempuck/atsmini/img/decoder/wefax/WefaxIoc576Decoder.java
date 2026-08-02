/*
MemPuck WEFAX decoder

Implements the WMO analogue radiofacsimile parameters used by the first
MemPuck WEFAX slice: IOC 576, 120 lines per minute, 1500 Hz black,
2300 Hz white, and a 1900 Hz centre frequency.
*/

package com.n5nbd.mempuck.atsmini.img.decoder.wefax;

import java.util.Arrays;
import java.util.Locale;

public final class WefaxIoc576Decoder {
    public interface Listener {
        void onModeDetected(String modeName);

        void onFrame(
            int width,
            int height,
            int[] argbPixels,
            int completedLines,
            boolean complete
        );

        void onDiagnostic(String message);
    }

    public static final int IMAGE_WIDTH = 1809;
    public static final int INITIAL_CAPACITY_LINES = 256;
    public static final int MAX_LINES = 4096;
    public static final int LINES_PER_MINUTE = 120;
    public static final double BLACK_FREQUENCY_HZ = 1500.0;
    public static final double WHITE_FREQUENCY_HZ = 2300.0;
    public static final double CENTER_FREQUENCY_HZ = 1900.0;

    private static final String MODE_NAME = "WEFAX IOC 576 / 120 LPM";
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double BASEBAND_CUTOFF_HZ = 700.0;
    private static final double FREQUENCY_SMOOTHING_SECONDS = 0.0015;
    private static final double MIN_BASEBAND_AMPLITUDE = 0.00005;

    // Acquisition intentionally ignores silence and the out-of-band APT start tone.
    // The timeout therefore starts only after sustained fax-band audio is present.
    private static final double ACQUIRE_SIGNAL_AMPLITUDE = 0.008;
    private static final double ACQUIRE_FREQUENCY_MIN_HZ = 1250.0;
    private static final double ACQUIRE_FREQUENCY_MAX_HZ = 2550.0;
    private static final double ACQUIRE_MIN_ACTIVE_RUN_SECONDS = 0.20;
    private static final double ACQUIRE_DROPOUT_RESET_SECONDS = 0.35;
    private static final double PHASE_ACQUIRE_TIMEOUT_ACTIVE_SECONDS = 4.0;

    // Standard 120-LPM phasing is predominantly black with a short white sector.
    // Several consecutive intervals are required so one picture edge cannot set
    // the line origin or clock by itself. A robust line fit across the accepted
    // edge train rejects endpoint jitter that would otherwise leave residual skew.
    private static final double PHASE_INTERVAL_MIN_SECONDS = 0.45;
    private static final double PHASE_INTERVAL_MAX_SECONDS = 0.55;
    private static final double PHASE_MIN_BLACK_RUN_SECONDS = 0.18;
    private static final double PHASE_MIN_WHITE_RUN_SECONDS = 0.008;
    private static final int PHASE_REQUIRED_INTERVALS = 5;
    private static final int PHASE_EDGE_CAPACITY = 96;
    private static final double PHASE_CALIBRATION_LOSS_SECONDS = 1.25;
    private static final int PHASE_BLACK_LEVEL = 72;
    private static final int PHASE_WHITE_LEVEL = 184;

    // Late-entry reception has no phasing train. The decoder conservatively looks
    // for a repeated horizontal displacement in vertical edge structure. Three
    // agreeing estimates are required before one clock correction is applied.
    private static final int MID_CLOCK_PROFILE_LINES = 64;
    private static final int MID_CLOCK_LINE_GAP = 96;
    private static final int MID_CLOCK_EVALUATE_EVERY_LINES = 32;
    private static final int MID_CLOCK_MAX_SHIFT_PIXELS = 40;
    private static final int MID_CLOCK_PROFILE_MARGIN_PIXELS = 32;
    private static final double MID_CLOCK_MIN_PROFILE_STDDEV = 0.75;
    private static final int MID_CLOCK_MIN_DISTINCT_PEAKS = 3;
    private static final double MID_CLOCK_MIN_CORRELATION = 0.50;
    private static final double MID_CLOCK_MIN_PEAK_MARGIN = 0.035;
    private static final double MID_CLOCK_MIN_ABS_PPM = 8.0;
    private static final double MID_CLOCK_MAX_ABS_PPM = 180.0;
    private static final int MID_CLOCK_REQUIRED_ESTIMATES = 3;
    private static final double MID_CLOCK_MAX_ESTIMATE_SPREAD_PPM = 28.0;

    private static final int PUBLISH_EVERY_LINES = 4;

    private final int sampleRateHz;
    private final Listener listener;
    private final boolean phaseAcquisitionEnabled;
    private final double oscillatorStep;
    private final double basebandAlpha;
    private final double frequencyAlpha;
    private final int phaseIntervalMinSamples;
    private final int phaseIntervalMaxSamples;
    private final int acquireMinActiveRunSamples;
    private final int acquireDropoutResetSamples;
    private final int phaseAcquireTimeoutActiveSamples;
    private final int phaseMinBlackRunSamples;
    private final int phaseMinWhiteRunSamples;
    private final int phaseCalibrationLossSamples;
    private final double nominalSamplesPerLine;
    private final int[] lineLevelSums = new int[IMAGE_WIDTH];
    private final int[] lineLevelCounts = new int[IMAGE_WIDTH];
    private final long[] phaseEdgeSamples = new long[PHASE_EDGE_CAPACITY];
    private final double[] midClockPpmEstimates = new double[MID_CLOCK_REQUIRED_ESTIMATES];
    private final double[] midClockCorrelations = new double[MID_CLOCK_REQUIRED_ESTIMATES];

    private int[] argbPixels = new int[IMAGE_WIDTH * INITIAL_CAPACITY_LINES];
    private int capacityLines = INITIAL_CAPACITY_LINES;
    private int completedLines;
    private long totalSamples;
    private boolean complete;

    private double samplesPerLine;
    private double linePositionSamples;
    private String phaseSource = "UNLOCKED";

    private double oscillatorPhase;
    private double iStage1;
    private double qStage1;
    private double iStage2;
    private double qStage2;
    private double previousBasebandPhase;
    private boolean havePreviousBasebandPhase;
    private double smoothedFrequencyHz = CENTER_FREQUENCY_HZ;
    private double currentBasebandAmplitude;
    private int lastLevel = 128;

    private boolean phaseLocked;
    private boolean carrierReady;
    private int activeRunSamples;
    private int inactiveRunSamples;
    private int activeAcquisitionSamples;

    private boolean inBlackRun;
    private int blackRunSamples;
    private int whiteCandidateSamples;
    private long whiteCandidateStartSample = -1L;
    private long previousWhiteEdgeSample = -1L;
    private int phaseEdgeCount;
    private int consecutivePhaseIntervals;
    private boolean phaseCalibrationActive;
    private long lastCalibrationEdgeSample = -1L;

    private int midClockEstimateCount;
    private boolean midClockCorrectionApplied;

    public WefaxIoc576Decoder(int sampleRateHz, Listener listener) {
        this(sampleRateHz, listener, true);
    }

    public WefaxIoc576Decoder(
        int sampleRateHz,
        Listener listener,
        boolean phaseAcquisitionEnabled
    ) {
        if (sampleRateHz <= 0) {
            throw new IllegalArgumentException("sampleRateHz must be positive");
        }
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        this.sampleRateHz = sampleRateHz;
        this.listener = listener;
        this.phaseAcquisitionEnabled = phaseAcquisitionEnabled;
        this.oscillatorStep = TWO_PI * CENTER_FREQUENCY_HZ / sampleRateHz;
        this.basebandAlpha = 1.0 - Math.exp(-TWO_PI * BASEBAND_CUTOFF_HZ / sampleRateHz);
        this.frequencyAlpha = 1.0 - Math.exp(
            -1.0 / (sampleRateHz * FREQUENCY_SMOOTHING_SECONDS)
        );
        this.phaseIntervalMinSamples = (int) Math.round(
            PHASE_INTERVAL_MIN_SECONDS * sampleRateHz
        );
        this.phaseIntervalMaxSamples = (int) Math.round(
            PHASE_INTERVAL_MAX_SECONDS * sampleRateHz
        );
        this.acquireMinActiveRunSamples = (int) Math.round(
            ACQUIRE_MIN_ACTIVE_RUN_SECONDS * sampleRateHz
        );
        this.acquireDropoutResetSamples = (int) Math.round(
            ACQUIRE_DROPOUT_RESET_SECONDS * sampleRateHz
        );
        this.phaseAcquireTimeoutActiveSamples = (int) Math.round(
            PHASE_ACQUIRE_TIMEOUT_ACTIVE_SECONDS * sampleRateHz
        );
        this.phaseMinBlackRunSamples = (int) Math.round(
            PHASE_MIN_BLACK_RUN_SECONDS * sampleRateHz
        );
        this.phaseMinWhiteRunSamples = Math.max(1, (int) Math.round(
            PHASE_MIN_WHITE_RUN_SECONDS * sampleRateHz
        ));
        this.phaseCalibrationLossSamples = (int) Math.round(
            PHASE_CALIBRATION_LOSS_SECONDS * sampleRateHz
        );
        this.nominalSamplesPerLine = sampleRateHz * 60.0 / LINES_PER_MINUTE;
        this.samplesPerLine = nominalSamplesPerLine;
        this.phaseLocked = !phaseAcquisitionEnabled;
        if (phaseLocked) {
            phaseSource = "DISABLED";
        }
        this.listener.onModeDetected(MODE_NAME);
        this.listener.onDiagnostic(
            "WEFAX init sample_rate=" + sampleRateHz
                + " width=" + IMAGE_WIDTH
                + " lpm=" + LINES_PER_MINUTE
                + " nominal_samples_per_line=" + format3(nominalSamplesPerLine)
                + " phase_acquire=" + phaseAcquisitionEnabled
        );
    }

    public void process(short[] samples, int count) {
        if (complete || samples == null) {
            return;
        }
        int safeCount = Math.max(0, Math.min(count, samples.length));
        for (int index = 0; index < safeCount && !complete; index++) {
            int level = demodulateLevel(samples[index]);
            totalSamples++;

            if (!phaseLocked) {
                acquirePhase(level);
                continue;
            }

            if (phaseCalibrationActive) {
                refinePhasingClock(level);
            }
            appendLevel(level);
        }
    }

    public void finishCapture(String reason) {
        if (complete) {
            return;
        }
        complete = true;
        if (completedLines > 0) {
            publishFrame(true);
        }
        listener.onDiagnostic(
            "WEFAX finish reason=" + reason
                + " samples=" + totalSamples
                + " lines=" + completedLines
                + " phase_source=" + phaseSource
                + " samples_per_line=" + format3(samplesPerLine)
                + " clock_error_ppm=" + format1(clockErrorPpm())
                + " mid_clock_corrected=" + midClockCorrectionApplied
        );
    }

    public int getCompletedLines() {
        return completedLines;
    }

    public double getSamplesPerLine() {
        return samplesPerLine;
    }

    public String getPhaseSource() {
        return phaseSource;
    }

    public boolean isMidImageClockCorrected() {
        return midClockCorrectionApplied;
    }

    private int demodulateLevel(short pcmSample) {
        double input = pcmSample / 32768.0;
        double cosine = Math.cos(oscillatorPhase);
        double sine = Math.sin(oscillatorPhase);
        oscillatorPhase += oscillatorStep;
        if (oscillatorPhase >= TWO_PI) {
            oscillatorPhase -= TWO_PI;
        }

        double mixedI = input * cosine;
        double mixedQ = -input * sine;
        iStage1 += basebandAlpha * (mixedI - iStage1);
        qStage1 += basebandAlpha * (mixedQ - qStage1);
        iStage2 += basebandAlpha * (iStage1 - iStage2);
        qStage2 += basebandAlpha * (qStage1 - qStage2);

        currentBasebandAmplitude = Math.hypot(iStage2, qStage2);
        if (currentBasebandAmplitude < MIN_BASEBAND_AMPLITUDE) {
            return lastLevel;
        }

        double basebandPhase = Math.atan2(qStage2, iStage2);
        if (!havePreviousBasebandPhase) {
            previousBasebandPhase = basebandPhase;
            havePreviousBasebandPhase = true;
            return lastLevel;
        }

        double delta = basebandPhase - previousBasebandPhase;
        previousBasebandPhase = basebandPhase;
        if (delta > Math.PI) {
            delta -= TWO_PI;
        } else if (delta < -Math.PI) {
            delta += TWO_PI;
        }

        double frequencyHz = CENTER_FREQUENCY_HZ + delta * sampleRateHz / TWO_PI;
        smoothedFrequencyHz += frequencyAlpha * (frequencyHz - smoothedFrequencyHz);
        int level = (int) Math.round(
            (smoothedFrequencyHz - BLACK_FREQUENCY_HZ)
                * 255.0 / (WHITE_FREQUENCY_HZ - BLACK_FREQUENCY_HZ)
        );
        lastLevel = Math.max(0, Math.min(255, level));
        return lastLevel;
    }

    private void acquirePhase(int level) {
        boolean active = currentBasebandAmplitude >= ACQUIRE_SIGNAL_AMPLITUDE
            && smoothedFrequencyHz >= ACQUIRE_FREQUENCY_MIN_HZ
            && smoothedFrequencyHz <= ACQUIRE_FREQUENCY_MAX_HZ;

        if (!active) {
            activeRunSamples = 0;
            inactiveRunSamples++;
            if (inactiveRunSamples >= acquireDropoutResetSamples) {
                resetPhaseCandidate();
            }
            return;
        }

        inactiveRunSamples = 0;
        activeRunSamples++;
        if (!carrierReady) {
            if (activeRunSamples < acquireMinActiveRunSamples) {
                return;
            }
            carrierReady = true;
            listener.onDiagnostic(
                "WEFAX carrier_acquired sample=" + totalSamples
                    + " amplitude=" + format4(currentBasebandAmplitude)
                    + " frequency_hz=" + Math.round(smoothedFrequencyHz)
            );
        }

        activeAcquisitionSamples++;
        if (trackPhasingEdge(level)) {
            return;
        }

        if (activeAcquisitionSamples >= phaseAcquireTimeoutActiveSamples) {
            lockPhase("MANUAL_TIMEOUT", nominalSamplesPerLine, 0.0, 0.0);
            appendLevel(level);
        }
    }

    private long detectPhasingEdge(int level) {
        if (level <= PHASE_BLACK_LEVEL) {
            if (!inBlackRun) {
                inBlackRun = true;
                blackRunSamples = 1;
            } else {
                blackRunSamples++;
            }
            whiteCandidateSamples = 0;
            whiteCandidateStartSample = -1L;
            return -1L;
        }

        if (!inBlackRun || blackRunSamples < phaseMinBlackRunSamples) {
            return -1L;
        }

        if (level < PHASE_WHITE_LEVEL) {
            return -1L;
        }

        if (whiteCandidateSamples == 0) {
            whiteCandidateStartSample = totalSamples;
        }
        whiteCandidateSamples++;
        if (whiteCandidateSamples < phaseMinWhiteRunSamples) {
            return -1L;
        }

        long edgeSample = whiteCandidateStartSample;
        inBlackRun = false;
        blackRunSamples = 0;
        whiteCandidateSamples = 0;
        whiteCandidateStartSample = -1L;
        return edgeSample;
    }

    private boolean registerAcquisitionEdge(long edgeSample) {
        if (previousWhiteEdgeSample < 0L) {
            startPhaseEdgeSequence(edgeSample);
            return false;
        }

        long interval = edgeSample - previousWhiteEdgeSample;
        if (interval < phaseIntervalMinSamples || interval > phaseIntervalMaxSamples) {
            startPhaseEdgeSequence(edgeSample);
            return false;
        }

        appendPhaseEdge(edgeSample);
        return consecutivePhaseIntervals >= PHASE_REQUIRED_INTERVALS;
    }

    private boolean trackPhasingEdge(int level) {
        long edgeSample = detectPhasingEdge(level);
        if (edgeSample < 0L || !registerAcquisitionEdge(edgeSample)) {
            return false;
        }

        PhaseFit fit = robustPhaseFit();
        double elapsedSinceEdge = totalSamples - fit.predictedLastEdgeSample;
        lockPhase("PHASING", fit.samplesPerLine, elapsedSinceEdge, fit.medianResidualSamples);
        phaseCalibrationActive = true;
        lastCalibrationEdgeSample = edgeSample;
        appendLevel(level);
        return true;
    }

    private void refinePhasingClock(int level) {
        long edgeSample = detectPhasingEdge(level);
        if (edgeSample >= 0L) {
            long interval = edgeSample - previousWhiteEdgeSample;
            if (interval < phaseIntervalMinSamples || interval > phaseIntervalMaxSamples) {
                finishPhaseCalibration("EDGE_OUT_OF_RANGE");
                return;
            }

            appendPhaseEdge(edgeSample);
            PhaseFit fit = robustPhaseFit();
            samplesPerLine = clamp(
                fit.samplesPerLine,
                phaseIntervalMinSamples,
                phaseIntervalMaxSamples
            );
            linePositionSamples = positiveModulo(
                totalSamples - fit.predictedLastEdgeSample,
                samplesPerLine
            );
            lastCalibrationEdgeSample = edgeSample;
            if (consecutivePhaseIntervals % 8 == 0) {
                listener.onDiagnostic(
                    "WEFAX phase_refine interval_count=" + consecutivePhaseIntervals
                        + " samples_per_line=" + format3(samplesPerLine)
                        + " clock_error_ppm=" + format1(clockErrorPpm())
                        + " fit_residual_samples=" + format1(fit.medianResidualSamples)
                );
            }
            return;
        }

        if (
            lastCalibrationEdgeSample >= 0L
                && totalSamples - lastCalibrationEdgeSample >= phaseCalibrationLossSamples
        ) {
            finishPhaseCalibration("EDGE_TIMEOUT");
        }
    }

    private void finishPhaseCalibration(String reason) {
        if (!phaseCalibrationActive) {
            return;
        }
        PhaseFit fit = robustPhaseFit();
        samplesPerLine = clamp(
            fit.samplesPerLine,
            phaseIntervalMinSamples,
            phaseIntervalMaxSamples
        );
        linePositionSamples = positiveModulo(
            totalSamples - fit.predictedLastEdgeSample,
            samplesPerLine
        );
        phaseCalibrationActive = false;
        listener.onDiagnostic(
            "WEFAX phase_calibration_complete reason=" + reason
                + " interval_count=" + consecutivePhaseIntervals
                + " samples_per_line=" + format3(samplesPerLine)
                + " clock_error_ppm=" + format1(clockErrorPpm())
                + " fit_residual_samples=" + format1(fit.medianResidualSamples)
        );
    }

    private void startPhaseEdgeSequence(long edgeSample) {
        phaseEdgeCount = 1;
        phaseEdgeSamples[0] = edgeSample;
        previousWhiteEdgeSample = edgeSample;
        consecutivePhaseIntervals = 0;
    }

    private void appendPhaseEdge(long edgeSample) {
        if (phaseEdgeCount < phaseEdgeSamples.length) {
            phaseEdgeSamples[phaseEdgeCount] = edgeSample;
            phaseEdgeCount++;
        } else {
            System.arraycopy(
                phaseEdgeSamples,
                1,
                phaseEdgeSamples,
                0,
                phaseEdgeSamples.length - 1
            );
            phaseEdgeSamples[phaseEdgeSamples.length - 1] = edgeSample;
        }
        previousWhiteEdgeSample = edgeSample;
        consecutivePhaseIntervals = phaseEdgeCount - 1;
    }

    private PhaseFit robustPhaseFit() {
        if (phaseEdgeCount < 2) {
            return new PhaseFit(nominalSamplesPerLine, previousWhiteEdgeSample, 0.0);
        }

        int slopeCount = phaseEdgeCount * (phaseEdgeCount - 1) / 2;
        double[] slopes = new double[slopeCount];
        int output = 0;
        for (int first = 0; first < phaseEdgeCount - 1; first++) {
            for (int second = first + 1; second < phaseEdgeCount; second++) {
                slopes[output++] = (phaseEdgeSamples[second] - phaseEdgeSamples[first])
                    / (double) (second - first);
            }
        }
        double slope = median(slopes, output);

        double[] intercepts = new double[phaseEdgeCount];
        for (int index = 0; index < phaseEdgeCount; index++) {
            intercepts[index] = phaseEdgeSamples[index] - slope * index;
        }
        double intercept = median(intercepts, phaseEdgeCount);

        double[] residuals = new double[phaseEdgeCount];
        for (int index = 0; index < phaseEdgeCount; index++) {
            residuals[index] = Math.abs(
                phaseEdgeSamples[index] - (intercept + slope * index)
            );
        }
        double medianResidual = median(residuals, phaseEdgeCount);
        double predictedLastEdge = intercept + slope * (phaseEdgeCount - 1);
        return new PhaseFit(slope, predictedLastEdge, medianResidual);
    }

    private void resetPhaseCandidate() {
        inBlackRun = false;
        blackRunSamples = 0;
        whiteCandidateSamples = 0;
        whiteCandidateStartSample = -1L;
        previousWhiteEdgeSample = -1L;
        phaseEdgeCount = 0;
        consecutivePhaseIntervals = 0;
        if (carrierReady) {
            carrierReady = false;
            activeAcquisitionSamples = 0;
            listener.onDiagnostic("WEFAX carrier_lost_before_lock sample=" + totalSamples);
        }
    }

    private void lockPhase(
        String source,
        double selectedSamplesPerLine,
        double elapsedSinceLineStart,
        double fitResidualSamples
    ) {
        phaseLocked = true;
        phaseSource = source;
        samplesPerLine = clamp(
            selectedSamplesPerLine,
            phaseIntervalMinSamples,
            phaseIntervalMaxSamples
        );
        linePositionSamples = positiveModulo(elapsedSinceLineStart, samplesPerLine);
        Arrays.fill(lineLevelSums, 0);
        Arrays.fill(lineLevelCounts, 0);
        listener.onDiagnostic(
            "WEFAX phase_lock source=" + source
                + " active_acquisition_samples=" + activeAcquisitionSamples
                + " interval_count=" + consecutivePhaseIntervals
                + " samples_per_line=" + format3(samplesPerLine)
                + " clock_error_ppm=" + format1(clockErrorPpm())
                + " phase_offset_samples=" + format1(linePositionSamples)
                + " fit_residual_samples=" + format1(fitResidualSamples)
        );
    }

    private void appendLevel(int level) {
        int pixel = (int) Math.floor(
            linePositionSamples * IMAGE_WIDTH / samplesPerLine
        );
        if (pixel < 0) {
            pixel = 0;
        } else if (pixel >= IMAGE_WIDTH) {
            pixel = IMAGE_WIDTH - 1;
        }
        lineLevelSums[pixel] += level;
        lineLevelCounts[pixel]++;

        linePositionSamples += 1.0;
        if (linePositionSamples >= samplesPerLine) {
            linePositionSamples -= samplesPerLine;
            completeLine();
        }
    }

    private void completeLine() {
        if (completedLines >= MAX_LINES) {
            finishCapture("MAX_LINES");
            return;
        }
        ensureCapacity(completedLines + 1);
        int rowOffset = completedLines * IMAGE_WIDTH;
        int previousLevel = 255;
        for (int x = 0; x < IMAGE_WIDTH; x++) {
            int count = lineLevelCounts[x];
            int level = count > 0 ? lineLevelSums[x] / count : previousLevel;
            previousLevel = level;
            argbPixels[rowOffset + x] = 0xff000000 | level << 16 | level << 8 | level;
        }
        Arrays.fill(lineLevelSums, 0);
        Arrays.fill(lineLevelCounts, 0);
        completedLines++;
        maybeCorrectMidImageClock();
        if (completedLines == 1 || completedLines % PUBLISH_EVERY_LINES == 0) {
            publishFrame(false);
        }
    }

    private void maybeCorrectMidImageClock() {
        if (
            midClockCorrectionApplied
                || !"MANUAL_TIMEOUT".equals(phaseSource)
                || completedLines < MID_CLOCK_LINE_GAP + MID_CLOCK_PROFILE_LINES
        ) {
            return;
        }
        int firstEligibleLine = MID_CLOCK_LINE_GAP + MID_CLOCK_PROFILE_LINES;
        if (
            (completedLines - firstEligibleLine) % MID_CLOCK_EVALUATE_EVERY_LINES != 0
        ) {
            return;
        }

        int recentStart = completedLines - MID_CLOCK_PROFILE_LINES;
        int earlierStart = recentStart - MID_CLOCK_LINE_GAP;
        double[] earlierProfile = buildVerticalEdgeProfile(
            earlierStart,
            MID_CLOCK_PROFILE_LINES
        );
        double[] recentProfile = buildVerticalEdgeProfile(
            recentStart,
            MID_CLOCK_PROFILE_LINES
        );
        ClockEstimate estimate = correlateProfiles(earlierProfile, recentProfile);
        if (!estimate.accepted) {
            listener.onDiagnostic(
                "WEFAX mid_clock_rejected lines=" + completedLines
                    + " correlation=" + format3(estimate.correlation)
                    + " peak_margin=" + format3(estimate.peakMargin)
                    + " profile_stddev=" + format3(estimate.profileStdDev)
                    + " peak_count=" + estimate.distinctPeakCount
            );
            midClockEstimateCount = 0;
            return;
        }

        double correctionPpm = estimate.shiftPixels
            / MID_CLOCK_LINE_GAP
            / IMAGE_WIDTH
            * 1_000_000.0;
        if (
            Math.abs(correctionPpm) < MID_CLOCK_MIN_ABS_PPM
                || Math.abs(correctionPpm) > MID_CLOCK_MAX_ABS_PPM
        ) {
            listener.onDiagnostic(
                "WEFAX mid_clock_rejected lines=" + completedLines
                    + " correction_ppm=" + format1(correctionPpm)
                    + " reason=OUT_OF_RANGE"
            );
            midClockEstimateCount = 0;
            return;
        }

        listener.onDiagnostic(
            "WEFAX mid_clock_candidate lines=" + completedLines
                + " shift_pixels=" + format3(estimate.shiftPixels)
                + " correction_ppm=" + format1(correctionPpm)
                + " correlation=" + format3(estimate.correlation)
                + " peak_margin=" + format3(estimate.peakMargin)
                + " peak_count=" + estimate.distinctPeakCount
        );
        recordMidClockEstimate(correctionPpm, estimate.correlation);
    }

    private void recordMidClockEstimate(double correctionPpm, double correlation) {
        if (midClockEstimateCount > 0) {
            double previous = midClockPpmEstimates[midClockEstimateCount - 1];
            if (Math.signum(previous) != Math.signum(correctionPpm)) {
                midClockEstimateCount = 0;
            }
        }

        if (midClockEstimateCount < MID_CLOCK_REQUIRED_ESTIMATES) {
            midClockPpmEstimates[midClockEstimateCount] = correctionPpm;
            midClockCorrelations[midClockEstimateCount] = correlation;
            midClockEstimateCount++;
        }
        if (midClockEstimateCount < MID_CLOCK_REQUIRED_ESTIMATES) {
            return;
        }

        double minimum = midClockPpmEstimates[0];
        double maximum = midClockPpmEstimates[0];
        double correlationSum = 0.0;
        for (int index = 0; index < midClockEstimateCount; index++) {
            minimum = Math.min(minimum, midClockPpmEstimates[index]);
            maximum = Math.max(maximum, midClockPpmEstimates[index]);
            correlationSum += midClockCorrelations[index];
        }
        if (maximum - minimum > MID_CLOCK_MAX_ESTIMATE_SPREAD_PPM) {
            listener.onDiagnostic(
                "WEFAX mid_clock_rejected estimates=" + midClockEstimateCount
                    + " spread_ppm=" + format1(maximum - minimum)
                    + " reason=DISAGREE"
            );
            midClockEstimateCount = 0;
            return;
        }

        double medianCorrectionPpm = median(
            Arrays.copyOf(midClockPpmEstimates, midClockEstimateCount),
            midClockEstimateCount
        );
        double averageCorrelation = correlationSum / midClockEstimateCount;
        applyMidImageClockCorrection(medianCorrectionPpm, averageCorrelation);
    }

    private void applyMidImageClockCorrection(
        double correctionPpm,
        double averageCorrelation
    ) {
        double oldSamplesPerLine = samplesPerLine;
        double newSamplesPerLine = clamp(
            oldSamplesPerLine * (1.0 + correctionPpm / 1_000_000.0),
            phaseIntervalMinSamples,
            phaseIntervalMaxSamples
        );
        double shiftPixelsPerLine = correctionPpm * IMAGE_WIDTH / 1_000_000.0;
        shearCompletedRowsToLatest(shiftPixelsPerLine);
        linePositionSamples = linePositionSamples / oldSamplesPerLine * newSamplesPerLine;
        samplesPerLine = newSamplesPerLine;
        midClockCorrectionApplied = true;
        listener.onDiagnostic(
            "WEFAX mid_clock_corrected estimates=" + midClockEstimateCount
                + " correction_ppm=" + format1(correctionPpm)
                + " shift_pixels_per_line=" + format4(shiftPixelsPerLine)
                + " samples_per_line=" + format3(samplesPerLine)
                + " clock_error_ppm=" + format1(clockErrorPpm())
                + " correlation=" + format3(averageCorrelation)
                + " corrected_lines=" + completedLines
        );
    }

    private double[] buildVerticalEdgeProfile(int startLine, int lineCount) {
        double[] raw = new double[IMAGE_WIDTH];
        int endLine = Math.min(completedLines, startLine + lineCount);
        int actualLineCount = Math.max(1, endLine - startLine);
        for (int row = startLine; row < endLine; row++) {
            int rowOffset = row * IMAGE_WIDTH;
            for (int x = 1; x < IMAGE_WIDTH - 1; x++) {
                int left = argbPixels[rowOffset + x - 1] & 0xff;
                int right = argbPixels[rowOffset + x + 1] & 0xff;
                raw[x] += Math.abs(right - left) * 0.5;
            }
        }
        for (int x = 0; x < IMAGE_WIDTH; x++) {
            raw[x] /= actualLineCount;
        }

        double[] smoothed = new double[IMAGE_WIDTH];
        for (int x = 0; x < IMAGE_WIDTH; x++) {
            double sum = 0.0;
            int count = 0;
            for (int offset = -2; offset <= 2; offset++) {
                int source = x + offset;
                if (source >= 0 && source < IMAGE_WIDTH) {
                    sum += raw[source];
                    count++;
                }
            }
            smoothed[x] = sum / count;
        }
        return smoothed;
    }

    private ClockEstimate correlateProfiles(double[] earlier, double[] recent) {
        double earlierStdDev = profileStdDev(earlier);
        double recentStdDev = profileStdDev(recent);
        double profileStdDev = Math.min(earlierStdDev, recentStdDev);
        int distinctPeakCount = Math.min(
            countDistinctProfilePeaks(earlier, earlierStdDev),
            countDistinctProfilePeaks(recent, recentStdDev)
        );
        if (
            profileStdDev < MID_CLOCK_MIN_PROFILE_STDDEV
                || distinctPeakCount < MID_CLOCK_MIN_DISTINCT_PEAKS
        ) {
            return ClockEstimate.rejected(
                0.0,
                0.0,
                profileStdDev,
                distinctPeakCount
            );
        }

        int shiftCount = MID_CLOCK_MAX_SHIFT_PIXELS * 2 + 1;
        double[] correlations = new double[shiftCount];
        int bestIndex = 0;
        double bestCorrelation = -2.0;
        for (int shift = -MID_CLOCK_MAX_SHIFT_PIXELS;
             shift <= MID_CLOCK_MAX_SHIFT_PIXELS;
             shift++) {
            double correlation = normalizedCorrelation(earlier, recent, shift);
            int index = shift + MID_CLOCK_MAX_SHIFT_PIXELS;
            correlations[index] = correlation;
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestIndex = index;
            }
        }

        int bestShift = bestIndex - MID_CLOCK_MAX_SHIFT_PIXELS;
        double farBest = -2.0;
        for (int index = 0; index < correlations.length; index++) {
            int shift = index - MID_CLOCK_MAX_SHIFT_PIXELS;
            if (Math.abs(shift - bestShift) >= 4) {
                farBest = Math.max(farBest, correlations[index]);
            }
        }
        double peakMargin = bestCorrelation - farBest;

        double subpixelOffset = 0.0;
        if (bestIndex > 0 && bestIndex < correlations.length - 1) {
            double previous = correlations[bestIndex - 1];
            double current = correlations[bestIndex];
            double next = correlations[bestIndex + 1];
            double denominator = previous - 2.0 * current + next;
            if (Math.abs(denominator) > 1.0e-9) {
                subpixelOffset = 0.5 * (previous - next) / denominator;
                subpixelOffset = clamp(subpixelOffset, -0.5, 0.5);
            }
        }
        double shiftPixels = bestShift + subpixelOffset;
        boolean accepted = bestCorrelation >= MID_CLOCK_MIN_CORRELATION
            && peakMargin >= MID_CLOCK_MIN_PEAK_MARGIN
            && distinctPeakCount >= MID_CLOCK_MIN_DISTINCT_PEAKS;
        return new ClockEstimate(
            accepted,
            shiftPixels,
            bestCorrelation,
            peakMargin,
            profileStdDev,
            distinctPeakCount
        );
    }

    private int countDistinctProfilePeaks(double[] profile, double standardDeviation) {
        int start = MID_CLOCK_PROFILE_MARGIN_PIXELS + 2;
        int end = IMAGE_WIDTH - MID_CLOCK_PROFILE_MARGIN_PIXELS - 2;
        double mean = 0.0;
        for (int x = start; x < end; x++) {
            mean += profile[x];
        }
        mean /= end - start;
        double threshold = mean + standardDeviation;
        int count = 0;
        int lastPeak = -100;
        for (int x = start; x < end; x++) {
            if (
                profile[x] >= threshold
                    && profile[x] >= profile[x - 1]
                    && profile[x] > profile[x + 1]
                    && x - lastPeak >= 20
            ) {
                count++;
                lastPeak = x;
            }
        }
        return count;
    }

    private double normalizedCorrelation(double[] first, double[] second, int shift) {
        int firstStart = MID_CLOCK_PROFILE_MARGIN_PIXELS + Math.max(0, -shift);
        int secondStart = MID_CLOCK_PROFILE_MARGIN_PIXELS + Math.max(0, shift);
        int count = IMAGE_WIDTH
            - MID_CLOCK_PROFILE_MARGIN_PIXELS * 2
            - Math.abs(shift);
        if (count <= 8) {
            return -1.0;
        }

        double firstMean = 0.0;
        double secondMean = 0.0;
        for (int index = 0; index < count; index++) {
            firstMean += first[firstStart + index];
            secondMean += second[secondStart + index];
        }
        firstMean /= count;
        secondMean /= count;

        double numerator = 0.0;
        double firstEnergy = 0.0;
        double secondEnergy = 0.0;
        for (int index = 0; index < count; index++) {
            double firstCentered = first[firstStart + index] - firstMean;
            double secondCentered = second[secondStart + index] - secondMean;
            numerator += firstCentered * secondCentered;
            firstEnergy += firstCentered * firstCentered;
            secondEnergy += secondCentered * secondCentered;
        }
        double denominator = Math.sqrt(firstEnergy * secondEnergy);
        return denominator > 1.0e-12 ? numerator / denominator : -1.0;
    }

    private double profileStdDev(double[] profile) {
        int start = MID_CLOCK_PROFILE_MARGIN_PIXELS;
        int end = IMAGE_WIDTH - MID_CLOCK_PROFILE_MARGIN_PIXELS;
        double mean = 0.0;
        for (int x = start; x < end; x++) {
            mean += profile[x];
        }
        mean /= end - start;
        double variance = 0.0;
        for (int x = start; x < end; x++) {
            double difference = profile[x] - mean;
            variance += difference * difference;
        }
        return Math.sqrt(variance / (end - start));
    }

    private void shearCompletedRowsToLatest(double shiftPixelsPerLine) {
        if (completedLines < 2 || Math.abs(shiftPixelsPerLine) < 1.0e-9) {
            return;
        }
        int[] rowCopy = new int[IMAGE_WIDTH];
        int latestRow = completedLines - 1;
        for (int row = 0; row < completedLines; row++) {
            int shift = (int) Math.round(shiftPixelsPerLine * (latestRow - row));
            if (shift == 0) {
                continue;
            }
            int rowOffset = row * IMAGE_WIDTH;
            System.arraycopy(argbPixels, rowOffset, rowCopy, 0, IMAGE_WIDTH);
            for (int x = 0; x < IMAGE_WIDTH; x++) {
                int destination = positiveModulo(x + shift, IMAGE_WIDTH);
                argbPixels[rowOffset + destination] = rowCopy[x];
            }
        }
    }

    private void ensureCapacity(int requiredLines) {
        if (requiredLines <= capacityLines) {
            return;
        }
        int newCapacity = capacityLines;
        while (newCapacity < requiredLines && newCapacity < MAX_LINES) {
            newCapacity = Math.min(MAX_LINES, newCapacity * 2);
        }
        argbPixels = Arrays.copyOf(argbPixels, IMAGE_WIDTH * newCapacity);
        capacityLines = newCapacity;
        listener.onDiagnostic("WEFAX buffer_grow lines=" + newCapacity);
    }

    private void publishFrame(boolean isComplete) {
        listener.onFrame(
            IMAGE_WIDTH,
            capacityLines,
            argbPixels,
            completedLines,
            isComplete
        );
    }

    private double clockErrorPpm() {
        return (samplesPerLine / nominalSamplesPerLine - 1.0) * 1_000_000.0;
    }

    private static double median(double[] values, int count) {
        if (count <= 0) {
            return 0.0;
        }
        double[] copy = Arrays.copyOf(values, count);
        Arrays.sort(copy);
        int middle = count / 2;
        if ((count & 1) == 1) {
            return copy[middle];
        }
        return (copy[middle - 1] + copy[middle]) * 0.5;
    }

    private static double positiveModulo(double value, double modulus) {
        double result = value % modulus;
        return result < 0.0 ? result + modulus : result;
    }

    private static int positiveModulo(int value, int modulus) {
        int result = value % modulus;
        return result < 0 ? result + modulus : result;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String format1(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private static String format3(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static String format4(double value) {
        return String.format(Locale.US, "%.4f", value);
    }

    private static final class PhaseFit {
        final double samplesPerLine;
        final double predictedLastEdgeSample;
        final double medianResidualSamples;

        PhaseFit(
            double samplesPerLine,
            double predictedLastEdgeSample,
            double medianResidualSamples
        ) {
            this.samplesPerLine = samplesPerLine;
            this.predictedLastEdgeSample = predictedLastEdgeSample;
            this.medianResidualSamples = medianResidualSamples;
        }
    }

    private static final class ClockEstimate {
        final boolean accepted;
        final double shiftPixels;
        final double correlation;
        final double peakMargin;
        final double profileStdDev;
        final int distinctPeakCount;

        ClockEstimate(
            boolean accepted,
            double shiftPixels,
            double correlation,
            double peakMargin,
            double profileStdDev,
            int distinctPeakCount
        ) {
            this.accepted = accepted;
            this.shiftPixels = shiftPixels;
            this.correlation = correlation;
            this.peakMargin = peakMargin;
            this.profileStdDev = profileStdDev;
            this.distinctPeakCount = distinctPeakCount;
        }

        static ClockEstimate rejected(
            double correlation,
            double peakMargin,
            double profileStdDev,
            int distinctPeakCount
        ) {
            return new ClockEstimate(
                false,
                0.0,
                correlation,
                peakMargin,
                profileStdDev,
                distinctPeakCount
            );
        }
    }
}
