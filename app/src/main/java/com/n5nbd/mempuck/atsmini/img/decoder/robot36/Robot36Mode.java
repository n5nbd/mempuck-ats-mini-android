/*
Robot 36 Color

Copyright 2024 Ahmet Inan <xdsopl@gmail.com>

Adapted for MemPuck for ATS Mini. The Activity/View UI from Robot36 is not used.
*/

package com.n5nbd.mempuck.atsmini.img.decoder.robot36;

import java.util.Arrays;

final class Robot36Mode {
    static final String NAME = "ROBOT 36";
    static final int VIS_CODE = 8;

    private final ExponentialMovingAverage lowPassFilter;
    private final int horizontalPixels;
    private final int verticalPixels;
    private final int scanLineSamples;
    private final int luminanceSamples;
    private final int separatorSamples;
    private final int chrominanceSamples;
    private final int chrominanceGuardSamples;
    private final int beginSamples;
    private final int luminanceBeginSamples;
    private final int separatorBeginSamples;
    private final int chrominanceBeginSamples;
    private final int endSamples;
    private final float[] pendingEvenLuminance;
    private final float[] pendingEvenChroma;
    private boolean lastEven;
    private boolean haveEvenLine;
    private float pendingEvenSeparatorLevel;
    private String pendingEvenParitySource = "NONE";
    private float lastSeparatorLevel;
    private String lastParitySource = "RESET";
    private String lastLuminanceProbe = "";
    private int[] lastTraceSampleIndices = new int[0];
    private float[] lastTraceRawFrequenciesHz = new float[0];
    private float[] lastTraceCorrectedFrequenciesHz = new float[0];
    private int[] lastTraceGray = new int[0];

    Robot36Mode(int sampleRate) {
        horizontalPixels = 320;
        verticalPixels = 240;
        double syncPulseSeconds = 0.009;
        double syncPorchSeconds = 0.003;
        double luminanceSeconds = 0.088;
        double separatorSeconds = 0.0045;
        double porchSeconds = 0.0015;
        double chrominanceSeconds = 0.044;
        double scanLineSeconds = syncPulseSeconds
            + syncPorchSeconds
            + luminanceSeconds
            + separatorSeconds
            + porchSeconds
            + chrominanceSeconds;
        scanLineSamples = (int) Math.round(scanLineSeconds * sampleRate);
        luminanceSamples = (int) Math.round(luminanceSeconds * sampleRate);
        separatorSamples = (int) Math.round(separatorSeconds * sampleRate);
        chrominanceSamples = (int) Math.round(chrominanceSeconds * sampleRate);
        chrominanceGuardSamples = Math.max(1, (int) Math.round(0.0004 * sampleRate));
        double luminanceBeginSeconds = syncPorchSeconds;
        luminanceBeginSamples = (int) Math.round(luminanceBeginSeconds * sampleRate);
        beginSamples = luminanceBeginSamples;
        double separatorBeginSeconds = luminanceBeginSeconds + luminanceSeconds;
        separatorBeginSamples = (int) Math.round(separatorBeginSeconds * sampleRate);
        double separatorEndSeconds = separatorBeginSeconds + separatorSeconds;
        double chrominanceBeginSeconds = separatorEndSeconds + porchSeconds;
        chrominanceBeginSamples = (int) Math.round(chrominanceBeginSeconds * sampleRate);
        double chrominanceEndSeconds = chrominanceBeginSeconds + chrominanceSeconds;
        endSamples = (int) Math.round(chrominanceEndSeconds * sampleRate);
        pendingEvenLuminance = new float[horizontalPixels];
        pendingEvenChroma = new float[horizontalPixels];
        lowPassFilter = new ExponentialMovingAverage();
    }

    private float freqToLevel(float frequency, float offset) {
        return 0.5f * (frequency - offset + 1.f);
    }

    int getWidth() {
        return horizontalPixels;
    }

    int getHeight() {
        return verticalPixels;
    }

    int getFirstPixelSampleIndex() {
        return beginSamples;
    }

    int getScanLineSamples() {
        return scanLineSamples;
    }

    int getRequiredSamplesAfterSync() {
        return endSamples;
    }

    void resetState() {
        lastEven = false;
        haveEvenLine = false;
        pendingEvenSeparatorLevel = 0f;
        pendingEvenParitySource = "NONE";
        lastSeparatorLevel = 0f;
        lastParitySource = "RESET";
        lastLuminanceProbe = "";
        lastTraceSampleIndices = new int[0];
        lastTraceRawFrequenciesHz = new float[0];
        lastTraceCorrectedFrequenciesHz = new float[0];
        lastTraceGray = new int[0];
        Arrays.fill(pendingEvenLuminance, 0f);
        Arrays.fill(pendingEvenChroma, 0f);
    }

