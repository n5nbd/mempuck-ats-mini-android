package com.n5nbd.mempuck.atsmini.img.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageReplacementProtectionTest {
    @Test
    fun emptyDisplayDoesNotArmProtection() {
        val protection = ImageReplacementProtection()

        protection.arm(completedLines = 0)

        assertFalse(protection.active)
        assertFalse(protection.holdsFrame(completedLines = 0))
        assertNull(protection.releaseForFrame(completedLines = 1))
    }

    @Test
    fun usefulImageRemainsProtectedUntilFirstReplacementLine() {
        val protection = ImageReplacementProtection()
        protection.arm(completedLines = 256)
        protection.recordMode("MARTIN M2")
        protection.recordAdaptiveStatus("MARTIN M2", correctionHz = -12, confidence = 97)

        assertTrue(protection.active)
        assertTrue(protection.holdsFrame(completedLines = 0))
        assertNull(protection.releaseForFrame(completedLines = 0))

        val metadata = protection.releaseForFrame(completedLines = 1)

        assertFalse(protection.active)
        assertEquals("MARTIN M2", metadata?.detectedMode)
        assertEquals(-12, metadata?.frequencyCorrectionHz)
        assertEquals(97, metadata?.decoderConfidence)
    }

    @Test
    fun rearmingDiscardsStaleCandidateMetadataButKeepsProtection() {
        val protection = ImageReplacementProtection()
        protection.arm(completedLines = 120)
        protection.recordAdaptiveStatus("ROBOT 36", correctionHz = 8, confidence = 92)

        protection.arm(completedLines = 120)
        val metadata = protection.releaseForFrame(completedLines = 1)

        assertFalse(protection.active)
        assertNull(metadata?.detectedMode)
        assertNull(metadata?.frequencyCorrectionHz)
        assertEquals(0, metadata?.decoderConfidence)
    }
}
