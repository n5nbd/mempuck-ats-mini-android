/*
Martin M2 RGB mode

Timing and RGB channel structure adapted from MartinM2 by Ahmet Inan.
Copyright 2024 Ahmet Inan <xdsopl@gmail.com>

Adapted for MemPuck for ATS Mini. The Activity/View UI from Robot36 is not used.
*/

package com.n5nbd.mempuck.atsmini.img.decoder.robot36;

final class MartinM2Mode {
    static final String NAME = "MARTIN M2";
    static final int VIS_CODE = 40;

    private final ExponentialMovingAverage lowPassFilter = new ExponentialMovingAverage();
    private final int horizontalPixels = 320;
    private final int verticalPixels = 256;
    private final int scanLineSamples;
    private final int beginSamples;
    private final int greenBeginSamples;
    private final int greenSamples;
    private final int blueBeginSamples;
    private final int blueSamples;
    private final int redBeginSamples;
    private final int redSamples;
    private final int endSamples;
    private String lastLuminanceProbe = "";
    private int[] lastTraceSampleIndices = new int[0];
    private float[] lastTraceRawFrequenciesHz = new float[0];
    private float[] lastTraceCorrectedFrequenciesHz = new float[0];
    private int[] lastTraceGray = new int[0];

    MartinM2Mode(int sampleRate) {
        double syncPulseSeconds = 0.004862;
        double separatorSeconds = 0.000572;
        double channelSeconds = 0.073216;
        double scanLineSeconds = syncPulseSeconds
            + separatorSeconds
            + 3 * (channelSeconds + separatorSeconds);
        double greenBeginSeconds = separatorSeconds;
        double greenEndSeconds = greenBeginSeconds + channelSeconds;
        double blueBeginSeconds = greenEndSeconds + separatorSeconds;
        double blueEndSeconds = blueBeginSeconds + channelSeconds;
        double redBeginSeconds = blueEndSeconds + separatorSeconds;
        double redEndSeconds = redBeginSeconds + channelSeconds;

        scanLineSamples = (int) Math.round(scanLineSeconds * sampleRate);
        beginSamples = (int) Math.round(greenBeginSeconds * sampleRate);
        greenBeginSamples = 0;
        greenSamples = (int) Math.round(channelSeconds * sampleRate);
        blueBeginSamples = (int) Math.round(blueBeginSeconds * sampleRate) - beginSamples;
        blueSamples = (int) Math.round(channelSeconds * sampleRate);
        redBeginSamples = (int) Math.round(redBeginSeconds * sampleRate) - beginSamples;
        redSamples = (int) Math.round(channelSeconds * sampleRate);
        endSamples = (int) Math.round(redEndSeconds * sampleRate);
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

    int getChannelSamples() {
        return greenSamples;
    }

    void resetState() {
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

    private float frequencyToLevel(float frequency, float offset) {
        return clamp(0.5f * (frequency - offset + 1f));
    }

    private float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private int lumaByte(float red, float green, float blue) {
        return Math.round(clamp(0.299f * red + 0.587f * green + 0.114f * blue) * 255f);
    }

    boolean decodeScanLine(
        PixelBuffer pixelBuffer,
        byte[] rawLuminanceRows,
        float[] scratchBuffer,
        float[] scanLineBuffer,
        int syncPulseIndex,
        float frequencyOffset
    ) {
        if (syncPulseIndex + beginSamples < 0
            || syncPulseIndex + endSamples > scanLineBuffer.length) {
            return false;
        }
        if (pixelBuffer.pixels.length < horizontalPixels
            || rawLuminanceRows.length < horizontalPixels
            || scratchBuffer.length < endSamples - beginSamples) {
            throw new IllegalArgumentException("Martin M2 decode buffers are too small");
        }

        int payloadSamples = endSamples - beginSamples;
        lowPassFilter.cutoff(horizontalPixels, 2.0 * greenSamples, 2);
        lowPassFilter.reset();
        for (int i = 0; i < payloadSamples; ++i) {
            scratchBuffer[i] = lowPassFilter.avg(
                scanLineBuffer[syncPulseIndex + beginSamples + i]
            );
        }
        lowPassFilter.reset();
        for (int i = payloadSamples - 1; i >= 0; --i) {
            scratchBuffer[i] = frequencyToLevel(
                lowPassFilter.avg(scratchBuffer[i]),
                frequencyOffset
            );
        }

        int minimum = 255;
        int maximum = 0;
        int[] traceIndices = new int[horizontalPixels];
        float[] traceRaw = new float[horizontalPixels];
        float[] traceCorrected = new float[horizontalPixels];
        int[] traceGray = new int[horizontalPixels];
        for (int pixel = 0; pixel < horizontalPixels; ++pixel) {
            int greenPosition = greenBeginSamples + (pixel * greenSamples) / horizontalPixels;
            int bluePosition = blueBeginSamples + (pixel * blueSamples) / horizontalPixels;
            int redPosition = redBeginSamples + (pixel * redSamples) / horizontalPixels;
            float red = scratchBuffer[redPosition];
            float green = scratchBuffer[greenPosition];
            float blue = scratchBuffer[bluePosition];
            pixelBuffer.pixels[pixel] = ColorConverter.RGB(red, green, blue);
            int gray = lumaByte(red, green, blue);
            rawLuminanceRows[pixel] = (byte) gray;
            minimum = Math.min(minimum, gray);
            maximum = Math.max(maximum, gray);

            int rawIndex = syncPulseIndex + beginSamples + greenPosition;
            float normalizedRaw = scanLineBuffer[rawIndex];
            traceIndices[pixel] = rawIndex;
            traceRaw[pixel] = normalizedRaw * 400f + 1900f;
            traceCorrected[pixel] = (normalizedRaw - frequencyOffset) * 400f + 1900f;
            traceGray[pixel] = gray;
        }
        pixelBuffer.width = horizontalPixels;
        pixelBuffer.height = 1;
        lastTraceSampleIndices = traceIndices;
        lastTraceRawFrequenciesHz = traceRaw;
        lastTraceCorrectedFrequenciesHz = traceCorrected;
        lastTraceGray = traceGray;
        lastLuminanceProbe = "rgb_order=G,B,R"
            + " gray_min=" + minimum
            + " gray_max=" + maximum
            + " correction_hz=" + Math.round(-frequencyOffset * 400f);
        return true;
    }
}
