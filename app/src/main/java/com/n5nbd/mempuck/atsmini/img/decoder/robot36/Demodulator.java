/*
SSTV Demodulator

Copyright 2024 Ahmet Inan <xdsopl@gmail.com>

Adapted for MemPuck for ATS Mini. The Activity/View UI from Robot36 is not used.
*/

package com.n5nbd.mempuck.atsmini.img.decoder.robot36;

final class Demodulator {
    private final SimpleMovingAverage syncPulseFilter;
    private final ComplexConvolution baseBandLowPass;
    private final FrequencyModulation frequencyModulation;
    private final SchmittTrigger syncPulseTrigger;
    private final Phasor baseBandOscillator;
    private final Delay syncPulseValueDelay;
    private final double scanLineBandwidth;
    private final double centerFrequency;
    private final float syncPulseFrequencyValue;
    private final float syncPulseFrequencyTolerance;
    private final int syncPulse5msMinSamples;
    private final int syncPulse5msMaxSamples;
    private final int syncPulse9msMaxSamples;
    private final int syncPulse20msMaxSamples;
    private final int syncPulseFilterDelay;
    private int syncPulseCounter;
    private Complex baseBand;

    enum SyncPulseWidth {
        FiveMilliSeconds,
        NineMilliSeconds,
        TwentyMilliSeconds,
    }

    SyncPulseWidth syncPulseWidth;
    int syncPulseOffset;
    float frequencyOffset;

    static final double SYNC_PULSE_FREQUENCY = 1200;
    static final double BLACK_FREQUENCY = 1500;
    static final double WHITE_FREQUENCY = 2300;

    Demodulator(int sampleRate) {
        scanLineBandwidth = WHITE_FREQUENCY - BLACK_FREQUENCY;
        frequencyModulation = new FrequencyModulation(scanLineBandwidth, sampleRate);
        double syncPulse5msSeconds = 0.005;
        double syncPulse9msSeconds = 0.009;
        double syncPulse20msSeconds = 0.020;
        double syncPulse5msMinSeconds = syncPulse5msSeconds / 2;
        double syncPulse5msMaxSeconds = (syncPulse5msSeconds + syncPulse9msSeconds) / 2;
        double syncPulse9msMaxSeconds = (syncPulse9msSeconds + syncPulse20msSeconds) / 2;
        double syncPulse20msMaxSeconds = syncPulse20msSeconds + syncPulse5msSeconds;
        syncPulse5msMinSamples = (int) Math.round(syncPulse5msMinSeconds * sampleRate);
        syncPulse5msMaxSamples = (int) Math.round(syncPulse5msMaxSeconds * sampleRate);
        syncPulse9msMaxSamples = (int) Math.round(syncPulse9msMaxSeconds * sampleRate);
        syncPulse20msMaxSamples = (int) Math.round(syncPulse20msMaxSeconds * sampleRate);
        double syncPulseFilterSeconds = syncPulse5msSeconds / 2;
        int syncPulseFilterSamples = (int) Math.round(syncPulseFilterSeconds * sampleRate) | 1;
        syncPulseFilterDelay = (syncPulseFilterSamples - 1) / 2;
        syncPulseFilter = new SimpleMovingAverage(syncPulseFilterSamples);
        syncPulseValueDelay = new Delay(syncPulseFilterSamples);
        double lowestFrequency = 1000;
        double highestFrequency = 2800;
        double cutoffFrequency = (highestFrequency - lowestFrequency) / 2;
        double baseBandLowPassSeconds = 0.002;
        int baseBandLowPassSamples = (int) Math.round(baseBandLowPassSeconds * sampleRate) | 1;
        baseBandLowPass = new ComplexConvolution(baseBandLowPassSamples);
        Kaiser kaiser = new Kaiser();
        for (int i = 0; i < baseBandLowPass.length; ++i) {
            baseBandLowPass.taps[i] = (float) (
                kaiser.window(2.0, i, baseBandLowPass.length)
                    * Filter.lowPass(cutoffFrequency, sampleRate, i, baseBandLowPass.length)
            );
        }
        centerFrequency = (lowestFrequency + highestFrequency) / 2;
        baseBandOscillator = new Phasor(-centerFrequency, sampleRate);
        syncPulseFrequencyValue = (float) normalizeFrequency(SYNC_PULSE_FREQUENCY);
        // Accept a translated SSTV audio passband during acquisition.  The measured
        // sync tone supplies the correction used by the image decoder, so a receiver
        // or playback path that moves every tone by a few hundred hertz can still lock.
        syncPulseFrequencyTolerance = (float) (350 * 2 / scanLineBandwidth);
        double syncHighFrequency = 1500;
        double syncLowFrequency = 1450;
        double syncLowValue = normalizeFrequency(syncLowFrequency);
        double syncHighValue = normalizeFrequency(syncHighFrequency);
        syncPulseTrigger = new SchmittTrigger((float) syncLowValue, (float) syncHighValue);
        baseBand = new Complex();
    }

    private double normalizeFrequency(double frequency) {
        return (frequency - centerFrequency) * 2 / scanLineBandwidth;
    }

    boolean process(float[] buffer, int count) {
        boolean syncPulseDetected = false;
        for (int i = 0; i < count; ++i) {
            baseBand.set(buffer[i]);
            baseBand = baseBandLowPass.push(baseBand.mul(baseBandOscillator.rotate()));
            float frequencyValue = frequencyModulation.demod(baseBand);
            float syncPulseValue = syncPulseFilter.avg(frequencyValue);
            float syncPulseDelayedValue = syncPulseValueDelay.push(syncPulseValue);
            buffer[i] = frequencyValue;
            if (!syncPulseTrigger.latch(syncPulseValue)) {
                ++syncPulseCounter;
            } else if (
                syncPulseCounter < syncPulse5msMinSamples
                    || syncPulseCounter > syncPulse20msMaxSamples
                    || Math.abs(syncPulseDelayedValue - syncPulseFrequencyValue)
                        > syncPulseFrequencyTolerance
            ) {
                syncPulseCounter = 0;
            } else {
                if (syncPulseCounter < syncPulse5msMaxSamples) {
                    syncPulseWidth = SyncPulseWidth.FiveMilliSeconds;
                } else if (syncPulseCounter < syncPulse9msMaxSamples) {
                    syncPulseWidth = SyncPulseWidth.NineMilliSeconds;
                } else {
                    syncPulseWidth = SyncPulseWidth.TwentyMilliSeconds;
                }
                syncPulseOffset = i - syncPulseFilterDelay;
                frequencyOffset = syncPulseDelayedValue - syncPulseFrequencyValue;
                syncPulseDetected = true;
                syncPulseCounter = 0;
            }
        }
        return syncPulseDetected;
    }
}