    String getLastLuminanceProbe() {
        return lastLuminanceProbe;
    }

    int[] getLastTraceSampleIndices() {
        return lastTraceSampleIndices.clone();
    }

    float[] getLastTraceRawFrequenciesHz() {
        return lastTraceRawFrequenciesHz.clone();
    }

    float[] getLastTraceCorrectedFrequenciesHz() {
        return lastTraceCorrectedFrequenciesHz.clone();
    }

    int[] getLastTraceGray() {
        return lastTraceGray.clone();
    }

    private float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private int levelToByte(float level) {
        return Math.round(clamp(level, 0f, 1f) * 255f);
    }

    private float calibratedChromaLevel(
        float normalizedChroma,
        float pairOffset,
        float pairGain
    ) {
        float calibrated = (normalizedChroma - pairOffset) / pairGain;
        return 0.5f * (calibrated + 1f);
    }

    private float averageSamples(float[] samples, int start, int count) {
        int safeStart = Math.max(0, Math.min(samples.length - 1, start));
        int safeEnd = Math.max(safeStart + 1, Math.min(samples.length, safeStart + count));
        float sum = 0f;
        for (int index = safeStart; index < safeEnd; ++index) {
            sum += samples[index];
        }
        return sum / (safeEnd - safeStart);
    }

    private float sampleWindow(
        float[] scanLineBuffer,
        int syncPulseIndex,
        int windowBegin,
        int windowSamples,
        int pixel,
        int halfWindow,
        float[] pixelSamples
    ) {
        double center = windowBegin
            + ((pixel + 0.5) * windowSamples) / horizontalPixels;
        int centerIndex = (int) Math.floor(center);
        double fraction = center - centerIndex;
        int windowEnd = windowBegin + windowSamples - 1;

        for (int tap = -halfWindow; tap <= halfWindow; ++tap) {
            int sampleIndex = Math.max(windowBegin, Math.min(windowEnd, centerIndex + tap));
            int nextIndex = Math.max(windowBegin, Math.min(windowEnd, sampleIndex + 1));
            float a = scanLineBuffer[syncPulseIndex + sampleIndex];
            float b = scanLineBuffer[syncPulseIndex + nextIndex];
            pixelSamples[tap + halfWindow] = (float) (a + (b - a) * fraction);
        }
        Arrays.sort(pixelSamples);

        float average = 0f;
        for (int sample = 1; sample < pixelSamples.length - 1; ++sample) {
            average += pixelSamples[sample];
        }
        return average / (pixelSamples.length - 2);
    }

    private boolean detectEvenLine(float[] scanLineBuffer, int syncPulseIndex, float frequencyOffset) {
        float separator = 0f;
        for (int i = 0; i < separatorSamples; ++i) {
            separator += scanLineBuffer[syncPulseIndex + separatorBeginSamples + i];
        }
        separator = separator / separatorSamples - frequencyOffset;

        boolean even;
        if (separator >= -1.1f && separator <= -0.9f) {
            even = true;
            lastParitySource = "LOW";
        } else if (separator >= 0.9f && separator <= 1.1f) {
            even = false;
            lastParitySource = "HIGH";
        } else {
            // A damaged separator must not freeze parity. Continue the established
            // Robot 36 alternation until a valid separator tone is received again.
            even = !lastEven;
            lastParitySource = "ALTERNATE";
        }
        lastSeparatorLevel = separator;
        lastEven = even;
        return even;
    }

