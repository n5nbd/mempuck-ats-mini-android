package com.n5nbd.mempuck.atsmini.img.decoder.robot36

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class MartinM2DecoderTest {
    @Test
    fun correctsCallbackFrameAliasedSyncPositions() {
        val expected = 1_112_010L
        assertEquals(
            expected - 3,
            MartinM2Decoder.correctFrameAliasedSyncSample(
                expected - 885,
                expected,
                882,
                264,
            ),
        )
        assertEquals(
            expected + 4,
            MartinM2Decoder.correctFrameAliasedSyncSample(
                expected + 886,
                expected,
                882,
                264,
            ),
        )
    }


    @Test
    fun acceptsMartinM2VisWhenFirstLeaderWindowIsDegraded() {
        val audio = MartinM2SignalBuilder(sampleRateHz = 44_100).apply {
            tone(1_900.0, 0.100)
            visHeader(
                code = 40,
                firstLeaderHz = 1_718.0,
                stableFrequencyOffsetHz = 63.0,
            )
            repeat(4) {
                martinM2Line(
                    redHz = 1_750.0,
                    greenHz = 1_750.0,
                    blueHz = 1_750.0,
                    frequencyOffsetHz = 63.0,
                )
            }
        }.build()

        var detectedMode: String? = null
        var completedLines = 0
        val diagnostics = mutableListOf<String>()
        val decoder = MartinM2Decoder(
            44_100,
            object : MartinM2Decoder.Listener {
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
                    completedLines = maxOf(completedLines, completedLinesValue)
                }

                override fun onDiagnostic(message: String) {
                    diagnostics += message
                }
            },
        )

        var offset = 0
        while (offset < audio.size) {
            val count = minOf(882, audio.size - offset)
            decoder.process(audio.copyOfRange(offset, offset + count), count)
            offset += count
        }
        decoder.finishCapture("TEST_EOF")

        assertEquals("MARTIN M2", detectedMode)
        assertTrue("Expected at least one decoded Martin line", completedLines >= 1)
        assertTrue(diagnostics.any { it == "HEADER pre_leader_degraded_hz=1718 continuing_with_post_leader" })
        assertTrue(diagnostics.any { it.startsWith("VIS accepted code=40") })
    }

    @Test
    fun detectsMartinM2VisAndRendersProgressiveColorLines() {
        val audio = MartinM2SignalBuilder(sampleRateHz = 44_100).apply {
            tone(1_900.0, 0.100)
            visHeader(code = 40)
            repeat(8) { line ->
                when (line % 3) {
                    0 -> martinM2Line(redHz = 2_250.0, greenHz = 1_650.0, blueHz = 1_550.0)
                    1 -> martinM2Line(redHz = 1_550.0, greenHz = 2_250.0, blueHz = 1_650.0)
                    else -> martinM2Line(redHz = 1_650.0, greenHz = 1_550.0, blueHz = 2_250.0)
                }
            }
        }.build()

        var detectedMode: String? = null
        var completedLines = 0
        var lastFrame: IntArray? = null
        val diagnostics = mutableListOf<String>()
        val decoder = MartinM2Decoder(
            44_100,
            object : MartinM2Decoder.Listener {
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
                    assertEquals(256, height)
                    assertEquals(width * height, argbPixels.size)
                    completedLines = maxOf(completedLines, completedLinesValue)
                    lastFrame = argbPixels
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
        decoder.finishCapture("TEST_EOF")

        assertEquals("MARTIN M2", detectedMode)
        assertTrue("Expected progressive Martin line output", completedLines >= 4)
        assertTrue(diagnostics.any { it.startsWith("VIS accepted code=40") })
        assertTrue(diagnostics.any { it.startsWith("MARTIN2 line_anchor_consumed") })
        assertTrue(lastFrame?.any { it != 0xff000000.toInt() } == true)
    }

    @Test
    fun rejectsMartinM1VisCode() {
        val audio = MartinM2SignalBuilder(sampleRateHz = 44_100).apply {
            tone(1_900.0, 0.100)
            visHeader(code = 44)
            repeat(4) {
                martinM2Line(redHz = 1_750.0, greenHz = 1_750.0, blueHz = 1_750.0)
            }
        }.build()

        var detectedMode: String? = null
        var completedLines = 0
        val decoder = MartinM2Decoder(
            44_100,
            object : MartinM2Decoder.Listener {
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
                    completedLines = maxOf(completedLines, completedLinesValue)
                }
            },
        )

        audio.asList().chunked(882).forEach { chunk ->
            val block = chunk.toShortArray()
            decoder.process(block, block.size)
        }
        decoder.finishCapture("TEST_EOF")

        assertEquals(null, detectedMode)
        assertEquals(0, completedLines)
    }

    @Test
    fun manualMartinM2StartsWithoutVisHeader() {
        val audio = MartinM2SignalBuilder(sampleRateHz = 44_100).apply {
            repeat(12) {
                martinM2Line(redHz = 2_100.0, greenHz = 1_650.0, blueHz = 1_550.0)
            }
        }.build()

        var detectedMode: String? = null
        var completedLines = 0
        val decoder = MartinM2Decoder(
            44_100,
            object : MartinM2Decoder.Listener {
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
                    completedLines = maxOf(completedLines, completedLinesValue)
                }
            },
            true,
        )

        audio.asList().chunked(882).forEach { chunk ->
            val block = chunk.toShortArray()
            decoder.process(block, block.size)
        }
        decoder.finishCapture("TEST_EOF")

        assertTrue(detectedMode?.contains("MARTIN M2 RAW") == true)
        assertTrue("Expected manual M2 partial lines", completedLines > 0)
    }

    @Test
    fun completesFullMartinM2Frame() {
        val audio = MartinM2SignalBuilder(sampleRateHz = 44_100).apply {
            tone(1_900.0, 0.100)
            visHeader(code = 40)
            repeat(256) { line ->
                when (line % 3) {
                    0 -> martinM2Line(redHz = 2_250.0, greenHz = 1_550.0, blueHz = 1_550.0)
                    1 -> martinM2Line(redHz = 1_550.0, greenHz = 2_250.0, blueHz = 1_550.0)
                    else -> martinM2Line(redHz = 1_550.0, greenHz = 1_550.0, blueHz = 2_250.0)
                }
            }
            tone(1_900.0, 0.250)
        }.build()

        var completedLines = 0
        var complete = false
        val decoder = MartinM2Decoder(
            44_100,
            object : MartinM2Decoder.Listener {
                override fun onModeDetected(modeName: String) = Unit

                override fun onFrame(
                    width: Int,
                    height: Int,
                    argbPixels: IntArray,
                    completedLinesValue: Int,
                    completeValue: Boolean,
                ) {
                    assertEquals(320, width)
                    assertEquals(256, height)
                    completedLines = maxOf(completedLines, completedLinesValue)
                    complete = complete || completeValue
                }
            },
        )

        var offset = 0
        while (offset < audio.size) {
            val count = minOf(882, audio.size - offset)
            decoder.process(audio.copyOfRange(offset, offset + count), count)
            offset += count
        }
        decoder.finishCapture("TEST_EOF")

        assertEquals(256, completedLines)
        assertTrue(complete)
    }
}

