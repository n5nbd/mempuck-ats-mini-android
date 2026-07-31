package com.n5nbd.mempuck.atsmini.ui

fun formatFrequencyHz(frequencyHz: Long): String {
    val digits = frequencyHz.toString().padStart(if (frequencyHz >= 100_000_000L) 9 else 8, '0')
    val firstGroup = digits.length % 3
    val parts = mutableListOf<String>()
    var index = 0
    if (firstGroup > 0) {
        parts += digits.substring(0, firstGroup)
        index = firstGroup
    }
    while (index < digits.length) {
        parts += digits.substring(index, index + 3)
        index += 3
    }
    return parts.joinToString(".")
}

fun parseFrequencyText(text: String): Long? {
    val normalized = text.trim().replace(" ", "").replace(',', '.')
    if (normalized.isBlank()) return null

    // The MemPuck display uses dots as thousands separators (07.074.000).
    if (normalized.count { it == '.' } >= 2) {
        return normalized.replace(".", "").toLongOrNull()
    }

    // A single decimal point is convenient shorthand for MHz (7.074).
    if (normalized.count { it == '.' } == 1) {
        return normalized.toDoubleOrNull()?.let { (it * 1_000_000.0).toLong() }
    }

    return normalized.toLongOrNull()
}
