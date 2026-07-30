package com.n5nbd.mempuck.atsmini.protocol

/**
 * Stream parser for the ATS Mini Ad hoc text protocol.
 *
 * BLE notifications may split or combine text at arbitrary byte boundaries, so
 * protocol recognition must operate on completed CR/LF-delimited lines rather
 * than assuming one notification equals one response. Echoed commands and blank
 * lines are retained for diagnostics but do not affect capability detection.
 */
class AtsAdHocProtocol {
    private val pending = StringBuilder()

    sealed interface Event {
        data class Line(val text: String) : Event
        data class AbsoluteTuneCapability(val version: Int) : Event
        data class Error(val message: String) : Event
    }

    @Synchronized
    fun feed(bytes: ByteArray): List<Event> {
        pending.append(bytes.toString(Charsets.UTF_8))
        val events = mutableListOf<Event>()

        while (true) {
            val terminator = pending.indexOfFirst { it == '\r' || it == '\n' }
            if (terminator < 0) break

            val line = pending.substring(0, terminator)
            var consumed = terminator + 1
            while (consumed < pending.length &&
                (pending[consumed] == '\r' || pending[consumed] == '\n')
            ) {
                consumed++
            }
            pending.delete(0, consumed)

            if (line.isBlank()) continue
            events += Event.Line(line)

            val capability = CAPABILITY.matchEntire(line)
            if (capability != null) {
                events += Event.AbsoluteTuneCapability(capability.groupValues[1].toInt())
            } else if (line.startsWith("Error:")) {
                events += Event.Error(line.removePrefix("Error:").trim())
            }
        }

        return events
    }

    @Synchronized
    fun reset() {
        pending.clear()
    }

    companion object {
        const val CAPABILITY_COMMAND = "Z?\r"
        private val CAPABILITY = Regex("OK,Z,(\\d+)")
    }
}
