package com.n5nbd.mempuck.atsmini.protocol

import com.n5nbd.mempuck.atsmini.model.AtsStatus
import com.n5nbd.mempuck.atsmini.model.RadioMode

/**
 * Stream parser for the ATS Mini Ad hoc text protocol.
 *
 * BLE notifications may split or combine text at arbitrary byte boundaries, so
 * protocol recognition operates on completed CR/LF-delimited lines. The parser
 * recognizes the MemPuck absolute-tune extension as well as the stock ATS Mini
 * 15-column monitor stream.
 */
class AtsAdHocProtocol {
    private val pending = StringBuilder()

    sealed interface Event {
        data class Line(val text: String) : Event
        data class AbsoluteTuneCapability(val version: Int) : Event
        data class AbsoluteTuneConfirmed(val frequencyHz: Long, val mode: RadioMode) : Event
        data class Status(val value: AtsStatus) : Event
        data class Error(val message: String) : Event
    }

    @Synchronized
    fun feed(bytes: ByteArray): List<Event> {
        pending.append(bytes.toString(Charsets.UTF_8))
        val events = mutableListOf<Event>()

        while (true) {
            val terminator = pending.indexOfFirst { it == '\r' || it == '\n' }
            if (terminator < 0) break

            val line = pending.substring(0, terminator).trim()
            var consumed = terminator + 1
            while (consumed < pending.length &&
                (pending[consumed] == '\r' || pending[consumed] == '\n')
            ) {
                consumed++
            }
            pending.delete(0, consumed)

            if (line.isBlank()) continue

            val status = parseStatus(line)
            if (status != null) {
                // The monitor emits a line every 500 ms. Keep it out of the
                // human protocol log while still publishing parsed status.
                events += Event.Status(status)
                continue
            }

            events += Event.Line(line)
            val capability = parseCapability(line)
            if (capability != null) {
                events += capability
                continue
            }
            val confirmation = parseTuneConfirmation(line)
            if (confirmation != null) {
                events += confirmation
                continue
            }
            if (line.startsWith("Error:")) {
                events += Event.Error(line.removePrefix("Error:").trim())
            }
        }

        return events
    }

    @Synchronized
    fun reset() {
        pending.clear()
    }

    private fun parseCapability(line: String): Event.AbsoluteTuneCapability? {
        val match = CAPABILITY.matchEntire(line) ?: return null
        return Event.AbsoluteTuneCapability(match.groupValues[1].toInt())
    }

    private fun parseTuneConfirmation(line: String): Event.AbsoluteTuneConfirmed? {
        val match = TUNE_CONFIRMATION.matchEntire(line) ?: return null
        val frequency = match.groupValues[1].toLongOrNull() ?: return null
        val mode = RadioMode.fromAts(match.groupValues[2]) ?: return null
        return Event.AbsoluteTuneConfirmed(frequency, mode)
    }

    private fun parseStatus(line: String): AtsStatus? {
        val fields = line.split(',')
        if (fields.size != STATUS_FIELD_COUNT) return null

        val appVersion = fields[0].toIntOrNull() ?: return null
        val currentFrequency = fields[1].toLongOrNull() ?: return null
        val bfo = fields[2].toIntOrNull() ?: return null
        val calibration = fields[3].toIntOrNull() ?: return null
        val mode = RadioMode.fromAts(fields[5]) ?: return null
        val agcIndex = fields[8].toIntOrNull() ?: return null
        val volume = fields[9].toIntOrNull() ?: return null
        val rssi = fields[10].toIntOrNull() ?: return null
        val snr = fields[11].toIntOrNull() ?: return null
        val capacitor = fields[12].toIntOrNull() ?: return null
        val voltage = fields[13].toFloatOrNull() ?: return null
        val sequence = fields[14].toIntOrNull() ?: return null

        val frequencyHz = when (mode) {
            RadioMode.FM -> currentFrequency * 10_000L
            RadioMode.LSB, RadioMode.USB -> currentFrequency * 1_000L + bfo
            RadioMode.AM -> currentFrequency * 1_000L
            RadioMode.CW -> return null // ATS never reports CW as a hardware mode.
        }

        return AtsStatus(
            appVersion = appVersion,
            frequencyHz = frequencyHz,
            bfoHz = bfo,
            calibrationHz = calibration,
            bandName = fields[4],
            mode = mode,
            step = fields[6],
            bandwidth = fields[7],
            agcIndex = agcIndex,
            volume = volume,
            rssi = rssi,
            snr = snr,
            tuningCapacitor = capacitor,
            voltage = voltage,
            sequence = sequence,
        )
    }

    companion object {
        const val CAPABILITY_COMMAND = "Z?\r"
        const val STATUS_TOGGLE_COMMAND = "t"

        fun absoluteTuneCommand(frequencyHz: Long, mode: RadioMode): String =
            "Z$frequencyHz,${mode.atsMode}\r"

        private const val STATUS_FIELD_COUNT = 15
        private val CAPABILITY = Regex("OK,Z,(\\d+)")
        private val TUNE_CONFIRMATION = Regex("OK,Z,(\\d+),(FM|AM|LSB|USB)")
    }
}
