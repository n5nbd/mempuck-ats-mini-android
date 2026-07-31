package com.n5nbd.mempuck.atsmini.model

data class AtsDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
)

sealed interface LinkState {
    data object Disconnected : LinkState
    data object Connecting : LinkState
    data class Ready(val device: AtsDevice) : LinkState
    data class Failed(val message: String) : LinkState
}

sealed interface CapabilityState {
    data object NotChecked : CapabilityState
    data object Checking : CapabilityState
    data class Supported(val version: Int) : CapabilityState
    data class Unsupported(val detail: String) : CapabilityState
}

enum class RadioMode(
    val label: String,
    val atsMode: String,
) {
    LSB("LSB", "LSB"),
    USB("USB", "USB"),
    CW("CW", "USB"),
    AM("AM", "AM"),
    FM("FM", "FM"),
    ;

    companion object {
        fun fromAts(value: String): RadioMode? = when (value.uppercase()) {
            "LSB" -> LSB
            "USB" -> USB
            "AM" -> AM
            "FM" -> FM
            else -> null
        }
    }
}

data class AtsStatus(
    val appVersion: Int,
    val frequencyHz: Long,
    val bfoHz: Int,
    val calibrationHz: Int,
    val bandName: String,
    val mode: RadioMode,
    val step: String,
    val bandwidth: String,
    val agcIndex: Int,
    val volume: Int,
    val rssi: Int,
    val snr: Int,
    val tuningCapacitor: Int,
    val voltage: Float,
    val sequence: Int,
) {
    val firmwareVersion: String
        get() = "${appVersion / 100}.${(appVersion % 100).toString().padStart(2, '0')}"
}

sealed interface TuneState {
    data object Idle : TuneState
    data class Sending(val frequencyHz: Long, val logicalMode: RadioMode) : TuneState
    data class Confirmed(
        val frequencyHz: Long,
        val logicalMode: RadioMode,
        val actualMode: RadioMode,
    ) : TuneState
    data class Failed(val message: String) : TuneState
}

enum class StatusStreamState {
    Waiting,
    Starting,
    Active,
}

data class RadioSnapshot(
    val scanning: Boolean = false,
    val devices: List<AtsDevice> = emptyList(),
    val link: LinkState = LinkState.Disconnected,
    val capability: CapabilityState = CapabilityState.NotChecked,
    val statusStream: StatusStreamState = StatusStreamState.Waiting,
    val status: AtsStatus? = null,
    val targetFrequencyHz: Long = 7_074_000L,
    val selectedMode: RadioMode = RadioMode.USB,
    val tuneState: TuneState = TuneState.Idle,
    val log: List<String> = emptyList(),
)
