package com.n5nbd.mempuck.atsmini.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEntryTest {
    @Test
    fun tagsAreSplitOnWhitespacePrefixedAndNormalized() {
        assertEquals(
            "#POTA #PARKS #TEXAS",
            normalizeMemoryTags("  pota   #Parks texas  "),
        )
    }

    @Test
    fun repeatedHashesCommasAndDuplicatesAreCleaned() {
        assertEquals(
            listOf("#POTA", "#TEXAS"),
            memoryTagTokens("##pota, #texas POTA"),
        )
    }

    @Test
    fun tagCloudSplitsLegacyAndCanonicalTagsAndReservesFav() {
        val memories = listOf(
            entry(id = 1, tags = "POTA, TEXAS, FAV"),
            entry(id = 2, tags = "#TEXAS #AIR"),
        )

        assertEquals(
            listOf("#AIR", "#POTA", "#TEXAS"),
            com.n5nbd.mempuck.atsmini.ui.memoryTagCloud(memories),
        )
    }

    @Test
    fun noSelectedCriteriaShowsAllMemoriesIncludingSkip() {
        val memory = entry(id = 1, tags = "#POTA", skip = true)

        assertTrue(
            com.n5nbd.mempuck.atsmini.ui.memoryMatchesFilters(
                entry = memory,
                selectedTags = emptySet(),
                favoriteSelected = false,
                matchAll = false,
            ),
        )
    }

    @Test
    fun favoriteToggleNarrowsAllTagsWhenNoOrdinaryTagsAreSelected() {
        val favorite = entry(id = 1, tags = "#AIR", favorite = true)
        val ordinary = entry(id = 2, tags = "#POTA")

        assertTrue(matches(favorite, favoriteSelected = true))
        assertFalse(matches(ordinary, favoriteSelected = true))
    }

    @Test
    fun favoriteToggleNarrowsTheResultOfOrTagMatching() {
        val favoritePota = entry(id = 1, tags = "#POTA", favorite = true)
        val favoriteAir = entry(id = 2, tags = "#AIR", favorite = true)
        val ordinaryPota = entry(id = 3, tags = "#POTA")

        assertTrue(matches(favoritePota, selectedTags = setOf("#POTA"), favoriteSelected = true))
        assertFalse(matches(favoriteAir, selectedTags = setOf("#POTA"), favoriteSelected = true))
        assertFalse(matches(ordinaryPota, selectedTags = setOf("#POTA"), favoriteSelected = true))
    }

    @Test
    fun favoriteToggleNarrowsTheResultOfAndTagMatching() {
        val matching = entry(id = 1, tags = "#POTA #TEXAS", favorite = true)
        val missingFavorite = entry(id = 2, tags = "#POTA #TEXAS")
        val missingTag = entry(id = 3, tags = "#POTA", favorite = true)

        assertTrue(matches(matching, setOf("#POTA", "#TEXAS"), favoriteSelected = true, matchAll = true))
        assertFalse(matches(missingFavorite, setOf("#POTA", "#TEXAS"), favoriteSelected = true, matchAll = true))
        assertFalse(matches(missingTag, setOf("#POTA", "#TEXAS"), favoriteSelected = true, matchAll = true))
    }

    @Test
    fun manualMemoryStepIncludesSkippedEntriesAndWraps() {
        val low = entry(id = 1, frequencyHz = 1_000_000)
        val skipped = entry(id = 2, frequencyHz = 2_000_000, skip = true)
        val high = entry(id = 3, frequencyHz = 3_000_000)
        val memories = listOf(high, low, skipped)

        assertEquals(skipped, memoryStepTarget(memories, low.frequencyHz, 1))
        assertEquals(high, memoryStepTarget(memories, low.frequencyHz, -1))
        assertEquals(low, memoryStepTarget(memories, high.frequencyHz, 1))
    }

    @Test
    fun scanCandidateStepOmitsSkippedEntries() {
        val low = entry(id = 1, frequencyHz = 1_000_000)
        val skipped = entry(id = 2, frequencyHz = 2_000_000, skip = true)
        val high = entry(id = 3, frequencyHz = 3_000_000)
        val scanCandidates = listOf(low, skipped, high).filter(MemoryEntry::scanEnabled)

        assertEquals(high, memoryStepTarget(scanCandidates, low.frequencyHz, 1))
        assertEquals(low, memoryStepTarget(scanCandidates, high.frequencyHz, 1))
    }

    @Test
    fun selectedTagsDefineBothManualAndScanMemoryPools() {
        val potaLow = entry(id = 1, frequencyHz = 1_000_000, tags = "#POTA")
        val air = entry(id = 2, frequencyHz = 2_000_000, tags = "#AIR")
        val potaSkipped = entry(
            id = 3,
            frequencyHz = 3_000_000,
            tags = "#POTA",
            skip = true,
        )
        val all = listOf(potaLow, air, potaSkipped)
        val visible = all.filter { memory ->
            matches(memory, selectedTags = setOf("#POTA"))
        }

        assertEquals(listOf(potaLow, potaSkipped), visible)
        assertEquals(potaSkipped, memoryStepTarget(visible, potaLow.frequencyHz, 1))
        assertEquals(
            potaLow,
            memoryStepTarget(visible.filter(MemoryEntry::scanEnabled), potaLow.frequencyHz, 1),
        )
    }

    private fun matches(
        entry: MemoryEntry,
        selectedTags: Set<String> = emptySet(),
        favoriteSelected: Boolean = false,
        matchAll: Boolean = false,
    ) = com.n5nbd.mempuck.atsmini.ui.memoryMatchesFilters(
        entry = entry,
        selectedTags = selectedTags,
        favoriteSelected = favoriteSelected,
        matchAll = matchAll,
    )

    private fun entry(
        id: Long,
        tags: String = "",
        favorite: Boolean = false,
        skip: Boolean = false,
        frequencyHz: Long = 7_100_000,
    ) = MemoryEntry(
        id = id,
        frequencyHz = frequencyHz,
        mode = RadioMode.LSB,
        name = "TEST",
        tags = tags,
        notes = "",
        favorite = favorite,
        skip = skip,
    )

}
