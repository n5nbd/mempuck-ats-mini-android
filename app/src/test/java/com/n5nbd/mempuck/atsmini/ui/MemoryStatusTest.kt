package com.n5nbd.mempuck.atsmini.ui

import com.n5nbd.mempuck.atsmini.model.MemoryEntry
import com.n5nbd.mempuck.atsmini.model.RadioMode
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryStatusTest {
    @Test
    fun positionLabelShowsCurrentPositionAndTotal() {
        assertEquals("3 / 17", memoryPositionLabel(position = 3, total = 17))
    }

    @Test
    fun positionLabelShowsNoSelectionWithoutInventingAPosition() {
        assertEquals("— / 17", memoryPositionLabel(position = null, total = 17))
    }

    @Test
    fun flagsAppearOnlyWhenAppliedBeforeDescription() {
        val memory = MemoryEntry(
            id = 1L,
            frequencyHz = 27_185_000L,
            mode = RadioMode.AM,
            name = "CB CH 19",
            tags = "#CB",
            notes = "TRUCKER CALLING CHANNEL",
            favorite = true,
            skip = true,
        )
        assertEquals(
            "FAV • SKIP • TRUCKER CALLING CHANNEL",
            memoryFlagsAndDescription(memory),
        )
    }

    @Test
    fun absentFlagsAreNotMentioned() {
        val memory = MemoryEntry(
            id = 2L,
            frequencyHz = 27_185_000L,
            mode = RadioMode.AM,
            name = "CB CH 19",
            tags = "#CB",
            notes = "TRUCKER CALLING CHANNEL",
            favorite = false,
            skip = false,
        )
        assertEquals("TRUCKER CALLING CHANNEL", memoryFlagsAndDescription(memory))
    }
}
