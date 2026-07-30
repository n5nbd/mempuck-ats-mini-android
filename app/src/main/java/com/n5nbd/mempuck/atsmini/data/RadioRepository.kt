package com.n5nbd.mempuck.atsmini.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.n5nbd.mempuck.atsmini.ble.AtsBleClient
import com.n5nbd.mempuck.atsmini.model.AtsDevice
import com.n5nbd.mempuck.atsmini.model.CapabilityState
import com.n5nbd.mempuck.atsmini.model.LinkState
import com.n5nbd.mempuck.atsmini.model.RadioSnapshot
import com.n5nbd.mempuck.atsmini.protocol.AtsAdHocProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coordinates BLE transport events with ATS protocol semantics. */
class RadioRepository(context: Context) : AtsBleClient.Listener {
    private val protocol = AtsAdHocProtocol()
    private val client = AtsBleClient(context, this)
    private val devicesByAddress = linkedMapOf<String, AtsDevice>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val capabilityTimeout = Runnable {
        if (_state.value.capability == CapabilityState.Checking) {
            _state.value = _state.value.copy(
                capability = CapabilityState.Unsupported("No Z? response within 3 seconds"),
            )
            appendLog("Z? timed out")
        }
    }
    private val _state = MutableStateFlow(RadioSnapshot())

    val state: StateFlow<RadioSnapshot> = _state.asStateFlow()

    fun startScan() {
        devicesByAddress.clear()
        _state.value = _state.value.copy(devices = emptyList(), capability = CapabilityState.NotChecked)
        appendLog("Scanning for ATS Mini Ad hoc / Nordic UART devices")
        client.startScan()
    }

    fun stopScan() = client.stopScan()

    fun connect(device: AtsDevice) {
        protocol.reset()
        _state.value = _state.value.copy(capability = CapabilityState.NotChecked)
        appendLog("Connecting to ${device.name ?: device.address}")
        client.connect(device)
    }

    fun disconnect() {
        appendLog("Disconnect requested")
        client.disconnect()
    }

    fun probeCapability() {
        if (_state.value.link !is LinkState.Ready) {
            appendLog("Z? not sent: radio is not ready")
            return
        }
        mainHandler.removeCallbacks(capabilityTimeout)
        _state.value = _state.value.copy(capability = CapabilityState.Checking)
        appendLog("TX  Z?")
        if (!client.write(AtsAdHocProtocol.CAPABILITY_COMMAND.toByteArray(Charsets.US_ASCII))) {
            _state.value = _state.value.copy(
                capability = CapabilityState.Unsupported("BLE write did not start"),
            )
            appendLog("BLE write did not start")
        } else {
            mainHandler.postDelayed(capabilityTimeout, CAPABILITY_TIMEOUT_MS)
        }
    }

    override fun onScanState(scanning: Boolean) {
        _state.value = _state.value.copy(scanning = scanning)
        appendLog(if (scanning) "BLE scan started" else "BLE scan stopped")
    }

    override fun onDevice(device: AtsDevice) {
        devicesByAddress[device.address] = device
        _state.value = _state.value.copy(
            devices = devicesByAddress.values.sortedByDescending(AtsDevice::rssi),
        )
    }

    override fun onConnecting(device: AtsDevice) {
        _state.value = _state.value.copy(link = LinkState.Connecting)
    }

    override fun onReady(device: AtsDevice) {
        _state.value = _state.value.copy(link = LinkState.Ready(device))
        appendLog("BLE UART ready: ${device.name ?: device.address}")
        // Capability negotiation is the first application-level transaction.
        probeCapability()
    }

    override fun onDisconnected() {
        mainHandler.removeCallbacks(capabilityTimeout)
        _state.value = _state.value.copy(
            link = LinkState.Disconnected,
            capability = CapabilityState.NotChecked,
        )
        appendLog("BLE disconnected")
    }

    override fun onPayload(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        for (event in protocol.feed(bytes)) {
            when (event) {
                is AtsAdHocProtocol.Event.Line -> appendLog("RX  ${event.text}")
                is AtsAdHocProtocol.Event.AbsoluteTuneCapability -> {
                    mainHandler.removeCallbacks(capabilityTimeout)
                    _state.value = _state.value.copy(
                        capability = CapabilityState.Supported(event.version),
                    )
                }
                is AtsAdHocProtocol.Event.Error -> {
                    if (_state.value.capability == CapabilityState.Checking) {
                        mainHandler.removeCallbacks(capabilityTimeout)
                        _state.value = _state.value.copy(
                            capability = CapabilityState.Unsupported(event.message),
                        )
                    }
                }
            }
        }
    }

    override fun onError(message: String) {
        _state.value = _state.value.copy(link = LinkState.Failed(message))
        appendLog("ERROR  $message")
    }

    private fun appendLog(message: String) {
        val next = (_state.value.log + message).takeLast(MAX_LOG_LINES)
        _state.value = _state.value.copy(log = next)
    }

    private companion object {
        const val MAX_LOG_LINES = 200
        const val CAPABILITY_TIMEOUT_MS = 3_000L
    }
}
