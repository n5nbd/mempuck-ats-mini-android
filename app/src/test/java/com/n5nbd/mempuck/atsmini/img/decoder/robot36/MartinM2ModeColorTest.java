package com.n5nbd.mempuck.atsmini.img.decoder.robot36;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class MartinM2ModeColorTest {
    private static final int SAMPLE_RATE = 44_100;
    private static final int WIDTH = 320;

    @Test
    public void decodesMartinGreenBlueRedChannelOrder() {
        MartinM2Mode mode = new MartinM2Mode(SAMPLE_RATE);
        PixelBuffer output = new PixelBuffer(WIDTH, 2);
        byte[] grayscale = new byte[WIDTH * 2];
        float[] scratch = new float[SAMPLE_RATE];
        float[] line = scanLine(0.85f, 0.30f, 0.10f, mode.getRequiredSamplesAfterSync());

        assertTrue(mode.decodeScanLine(output, grayscale, scratch, line, 0, 0f));
        assertEquals(WIDTH, output.width);
        assertEquals(1, output.height);

        int pixel = output.pixels[WIDTH / 2];
        int red = pixel >>> 16 & 0xff;
        int green = pixel >>> 8 & 0xff;
        int blue = pixel & 0xff;
        assertTrue("Martin red channel must come from the final channel", red > green);
        assertTrue("Martin green channel must come from the first channel", green > blue);
        assertTrue(mode.getLastLuminanceProbe().contains("rgb_order=G,B,R"));
    }

    @Test
    public void neutralChannelsProduceNeutralGray() {
        MartinM2Mode mode = new MartinM2Mode(SAMPLE_RATE);
        PixelBuffer output = new PixelBuffer(WIDTH, 2);
        byte[] grayscale = new byte[WIDTH * 2];
        float[] scratch = new float[SAMPLE_RATE];
        float[] line = scanLine(0.55f, 0.55f, 0.55f, mode.getRequiredSamplesAfterSync());

        assertTrue(mode.decodeScanLine(output, grayscale, scratch, line, 0, 0f));
        int pixel = output.pixels[WIDTH / 2];
        int red = pixel >>> 16 & 0xff;
        int green = pixel >>> 8 & 0xff;
        int blue = pixel & 0xff;
        assertTrue(Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue)) <= 2);
    }

    private static float[] scanLine(
        float red,
        float green,
        float blue,
        int requiredSamples
    ) {
        int separator = (int) Math.round(0.000572 * SAMPLE_RATE);
        int channel = (int) Math.round(0.073216 * SAMPLE_RATE);
        int greenBegin = separator;
        int blueBegin = greenBegin + channel + separator;
        int redBegin = blueBegin + channel + separator;
        float[] samples = new float[requiredSamples + 32];
        Arrays.fill(samples, greenBegin, greenBegin + channel, 2f * green - 1f);
        Arrays.fill(samples, blueBegin, blueBegin + channel, 2f * blue - 1f);
        Arrays.fill(samples, redBegin, redBegin + channel, 2f * red - 1f);
        return samples;
    }
}
