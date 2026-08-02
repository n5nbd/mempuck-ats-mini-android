package com.n5nbd.mempuck.atsmini.img.decoder.robot36

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class Robot36DecoderTest {
    @Test
    fun correctsCallbackFrameAliasedSyncPositions() {
        val expected = 1_112_010L
        assertEquals(
            expected - 3,
            Robot36Decoder.correctFrameAliasedSyncSample(
                expected - 885,
                expected,
                882,
                264,
            ),
        )
        assertEquals(
            expected + 4,
            Robot36Decoder.correctFrameAliasedSyncSample(
                expected + 886,
                expected,
                882,
                264,
            ),
        )
        val unrelatedFalsePulse = expected - 1_700
        assertEquals(
            unrelatedFalsePulse,
            Robot36Decoder.correctFrameAliasedSyncSample(
                unrelatedFalsePulse,
                expected,
                882,
                264,
            ),
        )
    }

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
        assertTrue("Normal Robot 36 must be clocked by physical sync pulses", diagnostics.any {
            it.startsWith("ROBOT36 line_anchor_consumed source=sync")
        })
        assertTrue("The accepted VIS sync anchor must remain in the buffer", diagnostics.none {
            it.startsWith("ROBOT36 anchor_expired")
        })
    }
    @Test
    fun droppedSyncFalsePulseAndBufferRolloverUseAbsoluteMonotonicAnchors() {
        val audio = Robot36SignalBuilder(sampleRateHz = 44_100).apply {
            tone(1_900.0, 0.100)
            visHeader(code = 8)
            repeat(120) { line ->
                robot36Line(
                    even = line % 2 == 0,
                    luminanceHz = 1_650.0 + (line % 5) * 80,
                    includeSync = line != 70,
                    falseSyncInLuminance = line == 90,
                )
            }
            tone(1_900.0, 0.500)
        }.build()

        val anchors = mutableListOf<Long>()
        val diagnostics = mutableListOf<String>()
        val decoder = Robot36Decoder(
            44_100,
            object : Robot36Decoder.Listener {
                override fun onModeDetected(modeName: String) = Unit

                override fun onFrame(
                    width: Int,
                    height: Int,
                    argbPixels: IntArray,
                    completedLinesValue: Int,
                    complete: Boolean,
                ) = Unit

                override fun onDiagnostic(message: String) {
                    diagnostics += message
                    if (message.startsWith("ROBOT36 line_anchor_consumed")) {
                        val anchor = message.substringAfter("anchor_sample=")
                            .substringBefore(' ')
                            .toLong()
                        anchors += anchor
                    }
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

        assertTrue("Expected timeout recovery to advance the line clock", diagnostics.any {
            it.startsWith("ROBOT36 predicted_anchor_advanced")
        })
        val syncOwned = diagnostics.count {
            it.startsWith("ROBOT36 line_anchor_consumed source=sync")
        }
        val timeoutOwned = diagnostics.count {
            it.startsWith("ROBOT36 line_anchor_consumed source=timeout")
        }
        assertTrue("Physical sync must remain the primary line clock after rollover", syncOwned > timeoutOwned)
        assertTrue("A dropped sync should require only bounded timeout recovery", timeoutOwned <= 6)
        assertEquals("Each scan-line anchor must be consumed once", anchors.size, anchors.toSet().size)
        assertTrue("Decoded anchors must advance monotonically", anchors.zipWithNext().all {
            (first, second) -> second > first
        })
        assertTrue(
            "Absolute anchors must continue beyond the seven-second rolling buffer",
            anchors.last() - anchors.first() > 8L * 44_100L,
        )
        assertTrue("Non-monotonic anchors must never reach pixel reconstruction", diagnostics.none {
            it.startsWith("ROBOT36 nonmonotonic_anchor_ignored")
        })
        assertTrue("The test must exercise rolling-buffer rebasing", diagnostics.any {
            it.contains("buffer_start_sample=") &&
                !it.contains("buffer_start_sample=0 ")
        })
    }

    @Test
    fun pairCalibratesRobot36ChromaOffsetAndGain() {
        val sampleRate = 44_100
        val syncPorch = (0.003 * sampleRate).roundToInt()
        val luminanceSamples = (0.088 * sampleRate).roundToInt()
        val separatorSamples = (0.0045 * sampleRate).roundToInt()
        val separatorBegin = syncPorch + luminanceSamples
        val chrominanceBegin = separatorBegin + separatorSamples +
            (0.0015 * sampleRate).roundToInt()
        val chrominanceSamples = (0.044 * sampleRate).roundToInt()

        fun decodePair(vNormalized: Float, uNormalized: Float): Pair<Int, String> {
            val mode = Robot36Mode(sampleRate)
            val pixels = PixelBuffer(320, 2)
            val rawLuminance = ByteArray(640)
            val scratch = FloatArray(sampleRate)
            val line = FloatArray(mode.requiredSamplesAfterSync + 32)
            val measuredGain = 0.8f
            val measuredOffset = -0.2f

            fun fillLine(even: Boolean, chromaNormalized: Float) {
                line.fill(0f)
                for (index in 0 until luminanceSamples) {
                    line[syncPorch + index] = 0f
                }
                val separator = measuredOffset + measuredGain * if (even) -1f else 1f
                for (index in 0 until separatorSamples) {
                    line[separatorBegin + index] = separator
                }
                val chroma = measuredOffset + measuredGain * chromaNormalized
                for (index in 0 until chrominanceSamples) {
                    line[chrominanceBegin + index] = chroma
                }
            }

            fillLine(even = true, chromaNormalized = vNormalized)
            assertTrue(!mode.decodeScanLine(pixels, rawLuminance, scratch, line, 0, 0f))
            fillLine(even = false, chromaNormalized = uNormalized)
            assertTrue(mode.decodeScanLine(pixels, rawLuminance, scratch, line, 0, 0f))
            return pixels.pixels[160] to mode.lastLuminanceProbe
        }

        val (neutral, neutralProbe) = decodePair(0f, 0f)
        val neutralR = neutral shr 16 and 0xff
        val neutralG = neutral shr 8 and 0xff
        val neutralB = neutral and 0xff
        assertTrue("Neutral chroma must remain neutral after pair calibration", maxOf(
            neutralR,
            neutralG,
            neutralB,
        ) - minOf(neutralR, neutralG, neutralB) <= 3)
        assertTrue(neutralProbe.contains("chroma_cal=PAIR"))
        assertTrue(neutralProbe.contains("pair_offset=-0.2"))
        assertTrue(neutralProbe.contains("pair_gain=0.8"))

        val (red, _) = decodePair(0.5f, 0f)
        val redR = red shr 16 and 0xff
        val redG = red shr 8 and 0xff
        val redB = red and 0xff
        assertTrue("Positive V must decode as red, not blue", redR > redG && redR > redB)

        val (blue, _) = decodePair(0f, 0.5f)
        val blueR = blue shr 16 and 0xff
        val blueG = blue shr 8 and 0xff
        val blueB = blue and 0xff
        assertTrue("Positive U must decode as blue, not red", blueB > blueG && blueB > blueR)
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

    fun robot36Line(
        even: Boolean,
        luminanceHz: Double,
        includeSync: Boolean = true,
        falseSyncInLuminance: Boolean = false,
    ) {
        tone(if (includeSync) 1_200.0 else 1_500.0, 0.009)
        tone(1_500.0, 0.003)
        if (falseSyncInLuminance) {
            tone(luminanceHz, 0.030)
            tone(1_200.0, 0.009)
            tone(luminanceHz, 0.049)
        } else {
            tone(luminanceHz, 0.088)
        }
        tone(if (even) 1_500.0 else 2_300.0, 0.0045)
        tone(1_900.0, 0.0015)
        tone(1_900.0, 0.044)
    }

    fun build(): ShortArray = samples.toShortArray()
}
