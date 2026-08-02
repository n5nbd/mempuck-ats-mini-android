/*
SSTV Decoder - Scottie S2 hardware slice

Decoder and DSP structure adapted from Robot36 by Ahmet Inan.
Copyright 2024 Ahmet Inan <xdsopl@gmail.com>

Adapted for MemPuck for ATS Mini. The Activity/View UI from Robot36 is not used.
*/

package com.n5nbd.mempuck.atsmini.img.decoder.robot36;

import java.util.Arrays;

public final class ScottieS2Decoder {
    public interface Listener {
        void onModeDetected(String modeName);

        void onFrame(
            int width,
            int height,
            int[] argbPixels,
            int completedLines,
            boolean complete
        );

        default void onAdaptiveStatus(String modeName, int correctionHz, int confidence) {
        }

        default void onDiagnostic(String message) {
        }

        default void onRawGrayscaleFrame(
            int width,
            int height,
            byte[] grayPixels,
            boolean complete
        ) {
        }

        default void onLineTrace(
            int lineNumber,
            int[] sampleIndices,
            float[] rawFrequenciesHz,
            float[] correctedFrequenciesHz,
            int[] grayValues
        ) {
        }

        default void onTimeline(String text) {
        }
    }

    private final Listener listener;
    private final SimpleMovingAverage pulseFilter;
    private final Demodulator demodulator;
    private final ScottieS2Mode mode;
    private final PixelBuffer lineBuffer;
    private final int[] imagePixels;
    private final byte[] rawGrayscalePixels;
    private final byte[] rawLuminanceRow;
    private final int[] lineConfidence;
    private final float[] scanLineBuffer;
    private final float[] scratchBuffer;
    private final long[] lastSyncPulses;
    private final int[] lastScanLines;
    private final float[] lastFrequencyOffsets;
    private final float[] visCodeBitFrequencies;
    private final int pulseFilterDelay;
    private final int scanLineMinSamples;
    private final int syncPulseToleranceSamples;
    private final int scanLineToleranceSamples;
    private final int leaderToneSamples;
    private final int leaderToneToleranceSamples;
    private final int transitionSamples;
    private final int visCodeBitSamples;
    private final int visCodeSamples;

    private final int sampleRate;
    private final boolean allowProvisionalStart;
    private final int processFrameSamples;
    private final float[] inputBuffer;
    private int inputBufferFill;
    private int currentSample;
    private int leaderBreakIndex;
    private long scanLineBufferStartSample;
    private long lastSyncPulseSample;
    private int currentScanLineSamples;
    private float lastFrequencyOffset;
    private int imageLine = -1;
    private long lastDecodedSyncSample = Long.MIN_VALUE;
    private long totalInputSamples;
    private long nextDiagnosticSample;
    private int pulse5msCount;
    private int pulse9msCount;
    private int pulse20msCount;
    private long provisionalSyncSample = -1;
    private int adaptiveConfidence;
    private float smoothedFrequencyOffset;
    private boolean provisionalDecode;
    private boolean physicalSyncCalibrated;
    private long physicalSyncBiasSamples;
    private int physicalSyncWarmupPulses;
    private boolean awaitingVisSync;
    private float pendingVisFrequencyOffset;
    private long pendingVisEndSample;

    public ScottieS2Decoder(int sampleRate, Listener listener) {
        this(sampleRate, listener, false);
    }

    public ScottieS2Decoder(int sampleRate, Listener listener, boolean allowProvisionalStart) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        this.listener = listener;
        this.sampleRate = sampleRate;
        this.allowProvisionalStart = allowProvisionalStart;
        processFrameSamples = Math.max(1, sampleRate / 50);
        inputBuffer = new float[processFrameSamples];
        nextDiagnosticSample = sampleRate;
        mode = new ScottieS2Mode(sampleRate);
        lineBuffer = new PixelBuffer(mode.getWidth(), 2);
        imagePixels = new int[mode.getWidth() * mode.getHeight()];
        rawGrayscalePixels = new byte[mode.getWidth() * mode.getHeight()];
        rawLuminanceRow = new byte[mode.getWidth() * 2];
        lineConfidence = new int[mode.getHeight()];
        Arrays.fill(imagePixels, 0xff000000);
        demodulator = new Demodulator(sampleRate);

        double pulseFilterSeconds = 0.0025;
        int pulseFilterSamples = (int) Math.round(pulseFilterSeconds * sampleRate) | 1;
        pulseFilterDelay = (pulseFilterSamples - 1) / 2;
        pulseFilter = new SimpleMovingAverage(pulseFilterSamples);

        double scanLineMaxSeconds = 7;
        scanLineBuffer = new float[(int) Math.round(scanLineMaxSeconds * sampleRate)];
        double scratchBufferSeconds = 1.1;
        scratchBuffer = new float[(int) Math.round(scratchBufferSeconds * sampleRate)];

        double leaderToneSeconds = 0.3;
        leaderToneSamples = (int) Math.round(leaderToneSeconds * sampleRate);
        double leaderToneToleranceSeconds = leaderToneSeconds * 0.2;
        leaderToneToleranceSamples = (int) Math.round(leaderToneToleranceSeconds * sampleRate);
        transitionSamples = (int) Math.round(0.0005 * sampleRate);
        visCodeBitSamples = (int) Math.round(0.03 * sampleRate);
        visCodeSamples = (int) Math.round(0.3 * sampleRate);
        visCodeBitFrequencies = new float[10];

