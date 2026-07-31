package com.n5nbd.mempuck.atsmini.model

enum class AtsFrequencyRegion {
    LowBand,
    BroadcastFm,
    Unsupported,
}

/**
 * Receiver coverage and mode routing for the SI4732-based ATS Mini.
 *
 * The low-frequency receiver path supports AM/SSB from 150 kHz through
 * 30 MHz. The high-frequency path is broadcast FM from 64 through 108 MHz.
 * The gap between those ranges is not tunable by the receiver.
 */
object AtsFrequencyPlan {
    const val MIN_FREQUENCY_HZ = 150_000L
    const val LOW_BAND_MAX_HZ = 30_000_000L
    const val FM_BAND_MIN_HZ = 64_000_000L
    const val MAX_FREQUENCY_HZ = 108_000_000L
    const val FM_TUNING_RESOLUTION_HZ = 10_000L

    fun regionFor(frequencyHz: Long): AtsFrequencyRegion = when (frequencyHz) {
        in MIN_FREQUENCY_HZ..LOW_BAND_MAX_HZ -> AtsFrequencyRegion.LowBand
        in FM_BAND_MIN_HZ..MAX_FREQUENCY_HZ -> AtsFrequencyRegion.BroadcastFm
        else -> AtsFrequencyRegion.Unsupported
    }

    fun modeForFrequency(
        frequencyHz: Long,
        lastLowBandMode: RadioMode,
    ): RadioMode? = when (regionFor(frequencyHz)) {
        AtsFrequencyRegion.LowBand -> lastLowBandMode.takeIf { it.isLowBandMode }
            ?: RadioMode.AM
        AtsFrequencyRegion.BroadcastFm -> RadioMode.FM
        AtsFrequencyRegion.Unsupported -> null
    }

    /**
     * The ATS Mini FM backend reports and accepts frequency in 10 kHz units.
     * Keep the app's authoritative target on that same grid so returned status
     * cannot make the UI flutter between an unattainable value and the radio's
     * actual tuned frequency.
     */
    fun normalizeReceiverFrequency(frequencyHz: Long): Long =
        if (regionFor(frequencyHz) == AtsFrequencyRegion.BroadcastFm) {
            (((frequencyHz + FM_TUNING_RESOLUTION_HZ / 2L) / FM_TUNING_RESOLUTION_HZ) *
                FM_TUNING_RESOLUTION_HZ)
                .coerceIn(FM_BAND_MIN_HZ, MAX_FREQUENCY_HZ)
        } else {
            frequencyHz
        }

    /**
     * Digit and VFO controls tune immediately. When one of those controls
     * crosses the receiver's untunable 30-64 MHz gap, jump directly to the
     * next valid edge instead of leaving the UI stranded on an invalid value.
     * Direct text entry remains strict and is rejected if it lands in the gap.
     */
    fun normalizeInteractiveFrequency(
        currentFrequencyHz: Long,
        candidateFrequencyHz: Long,
    ): Long {
        val candidate = candidateFrequencyHz.coerceIn(MIN_FREQUENCY_HZ, MAX_FREQUENCY_HZ)
        if (candidate <= LOW_BAND_MAX_HZ) return candidate
        if (candidate >= FM_BAND_MIN_HZ) return normalizeReceiverFrequency(candidate)

        return if (candidate >= currentFrequencyHz) {
            FM_BAND_MIN_HZ
        } else {
            LOW_BAND_MAX_HZ
        }
    }

    fun validationMessage(frequencyHz: Long): String = when {
        frequencyHz < MIN_FREQUENCY_HZ || frequencyHz > MAX_FREQUENCY_HZ ->
            "Frequency is outside ATS Mini coverage"
        frequencyHz > LOW_BAND_MAX_HZ && frequencyHz < FM_BAND_MIN_HZ ->
            "ATS Mini cannot tune between 30 and 64 MHz"
        else -> "Unsupported frequency"
    }
}
