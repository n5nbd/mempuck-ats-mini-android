package com.n5nbd.mempuck.atsmini.model

import com.n5nbd.mempuck.atsmini.protocol.AtsAdHocProtocol

/**
 * State machine for absolute tuning through the stock ATS ad hoc protocol.
 *
 * Stock firmware exposes relative band/mode controls plus F<frequency>. The
 * transaction advances only from monitor status records so every relative bump
 * is confirmed before the next command is sent.
 */
internal class LegacyTuneTransaction(
    val frequencyHz: Long,
    val logicalMode: RadioMode,
) {
    sealed interface Decision {
        data class Send(val command: String) : Decision
        data object Wait : Decision
        data class Complete(val actualMode: RadioMode) : Decision
        data class Failed(val message: String) : Decision
    }

    private enum class CommandKind {
        Band,
        Mode,
        Frequency,
    }

    private data class Awaiting(
        val kind: CommandKind,
        val bandName: String,
        val mode: RadioMode,
        val frequencyHz: Long,
        val sequence: Int,
        var unchangedRecords: Int = 0,
    )

    private val targetBandName = when (AtsFrequencyPlan.regionFor(frequencyHz)) {
        AtsFrequencyRegion.LowBand -> LOW_BAND_NAME
        AtsFrequencyRegion.BroadcastFm -> FM_BAND_NAME
        AtsFrequencyRegion.Unsupported -> ""
    }
    private val hardwareMode = if (logicalMode == RadioMode.CW) RadioMode.USB else logicalMode

    private var bandAttempts = 0
    private var modeAttempts = 0
    private var frequencyAttempts = 0
    private var awaiting: Awaiting? = null
    private var lastSequence: Int? = null

    fun advance(status: AtsStatus): Decision {
        if (lastSequence == status.sequence) return Decision.Wait
        lastSequence = status.sequence

        if (targetBandName.isEmpty()) {
            return Decision.Failed(AtsFrequencyPlan.validationMessage(frequencyHz))
        }

        val pending = awaiting
        if (pending != null) {
            val commandApplied = when (pending.kind) {
                CommandKind.Band -> status.bandName != pending.bandName
                CommandKind.Mode -> status.mode != pending.mode
                CommandKind.Frequency ->
                    status.frequencyHz != pending.frequencyHz || status.mode != pending.mode
            }
            val targetReached = status.bandName == targetBandName &&
                status.mode == hardwareMode &&
                status.frequencyHz == frequencyHz

            if (targetReached) {
                awaiting = null
                return Decision.Complete(status.mode)
            }
            if (commandApplied) {
                awaiting = null
            } else if (status.sequence != pending.sequence) {
                // Monitor records can already be queued when a command is sent.
                // Allow several unchanged records before retrying so a delayed
                // relative command cannot accidentally advance twice.
                pending.unchangedRecords++
                if (pending.unchangedRecords < UNCHANGED_RECORDS_BEFORE_RETRY) {
                    return Decision.Wait
                }
                awaiting = null
            }
        }

        if (status.bandName != targetBandName) {
            if (bandAttempts >= MAX_BAND_ATTEMPTS) {
                return Decision.Failed("Could not select ATS band $targetBandName")
            }
            bandAttempts++
            val command = bandBumpCommand(targetBandName)
            awaiting = Awaiting(
                kind = CommandKind.Band,
                bandName = status.bandName,
                mode = status.mode,
                frequencyHz = status.frequencyHz,
                sequence = status.sequence,
            )
            return Decision.Send(command)
        }

        if (status.mode != hardwareMode) {
            if (modeAttempts >= MAX_MODE_ATTEMPTS) {
                return Decision.Failed("Could not select ATS mode ${hardwareMode.label}")
            }
            modeAttempts++
            val command = modeBumpCommand(status.mode, hardwareMode)
                ?: return Decision.Failed(
                    "ATS reported ${status.mode.label} while selecting ${hardwareMode.label}",
                )
            awaiting = Awaiting(
                kind = CommandKind.Mode,
                bandName = status.bandName,
                mode = status.mode,
                frequencyHz = status.frequencyHz,
                sequence = status.sequence,
            )
            return Decision.Send(command)
        }

        if (status.frequencyHz == frequencyHz) {
            return Decision.Complete(status.mode)
        }

        if (frequencyAttempts >= MAX_FREQUENCY_ATTEMPTS) {
            return Decision.Failed("ATS did not confirm $frequencyHz Hz")
        }
        frequencyAttempts++
        awaiting = Awaiting(
            kind = CommandKind.Frequency,
            bandName = status.bandName,
            mode = status.mode,
            frequencyHz = status.frequencyHz,
            sequence = status.sequence,
        )
        return Decision.Send(AtsAdHocProtocol.frequencyCommand(frequencyHz))
    }

    companion object {
        const val MIN_STOCK_FIRMWARE_VERSION = 234
        private const val FM_BAND_NAME = "VHF"
        private const val LOW_BAND_NAME = "ALL"
        private const val MAX_BAND_ATTEMPTS = 64
        private const val MAX_MODE_ATTEMPTS = 6
        private const val MAX_FREQUENCY_ATTEMPTS = 3
        private const val UNCHANGED_RECORDS_BEFORE_RETRY = 3

        /**
         * Cycle in one stable direction until the named target appears. ALL and
         * VHF are adjacent in the official table, while this still works with a
         * customized table because it does not depend on every intermediate name.
         */
        internal fun bandBumpCommand(targetBand: String): String =
            if (targetBand == FM_BAND_NAME) {
                AtsAdHocProtocol.PREVIOUS_BAND_COMMAND
            } else {
                AtsAdHocProtocol.NEXT_BAND_COMMAND
            }

        internal fun modeBumpCommand(currentMode: RadioMode, targetMode: RadioMode): String? {
            val currentIndex = LOW_BAND_MODE_ORDER.indexOf(currentMode)
            val targetIndex = LOW_BAND_MODE_ORDER.indexOf(targetMode)
            if (currentIndex < 0 || targetIndex < 0 || currentIndex == targetIndex) return null

            val size = LOW_BAND_MODE_ORDER.size
            val forward = (targetIndex - currentIndex + size) % size
            val backward = (currentIndex - targetIndex + size) % size
            return if (forward <= backward) {
                AtsAdHocProtocol.NEXT_MODE_COMMAND
            } else {
                AtsAdHocProtocol.PREVIOUS_MODE_COMMAND
            }
        }

        private val LOW_BAND_MODE_ORDER = listOf(RadioMode.LSB, RadioMode.USB, RadioMode.AM)
    }
}