        int scanLineCount = 4;
        lastScanLines = new int[scanLineCount];
        lastSyncPulses = new long[scanLineCount + 1];
        lastFrequencyOffsets = new float[scanLineCount + 1];
        scanLineMinSamples = (int) Math.round(0.05 * sampleRate);
        syncPulseToleranceSamples = (int) Math.round(0.03 * sampleRate);
        scanLineToleranceSamples = (int) Math.round(0.001 * sampleRate);
        currentScanLineSamples = mode.getScanLineSamples();
    }

    public void process(short[] samples, int count) {
        if (samples == null || count <= 0) {
            return;
        }
        int safeCount = Math.min(count, samples.length);
        int offset = 0;
        while (offset < safeCount) {
            int copyCount = Math.min(processFrameSamples - inputBufferFill, safeCount - offset);
            for (int i = 0; i < copyCount; ++i) {
                inputBuffer[inputBufferFill + i] = samples[offset + i] / 32768.0f;
            }
            inputBufferFill += copyCount;
            offset += copyCount;
            if (inputBufferFill == processFrameSamples) {
                processFloats(inputBuffer, processFrameSamples);
                inputBufferFill = 0;
            }
        }
    }

    /**
     * Finalize a user-ended capture. A complete final Scottie S2 line may be
     * decoded here when its payload is already buffered. Partial lines remain
     * partial; this path never invents missing image rows.
     */
    public void finishCapture(String reason) {
        if (imageLine < 0 || imageLine >= mode.getHeight()) {
            listener.onDiagnostic(
                "SCOTTIE2 finish_ignored reason=" + reason
                    + " image_line=" + imageLine
                    + " state=" + (provisionalDecode ? "PROVISIONAL" : "CONFIRMED")
            );
            return;
        }

        long availableSamples = currentStreamSample() - lastSyncPulseSample;
        int requiredSamples = mode.getRequiredSamplesAfterSync();
        listener.onDiagnostic(
            "SCOTTIE2 finish_requested reason=" + reason
                + " image_line=" + imageLine
                + " available_samples=" + availableSamples
                + " required_samples=" + requiredSamples
                + " last_sync_sample=" + lastSyncPulseSample
        );
        if (availableSamples >= requiredSamples && imageLine < mode.getHeight()) {
            decodeLine(lastSyncPulseSample, lastFrequencyOffset, "finish");
        }
        if (imageLine < mode.getHeight()) {
            emitFrame(false);
        }
    }

    private void processFloats(float[] recordBuffer, int count) {
        boolean syncPulseDetected = demodulator.process(recordBuffer, count);
        long syncPulseSample = currentStreamSample() + demodulator.syncPulseOffset;
        for (int i = 0; i < count; ++i) {
            scanLineBuffer[currentSample++] = recordBuffer[i];
            if (currentSample >= scanLineBuffer.length) {
                shiftSamples(currentScanLineSamples);
            }
        }
        totalInputSamples += count;

        int syncPulseIndex = absoluteToLocal(syncPulseSample);
        if (syncPulseDetected && syncPulseIndex >= 0 && syncPulseIndex < currentSample) {
            switch (demodulator.syncPulseWidth) {
                case FiveMilliSeconds:
                    ++pulse5msCount;
                    // The streaming pulse-width estimate can shorten a clean
                    // 9 ms Scottie sync when callback boundaries or a rapid
                    // following tone trim its detected tail. Line-clock and
                    // VIS-state validation still reject unrelated 5 ms pulses.
                    handleScottieSyncPulse(syncPulseSample, syncPulseIndex);
                    break;
                case NineMilliSeconds:
                    ++pulse9msCount;
                    handleScottieSyncPulse(syncPulseSample, syncPulseIndex);
                    break;
                case TwentyMilliSeconds:
                    ++pulse20msCount;
                    // A noisy 9 ms Scottie pulse can occasionally land in the
                    // wider bucket. The line-clock validation rejects unrelated
                    // wide pulses after decoding has begun.
                    handleScottieSyncPulse(syncPulseSample, syncPulseIndex);
                    break;
                default:
                    break;
            }
        } else if (handleHeader()) {
            // VIS acceptance arms the decoder; the first Scottie line sync
            // completes mode acquisition because green and blue precede it.
        } else if (
            imageLine >= 0
                && imageLine < mode.getHeight()
                && currentStreamSample()
                    > lastSyncPulseSample + (long) (currentScanLineSamples * 5) / 4
        ) {
            // A missed following sync must not stall progressive output forever.
            // Decode at most one fully buffered pending line, but do not infer EOF
            // and never fill the rest of the frame from this callback path.
            long availableSamples = currentStreamSample() - lastSyncPulseSample;
            int requiredSamples = mode.getRequiredSamplesAfterSync();
            if (availableSamples >= requiredSamples) {
                long pendingSyncSample = lastSyncPulseSample;
                listener.onDiagnostic(
                    "SCOTTIE2 timeout_line_ready line=" + (imageLine + 1)
                        + " available_samples=" + availableSamples
                        + " required_samples=" + requiredSamples
                        + " anchor_sample=" + pendingSyncSample
                        + " buffer_start_sample=" + scanLineBufferStartSample
                );
                if (decodeLine(pendingSyncSample, lastFrequencyOffset, "timeout")) {
                    advancePredictedAnchor(pendingSyncSample);
                }
            }
        }
        emitPulseSummaryIfDue();
    }

    private void handleScottieSyncPulse(long syncPulseSample, int syncPulseIndex) {
        if (awaitingVisSync && imageLine < 0) {
            // Scottie adds a 9 ms starting sync immediately after the VIS stop
            // bit. Many transmitters make those two 1200 Hz periods continuous,
            // so the demodulator may either report that starting pulse or merge
            // it into the VIS stop. Distinguish it by time rather than blindly
            // discarding the first pulse: the first regular blue/red sync is
            // roughly 297 ms after VIS, while the starting pulse ends almost
            // immediately.
            long elapsedAfterVis = syncPulseSample - pendingVisEndSample;
            long startingSyncWindow = Math.round(0.180 * sampleRate);
            if (elapsedAfterVis >= 0 && elapsedAfterVis < startingSyncWindow) {
                listener.onDiagnostic(
                    "SCOTTIE2 starting_sync_ignored sync_end_sample=" + syncPulseSample
                        + " elapsed_after_vis_samples=" + elapsedAfterVis
                );
                return;
            }
            beginConfirmedDecodeAtSync(syncPulseSample, pendingVisFrequencyOffset);
            return;
        }
        if (imageLine >= 0) {
            processSyncPulse(syncPulseSample);
            return;
        }
        considerProvisionalStart(syncPulseSample, demodulator.frequencyOffset);
        leaderBreakIndex = syncPulseIndex;
    }


    private boolean handleHeader() {
        if (
            leaderBreakIndex < visCodeBitSamples + leaderToneToleranceSamples
                || currentSample
                    < leaderBreakIndex
                        + leaderToneSamples
                        + leaderToneToleranceSamples
                        + visCodeSamples
                        + visCodeBitSamples
        ) {
            return false;
        }

        int breakPulseIndex = leaderBreakIndex;
        leaderBreakIndex = 0;
        listener.onDiagnostic("HEADER candidate break_sample=" + breakPulseIndex);
        float preBreakFrequency = 0;
        for (int i = 0; i < leaderToneToleranceSamples; ++i) {
            preBreakFrequency += scanLineBuffer[
                breakPulseIndex - visCodeBitSamples - leaderToneToleranceSamples + i
            ];
        }
        float leaderToneFrequency = 1900;
        float centerFrequency = 1900;
        float decodedToneToleranceFrequency = 50;
        float leaderToneToleranceFrequency = 150;
        float preLeaderPlausibilityFrequency = 400;
        float halfBandWidth = 400;
        preBreakFrequency = preBreakFrequency * halfBandWidth / leaderToneToleranceSamples
            + centerFrequency;
        if (Math.abs(preBreakFrequency - leaderToneFrequency) > preLeaderPlausibilityFrequency) {
            return rejectHeader("pre_leader_hz=" + Math.round(preBreakFrequency));
        }
        if (Math.abs(preBreakFrequency - leaderToneFrequency) > leaderToneToleranceFrequency) {
            listener.onDiagnostic(
                "HEADER pre_leader_degraded_hz=" + Math.round(preBreakFrequency)
                    + " continuing_with_post_leader"
            );
        }

        float leaderFrequency = 0;
        for (int i = transitionSamples; i < leaderToneSamples - leaderToneToleranceSamples; ++i) {
            leaderFrequency += scanLineBuffer[breakPulseIndex + i];
        }
        float leaderFrequencyOffset = leaderFrequency
            / (leaderToneSamples - transitionSamples - leaderToneToleranceSamples);
        leaderFrequency = leaderFrequencyOffset * halfBandWidth + centerFrequency;
        if (Math.abs(leaderFrequency - leaderToneFrequency) > leaderToneToleranceFrequency) {
            return rejectHeader("post_leader_hz=" + Math.round(leaderFrequency));
        }

        float stopBitFrequency = 1200;
        float pulseThresholdFrequency = (stopBitFrequency + leaderToneFrequency) / 2;
        float pulseThresholdValue = (pulseThresholdFrequency - centerFrequency) / halfBandWidth;
        int visBeginIndex = breakPulseIndex + leaderToneSamples - leaderToneToleranceSamples;
        int visEndIndex = breakPulseIndex
            + leaderToneSamples
            + leaderToneToleranceSamples
            + visCodeBitSamples;
        for (int i = 0; i < pulseFilter.length; ++i) {
            pulseFilter.avg(scanLineBuffer[visBeginIndex++] - leaderFrequencyOffset);
        }
        while (++visBeginIndex < visEndIndex) {
            if (pulseFilter.avg(scanLineBuffer[visBeginIndex] - leaderFrequencyOffset) < pulseThresholdValue) {
                break;
            }
        }
        if (visBeginIndex >= visEndIndex) {
            return rejectHeader("vis_start_not_found");
        }
        visBeginIndex -= pulseFilterDelay;
        visEndIndex = visBeginIndex + visCodeSamples;
        Arrays.fill(visCodeBitFrequencies, 0);
        for (int bit = 0; bit < 10; ++bit) {
            for (int i = transitionSamples; i < visCodeBitSamples - transitionSamples; ++i) {
                visCodeBitFrequencies[bit] += scanLineBuffer[
                    visBeginIndex + visCodeBitSamples * bit + i
                ] - leaderFrequencyOffset;
            }
        }
        for (int i = 0; i < 10; ++i) {
            visCodeBitFrequencies[i] = visCodeBitFrequencies[i]
                * halfBandWidth
                / (visCodeBitSamples - 2 * transitionSamples)
                + centerFrequency;
        }
        if (
            Math.abs(visCodeBitFrequencies[0] - stopBitFrequency) > decodedToneToleranceFrequency
                || Math.abs(visCodeBitFrequencies[9] - stopBitFrequency) > decodedToneToleranceFrequency
        ) {
            return rejectHeader(
                "stop_bits_hz=" + Math.round(visCodeBitFrequencies[0])
                    + "," + Math.round(visCodeBitFrequencies[9])
            );
        }
        float oneBitFrequency = 1100;
        float zeroBitFrequency = 1300;
        for (int i = 1; i < 9; ++i) {
            if (
                Math.abs(visCodeBitFrequencies[i] - oneBitFrequency) > decodedToneToleranceFrequency
                    && Math.abs(visCodeBitFrequencies[i] - zeroBitFrequency) > decodedToneToleranceFrequency
            ) {
                return rejectHeader(
                    "vis_bit_" + i + "_hz=" + Math.round(visCodeBitFrequencies[i])
                );
            }
        }
        int visCode = 0;
        for (int i = 0; i < 8; ++i) {
            visCode |= (visCodeBitFrequencies[i + 1] < stopBitFrequency ? 1 : 0) << i;
        }
        boolean parityOkay = true;
        for (int i = 0; i < 8; ++i) {
            parityOkay ^= (visCode & 1 << i) != 0;
        }
        visCode &= 127;
        if (!parityOkay || visCode != ScottieS2Mode.VIS_CODE) {
            return rejectHeader("vis_code=" + visCode + " parity=" + parityOkay);
        }

        awaitingVisSync = true;
        pendingVisFrequencyOffset = leaderFrequencyOffset;
        pendingVisEndSample = scanLineBufferStartSample + visEndIndex;
        provisionalSyncSample = -1;
        listener.onDiagnostic(
            "VIS accepted code=" + ScottieS2Mode.VIS_CODE
                + " leader_offset_hz=" + Math.round(leaderFrequencyOffset * 400)
                + " awaiting_first_scottie_sync=true"
        );
        return true;
    }

    private void beginConfirmedDecodeAtSync(
        long syncPulseSample,
        float leaderFrequencyOffset
    ) {
        initializeDecode(syncPulseSample, leaderFrequencyOffset, false);
        listener.onDiagnostic(
            "SCOTTIE2 first_sync_acquired sync_end_sample=" + syncPulseSample
        );
        listener.onDiagnostic(
            "SCOTTIE2 geometry line_samples=" + mode.getScanLineSamples()
                + " green_begin_before_sync_sample=" + mode.getFirstPixelSampleIndex()
                + " red_payload_after_sync_samples=" + mode.getRequiredSamplesAfterSync()
                + " output=one_rgb_row_per_line"
        );
        listener.onTimeline(
            "mode=SCOTTIE S2\n"
                + "sample_rate_hz=" + sampleRate + "\n"
                + "scan_line_samples=" + mode.getScanLineSamples() + "\n"
                + "sync_end_sample=" + syncPulseSample + "\n"
                + "green_begin_sample=" + (syncPulseSample + mode.getFirstPixelSampleIndex()) + "\n"
                + "red_payload_after_sync_samples=" + mode.getRequiredSamplesAfterSync() + "\n"
                + "channel_duration_samples=" + mode.getChannelSamples() + "\n"
                + "pixel_count=" + mode.getWidth() + "\n"
        );
        listener.onModeDetected(ScottieS2Mode.NAME);
        adaptiveConfidence = 100;
        listener.onAdaptiveStatus(
            ScottieS2Mode.NAME,
            Math.round(-leaderFrequencyOffset * 400),
            adaptiveConfidence
        );
        emitFrame(false);
    }

    private void considerProvisionalStart(long syncPulseSample, float frequencyOffset) {
        if (!allowProvisionalStart || imageLine >= 0) {
            return;
        }
        if (provisionalSyncSample < 0) {
            provisionalSyncSample = syncPulseSample;
            smoothedFrequencyOffset = frequencyOffset;
            return;
        }
        long interval = syncPulseSample - provisionalSyncSample;
        int tolerance = (int) Math.round(0.020 * sampleRate);
        if (Math.abs(interval - mode.getScanLineSamples()) <= tolerance) {
            smoothedFrequencyOffset = 0.7f * smoothedFrequencyOffset + 0.3f * frequencyOffset;
            // The row whose green/blue channels precede this second pulse
            // is fully present in the local buffer. Anchor there rather than
            // at the first observed pulse, which may have incomplete history.
            initializeDecode(syncPulseSample, smoothedFrequencyOffset, true);
            adaptiveConfidence = 20;
            int correctionHz = Math.round(-smoothedFrequencyOffset * 400);
            listener.onDiagnostic(
                "ADAPTIVE raw_start sync_hz="
                    + Math.round(Demodulator.SYNC_PULSE_FREQUENCY - correctionHz)
                    + " correction_hz=" + correctionHz
                    + " line_samples=" + interval
            );
            listener.onModeDetected("SCOTTIE S2 RAW");
            listener.onAdaptiveStatus("SCOTTIE S2 RAW", correctionHz, adaptiveConfidence);
            emitFrame(false);
        }
        provisionalSyncSample = syncPulseSample;
    }

    private void initializeDecode(
        long syncPulseSample,
        float frequencyOffset,
        boolean provisional
    ) {
        mode.resetState();
        awaitingVisSync = false;
        Arrays.fill(imagePixels, 0xff000000);
        Arrays.fill(rawGrayscalePixels, (byte) 0);
        Arrays.fill(lineConfidence, 0);
        imageLine = 0;
        lastDecodedSyncSample = Long.MIN_VALUE;
        provisionalDecode = provisional;
        physicalSyncCalibrated = false;
        physicalSyncBiasSamples = 0;
        physicalSyncWarmupPulses = 0;
        lastSyncPulseSample = syncPulseSample;
        currentScanLineSamples = mode.getScanLineSamples();
        lastFrequencyOffset = frequencyOffset;
        smoothedFrequencyOffset = frequencyOffset;
        long oldestSyncPulseSample = lastSyncPulseSample
            - (long) (lastSyncPulses.length - 1) * currentScanLineSamples;
        for (int i = 0; i < lastSyncPulses.length; ++i) {
            lastSyncPulses[i] = oldestSyncPulseSample + (long) i * currentScanLineSamples;
        }
        Arrays.fill(lastScanLines, currentScanLineSamples);
        Arrays.fill(lastFrequencyOffsets, frequencyOffset);
    }

    private void processSyncPulse(long rawLatestSyncSample) {
        if (imageLine < 0 || imageLine >= mode.getHeight()) {
            return;
        }

        long previousSyncSample = lastSyncPulseSample;
        long expectedSyncSample = previousSyncSample + currentScanLineSamples;
        int candidateToleranceSamples = Math.max(6 * scanLineToleranceSamples, 6);

        // The VIS parser and streaming demodulator have different fixed filter
        // delays. Calibrate that one-time bias from the first plausible physical
        // line sync, then keep every later anchor in the VIS sync-end convention.
        if (!physicalSyncCalibrated) {
            long rawPhaseErrorSamples = rawLatestSyncSample - expectedSyncSample;
            if (Math.abs(rawPhaseErrorSamples) > candidateToleranceSamples) {
                listener.onDiagnostic(
                    "SCOTTIE2 sync_candidate_rejected reason=uncalibrated_phase"
                        + " raw_latest_sample=" + rawLatestSyncSample
                        + " expected_sample=" + expectedSyncSample
                        + " phase_error_samples=" + rawPhaseErrorSamples
                        + " tolerance_samples=" + candidateToleranceSamples
                );
                return;
            }
            if (physicalSyncWarmupPulses == 0) {
                // The first streaming sync after VIS still carries demodulator
                // filter-startup delay. Let timeout ownership finish the first
                // buffered line, then calibrate from the next stable pulse.
                physicalSyncWarmupPulses = 1;
                listener.onDiagnostic(
                    "SCOTTIE2 sync_warmup_ignored"
                        + " raw_latest_sample=" + rawLatestSyncSample
                        + " expected_sample=" + expectedSyncSample
                        + " phase_error_samples=" + rawPhaseErrorSamples
                );
                return;
            }
            physicalSyncBiasSamples = -rawPhaseErrorSamples;
            physicalSyncCalibrated = true;
            listener.onDiagnostic(
                "SCOTTIE2 sync_clock_calibrated"
                    + " raw_latest_sample=" + rawLatestSyncSample
                    + " aligned_latest_sample="
                    + (rawLatestSyncSample + physicalSyncBiasSamples)
                    + " bias_samples=" + physicalSyncBiasSamples
            );
        }

        long unaliasedLatestSyncSample = rawLatestSyncSample + physicalSyncBiasSamples;
        long latestSyncSample = correctFrameAliasedSyncSample(
            unaliasedLatestSyncSample,
            expectedSyncSample,
            processFrameSamples,
            candidateToleranceSamples
        );
        long frameAliasSamples = latestSyncSample - unaliasedLatestSyncSample;
        if (frameAliasSamples != 0) {
            listener.onDiagnostic(
                "SCOTTIE2 sync_frame_alias_corrected"
                    + " raw_latest_sample=" + rawLatestSyncSample
                    + " unaliased_latest_sample=" + unaliasedLatestSyncSample
                    + " corrected_latest_sample=" + latestSyncSample
                    + " expected_sample=" + expectedSyncSample
                    + " alias_samples=" + frameAliasSamples
            );
        }
        long phaseErrorSamples = latestSyncSample - expectedSyncSample;

        // Reject false 9/20 ms pulses before touching accepted timing history.
        if (Math.abs(phaseErrorSamples) > candidateToleranceSamples) {
            listener.onDiagnostic(
                "SCOTTIE2 sync_candidate_rejected reason=phase"
                    + " raw_latest_sample=" + rawLatestSyncSample
                    + " latest_sample=" + latestSyncSample
                    + " expected_sample=" + expectedSyncSample
                    + " phase_error_samples=" + phaseErrorSamples
                    + " tolerance_samples=" + candidateToleranceSamples
            );
            return;
        }

        // Demodulator group delay can move by a few milliseconds after a missed
        // or false pulse. Re-align that detector delay to the accepted line clock
        // instead of letting the sampling window walk sideways across the image.
        if (phaseErrorSamples != 0) {
            physicalSyncBiasSamples -= phaseErrorSamples;
            latestSyncSample = rawLatestSyncSample
                + physicalSyncBiasSamples
                + frameAliasSamples;
            listener.onDiagnostic(
                "SCOTTIE2 sync_phase_realigned"
                    + " raw_latest_sample=" + rawLatestSyncSample
                    + " phase_error_samples=" + phaseErrorSamples
                    + " new_bias_samples=" + physicalSyncBiasSamples
                    + " aligned_latest_sample=" + latestSyncSample
            );
            phaseErrorSamples = latestSyncSample - expectedSyncSample;
        }

        long candidateLineSamplesLong = latestSyncSample - previousSyncSample;
        if (candidateLineSamplesLong < Integer.MIN_VALUE
            || candidateLineSamplesLong > Integer.MAX_VALUE) {
            listener.onDiagnostic(
                "SCOTTIE2 sync_candidate_rejected reason=interval_range"
                    + " latest_sample=" + latestSyncSample
                    + " previous_sample=" + previousSyncSample
                    + " interval_samples=" + candidateLineSamplesLong
            );
            return;
        }
        int candidateLineSamples = (int) candidateLineSamplesLong;

        // Stage timing history in temporary arrays. A candidate that fails any
        // validation must leave the accepted clock completely untouched.
        long[] candidateSyncPulses = lastSyncPulses.clone();
        int[] candidateScanLines = lastScanLines.clone();
        float[] candidateFrequencyOffsets = lastFrequencyOffsets.clone();
        for (int i = 1; i < candidateSyncPulses.length; ++i) {
            candidateSyncPulses[i - 1] = candidateSyncPulses[i];
        }
        candidateSyncPulses[candidateSyncPulses.length - 1] = latestSyncSample;
        for (int i = 1; i < candidateScanLines.length; ++i) {
            candidateScanLines[i - 1] = candidateScanLines[i];
        }
        candidateScanLines[candidateScanLines.length - 1] = candidateLineSamples;
        for (int i = 1; i < candidateFrequencyOffsets.length; ++i) {
            candidateFrequencyOffsets[i - 1] = candidateFrequencyOffsets[i];
        }
        candidateFrequencyOffsets[candidateFrequencyOffsets.length - 1] =
            demodulator.frequencyOffset;

        double mean = scanLineMean(candidateScanLines);
        int scanLineSamples = (int) Math.round(mean);
        if (scanLineSamples < scanLineMinSamples || scanLineSamples > scratchBuffer.length) {
            listener.onDiagnostic(
                "SCOTTIE2 sync_candidate_rejected reason=mean_range"
                    + " latest_sample=" + latestSyncSample
                    + " interval_samples=" + candidateLineSamples
                    + " mean_samples=" + scanLineSamples
            );
            return;
        }
        double stdDev = scanLineStdDev(candidateScanLines, mean);
        if (stdDev > scanLineToleranceSamples) {
            listener.onDiagnostic(
                "SCOTTIE2 sync_candidate_rejected reason=jitter"
                    + " latest_sample=" + latestSyncSample
                    + " interval_samples=" + candidateLineSamples
                    + " mean_samples=" + scanLineSamples
                    + " stddev_samples=" + Math.round(stdDev)
                    + " tolerance_samples=" + scanLineToleranceSamples
            );
            return;
        }
        if (Math.abs(scanLineSamples - mode.getScanLineSamples()) > scanLineToleranceSamples) {
            listener.onDiagnostic(
                "SCOTTIE2 sync_candidate_rejected reason=mode_interval"
                    + " latest_sample=" + latestSyncSample
                    + " interval_samples=" + candidateLineSamples
                    + " mean_samples=" + scanLineSamples
                    + " nominal_samples=" + mode.getScanLineSamples()
                    + " tolerance_samples=" + scanLineToleranceSamples
            );
            return;
        }

        System.arraycopy(
            candidateSyncPulses,
            0,
            lastSyncPulses,
            0,
            lastSyncPulses.length
        );
        System.arraycopy(
            candidateScanLines,
            0,
            lastScanLines,
            0,
            lastScanLines.length
        );
        System.arraycopy(
            candidateFrequencyOffsets,
            0,
            lastFrequencyOffsets,
            0,
            lastFrequencyOffsets.length
        );

        float previousFrequencyOffset = lastFrequencyOffset;
        float frequencyOffset = (float) frequencyOffsetMean(candidateFrequencyOffsets);
        smoothedFrequencyOffset = smoothedFrequencyOffset == 0
            ? frequencyOffset
            : 0.8f * smoothedFrequencyOffset + 0.2f * frequencyOffset;
        adaptiveConfidence = Math.min(95, adaptiveConfidence + 8);
        listener.onAdaptiveStatus(
            provisionalDecode ? "SCOTTIE S2 RAW" : ScottieS2Mode.NAME,
            Math.round(-smoothedFrequencyOffset * 400),
            adaptiveConfidence
        );
        listener.onDiagnostic(
            "SCOTTIE2 sync_candidate_accepted"
                + " previous_sample=" + previousSyncSample
                + " latest_sample=" + latestSyncSample
                + " interval_samples=" + candidateLineSamples
                + " phase_error_samples=" + phaseErrorSamples
                + " mean_samples=" + scanLineSamples
        );

        long requiredSample = previousSyncSample + mode.getRequiredSamplesAfterSync();
        long availableSamples = currentStreamSample() - previousSyncSample;
        if (lastDecodedSyncSample == previousSyncSample) {
            listener.onDiagnostic(
                "SCOTTIE2 line_already_decoded"
                    + " previous_sync_sample=" + previousSyncSample
                    + " next_sync_sample=" + latestSyncSample
            );
        } else if (currentStreamSample() >= requiredSample) {
            listener.onDiagnostic(
                "SCOTTIE2 line_ready line=" + (imageLine + 1)
                    + " available_samples=" + availableSamples
                    + " required_samples=" + mode.getRequiredSamplesAfterSync()
                    + " anchor_sample=" + previousSyncSample
                    + " buffer_start_sample=" + scanLineBufferStartSample
            );
            decodeLine(previousSyncSample, previousFrequencyOffset, "sync");
        } else {
            listener.onDiagnostic(
                "SCOTTIE2 line_deferred line=" + (imageLine + 1)
                    + " available_samples=" + availableSamples
                    + " required_samples=" + mode.getRequiredSamplesAfterSync()
                    + " anchor_sample=" + previousSyncSample
                    + " buffer_start_sample=" + scanLineBufferStartSample
            );
        }

        lastSyncPulseSample = latestSyncSample;
        currentScanLineSamples = scanLineSamples;
        lastFrequencyOffset = smoothedFrequencyOffset;
    }

    private void advancePredictedAnchor(long decodedAnchorSample) {
        if (lastSyncPulseSample != decodedAnchorSample) {
            return;
        }
        int predictedLineSamples = mode.getScanLineSamples();
        long predictedSyncSample = decodedAnchorSample + predictedLineSamples;
        lastSyncPulseSample = predictedSyncSample;

        for (int i = 1; i < lastSyncPulses.length; ++i) {
            lastSyncPulses[i - 1] = lastSyncPulses[i];
        }
        lastSyncPulses[lastSyncPulses.length - 1] = predictedSyncSample;
        for (int i = 1; i < lastScanLines.length; ++i) {
            lastScanLines[i - 1] = lastScanLines[i];
        }
        lastScanLines[lastScanLines.length - 1] = predictedLineSamples;
        for (int i = 1; i < lastFrequencyOffsets.length; ++i) {
            lastFrequencyOffsets[i - 1] = lastFrequencyOffsets[i];
        }
        lastFrequencyOffsets[lastFrequencyOffsets.length - 1] = lastFrequencyOffset;

        listener.onDiagnostic(
            "SCOTTIE2 predicted_anchor_advanced"
                + " decoded_anchor_sample=" + decodedAnchorSample
                + " next_anchor_sample=" + predictedSyncSample
                + " line_samples=" + predictedLineSamples
        );
    }

    private boolean decodeLine(long syncPulseSample, float frequencyOffset, String source) {
        long availableSamples = currentStreamSample() - syncPulseSample;
        int requiredSamples = mode.getRequiredSamplesAfterSync();
        int syncPulseIndex = absoluteToLocal(syncPulseSample);
        int earliestPayloadIndex = syncPulseIndex - mode.getRequiredSamplesBeforeSync();
        if (syncPulseIndex < 0 || earliestPayloadIndex < 0) {
            listener.onDiagnostic(
                "SCOTTIE2 anchor_history_expired"
                    + " source=" + source
                    + " anchor_sample=" + syncPulseSample
                    + " earliest_payload_index=" + earliestPayloadIndex
                    + " buffer_start_sample=" + scanLineBufferStartSample
                    + " image_line=" + imageLine
            );
            rebaseExpiredAnchor(syncPulseSample);
            return false;
        }
        if (availableSamples < requiredSamples
            || syncPulseIndex + requiredSamples > currentSample) {
            listener.onDiagnostic(
                "SCOTTIE2 decode_deferred line=" + (imageLine + 1)
                    + " available_samples=" + availableSamples
                    + " required_samples=" + requiredSamples
                    + " anchor_sample=" + syncPulseSample
                    + " buffer_offset=" + syncPulseIndex
            );
            return false;
        }
        if (syncPulseSample <= lastDecodedSyncSample) {
            listener.onDiagnostic(
                "SCOTTIE2 nonmonotonic_anchor_ignored"
                    + " source=" + source
                    + " anchor_sample=" + syncPulseSample
                    + " previous_anchor_sample=" + lastDecodedSyncSample
                    + " image_line=" + imageLine
            );
            return false;
        }
        boolean decodedLine = mode.decodeScanLine(
            lineBuffer,
            rawLuminanceRow,
            scratchBuffer,
            scanLineBuffer,
            syncPulseIndex,
            frequencyOffset
        );
        lastDecodedSyncSample = syncPulseSample;
        listener.onDiagnostic(
            "SCOTTIE2 line_anchor_consumed"
                + " source=" + source
                + " anchor_sample=" + syncPulseSample
                + " buffer_offset=" + syncPulseIndex
                + " output_line=" + (imageLine + 1)
                + " line_decoded=" + decodedLine
        );
        if (!decodedLine || imageLine < 0 || imageLine >= mode.getHeight()) {
            return true;
        }
        int rows = Math.min(lineBuffer.height, mode.getHeight() - imageLine);
        for (int row = 0; row < rows; ++row) {
            int targetLine = imageLine + row;
            System.arraycopy(
                lineBuffer.pixels,
                row * mode.getWidth(),
                imagePixels,
                targetLine * mode.getWidth(),
                mode.getWidth()
            );
            System.arraycopy(
                rawLuminanceRow,
                row * mode.getWidth(),
                rawGrayscalePixels,
                targetLine * mode.getWidth(),
                mode.getWidth()
            );
            lineConfidence[targetLine] = calculateLineConfidence(
                rawLuminanceRow,
                row * mode.getWidth()
            );
            listener.onDiagnostic(
                "SCOTTIE2 line_confidence line=" + (targetLine + 1)
                    + " score=" + lineConfidence[targetLine]
            );
            repairPreviousLineIfIsolated(targetLine);
        }
        imageLine += rows;
        if (imageLine <= 2 || imageLine == 60 || imageLine == 120
            || imageLine == 180 || imageLine == mode.getHeight()) {
            listener.onDiagnostic(
                "SCOTTIE2 line_probe line=" + imageLine
                    + " sync_end_sample=" + syncPulseSample
                    + " luminance_begin_sample="
                    + (syncPulseSample + mode.getFirstPixelSampleIndex())
                    + " " + mode.getLastLuminanceProbe()
            );
        }
        boolean complete = imageLine >= mode.getHeight();
        emitFrame(complete);
        if (complete) {
            listener.onRawGrayscaleFrame(
                mode.getWidth(),
                mode.getHeight(),
                Arrays.copyOf(rawGrayscalePixels, rawGrayscalePixels.length),
                true
            );
        }
        return true;
    }


    private int calculateLineConfidence(byte[] rows, int rowOffset) {
        int width = mode.getWidth();
        int clipped = 0;
        int sharpSteps = 0;
        int previous = rows[rowOffset] & 0xff;
        for (int x = 0; x < width; ++x) {
            int value = rows[rowOffset + x] & 0xff;
            if (value <= 2 || value >= 253) {
                ++clipped;
            }
            if (x > 0 && Math.abs(value - previous) > 150) {
                ++sharpSteps;
            }
            previous = value;
        }
        int penalty = Math.min(45, clipped * 100 / width)
            + Math.min(25, sharpSteps * 5);
        return Math.max(0, 100 - penalty);
    }

    /**
     * Conceal only short, isolated horizontal dropouts. The candidate row is
     * evaluated after the following row exists, so both clean neighbours are
     * available. Long runs and consecutive damaged rows are deliberately left
     * untouched to avoid erasing real image detail.
     */
    private void repairPreviousLineIfIsolated(int newestLine) {
        int candidateLine = newestLine - 1;
        if (candidateLine <= 0 || newestLine >= mode.getHeight()) {
            return;
        }
        // High-confidence lines are already trustworthy and must remain byte-for-byte
        // unchanged. Concealment is reserved for a single suspect row bracketed by
        // two strong neighbours.
        if (lineConfidence[candidateLine] >= 95
            || lineConfidence[candidateLine - 1] < 95
            || lineConfidence[newestLine] < 95) {
            return;
        }

        int width = mode.getWidth();
        int aboveOffset = (candidateLine - 1) * width;
        int candidateOffset = candidateLine * width;
        int belowOffset = newestLine * width;
        int repairedPixels = 0;
        int repairedRuns = 0;
        int x = 0;

        while (x < width) {
            if (!isIsolatedPixelOutlier(aboveOffset, candidateOffset, belowOffset, x)) {
                ++x;
                continue;
            }
            int start = x;
            while (x < width
                && isIsolatedPixelOutlier(aboveOffset, candidateOffset, belowOffset, x)) {
                ++x;
            }
            int length = x - start;
            if (length < 3 || length > 80) {
                continue;
            }
            for (int pixel = start; pixel < x; ++pixel) {
                int above = rawGrayscalePixels[aboveOffset + pixel] & 0xff;
                int below = rawGrayscalePixels[belowOffset + pixel] & 0xff;
                int repaired = (above + below + 1) / 2;
                rawGrayscalePixels[candidateOffset + pixel] = (byte) repaired;
                imagePixels[candidateOffset + pixel] = averageArgb(
                    imagePixels[aboveOffset + pixel],
                    imagePixels[belowOffset + pixel]
                );
            }
            repairedPixels += length;
            ++repairedRuns;
        }

        if (repairedPixels > 0) {
            int repairPenalty = Math.min(35, repairedPixels * 100 / width);
            lineConfidence[candidateLine] = Math.max(
                lineConfidence[candidateLine],
                100 - repairPenalty
            );
            listener.onDiagnostic(
                "SCOTTIE2 isolated_repair line=" + (candidateLine + 1)
                    + " runs=" + repairedRuns
                    + " pixels=" + repairedPixels
                    + " confidence=" + lineConfidence[candidateLine]
            );
        }
    }


    private int averageArgb(int first, int second) {
        int alpha = (((first >>> 24) & 0xff) + ((second >>> 24) & 0xff) + 1) / 2;
        int red = (((first >>> 16) & 0xff) + ((second >>> 16) & 0xff) + 1) / 2;
        int green = (((first >>> 8) & 0xff) + ((second >>> 8) & 0xff) + 1) / 2;
        int blue = ((first & 0xff) + (second & 0xff) + 1) / 2;
        return alpha << 24 | red << 16 | green << 8 | blue;
    }

    private boolean isIsolatedPixelOutlier(
        int aboveOffset,
        int candidateOffset,
        int belowOffset,
        int x
    ) {
        int above = rawGrayscalePixels[aboveOffset + x] & 0xff;
        int candidate = rawGrayscalePixels[candidateOffset + x] & 0xff;
        int below = rawGrayscalePixels[belowOffset + x] & 0xff;
        if (Math.abs(above - below) > 38) {
            return false;
        }
        int expected = (above + below + 1) / 2;
        return Math.abs(candidate - expected) >= 105;
    }

    private void emitFrame(boolean complete) {
        listener.onFrame(
            mode.getWidth(),
            mode.getHeight(),
            imagePixels,
            Math.max(0, imageLine),
            complete
        );
    }

    private boolean rejectHeader(String reason) {
        listener.onDiagnostic("HEADER rejected " + reason);
        return false;
    }

    private void emitPulseSummaryIfDue() {
        if (totalInputSamples < nextDiagnosticSample) {
            return;
        }
        listener.onDiagnostic(
            "pulses_1s 5ms=" + pulse5msCount
                + " 9ms=" + pulse9msCount
                + " 20ms=" + pulse20msCount
                + " frame_samples=" + processFrameSamples
        );
        pulse5msCount = 0;
        pulse9msCount = 0;
        pulse20msCount = 0;
        while (nextDiagnosticSample <= totalInputSamples) {
            nextDiagnosticSample += sampleRate;
        }
    }

    private double scanLineMean(int[] lines) {
        double mean = 0;
        for (int line : lines) {
            mean += line;
        }
        return mean / lines.length;
    }

    private double scanLineStdDev(int[] lines, double mean) {
        double stdDev = 0;
        for (int line : lines) {
            stdDev += (line - mean) * (line - mean);
        }
        return Math.sqrt(stdDev / lines.length);
    }

    private double frequencyOffsetMean(float[] offsets) {
        double mean = 0;
        for (float offset : offsets) {
            mean += offset;
        }
        return mean / offsets.length;
    }


    static long correctFrameAliasedSyncSample(
        long latestSyncSample,
        long expectedSyncSample,
        int frameSamples,
        int toleranceSamples
    ) {
        if (frameSamples <= 0 || toleranceSamples < 0) {
            return latestSyncSample;
        }
        long bestSample = latestSyncSample;
        long bestError = Math.abs(latestSyncSample - expectedSyncSample);
        for (int frameOffset = -1; frameOffset <= 1; ++frameOffset) {
            long candidate = latestSyncSample + (long) frameOffset * frameSamples;
            long candidateError = Math.abs(candidate - expectedSyncSample);
            if (candidateError < bestError) {
                bestSample = candidate;
                bestError = candidateError;
            }
        }
        return bestError <= toleranceSamples ? bestSample : latestSyncSample;
    }

    private long currentStreamSample() {
        return scanLineBufferStartSample + currentSample;
    }

    private long localToAbsolute(int localSample) {
        return scanLineBufferStartSample + localSample;
    }

    private int absoluteToLocal(long absoluteSample) {
        long localSample = absoluteSample - scanLineBufferStartSample;
        if (localSample < 0 || localSample > Integer.MAX_VALUE) {
            return -1;
        }
        return (int) localSample;
    }

    private void rebaseExpiredAnchor(long expiredAnchorSample) {
        if (lastSyncPulseSample != expiredAnchorSample) {
            return;
        }
        long lineSamples = mode.getScanLineSamples();
        long behindSamples = scanLineBufferStartSample - expiredAnchorSample;
        long skippedLines = Math.max(1, (behindSamples + lineSamples - 1) / lineSamples);
        long rebasedAnchorSample = expiredAnchorSample + skippedLines * lineSamples;
        lastSyncPulseSample = rebasedAnchorSample;

        long oldestSyncPulseSample = rebasedAnchorSample
            - (long) (lastSyncPulses.length - 1) * currentScanLineSamples;
        for (int i = 0; i < lastSyncPulses.length; ++i) {
            lastSyncPulses[i] = oldestSyncPulseSample + (long) i * currentScanLineSamples;
        }
        Arrays.fill(lastScanLines, currentScanLineSamples);
        Arrays.fill(lastFrequencyOffsets, lastFrequencyOffset);

        listener.onDiagnostic(
            "SCOTTIE2 anchor_rebased"
                + " expired_anchor_sample=" + expiredAnchorSample
                + " rebased_anchor_sample=" + rebasedAnchorSample
                + " skipped_scan_lines=" + skippedLines
                + " buffer_start_sample=" + scanLineBufferStartSample
        );
    }

    private void shiftSamples(int shift) {
        if (shift <= 0 || shift > currentSample) {
            return;
        }
        currentSample -= shift;
        scanLineBufferStartSample += shift;
        leaderBreakIndex -= shift;
        System.arraycopy(scanLineBuffer, shift, scanLineBuffer, 0, currentSample);
    }
}
