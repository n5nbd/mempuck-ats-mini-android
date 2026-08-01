package com.n5nbd.mempuck.atsmini.data

import com.n5nbd.mempuck.atsmini.model.AtsFrequencyPlan
import com.n5nbd.mempuck.atsmini.model.AtsFrequencyRegion
import com.n5nbd.mempuck.atsmini.model.MemoryEntry
import com.n5nbd.mempuck.atsmini.model.NOW_SOURCE_FILE
import com.n5nbd.mempuck.atsmini.model.RadioMode
import com.n5nbd.mempuck.atsmini.model.normalizeMemoryTags
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

internal data class EibiScheduleRecord(
    val frequencyHz: Long,
    val timeUtc: String,
    val days: String,
    val countryCode: String,
    val station: String,
    val languageCode: String,
    val targetCode: String,
    val remarks: String,
    val startMinute: Int,
    val endMinute: Int,
)

internal data class EibiScheduleDocument(
    val records: List<EibiScheduleRecord>,
    val sourceLastUpdate: String?,
)

internal object EibiScheduleParser {
    private val timePattern = Regex("^(\\d{4})-(\\d{4})$")
    private val dayCodePattern = Regex("Mo|Tu|We|Th|Fr|Sa|Su", RegexOption.IGNORE_CASE)
    private val nonTagCharacter = Regex("[^A-Z0-9]+")
    private val digitalBroadcastPattern = Regex("\\b(?:DRM|DIGITAL)\\b", RegexOption.IGNORE_CASE)
    private val utilityStationPattern = Regex(
        "\\b(?:VOLMET|COAST\\s*GUARD|COASTGUARD|NAVTEX|HFDL|FACSIMILE|FAX|RTTY|" +
            "SITOR|NDB|BEACON|TIME\\s+SIGNAL|STANDARD\\s+FREQUENCY|MARITIME|" +
            "METEOROLOGICAL|METEO|WEATHER|FISHER(?:Y|IES)|HFGCS|USAF|RAF|NAVY|ARMY|" +
            "AIR\\s+FORCE|MILITARY|AERONAUTICAL|AVIATION)\\b|\\bWX\\b",
        RegexOption.IGNORE_CASE,
    )