private class MartinM2SignalBuilder(
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

    fun visHeader(
        code: Int,
        firstLeaderHz: Double = 1_900.0,
        stableFrequencyOffsetHz: Double = 0.0,
    ) {
        tone(firstLeaderHz, 0.300)
        tone(1_200.0 + stableFrequencyOffsetHz, 0.010)
        tone(1_900.0 + stableFrequencyOffsetHz, 0.300)
        tone(1_200.0 + stableFrequencyOffsetHz, 0.030)
        var ones = 0
        repeat(7) { bit ->
            val one = code shr bit and 1 != 0
            if (one) ones += 1
            tone((if (one) 1_100.0 else 1_300.0) + stableFrequencyOffsetHz, 0.030)
        }
        val parity = ones and 1 != 0
        tone((if (parity) 1_100.0 else 1_300.0) + stableFrequencyOffsetHz, 0.030)
        tone(1_200.0 + stableFrequencyOffsetHz, 0.030)
    }

    fun martinM2Line(
        redHz: Double,
        greenHz: Double,
        blueHz: Double,
        frequencyOffsetHz: Double = 0.0,
    ) {
        tone(1_200.0 + frequencyOffsetHz, 0.004862)
        tone(1_500.0 + frequencyOffsetHz, 0.000572)
        tone(greenHz + frequencyOffsetHz, 0.073216)
        tone(1_500.0 + frequencyOffsetHz, 0.000572)
        tone(blueHz + frequencyOffsetHz, 0.073216)
        tone(1_500.0 + frequencyOffsetHz, 0.000572)
        tone(redHz + frequencyOffsetHz, 0.073216)
        tone(1_500.0 + frequencyOffsetHz, 0.000572)
    }

    fun build(): ShortArray = samples.toShortArray()
}