    boolean decodeScanLine(
        PixelBuffer pixelBuffer,
        byte[] rawLuminanceRows,
        float[] scratchBuffer,
        float[] scanLineBuffer,
        int syncPulseIndex,
        float frequencyOffset
    ) {
        if (syncPulseIndex + beginSamples < 0 || syncPulseIndex + endSamples > scanLineBuffer.length) {
            return false;
        }
        if (pixelBuffer.pixels.length < horizontalPixels * 2
            || rawLuminanceRows.length < horizontalPixels * 2) {
            throw new IllegalArgumentException("Robot 36 color decode requires two-row buffers");
        }

        boolean even = detectEvenLine(scanLineBuffer, syncPulseIndex, frequencyOffset);

        float minimumNormalized = Float.POSITIVE_INFINITY;
        float maximumNormalized = Float.NEGATIVE_INFINITY;
        int minimumByte = 255;
        int maximumByte = 0;
        int[] probeBuckets = new int[16];
        int[] probeCounts = new int[16];
        int[] traceSampleIndices = new int[horizontalPixels];
        float[] traceRawFrequenciesHz = new float[horizontalPixels];
        float[] traceCorrectedFrequenciesHz = new float[horizontalPixels];
        int[] traceGray = new int[horizontalPixels];
        float[] luminanceWindow = new float[7];
        int rawRowOffset = even ? 0 : horizontalPixels;
        long pairY0 = 0;
        long pairY1 = 0;
        long pairU = 0;
        long pairV = 0;
        long pairR = 0;
        long pairG = 0;
        long pairB = 0;

        // Robot 36 chrominance is only 44 ms wide and is especially sensitive to
        // phase noise. Filter that window in both directions, but prime each pass
        // from its nearest edge so the 1500/2300 Hz separator and the zero-valued
        // filter reset cannot pull the first or last color pixels off neutral.
        lowPassFilter.cutoff(horizontalPixels, 2 * luminanceSamples, 2);
        lowPassFilter.reset();
        float firstChroma = averageSamples(
            scanLineBuffer,
            syncPulseIndex + chrominanceBeginSamples + chrominanceGuardSamples,
            32
        );
        for (int sample = 0; sample < 64; ++sample) {
            lowPassFilter.avg(firstChroma);
        }
        for (int sample = chrominanceBeginSamples; sample < endSamples; ++sample) {
            scratchBuffer[sample] = lowPassFilter.avg(scanLineBuffer[syncPulseIndex + sample]);
        }
        lowPassFilter.reset();
        float lastChroma = averageSamples(
            scratchBuffer,
            endSamples - chrominanceGuardSamples - 32,
            32
        );
        for (int sample = 0; sample < 64; ++sample) {
            lowPassFilter.avg(lastChroma);
        }
        for (int sample = endSamples - 1; sample >= chrominanceBeginSamples; --sample) {
            scratchBuffer[sample] = lowPassFilter.avg(scratchBuffer[sample]);
        }

        float pairOffset = 0f;
        float pairGain = 1f;
        boolean pairCalibrationApplied = false;
        if (!even && haveEvenLine) {
            float measuredGain = 0.5f * (lastSeparatorLevel - pendingEvenSeparatorLevel);
            float measuredOffset = 0.5f * (lastSeparatorLevel + pendingEvenSeparatorLevel);
            if (pendingEvenSeparatorLevel < -0.35f
                && lastSeparatorLevel > 0.35f
                && measuredGain >= 0.55f
                && measuredGain <= 1.45f
                && Math.abs(measuredOffset) <= 0.45f) {
                pairGain = measuredGain;
                pairOffset = measuredOffset;
                pairCalibrationApplied = true;
            }
        }

        for (int pixel = 0; pixel < horizontalPixels; ++pixel) {
            float normalizedLuminance = sampleWindow(
                scanLineBuffer,
                syncPulseIndex,
                luminanceBeginSamples,
                luminanceSamples,
                pixel,
                3,
                luminanceWindow
            );
            int guardedChromaSamples = chrominanceSamples - 2 * chrominanceGuardSamples;
            int chrominancePosition = chrominanceBeginSamples
                + chrominanceGuardSamples
                + (int) Math.floor(
                    ((pixel + 0.5) * guardedChromaSamples) / horizontalPixels
                );
            chrominancePosition = Math.min(
                endSamples - chrominanceGuardSamples - 1,
                chrominancePosition
            );

            float luminanceLevel = freqToLevel(normalizedLuminance, frequencyOffset);
            float chrominanceNormalized = scratchBuffer[chrominancePosition] - frequencyOffset;
            float rawFrequencyHz = 1900f + normalizedLuminance * 400f;
            float correctedFrequencyHz = 1900f
                + (normalizedLuminance - frequencyOffset) * 400f;
            minimumNormalized = Math.min(minimumNormalized, luminanceLevel);
            maximumNormalized = Math.max(maximumNormalized, luminanceLevel);

            int gray = Math.round(luminanceLevel * 255f);
            gray = Math.max(0, Math.min(255, gray));
            minimumByte = Math.min(minimumByte, gray);
            maximumByte = Math.max(maximumByte, gray);
            rawLuminanceRows[rawRowOffset + pixel] = (byte) gray;
            traceSampleIndices[pixel] = syncPulseIndex
                + luminanceBeginSamples
                + (int) Math.round(((pixel + 0.5) * luminanceSamples) / horizontalPixels);
            traceRawFrequenciesHz[pixel] = rawFrequencyHz;
            traceCorrectedFrequenciesHz[pixel] = correctedFrequencyHz;
            traceGray[pixel] = gray;

            if (even) {
                // Robot 36 carries V (red difference) on the low-separator line
                // and U (blue difference) on the following high-separator line.
                // Retain the uncalibrated pair so both separator tones can remove
                // common chroma offset and gain before YUV conversion.
                pendingEvenLuminance[pixel] = luminanceLevel;
                pendingEvenChroma[pixel] = chrominanceNormalized;
            } else if (haveEvenLine) {
                float vLevel = calibratedChromaLevel(
                    pendingEvenChroma[pixel],
                    pairOffset,
                    pairGain
                );
                float uLevel = calibratedChromaLevel(
                    chrominanceNormalized,
                    pairOffset,
                    pairGain
                );
                int upper = ColorConverter.YUV2RGB(
                    pendingEvenLuminance[pixel],
                    uLevel,
                    vLevel
                );
                int lower = ColorConverter.YUV2RGB(
                    luminanceLevel,
                    uLevel,
                    vLevel
                );
                pixelBuffer.pixels[pixel] = upper;
                pixelBuffer.pixels[pixel + horizontalPixels] = lower;
                pairY0 += levelToByte(pendingEvenLuminance[pixel]);
                pairY1 += levelToByte(luminanceLevel);
                pairU += levelToByte(uLevel);
                pairV += levelToByte(vLevel);
                pairR += ((upper >>> 16) & 0xff) + ((lower >>> 16) & 0xff);
                pairG += ((upper >>> 8) & 0xff) + ((lower >>> 8) & 0xff);
                pairB += (upper & 0xff) + (lower & 0xff);
            }

            int bucket = Math.min(15, (pixel * 16) / horizontalPixels);
            probeBuckets[bucket] += gray;
            probeCounts[bucket] += 1;
        }

        StringBuilder probe = new StringBuilder();
        probe.append("parity=").append(even ? "EVEN" : "ODD");
        probe.append(" raw_norm_min=").append(Math.round(minimumNormalized * 1000) / 1000.0f);
        probe.append(" raw_norm_max=").append(Math.round(maximumNormalized * 1000) / 1000.0f);
        probe.append(" byte_min=").append(minimumByte);
        probe.append(" byte_max=").append(maximumByte);
        probe.append(" buckets=");
        for (int bucket = 0; bucket < probeBuckets.length; ++bucket) {
            if (bucket > 0) probe.append(',');
            int value = probeCounts[bucket] == 0 ? 0 : probeBuckets[bucket] / probeCounts[bucket];
            probe.append(value);
        }
        probe.append(" separator=")
            .append(Math.round(lastSeparatorLevel * 1000) / 1000.0f);
        probe.append(" parity_source=").append(lastParitySource);
        if (even) {
            pendingEvenSeparatorLevel = lastSeparatorLevel;
            pendingEvenParitySource = lastParitySource;
        } else if (haveEvenLine) {
            probe.append(" pair_sep=")
                .append(Math.round(pendingEvenSeparatorLevel * 1000) / 1000.0f)
                .append('/')
                .append(Math.round(lastSeparatorLevel * 1000) / 1000.0f);
            probe.append(" pair_source=")
                .append(pendingEvenParitySource)
                .append('/')
                .append(lastParitySource);
            probe.append(" chroma_cal=")
                .append(pairCalibrationApplied ? "PAIR" : "FALLBACK");
            probe.append(" pair_offset=")
                .append(Math.round(pairOffset * 1000) / 1000.0f);
            probe.append(" pair_gain=")
                .append(Math.round(pairGain * 1000) / 1000.0f);
            probe.append(" mean_yuv=")
                .append(pairY0 / horizontalPixels).append(',')
                .append(pairY1 / horizontalPixels).append(',')
                .append(pairU / horizontalPixels).append(',')
                .append(pairV / horizontalPixels);
            int rgbDivisor = horizontalPixels * 2;
            probe.append(" mean_rgb=")
                .append(pairR / rgbDivisor).append(',')
                .append(pairG / rgbDivisor).append(',')
                .append(pairB / rgbDivisor);
        }
        lastLuminanceProbe = probe.toString();
        lastTraceSampleIndices = traceSampleIndices;
        lastTraceRawFrequenciesHz = traceRawFrequenciesHz;
        lastTraceCorrectedFrequenciesHz = traceCorrectedFrequenciesHz;
        lastTraceGray = traceGray;

        if (even) {
            haveEvenLine = true;
            pixelBuffer.width = horizontalPixels;
            pixelBuffer.height = 1;
            return false;
        }
        if (!haveEvenLine) {
            // A capture that begins on an odd line has no matching U line yet.
            // Do not emit synthetic color; wait for the next complete pair.
            pixelBuffer.width = horizontalPixels;
            pixelBuffer.height = 1;
            return false;
        }

        haveEvenLine = false;
        pixelBuffer.width = horizontalPixels;
        pixelBuffer.height = 2;
        return true;
    }
}
