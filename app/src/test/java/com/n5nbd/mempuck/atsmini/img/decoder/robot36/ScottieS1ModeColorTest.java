package com.n5nbd.mempuck.atsmini.img.decoder.robot36;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class ScottieS1ModeColorTest {
    private static final int SAMPLE_RATE = 44_100;
    private static final int WIDTH = 320;

    @Test
    public void decodesScottieGreenBlueSyncRedChannelOrder() {
        ScottieS1Mode mode = new ScottieS1Mode(SAMPLE_RATE);
        PixelBuffer output = new PixelBuffer(WIDTH, 2);
        byte[] grayscale = new byte[WIDTH * 2];
        float[] scratch = new float[SAMPLE_RATE];
        int syncIndex = mode.getRequiredSamplesBeforeSync() + 32;
        float[] line = scanLine(0.85f, 0.30f, 0.10f, syncIndex, mode.getRequiredSamplesAfterSync());

        assertTrue(mode.decodeScanLine(output, grayscale, scratch, line, syncIndex, 0f));
        assertEquals(WIDTH, output.width);
        assertEquals(1, output.height);

        int pixel = output.pixels[WIDTH / 2];
        int red = pixel >>> 16 & 0xff;
        int green = pixel >>> 8 & 0xff;
        int blue = pixel & 0xff;
        assertTrue("Scottie red channel must come after sync", red > green);
        assertTrue("Scottie green channel must come from the earliest channel", green > blue);
        assertTrue(mode.getLastLuminanceProbe().contains("rgb_order=G,B,[SYNC],R"));
    }

    @Test
    public void neutralChannelsProduceNeutralGray() {
        ScottieS1Mode mode = new ScottieS1Mode(SAMPLE_RATE);
        PixelBuffer output = new PixelBuffer(WIDTH, 2);
        byte[] grayscale = new byte[WIDTH * 2];
        float[] scratch = new float[SAMPLE_RATE];
        int syncIndex = mode.getRequiredSamplesBeforeSync() + 32;
        float[] line = scanLine(0.55f, 0.55f, 0.55f, syncIndex, mode.getRequiredSamplesAfterSync());

        assertTrue(mode.decodeScanLine(output, grayscale, scratch, line, syncIndex, 0f));
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
        int syncIndex,
        int requiredAfterSync
    ) {
        int channel = (int) Math.round(0.138240 * SAMPLE_RATE);
        int greenBegin = syncIndex - (int) Math.round(0.286980 * SAMPLE_RATE);
        int blueBegin = syncIndex - (int) Math.round(0.147240 * SAMPLE_RATE);
        int redBegin = syncIndex + (int) Math.round(0.001500 * SAMPLE_RATE);
        float[] samples = new float[syncIndex + requiredAfterSync + 32];
        Arrays.fill(samples, greenBegin, greenBegin + channel, 2f * green - 1f);
        Arrays.fill(samples, blueBegin, blueBegin + channel, 2f * blue - 1f);
        Arrays.fill(samples, redBegin, redBegin + channel, 2f * red - 1f);
        return samples;
    }
}