    fun parse(raw: String): EibiScheduleDocument {
        val sourceLastUpdate = raw.lineSequence()
            .map(String::trim)
            .firstOrNull { it.startsWith("Last update:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf(String::isNotBlank)

        return EibiScheduleDocument(
            records = raw.lineSequence().mapNotNull(::parseLine).toList(),
            sourceLastUpdate = sourceLastUpdate,
        )
    }

    fun activeMemories(
        document: EibiScheduleDocument,
        instant: Instant,
    ): List<MemoryEntry> {
        val nowUtc = instant.atZone(ZoneOffset.UTC)
        return document.records
            .asSequence()
            .filter { record -> isActive(record, nowUtc) }
            .groupBy(EibiScheduleRecord::frequencyHz)
            .toSortedMap()
            .map { (frequencyHz, records) -> records.toNowMemory(frequencyHz) }
    }

    internal fun parseLine(line: String): EibiScheduleRecord? {
        if (line.length < MIN_DATA_LINE_LENGTH) return null

        val frequencyText = line.fixedColumn(0, 13)
        val timeText = line.fixedColumn(14, 9)
        val days = line.fixedColumn(24, 5)
        val country = line.fixedColumn(30, 3)
        val station = line.fixedColumn(34, 24)
        val language = line.fixedColumn(59, 3)
        val target = line.fixedColumn(63, 11)
        val remarks = if (line.length > 75) line.substring(75).trim() else ""

        val frequencyKhz = frequencyText.toBigDecimalOrNull() ?: return null
        val frequencyHz = runCatching {
            frequencyKhz
                .multiply(BigDecimal(1_000))
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull() ?: return null
        if (frequencyHz < EIBI_SHORTWAVE_MIN_HZ) return null
        if (AtsFrequencyPlan.regionFor(frequencyHz) != AtsFrequencyRegion.LowBand) return null

        val timeMatch = timePattern.matchEntire(timeText) ?: return null
        val startMinute = parseClockMinute(timeMatch.groupValues[1], allow2400 = false) ?: return null
        val endMinute = parseClockMinute(timeMatch.groupValues[2], allow2400 = true) ?: return null
        if (station.isBlank()) return null
        if (!isBroadcastProgramming(station, language, remarks)) return null

        return EibiScheduleRecord(
            frequencyHz = frequencyHz,
            timeUtc = timeText,
            days = days,
            countryCode = country,
            station = station,
            languageCode = language,
            targetCode = target,
            remarks = remarks,
            startMinute = startMinute,
            endMinute = endMinute,
        )
    }

    internal fun isActive(record: EibiScheduleRecord, nowUtc: ZonedDateTime): Boolean {
        val minuteInstant = nowUtc.withSecond(0).withNano(0)
        return listOf(nowUtc.toLocalDate(), nowUtc.toLocalDate().minusDays(1)).any { startDate ->
            if (!matchesDay(record.days, startDate)) return@any false
            val start = startDate.atStartOfDay(ZoneOffset.UTC).plusMinutes(record.startMinute.toLong())
            val end = when {
                record.endMinute == MINUTES_PER_DAY -> startDate.plusDays(1)
                    .atStartOfDay(ZoneOffset.UTC)
                record.endMinute > record.startMinute -> startDate.atStartOfDay(ZoneOffset.UTC)
                    .plusMinutes(record.endMinute.toLong())
                else -> startDate.plusDays(1).atStartOfDay(ZoneOffset.UTC)
                    .plusMinutes(record.endMinute.toLong())
            }
            !minuteInstant.isBefore(start) && minuteInstant.isBefore(end)
        }
    }

    internal fun matchesDay(specification: String, date: LocalDate): Boolean {
        val compact = specification.replace(" ", "").trim()
        if (compact.isBlank()) return true

        if (compact.all(Char::isDigit)) {
            return date.dayOfWeek.value.digitToChar() in compact
        }

        val calendarDate = Regex("^(\\d{1,2})([A-Za-z]{3})$")
            .matchEntire(compact)
        if (calendarDate != null) {
            val dayOfMonth = calendarDate.groupValues[1].toIntOrNull() ?: return false
            val month = monthCode(calendarDate.groupValues[2]) ?: return false
            return date.dayOfMonth == dayOfMonth && date.month == month
        }

        val ordinalMatch = Regex("^([1-5])(${dayCodePattern.pattern})$", RegexOption.IGNORE_CASE)
            .matchEntire(compact)
        if (ordinalMatch != null) {
            val ordinal = ordinalMatch.groupValues[1].toInt()
            val day = dayCode(ordinalMatch.groupValues[2]) ?: return false
            return date.dayOfWeek == day && ((date.dayOfMonth - 1) / 7 + 1) == ordinal
        }

        val lastMatch = Regex("^L(${dayCodePattern.pattern})$", RegexOption.IGNORE_CASE)
            .matchEntire(compact)
        if (lastMatch != null) {
            val day = dayCode(lastMatch.groupValues[1]) ?: return false
            return date.dayOfWeek == day &&
                date == date.with(TemporalAdjusters.lastInMonth(day))
        }

        val rangeParts = compact.split('-', limit = 2)
        if (rangeParts.size == 2) {
            val start = dayCode(rangeParts[0]) ?: return false
            val end = dayCode(rangeParts[1]) ?: return false
            val value = date.dayOfWeek.value
            return if (start.value <= end.value) {
                value in start.value..end.value
            } else {
                value >= start.value || value <= end.value
            }
        }

        val days = dayCodePattern.findAll(compact)
            .mapNotNull { match -> dayCode(match.value) }
            .toSet()
        return days.isNotEmpty() && date.dayOfWeek in days
    }

    private fun List<EibiScheduleRecord>.toNowMemory(frequencyHz: Long): MemoryEntry {
        val ordered = sortedWith(compareBy(EibiScheduleRecord::station, EibiScheduleRecord::timeUtc))
        val first = ordered.first()
        val name = if (ordered.size == 1) first.station else "${first.station} +${ordered.size - 1}"
        val tags = buildList {
            broadcastBandTag(frequencyHz)?.let(::add)
            addAll(ordered.mapNotNull { record -> countryTag(record.countryCode) }.distinct().sorted())
            addAll(ordered.mapNotNull { record -> languageTag(record.languageCode) }.distinct().sorted())
        }.joinToString(" ")

        val notes = buildString {
            ordered.forEachIndexed { index, record ->
                if (index > 0) append("\n\n")
                if (ordered.size > 1) append(record.station).append('\n')
                append(record.timeUtc).append(" UTC")
                append(" • ").append(record.days.ifBlank { "DAILY" })
                append('\n')
                append("COUNTRY: ").append(record.countryCode.ifBlank { "—" })
                append(" • LANG: ").append(record.languageCode.ifBlank { "—" })
                append(" • TARGET: ").append(record.targetCode.ifBlank { "—" })
                if (record.remarks.isNotBlank()) {
                    append('\n').append("SITE/NOTES: ").append(record.remarks)
                }
            }
            append("\n\nSOURCE: EIBI")
        }

        return MemoryEntry(
            id = frequencyHz,
            frequencyHz = frequencyHz,
            mode = RadioMode.AM,
            name = name,
            tags = normalizeMemoryTags(tags),
            notes = notes,
            favorite = false,
            skip = false,
            sourceFile = NOW_SOURCE_FILE,
        )
    }

    private fun isBroadcastProgramming(
        station: String,
        languageCode: String,
        remarks: String,
    ): Boolean {
        if (languageCode.isBlank() || languageCode.startsWith('-')) return false
        if (station.contains("JAMMER", ignoreCase = true)) return false
        if (digitalBroadcastPattern.containsMatchIn(station) ||
            digitalBroadcastPattern.containsMatchIn(remarks)
        ) {
            return false
        }
        return !utilityStationPattern.containsMatchIn(station)
    }

    private fun parseClockMinute(value: String, allow2400: Boolean): Int? {
        if (allow2400 && value == "2400") return MINUTES_PER_DAY
        val hour = value.take(2).toIntOrNull() ?: return null
        val minute = value.takeLast(2).toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    private fun monthCode(value: String): Month? = when (value.lowercase(Locale.ROOT)) {
        "jan" -> Month.JANUARY
        "feb" -> Month.FEBRUARY
        "mar" -> Month.MARCH
        "apr" -> Month.APRIL
        "may" -> Month.MAY
        "jun" -> Month.JUNE
        "jul" -> Month.JULY
        "aug" -> Month.AUGUST
        "sep" -> Month.SEPTEMBER
        "oct" -> Month.OCTOBER
        "nov" -> Month.NOVEMBER
        "dec" -> Month.DECEMBER
        else -> null
    }

    private fun dayCode(value: String): DayOfWeek? = when (value.lowercase(Locale.ROOT)) {
        "mo" -> DayOfWeek.MONDAY
        "tu" -> DayOfWeek.TUESDAY
        "we" -> DayOfWeek.WEDNESDAY
        "th" -> DayOfWeek.THURSDAY
        "fr" -> DayOfWeek.FRIDAY
        "sa" -> DayOfWeek.SATURDAY
        "su" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun countryTag(raw: String): String? {
        val code = raw.uppercase(Locale.ROOT)
            .replace(nonTagCharacter, "")
            .takeIf(String::isNotBlank)
            ?: return null
        return "#${EIBI_COUNTRY_TO_ISO3[code] ?: code}"
    }

    private fun languageTag(raw: String): String? {
        val code = raw.uppercase(Locale.ROOT)
            .replace(nonTagCharacter, "")
            .takeIf(String::isNotBlank)
            ?: return null
        val language = EIBI_LANGUAGE_NAMES[code] ?: code
        return "\$$language"
    }

    private fun broadcastBandTag(frequencyHz: Long): String? {
        val khz = frequencyHz / 1_000.0
        return when (khz) {
            in 150.0..<520.0 -> "#LW"
            in 520.0..1_710.0 -> "#MW"
            in 2_300.0..2_495.0 -> "#120M"
            in 3_200.0..3_400.0 -> "#90M"
            in 3_900.0..4_000.0 -> "#75M"
            in 4_750.0..5_060.0 -> "#60M"
            in 5_900.0..6_200.0 -> "#49M"
            in 7_200.0..7_600.0 -> "#41M"
            in 9_400.0..10_000.0 -> "#31M"
            in 11_600.0..12_100.0 -> "#25M"
            in 13_570.0..13_870.0 -> "#22M"
            in 15_100.0..15_830.0 -> "#19M"
            in 17_480.0..17_900.0 -> "#16M"
            in 18_900.0..19_020.0 -> "#15M"
            in 21_450.0..21_850.0 -> "#13M"
            in 25_600.0..26_100.0 -> "#11M"
            else -> null
        }
    }

    private fun String.fixedColumn(start: Int, width: Int): String {
        if (start >= length) return ""
        return substring(start, minOf(length, start + width)).trim()
    }

    private const val EIBI_SHORTWAVE_MIN_HZ = 2_300_000L
    private val EIBI_COUNTRY_TO_ISO3 = mapOf(
        "D" to "DEU",
        "E" to "ESP",
        "F" to "FRA",
        "G" to "GBR",
        "I" to "ITA",
        "S" to "SWE",
        "AFS" to "ZAF",
        "ALG" to "DZA",
        "ARS" to "SAU",
        "BUL" to "BGR",
        "BUR" to "MMR",
        "CLN" to "LKA",
        "HOL" to "NLD",
        "INS" to "IDN",
        "KRE" to "PRK",
        "POR" to "PRT",
        "SUI" to "CHE",
        "UAE" to "ARE",
    )

    private val EIBI_LANGUAGE_NAMES = mapOf(
        "A" to "ARABIC",
        "AL" to "ALBANIAN",
        "AM" to "AMOY",
        "B" to "BURMESE",
        "BEN" to "BENGALI",
        "C" to "CHINESE",
        "CA" to "CANTONESE",
        "D" to "DUTCH",
        "E" to "ENGLISH",
        "F" to "FRENCH",
        "G" to "GERMAN",
        "GR" to "GREEK",
        "H" to "HUNGARIAN",
        "HI" to "HINDI",
        "I" to "ITALIAN",
        "J" to "JAPANESE",
        "K" to "KOREAN",
        "P" to "PORTUGUESE",
        "R" to "RUSSIAN",
        "S" to "SPANISH",
        "T" to "TURKISH",
        "UR" to "URDU",
        "V" to "VIETNAMESE",
        "VN" to "VERNACULAR",
    )

    private const val MIN_DATA_LINE_LENGTH = 74
    private const val MINUTES_PER_DAY = 24 * 60
}
