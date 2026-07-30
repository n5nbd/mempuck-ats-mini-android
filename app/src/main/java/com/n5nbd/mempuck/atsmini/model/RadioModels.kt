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

data class RadioSnapshot(
    val scanning: Boolean = false,
    val devices: List<AtsDevice> = emptyList(),
    val link: LinkState = LinkState.Disconnected,
    val capability: CapabilityState = CapabilityState.NotChecked,
    val log: List<String> = emptyList(),
)
