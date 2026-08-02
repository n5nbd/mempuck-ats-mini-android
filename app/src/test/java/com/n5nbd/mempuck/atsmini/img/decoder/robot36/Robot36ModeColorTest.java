package com.n5nbd.mempuck.atsmini.img.decoder.robot36;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import org.junit.Test;

public class Robot36ModeColorTest {
    private static final int SAMPLE_RATE = 44_100;
    private static final int WIDTH = 320;

    @Test
    public void completeEvenOddPairProducesTwoOpaqueColorRows() {
        Robot36Mode mode = new Robot36Mode(SAMPLE_RATE);
        PixelBuffer output = new PixelBuffer(WIDTH, 2);
        byte[] rawRows = new byte[WIDTH * 2];
        float[] scratch = new float[SAMPLE_RATE];

        boolean evenComplete = mode.decodeScanLine(
            output,
            rawRows,
            scratch,
            scanLine(0.35f, 0.75f, true),
            0,
            0f
        );
        assertFalse(evenComplete);

        boolean oddComplete = mode.decodeScanLine(
            output,
            rawRows,
            scratch,
            scanLine(0.65f, 0.25f, false),
            0,
            0f
        );
        assertTrue(oddComplete);
        assertEquals(2, output.height);
        assertEquals(WIDTH, output.width);

        int upper = output.pixels[0];
        int lower = output.pixels[WIDTH];
        assertEquals(0xff, upper >>> 24);
        assertEquals(0xff, lower >>> 24);
        assertTrue(isColor(upper));
        assertTrue(isColor(lower));
        assertNotEquals(upper, lower);
        assertTrue(luma(lower) > luma(upper));
        assertEquals(Math.round(0.35f * 255f), rawRows[0] & 0xff);
        assertEquals(Math.round(0.65f * 255f), rawRows[WIDTH] & 0xff);
    }

    @Test
    public void neutralChrominancePreservesGrayscaleLuminance() {
        Robot36Mode mode = new Robot36Mode(SAMPLE_RATE);
        PixelBuffer output = new PixelBuffer(WIDTH, 2);
        byte[] rawRows = new byte[WIDTH * 2];
        float[] scratch = new float[SAMPLE_RATE];

        mode.decodeScanLine(output, rawRows, scratch, scanLine(0.25f, 0.5f, true), 0, 0f);
        assertTrue(
            mode.decodeScanLine(output, rawRows, scratch, scanLine(0.75f, 0.5f, false), 0, 0f)
        );

        int upper = output.pixels[0];
        int lower = output.pixels[WIDTH];
        assertTrue(channelSpread(upper) <= 2);
        assertTrue(channelSpread(lower) <= 2);
        assertTrue(luma(lower) > luma(upper));
    }

    @Test
    public void lowSeparatorChrominanceProducesRedDifference() {
        Robot36Mode mode = new Robot36Mode(SAMPLE_RATE);
        PixelBuffer output = new PixelBuffer(WIDTH, 2);
        byte[] rawRows = new byte[WIDTH * 2];
        float[] scratch = new float[SAMPLE_RATE];

        mode.decodeScanLine(output, rawRows, scratch, scanLine(0.50f, 0.75f, true), 0, 0f);
        assertTrue(
            mode.decodeScanLine(output, rawRows, scratch, scanLine(0.50f, 0.50f, false), 0, 0f)
        );

        int upper = output.pixels[WIDTH / 2];
        int red = upper >>> 16 & 0xff;
        int green = upper >>> 8 & 0xff;
        int blue = upper & 0xff;
        assertTrue(red > green);
        assertTrue(red > blue);
    }

    @Test
    public void highSeparatorChrominanceProducesBlueDifference() {
        Robot36Mode mode = new Robot36Mode(SAMPLE_RATE);
        PixelBuffer output = new PixelBuffer(WIDTH, 2);
        byte[] rawRows = new byte[WIDTH * 2];
        float[] scratch = new float[SAMPLE_RATE];

        mode.decodeScanLine(output, rawRows, scratch, scanLine(0.50f, 0.50f, true), 0, 0f);
        assertTrue(
            mode.decodeScanLine(output, rawRows, scratch, scanLine(0.50f, 0.75f, false), 0, 0f)
        );

        int upper = output.pixels[WIDTH / 2];
        int red = upper >>> 16 & 0xff;
        int green = upper >>> 8 & 0xff;
        int blue = upper & 0xff;
        assertTrue(blue > red);
        assertTrue(blue > green);
    }

    private static float[] scanLine(float luminance, float chrominance, boolean even) {
        int luminanceBegin = (int) Math.round(0.003 * SAMPLE_RATE);
        int luminanceSamples = (int) Math.round(0.088 * SAMPLE_RATE);
        int separatorBegin = (int) Math.round((0.003 + 0.088) * SAMPLE_RATE);
        int separatorSamples = (int) Math.round(0.0045 * SAMPLE_RATE);
        int chrominanceBegin = (int) Math.round((0.003 + 0.088 + 0.0045 + 0.0015) * SAMPLE_RATE);
        int chrominanceSamples = (int) Math.round(0.044 * SAMPLE_RATE);
        int end = chrominanceBegin + chrominanceSamples;
        float[] samples = new float[end + 16];
        Arrays.fill(
            samples,
            luminanceBegin,
            luminanceBegin + luminanceSamples,
            2f * luminance - 1f
        );
        Arrays.fill(
            samples,
            separatorBegin,
            separatorBegin + separatorSamples,
            even ? -1f : 1f
        );
        Arrays.fill(
            samples,
            chrominanceBegin,
            chrominanceBegin + chrominanceSamples,
            2f * chrominance - 1f
        );
        return samples;
    }

    private static boolean isColor(int argb) {
        return channelSpread(argb) > 2;
    }

    private static int channelSpread(int argb) {
        int red = argb >>> 16 & 0xff;
        int green = argb >>> 8 & 0xff;
        int blue = argb & 0xff;
        return Math.max(red, Math.max(green, blue)) - Math.min(red, Math.min(green, blue));
    }

    private static int luma(int argb) {
        int red = argb >>> 16 & 0xff;
        int green = argb >>> 8 & 0xff;
        int blue = argb & 0xff;
        return (77 * red + 150 * green + 29 * blue) >>> 8;
    }
}
