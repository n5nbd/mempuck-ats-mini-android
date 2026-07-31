package com.n5nbd.mempuck.atsmini.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.n5nbd.mempuck.atsmini.ble.AtsBleClient
import com.n5nbd.mempuck.atsmini.model.AtsDevice
import com.n5nbd.mempuck.atsmini.model.AtsFrequencyPlan
import com.n5nbd.mempuck.atsmini.model.AtsFrequencyRegion
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
    private var vfoScanStepHz: Long? = null
    private var volumeTarget: Int? = null
    private var volumeWorkingValue: Int = 0

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
            setTuneFailure("No Z confirmation within 3 seconds")
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

    private val vfoScanRunnable = object : Runnable {
        override fun run() {
            val stepHz = vfoScanStepHz ?: return
            val current = _state.value
            if (current.link !is LinkState.Ready || current.capability !is CapabilityState.Supported) {
                stopVfoScanInternal("VFO scan stopped: ATS Mini is not ready")
                return
            }

            if (current.tuneState !is TuneState.Sending) {
                val nextFrequency = AtsFrequencyPlan.normalizeInteractiveFrequency(
                    currentFrequencyHz = current.targetFrequencyHz,
                    candidateFrequencyHz = current.targetFrequencyHz + stepHz,
                )
                if (nextFrequency == current.targetFrequencyHz) {
                    stopVfoScanInternal("VFO scan reached the receiver limit")
                    return
                }
                tuneFrequency(nextFrequency)
            }

            if (vfoScanStepHz != null) {
                mainHandler.postDelayed(this, SCAN_DWELL_MS)
            }
        }
    }

    private val volumeAdjustRunnable = object : Runnable {
        override fun run() {
            val target = volumeTarget ?: return
            if (_state.value.link !is LinkState.Ready) {
                stopVolumeAdjustment("Volume adjustment stopped: ATS Mini is not ready")
                return
            }
            if (volumeWorkingValue == target) {
                volumeTarget = null
                appendLog("VOLUME  $target")
                return
            }

            val command = if (target > volumeWorkingValue) {
                AtsAdHocProtocol.VOLUME_UP_COMMAND
            } else {
                AtsAdHocProtocol.VOLUME_DOWN_COMMAND
            }
            if (!client.write(command.toByteArray(Charsets.US_ASCII))) {
                stopVolumeAdjustment("BLE volume write did not start")
                return
            }

            volumeWorkingValue += if (target > volumeWorkingValue) 1 else -1
            val current = _state.value
            val status = current.status
            if (status != null) {
                _state.value = current.copy(status = status.copy(volume = volumeWorkingValue))
            }
            mainHandler.postDelayed(this, VOLUME_STEP_INTERVAL_MS)
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
        stopVfoScanInternal(null)
        stopVolumeAdjustment(null)
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

    fun tuneFrequency(frequencyHz: Long) {
        val current = _state.value
        val logicalMode = AtsFrequencyPlan.modeForFrequency(
            frequencyHz = frequencyHz,
            lastLowBandMode = current.lastLowBandMode,
        )
        if (logicalMode == null) {
            setTuneFailure(AtsFrequencyPlan.validationMessage(frequencyHz))
            return
        }
        tuneResolved(frequencyHz, logicalMode)
    }

    fun selectLowBandMode(logicalMode: RadioMode) {
        if (!logicalMode.isLowBandMode) {
            setTuneFailure("FM is selected automatically by frequency")
            return
        }
        if (AtsFrequencyPlan.regionFor(_state.value.targetFrequencyHz) != AtsFrequencyRegion.LowBand) {
            setTuneFailure("Low-band modes are available only at 30 MHz and below")
            return
        }
        tuneResolved(_state.value.targetFrequencyHz, logicalMode)
    }

    fun startVfoScan(stepHz: Long) {
        if (stepHz == 0L) return
        val current = _state.value
        if (current.link !is LinkState.Ready || current.capability !is CapabilityState.Supported) {
            setTuneFailure("ATS Mini is not ready for VFO scanning")
            return
        }
        mainHandler.removeCallbacks(vfoScanRunnable)
        vfoScanStepHz = stepHz
        _state.value = current.copy(vfoScanning = true)
        appendLog("VFO SCAN  ${if (stepHz > 0) "+" else ""}$stepHz Hz / ${SCAN_DWELL_MS} ms")
        vfoScanRunnable.run()
    }

    fun stopVfoScan() {
        stopVfoScanInternal(null)
    }

    fun setVolume(volume: Int) {
        val status = _state.value.status
        if (_state.value.link !is LinkState.Ready || status == null) {
            appendLog("VOLUME ERROR  Live ATS status is unavailable")
            return
        }
        val target = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        mainHandler.removeCallbacks(volumeAdjustRunnable)
        volumeTarget = target
        volumeWorkingValue = status.volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        if (volumeWorkingValue == target) {
            volumeTarget = null
            return
        }
        volumeAdjustRunnable.run()
    }

    private fun tuneResolved(frequencyHz: Long, logicalMode: RadioMode) {
        if (_state.value.link !is LinkState.Ready) {
            setTuneFailure("ATS Mini is not connected")
            return
        }
        if (_state.value.capability !is CapabilityState.Supported) {
            setTuneFailure("Absolute tuning is not supported by this firmware")
            return
        }

        val region = AtsFrequencyPlan.regionFor(frequencyHz)
        if (region == AtsFrequencyRegion.Unsupported) {
            setTuneFailure(AtsFrequencyPlan.validationMessage(frequencyHz))
            return
        }
        if (region == AtsFrequencyRegion.BroadcastFm && logicalMode != RadioMode.FM) {
            setTuneFailure("Broadcast FM mode is selected automatically by frequency")
            return
        }
        if (region == AtsFrequencyRegion.LowBand && !logicalMode.isLowBandMode) {
            setTuneFailure("FM is unavailable at 30 MHz and below")
            return
        }

        val command = AtsAdHocProtocol.absoluteTuneCommand(frequencyHz, logicalMode)
        pendingLogicalMode = logicalMode
        mainHandler.removeCallbacks(tuneTimeout)
        val current = _state.value
        _state.value = current.copy(
            targetFrequencyHz = frequencyHz,
            selectedMode = logicalMode,
            lastLowBandMode = if (logicalMode.isLowBandMode) logicalMode else current.lastLowBandMode,
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
        stopVfoScanInternal(null)
        stopVolumeAdjustment(null)
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
                    val current = _state.value
                    _state.value = current.copy(
                        targetFrequencyHz = event.frequencyHz,
                        selectedMode = logical,
                        lastLowBandMode = if (logical.isLowBandMode) logical else current.lastLowBandMode,
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
                    val reportedLogicalMode = when {
                        sending -> current.selectedMode
                        AtsFrequencyPlan.regionFor(event.value.frequencyHz) == AtsFrequencyRegion.BroadcastFm ->
                            RadioMode.FM
                        keepLogicalCw -> RadioMode.CW
                        else -> event.value.mode
                    }
                    val displayedStatus = if (volumeTarget != null) {
                        event.value.copy(volume = volumeWorkingValue)
                    } else {
                        event.value
                    }
                    _state.value = current.copy(
                        status = displayedStatus,
                        statusStream = StatusStreamState.Active,
                        targetFrequencyHz = if (sending) current.targetFrequencyHz else event.value.frequencyHz,
                        selectedMode = reportedLogicalMode,
                        lastLowBandMode = if (reportedLogicalMode.isLowBandMode) {
                            reportedLogicalMode
                        } else {
                            current.lastLowBandMode
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
        stopVfoScanInternal(null)
        stopVolumeAdjustment(null)
        _state.value = _state.value.copy(link = LinkState.Failed(message))
        appendLog("ERROR  $message")
    }

    private fun setTuneFailure(message: String) {
        mainHandler.removeCallbacks(tuneTimeout)
        pendingLogicalMode = null
        stopVfoScanInternal(null)
        _state.value = _state.value.copy(tuneState = TuneState.Failed(message))
        appendLog("TUNE ERROR  $message")
    }

    private fun stopVfoScanInternal(logMessage: String?) {
        mainHandler.removeCallbacks(vfoScanRunnable)
        val wasScanning = vfoScanStepHz != null || _state.value.vfoScanning
        vfoScanStepHz = null
        if (_state.value.vfoScanning) {
            _state.value = _state.value.copy(vfoScanning = false)
        }
        if (wasScanning && logMessage != null) appendLog(logMessage)
    }

    private fun stopVolumeAdjustment(logMessage: String?) {
        mainHandler.removeCallbacks(volumeAdjustRunnable)
        val wasAdjusting = volumeTarget != null
        volumeTarget = null
        if (wasAdjusting && logMessage != null) appendLog("VOLUME ERROR  $logMessage")
    }

    private fun cancelTimers() {
        mainHandler.removeCallbacks(capabilityTimeout)
        mainHandler.removeCallbacks(tuneTimeout)
        mainHandler.removeCallbacks(monitorCheck)
        mainHandler.removeCallbacks(vfoScanRunnable)
        mainHandler.removeCallbacks(volumeAdjustRunnable)
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
        const val SCAN_DWELL_MS = 1_500L
        const val VOLUME_STEP_INTERVAL_MS = 55L
        const val MIN_VOLUME = 0
        const val MAX_VOLUME = 63
    }
}
