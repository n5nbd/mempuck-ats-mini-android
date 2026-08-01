package com.n5nbd.mempuck.atsmini.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class EibiScheduleParserTest {
    @Test
    fun parsesFixedWidthEiBiRecord() {
        val document = EibiScheduleParser.parse(
            """
            Last update: Jul 31, 2026
            kHz Time(UTC) Days ITU Station Lang. Target Remarks
            ${eibiLine("9955", "2300-0100", "Fr", "ROU", "Radio Romania Int.", "E", "NAm", "g")}
            """.trimIndent(),
        )

        assertEquals("Jul 31, 2026", document.sourceLastUpdate)
        assertEquals(1, document.records.size)
        val record = document.records.single()
        assertEquals(9_955_000L, record.frequencyHz)
        assertEquals("2300-0100", record.timeUtc)
        assertEquals("Fr", record.days)
        assertEquals("ROU", record.countryCode)
        assertEquals("Radio Romania Int.", record.station)
        assertEquals("E", record.languageCode)
        assertEquals("NAm", record.targetCode)
        assertEquals("g", record.remarks)
    }

    @Test
    fun honorsDayRangesListsAndOvernightStartDay() {
        assertTrue(EibiScheduleParser.matchesDay("Mo-Fr", LocalDate.parse("2026-07-31")))
        assertFalse(EibiScheduleParser.matchesDay("Mo-Fr", LocalDate.parse("2026-08-01")))
        assertTrue(EibiScheduleParser.matchesDay("Th-Tu", LocalDate.parse("2026-08-02")))
        assertFalse(EibiScheduleParser.matchesDay("Th-Tu", LocalDate.parse("2026-08-05")))
        assertTrue(EibiScheduleParser.matchesDay("Mo,We", LocalDate.parse("2026-07-29")))
        assertTrue(EibiScheduleParser.matchesDay("135", LocalDate.parse("2026-07-31")))
        assertTrue(EibiScheduleParser.matchesDay("24Oct", LocalDate.parse("2026-10-24")))
        assertFalse(EibiScheduleParser.matchesDay("24Oct", LocalDate.parse("2026-10-25")))

        val record = EibiScheduleParser.parseLine(
            eibiLine("9955", "2300-0100", "Fr", "ROU", "Radio Romania Int.", "E", "NAm", "g"),
        )!!
        assertTrue(
            EibiScheduleParser.isActive(
                record,
                Instant.parse("2026-08-01T00:30:00Z").atZone(java.time.ZoneOffset.UTC),
            ),
        )
        assertFalse(
            EibiScheduleParser.isActive(
                record,
                Instant.parse("2026-08-01T01:00:00Z").atZone(java.time.ZoneOffset.UTC),
            ),
        )
    }

    @Test
    fun groupsActiveStationsByFrequencyAndBuildsUsefulTags() {
        val raw = listOf(
            "Last update: Jul 31, 2026",
            eibiLine("9955", "2300-0100", "Fr", "ROU", "Radio Romania Int.", "E", "NAm", "g"),
            eibiLine("9955", "0000-2400", "", "USA", "Voice of America", "E", "NAm", "g"),
        ).joinToString("\n")

        val memories = EibiScheduleParser.activeMemories(
            EibiScheduleParser.parse(raw),
            Instant.parse("2026-08-01T00:30:00Z"),
        )

        assertEquals(1, memories.size)
        val memory = memories.single()
        assertEquals(9_955_000L, memory.frequencyHz)
        assertTrue(memory.name.endsWith("+1"))
        assertTrue("#31M" in memory.tags)
        assertTrue("#ROU" in memory.tags)
        assertTrue("#USA" in memory.tags)
        assertTrue("\$ENGLISH" in memory.tags)
        assertFalse("#NOW" in memory.tags)
        assertFalse("#SWL" in memory.tags)
        assertFalse("TARGET" in memory.tags)
        assertFalse("SITE" in memory.tags)
        assertTrue(memory.notes.contains("SOURCE: EIBI"))
    }

    @Test
    fun excludesUtilityDigitalAndNonProgrammingRecords() {
        val raw = listOf(
            eibiLine("531", "1900-1930", "", "ALG", "Radio Algerie Int.", "E", "NAf", "fk"),
            eibiLine("4666", "0000-2400", "", "SNG", "Radio Singapore", "", "SEA-3", "s"),
            eibiLine("5000", "0000-2400", "", "USA", "WWV Colorado", "-TS", "USA", "c"),
            eibiLine("4724", "0000-2400", "", "USA", "USAF HFGCS", "E", "NAm", "a"),
            eibiLine("5505", "0000-2400", "", "IRL", "Shannon VOLMET", "E", "Eu", "s"),
            eibiLine("1743", "0710-0725", "", "G", "Stornoway Coastguard Wx", "E", "WEu", "st"),
            eibiLine("9950", "0000-2400", "", "D", "Test DIGITAL Service", "E", "Eu", "DRM"),
            eibiLine("9955", "2300-0100", "Fr", "ROU", "Radio Romania Int.", "E", "NAm", "g"),
        ).joinToString("\n")

        val document = EibiScheduleParser.parse(raw)

        assertEquals(1, document.records.size)
        assertEquals("Radio Romania Int.", document.records.single().station)
    }

    private fun eibiLine(
        frequency: String,
        time: String,
        days: String,
        country: String,
        station: String,
        language: String,
        target: String,
        remarks: String,
    ): String = buildString {
        append(frequency.padEnd(13))
        append(' ')
        append(time.padEnd(9))
        append(' ')
        append(days.padEnd(5))
        append(' ')
        append(country.padEnd(3))
        append(' ')
        append(station.padEnd(24))
        append(' ')
        append(language.padEnd(3))
        append(' ')
        append(target.padEnd(11))
        append(' ')
        append(remarks)
    }
}
