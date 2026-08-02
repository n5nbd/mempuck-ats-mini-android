package com.n5nbd.mempuck.atsmini.img.decoder.robot36

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class ScottieS1DecoderTest {
    @Test
    fun correctsCallbackFrameAliasedSyncPositions() {
        val expected = 1_112_010L
        assertEquals(
            expected - 3,
            ScottieS1Decoder.correctFrameAliasedSyncSample(
                expected - 885,
                expected,
                882,
                264,
            ),
        )
        assertEquals(
            expected + 4,
            ScottieS1Decoder.correctFrameAliasedSyncSample(
                expected + 886,
                expected,
                882,
                264,
            ),
        )
    }

    @Test
    fun detectsScottieS1VisSkipsStartingSyncAndRendersProgressiveColorLines() {
        val audio = ScottieS1SignalBuilder(sampleRateHz = 44_100).apply {
            tone(1_900.0, 0.100)
            visHeader(code = 60)
            scottieImage(8) { line ->
                when (line % 3) {
                    0 -> Triple(2_250.0, 1_650.0, 1_550.0)
                    1 -> Triple(1_550.0, 2_250.0, 1_650.0)
                    else -> Triple(1_650.0, 1_550.0, 2_250.0)
                }
            }
        }.build()

        var detectedMode: String? = null
        var completedLines = 0
        var lastFrame: IntArray? = null
        val diagnostics = mutableListOf<String>()
        val decoder = ScottieS1Decoder(
            44_100,
            object : ScottieS1Decoder.Listener {
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

        feed(decoder, audio, intArrayOf(137, 881, 882, 883, 2_048, 11_025))
        decoder.finishCapture("TEST_EOF")

        assertEquals("SCOTTIE S1", detectedMode)
        assertTrue("Expected progressive Scottie line output", completedLines >= 4)
        assertTrue(diagnostics.any { it.startsWith("VIS accepted code=60") })
        assertTrue(diagnostics.any { it.startsWith("SCOTTIE1 first_sync_acquired") })
        assertTrue(diagnostics.any { it.startsWith("SCOTTIE1 line_anchor_consumed") })
        assertTrue(lastFrame?.any { it != 0xff000000.toInt() } == true)
    }

    @Test
    fun rejectsMartinM2VisCode() {
        val audio = ScottieS1SignalBuilder(sampleRateHz = 44_100).apply {
            tone(1_900.0, 0.100)
            visHeader(code = 40)
            scottieImage(4) { Triple(1_750.0, 1_750.0, 1_750.0) }
        }.build()

        var detectedMode: String? = null
        var completedLines = 0
        val decoder = ScottieS1Decoder(
            44_100,
            object : ScottieS1Decoder.Listener {
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

        feed(decoder, audio)
        decoder.finishCapture("TEST_EOF")

        assertEquals(null, detectedMode)
        assertEquals(0, completedLines)
    }

    @Test
    fun manualScottieS1StartsWithoutVisHeader() {
        val audio = ScottieS1SignalBuilder(sampleRateHz = 44_100).apply {
            scottieImage(
                lines = 12,
                includeStartingSync = false,
            ) { Triple(2_100.0, 1_650.0, 1_550.0) }
        }.build()

        var detectedMode: String? = null
        var completedLines = 0
        val decoder = ScottieS1Decoder(
            44_100,
            object : ScottieS1Decoder.Listener {
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

        feed(decoder, audio)
        decoder.finishCapture("TEST_EOF")

        assertTrue(detectedMode?.contains("SCOTTIE S1 RAW") == true)
        assertTrue("Expected manual S1 partial lines", completedLines > 0)
    }

    @Test
    fun completesFullScottieS1Frame() {
        val audio = ScottieS1SignalBuilder(sampleRateHz = 44_100).apply {
            tone(1_900.0, 0.100)
            visHeader(code = 60)
            scottieImage(256) { line ->
                when (line % 3) {
                    0 -> Triple(2_250.0, 1_550.0, 1_550.0)
                    1 -> Triple(1_550.0, 2_250.0, 1_550.0)
                    else -> Triple(1_550.0, 1_550.0, 2_250.0)
                }
            }
            tone(1_900.0, 0.250)
        }.build()

        var completedLines = 0
        var complete = false
        val decoder = ScottieS1Decoder(
            44_100,
            object : ScottieS1Decoder.Listener {
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

        feed(decoder, audio)
        decoder.finishCapture("TEST_EOF")

        assertEquals(256, completedLines)
        assertTrue(complete)
    }

    private fun feed(
        decoder: ScottieS1Decoder,
        audio: ShortArray,
        chunkSizes: IntArray = intArrayOf(882),
    ) {
        var offset = 0
        var chunkIndex = 0
        while (offset < audio.size) {
            val count = minOf(chunkSizes[chunkIndex % chunkSizes.size], audio.size - offset)
            decoder.process(audio.copyOfRange(offset, offset + count), count)
            offset += count
            chunkIndex += 1
        }
    }
}

private class ScottieS1SignalBuilder(
    private val sampleRateHz: Int,
) {
    private var samples = ShortArray(1 shl 16)
    private var size = 0

    fun tone(frequencyHz: Double, seconds: Double) {
        val count = (seconds * sampleRateHz).roundToInt()
        ensureCapacity(size + count)
        val start = size
        repeat(count) { index ->
            val phase = 2.0 * PI * frequencyHz * (start + index) / sampleRateHz
            samples[size++] = (sin(phase) * 14_000).roundToInt().toShort()
        }
    }

    fun visHeader(
        code: Int,
        frequencyOffsetHz: Double = 0.0,
    ) {
        tone(1_900.0 + frequencyOffsetHz, 0.300)
        tone(1_200.0 + frequencyOffsetHz, 0.010)
        tone(1_900.0 + frequencyOffsetHz, 0.300)
        tone(1_200.0 + frequencyOffsetHz, 0.030)
        var ones = 0
        repeat(7) { bit ->
            val one = code shr bit and 1 != 0
            if (one) ones += 1
            tone((if (one) 1_100.0 else 1_300.0) + frequencyOffsetHz, 0.030)
        }
        val parity = ones and 1 != 0
        tone((if (parity) 1_100.0 else 1_300.0) + frequencyOffsetHz, 0.030)
        tone(1_200.0 + frequencyOffsetHz, 0.030)
    }

    fun scottieImage(
        lines: Int,
        includeStartingSync: Boolean = true,
        colors: (Int) -> Triple<Double, Double, Double>,
    ) {
        if (includeStartingSync) {
            tone(1_200.0, 0.009)
        }
        repeat(lines) { line ->
            val (redHz, greenHz, blueHz) = colors(line)
            tone(1_500.0, 0.0015)
            tone(greenHz, 0.138240)
            tone(1_500.0, 0.0015)
            tone(blueHz, 0.138240)
            tone(1_200.0, 0.009)
            tone(1_500.0, 0.0015)
            tone(redHz, 0.138240)
        }
    }

    fun build(): ShortArray = samples.copyOf(size)

    private fun ensureCapacity(required: Int) {
        if (required <= samples.size) return
        var capacity = samples.size
        while (capacity < required) capacity *= 2
        samples = samples.copyOf(capacity)
    }
}
