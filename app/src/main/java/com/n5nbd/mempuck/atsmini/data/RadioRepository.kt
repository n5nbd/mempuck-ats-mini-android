package com.n5nbd.mempuck.atsmini.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.n5nbd.mempuck.atsmini.ble.AtsBleClient
import com.n5nbd.mempuck.atsmini.model.AtsDevice
import com.n5nbd.mempuck.atsmini.model.CapabilityState
import com.n5nbd.mempuck.atsmini.model.LinkState
import com.n5nbd.mempuck.atsmini.model.RadioMode
import com.n5nbd.mempuck.atsmini.model.RadioSnapshot
import com.n5nbd.mempuck.atsmini.model.StatusStreamState
import com.n5nbd.mempuck.atsmini.model.TuneState
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
    private var statusSeenSinceReady = false
    private var pendingLogicalMode: RadioMode? = null

    private val capabilityTimeout = Runnable {
        if (_state.value.capability == CapabilityState.Checking) {
            _state.value = _state.value.copy(
                capability = CapabilityState.Unsupported("No Z? response within 3 seconds"),
            )
            appendLog("Z? timed out")
        }
    }

    private val tuneTimeout = Runnable {
        if (_state.value.tuneState is TuneState.Sending) {
            _state.value = _state.value.copy(
                tuneState = TuneState.Failed("No Z confirmation within 3 seconds"),
            )
            appendLog("Absolute tune timed out")
        }
    }

    private val monitorCheck = Runnable {
        if (_state.value.link !is LinkState.Ready || statusSeenSinceReady) return@Runnable
        _state.value = _state.value.copy(statusStream = StatusStreamState.Starting)
        appendLog("TX  t (enable ATS status monitor)")
        if (!client.write(AtsAdHocProtocol.STATUS_TOGGLE_COMMAND.toByteArray(Charsets.US_ASCII))) {
            appendLog("BLE status-monitor write did not start")
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
        cancelTimers()
        statusSeenSinceReady = false
        pendingLogicalMode = null
        _state.value = _state.value.copy(
            capability = CapabilityState.NotChecked,
            statusStream = StatusStreamState.Waiting,
            tuneState = TuneState.Idle,
        )
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

    fun tune(frequencyHz: Long, logicalMode: RadioMode) {
        if (_state.value.link !is LinkState.Ready) {
            setTuneFailure("ATS Mini is not connected")
            return
        }
        if (_state.value.capability !is CapabilityState.Supported) {
            setTuneFailure("Absolute tuning is not supported by this firmware")
            return
        }
        if (frequencyHz !in MIN_FREQUENCY_HZ..MAX_FREQUENCY_HZ) {
            setTuneFailure("Frequency is outside the ATS Mini coverage")
            return
        }

        val command = AtsAdHocProtocol.absoluteTuneCommand(frequencyHz, logicalMode)
        pendingLogicalMode = logicalMode
        mainHandler.removeCallbacks(tuneTimeout)
        _state.value = _state.value.copy(
            targetFrequencyHz = frequencyHz,
            selectedMode = logicalMode,
            tuneState = TuneState.Sending(frequencyHz, logicalMode),
        )
        appendLog("TX  ${command.trim()}")
        if (!client.write(command.toByteArray(Charsets.US_ASCII))) {
            setTuneFailure("BLE tune write did not start")
        } else {
            mainHandler.postDelayed(tuneTimeout, TUNE_TIMEOUT_MS)
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
        statusSeenSinceReady = false
        _state.value = _state.value.copy(
            link = LinkState.Ready(device),
            statusStream = StatusStreamState.Waiting,
        )
        appendLog("BLE UART ready: ${device.name ?: device.address}")
        probeCapability()
        // A previously enabled ATS monitor may already be streaming. Give it
        // enough time to produce two 500 ms records before toggling it on.
        mainHandler.removeCallbacks(monitorCheck)
        mainHandler.postDelayed(monitorCheck, MONITOR_PRESENCE_WINDOW_MS)
    }

    override fun onDisconnected() {
        cancelTimers()
        statusSeenSinceReady = false
        pendingLogicalMode = null
        _state.value = _state.value.copy(
            link = LinkState.Disconnected,
            capability = CapabilityState.NotChecked,
            statusStream = StatusStreamState.Waiting,
            tuneState = TuneState.Idle,
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

                is AtsAdHocProtocol.Event.AbsoluteTuneConfirmed -> {
                    mainHandler.removeCallbacks(tuneTimeout)
                    val logical = pendingLogicalMode ?: event.mode
                    pendingLogicalMode = null
                    _state.value = _state.value.copy(
                        targetFrequencyHz = event.frequencyHz,
                        selectedMode = logical,
                        tuneState = TuneState.Confirmed(event.frequencyHz, logical, event.mode),
                    )
                }

                is AtsAdHocProtocol.Event.Status -> {
                    statusSeenSinceReady = true
                    mainHandler.removeCallbacks(monitorCheck)
                    val current = _state.value
                    val keepLogicalCw = current.selectedMode == RadioMode.CW &&
                        event.value.mode == RadioMode.USB &&
                        event.value.frequencyHz == current.targetFrequencyHz
                    val sending = current.tuneState is TuneState.Sending
                    _state.value = current.copy(
                        status = event.value,
                        statusStream = StatusStreamState.Active,
                        targetFrequencyHz = if (sending) current.targetFrequencyHz else event.value.frequencyHz,
                        selectedMode = when {
                            sending -> current.selectedMode
                            keepLogicalCw -> RadioMode.CW
                            else -> event.value.mode
                        },
                    )
                }

                is AtsAdHocProtocol.Event.Error -> {
                    if (_state.value.capability == CapabilityState.Checking) {
                        mainHandler.removeCallbacks(capabilityTimeout)
                        _state.value = _state.value.copy(
                            capability = CapabilityState.Unsupported(event.message),
                        )
                    }
                    if (_state.value.tuneState is TuneState.Sending) {
                        mainHandler.removeCallbacks(tuneTimeout)
                        pendingLogicalMode = null
                        setTuneFailure(event.message)
                    }
                }
            }
        }
    }

    override fun onError(message: String) {
        cancelTimers()
        _state.value = _state.value.copy(link = LinkState.Failed(message))
        appendLog("ERROR  $message")
    }

    private fun setTuneFailure(message: String) {
        mainHandler.removeCallbacks(tuneTimeout)
        pendingLogicalMode = null
        _state.value = _state.value.copy(tuneState = TuneState.Failed(message))
        appendLog("TUNE ERROR  $message")
    }

    private fun cancelTimers() {
        mainHandler.removeCallbacks(capabilityTimeout)
        mainHandler.removeCallbacks(tuneTimeout)
        mainHandler.removeCallbacks(monitorCheck)
    }

    private fun appendLog(message: String) {
        val next = (_state.value.log + message).takeLast(MAX_LOG_LINES)
        _state.value = _state.value.copy(log = next)
    }

    private companion object {
        const val MAX_LOG_LINES = 200
        const val CAPABILITY_TIMEOUT_MS = 3_000L
        const val TUNE_TIMEOUT_MS = 3_000L
        const val MONITOR_PRESENCE_WINDOW_MS = 1_200L
        const val MIN_FREQUENCY_HZ = 150_000L
        const val MAX_FREQUENCY_HZ = 108_000_000L
    }
}
