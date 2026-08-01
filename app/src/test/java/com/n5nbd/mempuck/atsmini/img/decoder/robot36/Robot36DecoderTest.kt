package com.n5nbd.mempuck.atsmini.img.decoder.robot36

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class Robot36DecoderTest {
    @Test
    fun detectsVisAndRendersProgressiveLines() {
        val audio = Robot36SignalBuilder(sampleRateHz = 44_100).apply {
            tone(1_900.0, 0.100)
            visHeader(code = 8)
            repeat(4) { pair ->
                robot36Line(even = true, luminanceHz = 1_600.0 + pair * 100)
                robot36Line(even = false, luminanceHz = 1_600.0 + pair * 100)
            }
        }.build()

        var detectedMode: String? = null
        var completedLines = 0
        val diagnostics = mutableListOf<String>()
        val decoder = Robot36Decoder(
            44_100,
            object : Robot36Decoder.Listener {
                override fun onModeDetected(modeName: String) {
                    detectedMode = modeName
                }

                override fun onFrame(
                    width: Int,
                    height: Int,
                    argbPixels: IntArray,
                    completedLinesValue: Int,
                    complete: Boolean,
                ) {
                    assertEquals(320, width)
                    assertEquals(240, height)
                    assertEquals(width * height, argbPixels.size)
                    completedLines = maxOf(completedLines, completedLinesValue)
                }

                override fun onDiagnostic(message: String) {
                    diagnostics += message
                }
            },
        )

        val chunkSizes = intArrayOf(137, 881, 882, 883, 2_048, 11_025)
        var offset = 0
        var chunkIndex = 0
        while (offset < audio.size) {
            val count = minOf(chunkSizes[chunkIndex % chunkSizes.size], audio.size - offset)
            decoder.process(audio.copyOfRange(offset, offset + count), count)
            offset += count
            chunkIndex += 1
        }

        assertEquals("ROBOT 36", detectedMode)
        assertTrue("Expected progressive line output", completedLines >= 4)
        assertTrue(diagnostics.any { it.startsWith("VIS accepted") })
        assertTrue(diagnostics.any { it.contains("frame_samples=882") })
    }
}

private class Robot36SignalBuilder(
    private val sampleRateHz: Int,
) {
    private val samples = ArrayList<Short>()

    fun tone(frequencyHz: Double, seconds: Double) {
        val count = (seconds * sampleRateHz).roundToInt()
        val start = samples.size
        repeat(count) { index ->
            val phase = 2.0 * PI * frequencyHz * (start + index) / sampleRateHz
            samples += (sin(phase) * 14_000).roundToInt().toShort()
        }
    }

    fun visHeader(code: Int) {
        tone(1_900.0, 0.300)
        tone(1_200.0, 0.010)
        tone(1_900.0, 0.300)
        tone(1_200.0, 0.030)
        var ones = 0
        repeat(7) { bit ->
            val one = code shr bit and 1 != 0
            if (one) ones += 1
            tone(if (one) 1_100.0 else 1_300.0, 0.030)
        }
        val parity = ones and 1 != 0
        tone(if (parity) 1_100.0 else 1_300.0, 0.030)
        tone(1_200.0, 0.030)
    }

    fun robot36Line(even: Boolean, luminanceHz: Double) {
        tone(1_200.0, 0.009)
        tone(1_500.0, 0.003)
        tone(luminanceHz, 0.088)
        tone(if (even) 1_500.0 else 2_300.0, 0.0045)
        tone(1_900.0, 0.0015)
        tone(1_900.0, 0.044)
    }

    fun build(): ShortArray = samples.toShortArray()
}
