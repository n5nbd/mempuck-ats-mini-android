package com.n5nbd.mempuck.atsmini.model

import java.util.Locale

data class MemoryEntry(
    val id: Long,
    val frequencyHz: Long,
    val mode: RadioMode,
    val name: String,
    val tags: String,
    val notes: String,
    val favorite: Boolean,
    val skip: Boolean,
    val sourceFile: String = USER_FREQUENCY_FILE,
) {
    val scanEnabled: Boolean
        get() = !skip
}

private val MEMORY_TAG_SEPARATOR = Regex("[\\s,]+")

fun memoryTagTokens(tags: String): List<String> = tags
    .trim()
    .split(MEMORY_TAG_SEPARATOR)
    .asSequence()
    .map { token -> token.trim().trimStart('#') }
    .filter(String::isNotEmpty)
    .map { token -> "#${token.uppercase(Locale.ROOT)}" }
    .distinct()
    .toList()

fun normalizeMemoryTags(tags: String): String = memoryTagTokens(tags).joinToString(" ")

internal fun memoryStepTarget(
    memories: List<MemoryEntry>,
    currentFrequencyHz: Long,
    direction: Int,
): MemoryEntry? {
    if (memories.isEmpty() || direction == 0) return null
    val ordered = memories.sortedWith(compareBy(MemoryEntry::frequencyHz, MemoryEntry::id))
    return if (direction > 0) {
        ordered.firstOrNull { it.frequencyHz > currentFrequencyHz } ?: ordered.first()
    } else {
        ordered.lastOrNull { it.frequencyHz < currentFrequencyHz } ?: ordered.last()
    }
}
