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
    private final int beginSamples;
    private final int luminanceBeginSamples;
    private final int separatorBeginSamples;
    private final int chrominanceBeginSamples;
    private final int endSamples;
    private boolean lastEven;
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
        lastLuminanceProbe = "";
        lastTraceSampleIndices = new int[0];
        lastTraceRawFrequenciesHz = new float[0];
        lastTraceCorrectedFrequenciesHz = new float[0];
        lastTraceGray = new int[0];
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

    boolean decodeScanLine(
        PixelBuffer pixelBuffer,
        byte[] rawLuminanceRow,
        float[] scratchBuffer,
        float[] scanLineBuffer,
        int syncPulseIndex,
        float frequencyOffset
    ) {
        if (syncPulseIndex + beginSamples < 0 || syncPulseIndex + endSamples > scanLineBuffer.length) {
            return false;
        }

        // Decode the complete luminance window at fractional pixel centers.
        // A short overlapping seven-sample window follows the signal continuously
        // across the line instead of dividing it into 320 independent blocks.
        // The outer sample at each end is discarded before averaging, which keeps
        // phase-wrap impulses from dominating while preserving more horizontal
        // detail than the wider 12-13 sample trimmed mean used by dev.14.
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

        final int halfWindow = 3;
        float[] pixelSamples = new float[halfWindow * 2 + 1];

        for (int pixel = 0; pixel < horizontalPixels; ++pixel) {
            double center = luminanceBeginSamples
                + ((pixel + 0.5) * luminanceSamples) / horizontalPixels;
            int centerIndex = (int) Math.floor(center);
            double fraction = center - centerIndex;

            for (int tap = -halfWindow; tap <= halfWindow; ++tap) {
                int sampleIndex = centerIndex + tap;
                int nextIndex = sampleIndex + 1;
                sampleIndex = Math.max(luminanceBeginSamples,
                    Math.min(luminanceBeginSamples + luminanceSamples - 1, sampleIndex));
                nextIndex = Math.max(luminanceBeginSamples,
                    Math.min(luminanceBeginSamples + luminanceSamples - 1, nextIndex));
                float a = scanLineBuffer[syncPulseIndex + sampleIndex];
                float b = scanLineBuffer[syncPulseIndex + nextIndex];
                pixelSamples[tap + halfWindow] = (float) (a + (b - a) * fraction);
            }
            Arrays.sort(pixelSamples);

            float normalizedFrequency = 0f;
            for (int sample = 1; sample < pixelSamples.length - 1; ++sample) {
                normalizedFrequency += pixelSamples[sample];
            }
            normalizedFrequency /= pixelSamples.length - 2;

            float levelUnclamped = freqToLevel(normalizedFrequency, frequencyOffset);
            float rawFrequencyHz = 1900f + normalizedFrequency * 400f;
            float correctedFrequencyHz = 1900f + (normalizedFrequency - frequencyOffset) * 400f;
            minimumNormalized = Math.min(minimumNormalized, levelUnclamped);
            maximumNormalized = Math.max(maximumNormalized, levelUnclamped);

            int gray = Math.round(levelUnclamped * 255f);
            gray = Math.max(0, Math.min(255, gray));
            minimumByte = Math.min(minimumByte, gray);
            maximumByte = Math.max(maximumByte, gray);
            rawLuminanceRow[pixel] = (byte) gray;
            traceSampleIndices[pixel] = syncPulseIndex + (int) Math.round(center);
            traceRawFrequenciesHz[pixel] = rawFrequencyHz;
            traceCorrectedFrequenciesHz[pixel] = correctedFrequencyHz;
            traceGray[pixel] = gray;
            pixelBuffer.pixels[pixel] = ColorConverter.GRAY_LINEAR(gray / 255f);

            int bucket = Math.min(15, (pixel * 16) / horizontalPixels);
            probeBuckets[bucket] += gray;
            probeCounts[bucket] += 1;
        }

        StringBuilder probe = new StringBuilder();
        probe.append("raw_norm_min=").append(Math.round(minimumNormalized * 1000) / 1000.0f);
        probe.append(" raw_norm_max=").append(Math.round(maximumNormalized * 1000) / 1000.0f);
        probe.append(" byte_min=").append(minimumByte);
        probe.append(" byte_max=").append(maximumByte);
        probe.append(" buckets=");
        for (int bucket = 0; bucket < probeBuckets.length; ++bucket) {
            if (bucket > 0) probe.append(',');
            int value = probeCounts[bucket] == 0 ? 0 : probeBuckets[bucket] / probeCounts[bucket];
            probe.append(value);
        }
        lastLuminanceProbe = probe.toString();
        lastTraceSampleIndices = traceSampleIndices;
        lastTraceRawFrequenciesHz = traceRawFrequenciesHz;
        lastTraceCorrectedFrequenciesHz = traceCorrectedFrequenciesHz;
        lastTraceGray = traceGray;
        pixelBuffer.width = horizontalPixels;
        pixelBuffer.height = 1;
        return true;
    }
}
