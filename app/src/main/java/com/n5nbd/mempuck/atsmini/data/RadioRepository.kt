package com.n5nbd.mempuck.atsmini.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.n5nbd.mempuck.atsmini.ble.AtsBleClient
import com.n5nbd.mempuck.atsmini.model.AtsDevice
import com.n5nbd.mempuck.atsmini.model.AtsFrequencyPlan
import com.n5nbd.mempuck.atsmini.model.AtsFrequencyRegion
import com.n5nbd.mempuck.atsmini.model.AtsStatus
import com.n5nbd.mempuck.atsmini.model.CapabilityState
import com.n5nbd.mempuck.atsmini.model.LegacyTuneTransaction
import com.n5nbd.mempuck.atsmini.model.LinkState
import com.n5nbd.mempuck.atsmini.model.RadioMode
import com.n5nbd.mempuck.atsmini.model.RadioSnapshot
import com.n5nbd.mempuck.atsmini.model.StatusStreamState
import com.n5nbd.mempuck.atsmini.model.StartupReconnectStage
import com.n5nbd.mempuck.atsmini.model.TuneState
import com.n5nbd.mempuck.atsmini.model.TuningProtocol
import com.n5nbd.mempuck.atsmini.protocol.AtsAdHocProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Coordinates BLE transport events with ATS protocol semantics. */
class RadioRepository(context: Context) : AtsBleClient.Listener {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        RADIO_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val protocol = AtsAdHocProtocol()
    private val client = AtsBleClient(appContext, this)
    private val devicesByAddress = linkedMapOf<String, AtsDevice>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var statusSeenSinceReady = false
    private var zProbeFailed = false
    private var pendingLogicalMode: RadioMode? = null
    private var legacyTune: LegacyTuneTransaction? = null
    private var vfoScanStepHz: Long? = null
    private var startupAttempted = false
    private var startupTargetAddress: String? = null
    private var startupConnectingAddress: String? = null
    private var startupFlowActive = false
    private var scanDwellMs = DEFAULT_SCAN_DWELL_MS

    private val capabilityTimeout = Runnable {
        if (_state.value.capability == CapabilityState.Checking) {
            appendLog("Z? not detected; checking stock ATS protocol")
            zProbeFailed = true
            val status = _state.value.status
            if (status != null) {
                activateLegacyCapability(status)
            } else {
                mainHandler.postDelayed(legacyStatusTimeout, LEGACY_STATUS_TIMEOUT_MS)
            }
        }
    }

    private val legacyStatusTimeout = Runnable {
        if (_state.value.capability == CapabilityState.Checking && zProbeFailed) {
            _state.value = _state.value.copy(
                capability = CapabilityState.Unsupported("ATS status monitor did not start"),
            )
            appendLog("Legacy detection timed out waiting for ATS status")
            if (startupConnectingAddress != null) {
                fallBackToReceiverScan("Saved ATS Mini did not expose a compatible status stream")
            } else {
                cancelStartupFlow()
            }
        }
    }

    private val tuneTimeout = Runnable {
        if (_state.value.tuneState is TuneState.Sending) {
            val protocolName = when ((_state.value.capability as? CapabilityState.Supported)?.protocol) {
                TuningProtocol.AbsoluteZ -> "Z confirmation"
                TuningProtocol.LegacyAdHoc -> "stock-protocol status confirmation"
                null -> "tune confirmation"
            }
            setTuneFailure("No $protocolName within the allowed time")
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
            if (current.tuneState is TuneState.Sending) {
                // Confirmation owns the next dwell timer. Do not let a slow stock
                // transition consume the listener's decision time.
                return
            }

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
    }


    private val _state = MutableStateFlow(RadioSnapshot())
    val state: StateFlow<RadioSnapshot> = _state.asStateFlow()

    fun startAutoConnect() {
        if (startupAttempted) return
        startupAttempted = true

        val savedAddress = preferences.getString(LAST_VERIFIED_RADIO_ADDRESS, null)
        if (savedAddress.isNullOrBlank()) {
            appendLog("No saved ATS Mini; starting receiver scan")
            startFullScan()
            return
        }

        devicesByAddress.clear()
        startupFlowActive = true
        startupTargetAddress = savedAddress
        startupConnectingAddress = null
        _state.value = _state.value.copy(
            devices = emptyList(),
            capability = CapabilityState.NotChecked,
            startupReconnectStage = StartupReconnectStage.Looking,
        )
        appendLog("Looking for saved ATS Mini $savedAddress")
        client.startScan(
            targetAddress = savedAddress,
            windowMs = SAVED_RADIO_SCAN_WINDOW_MS,
        )
    }

    fun startScan() {
        cancelStartupFlow()
        startFullScan()
    }

    fun stopScan() {
        cancelStartupFlow()
        client.stopScan()
    }

    fun connect(device: AtsDevice) {
        cancelStartupFlow()
        connectInternal(device, startupConnection = false)
    }

    fun disconnect() {
        cancelStartupFlow()
        client.stopScan()
        appendLog("Disconnect requested")
        client.disconnect()
    }

    private fun startFullScan() {
        devicesByAddress.clear()
        _state.value = _state.value.copy(
            devices = emptyList(),
            capability = CapabilityState.NotChecked,
            startupReconnectStage = StartupReconnectStage.Idle,
        )
        appendLog("Scanning for ATS Mini Ad hoc / Nordic UART devices")
        client.startScan()
    }

    private fun connectInternal(device: AtsDevice, startupConnection: Boolean) {
        protocol.reset()
        cancelTimers()
        statusSeenSinceReady = false
        zProbeFailed = false
        pendingLogicalMode = null
        legacyTune = null
        stopVfoScanInternal(null)
        startupConnectingAddress = if (startupConnection) device.address else null
        _state.value = _state.value.copy(
            capability = CapabilityState.NotChecked,
            statusStream = StatusStreamState.Waiting,
            status = null,
            tuneState = TuneState.Idle,
            startupReconnectStage = if (startupConnection) {
                StartupReconnectStage.Connecting
            } else {
                StartupReconnectStage.Idle
            },
        )
        appendLog("Connecting to ${device.name ?: device.address}")
        client.connect(device)
    }

    private fun cancelStartupFlow() {
        startupFlowActive = false
        startupTargetAddress = null
        startupConnectingAddress = null
        if (_state.value.startupReconnectStage != StartupReconnectStage.Idle) {
            _state.value = _state.value.copy(
                startupReconnectStage = StartupReconnectStage.Idle,
            )
        }
    }

    private fun fallBackToReceiverScan(message: String) {
        if (!startupFlowActive) return
        val disconnectCurrent = startupConnectingAddress != null
        appendLog("$message; scanning for another receiver")
        startupFlowActive = false
        startupTargetAddress = null
        startupConnectingAddress = null
        _state.value = _state.value.copy(
            startupReconnectStage = StartupReconnectStage.Idle,
        )
        if (disconnectCurrent) client.disconnect()
        startFullScan()
    }

    fun probeCapability() {
        if (_state.value.link !is LinkState.Ready) {
            appendLog("Z? not sent: radio is not ready")
            return
        }
        mainHandler.removeCallbacks(capabilityTimeout)
        mainHandler.removeCallbacks(legacyStatusTimeout)
        zProbeFailed = false
        _state.value = _state.value.copy(capability = CapabilityState.Checking)
        appendLog("TX  Z?")
        if (!client.write(AtsAdHocProtocol.CAPABILITY_COMMAND.toByteArray(Charsets.US_ASCII))) {
            _state.value = _state.value.copy(
                capability = CapabilityState.Unsupported("BLE write did not start"),
            )
            appendLog("BLE write did not start")
            if (startupConnectingAddress != null) {
                fallBackToReceiverScan("Saved ATS Mini capability probe could not be sent")
            }
        } else {
            mainHandler.postDelayed(capabilityTimeout, CAPABILITY_TIMEOUT_MS)
        }
    }

    fun tuneFrequency(frequencyHz: Long) {
        val current = _state.value
        val receiverFrequencyHz = AtsFrequencyPlan.normalizeReceiverFrequency(frequencyHz)
        val logicalMode = AtsFrequencyPlan.modeForFrequency(
            frequencyHz = receiverFrequencyHz,
            lastLowBandMode = current.lastLowBandMode,
        )
        if (logicalMode == null) {
            setTuneFailure(AtsFrequencyPlan.validationMessage(frequencyHz))
            return
        }
        tuneResolved(receiverFrequencyHz, logicalMode)
    }

    fun recallMemory(frequencyHz: Long, logicalMode: RadioMode) {
        val receiverFrequencyHz = AtsFrequencyPlan.normalizeReceiverFrequency(frequencyHz)
        tuneResolved(receiverFrequencyHz, logicalMode)
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

    fun setScanDwellMs(value: Long) {
        scanDwellMs = value.coerceIn(MIN_SCAN_DWELL_MS, MAX_SCAN_DWELL_MS)
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
        appendLog("VFO SCAN  ${if (stepHz > 0) "+" else ""}$stepHz Hz / $scanDwellMs ms confirmed dwell")
        if (current.tuneState is TuneState.Sending) {
            appendLog("VFO SCAN  waiting for the current tune to confirm")
        } else {
            vfoScanRunnable.run()
        }
    }

    fun stopVfoScan() {
        stopVfoScanInternal(null)
    }

    fun setVolume(volume: Int) {
        val currentState = _state.value
        val status = currentState.status
        if (currentState.link !is LinkState.Ready || status == null) {
            appendLog("VOLUME ERROR  Live ATS status is unavailable")
            return
        }

        val target = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        val command = AtsAdHocProtocol.volumeDeltaCommand(status.volume, target)
        if (command.isEmpty()) return

        val chunks = command.chunked(BLE_SAFE_WRITE_BYTES)
        for (chunk in chunks) {
            if (!client.write(chunk.toByteArray(Charsets.US_ASCII))) {
                appendLog("VOLUME ERROR  BLE volume transaction did not start")
                return
            }
        }

        _state.value = currentState.copy(status = status.copy(volume = target))
        appendLog("VOLUME  ${status.volume} -> $target (${chunks.size} BLE write${if (chunks.size == 1) "" else "s"})")
    }

    private fun tuneResolved(frequencyHz: Long, logicalMode: RadioMode) {
        if (_state.value.link !is LinkState.Ready) {
            setTuneFailure("ATS Mini is not connected")
            return
        }
        val capability = _state.value.capability as? CapabilityState.Supported
        if (capability == null) {
            setTuneFailure("ATS tuning capability is not ready")
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

        pendingLogicalMode = logicalMode
        legacyTune = null
        mainHandler.removeCallbacks(tuneTimeout)
        val current = _state.value
        _state.value = current.copy(
            targetFrequencyHz = frequencyHz,
            selectedMode = logicalMode,
            lastLowBandMode = if (logicalMode.isLowBandMode) logicalMode else current.lastLowBandMode,
            tuneState = TuneState.Sending(frequencyHz, logicalMode),
        )

        when (capability.protocol) {
            TuningProtocol.AbsoluteZ -> {
                val command = AtsAdHocProtocol.absoluteTuneCommand(frequencyHz, logicalMode)
                appendLog("TX  ${command.trim()}")
                if (!client.write(command.toByteArray(Charsets.US_ASCII))) {
                    setTuneFailure("BLE tune write did not start")
                    return
                }
                mainHandler.postDelayed(tuneTimeout, Z_TUNE_TIMEOUT_MS)
            }

            TuningProtocol.LegacyAdHoc -> {
                val status = current.status
                if (status == null || current.statusStream != StatusStreamState.Active) {
                    setTuneFailure("Waiting for live ATS status before stock-protocol tuning")
                    return
                }
                legacyTune = LegacyTuneTransaction(frequencyHz, logicalMode)
                mainHandler.postDelayed(tuneTimeout, LEGACY_TUNE_TIMEOUT_MS)
                advanceLegacyTune(status)
            }
        }
    }

    private fun advanceLegacyTune(status: AtsStatus) {
        val transaction = legacyTune ?: return
        when (val decision = transaction.advance(status)) {
            LegacyTuneTransaction.Decision.Wait -> Unit

            is LegacyTuneTransaction.Decision.Send -> {
                appendLog("TX  ${decision.command.trim()} (stock protocol)")
                if (!client.write(decision.command.toByteArray(Charsets.US_ASCII))) {
                    setTuneFailure("BLE stock-protocol write did not start")
                }
            }

            is LegacyTuneTransaction.Decision.Complete -> {
                mainHandler.removeCallbacks(tuneTimeout)
                legacyTune = null
                val logical = pendingLogicalMode ?: transaction.logicalMode
                pendingLogicalMode = null
                val current = _state.value
                _state.value = current.copy(
                    targetFrequencyHz = transaction.frequencyHz,
                    selectedMode = logical,
                    lastLowBandMode = if (logical.isLowBandMode) logical else current.lastLowBandMode,
                    tuneState = TuneState.Confirmed(
                        transaction.frequencyHz,
                        logical,
                        decision.actualMode,
                    ),
                )
                scheduleVfoScanAfterConfirmedTune()
            }

            is LegacyTuneTransaction.Decision.Failed -> setTuneFailure(decision.message)
        }
    }

    private fun activateLegacyCapability(status: AtsStatus) {
        mainHandler.removeCallbacks(capabilityTimeout)
        mainHandler.removeCallbacks(legacyStatusTimeout)
        if (status.appVersion < LegacyTuneTransaction.MIN_STOCK_FIRMWARE_VERSION) {
            _state.value = _state.value.copy(
                capability = CapabilityState.Unsupported(
                    "Stock ATS firmware ${status.firmwareVersion} lacks the F command; v2.34 or newer is required",
                ),
            )
            appendLog("Stock ATS firmware ${status.firmwareVersion} is too old for F tuning")
            if (startupConnectingAddress != null) {
                fallBackToReceiverScan("Saved ATS Mini firmware is older than v2.34")
            } else {
                cancelStartupFlow()
            }
            return
        }

        _state.value = _state.value.copy(
            capability = CapabilityState.Supported(
                protocol = TuningProtocol.LegacyAdHoc,
                version = status.appVersion,
            ),
        )
        appendLog("Using stock ATS protocol with B/M/F status verification")
        rememberReadyReceiver()
        cancelStartupFlow()
    }

    private fun rememberReadyReceiver() {
        val readyDevice = (_state.value.link as? LinkState.Ready)?.device ?: return
        preferences.edit()
            .putString(LAST_VERIFIED_RADIO_ADDRESS, readyDevice.address)
            .apply()
        appendLog("Remembered ATS Mini ${readyDevice.address}")
    }

    override fun onScanState(scanning: Boolean) {
        _state.value = _state.value.copy(scanning = scanning)
        appendLog(if (scanning) "BLE scan started" else "BLE scan stopped")

        if (!scanning && startupFlowActive && startupTargetAddress != null) {
            fallBackToReceiverScan("Saved ATS Mini was not found")
        }
    }

    override fun onDevice(device: AtsDevice) {
        devicesByAddress[device.address] = device
        _state.value = _state.value.copy(
            devices = devicesByAddress.values.sortedByDescending(AtsDevice::rssi),
        )

        val targetAddress = startupTargetAddress
        if (startupFlowActive && targetAddress != null &&
            device.address.equals(targetAddress, ignoreCase = true)
        ) {
            startupTargetAddress = null
            appendLog("Saved ATS Mini found")
            connectInternal(device, startupConnection = true)
        }
    }

    override fun onConnecting(device: AtsDevice) {
        _state.value = _state.value.copy(link = LinkState.Connecting)
    }

    override fun onReady(device: AtsDevice) {
        statusSeenSinceReady = false
        zProbeFailed = false
        _state.value = _state.value.copy(
            link = LinkState.Ready(device),
            statusStream = StatusStreamState.Waiting,
            startupReconnectStage = if (startupConnectingAddress != null) {
                StartupReconnectStage.Verifying
            } else {
                StartupReconnectStage.Idle
            },
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
        zProbeFailed = false
        pendingLogicalMode = null
        legacyTune = null
        stopVfoScanInternal(null)
        _state.value = _state.value.copy(
            link = LinkState.Disconnected,
            // Retain the last verified protocol so the compact header can report
            // OFFLINE:FAST or OFFLINE:STOCK after a disconnect. A new connection
            // resets capability while that receiver is being verified.
            statusStream = StatusStreamState.Waiting,
            status = null,
            tuneState = TuneState.Idle,
            startupReconnectStage = StartupReconnectStage.Idle,
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
                    mainHandler.removeCallbacks(legacyStatusTimeout)
                    zProbeFailed = false
                    _state.value = _state.value.copy(
                        capability = CapabilityState.Supported(
                            protocol = TuningProtocol.AbsoluteZ,
                            version = event.version,
                        ),
                    )
                    appendLog("Using Z absolute-tune protocol v${event.version}")
                    rememberReadyReceiver()
                    cancelStartupFlow()
                }

                is AtsAdHocProtocol.Event.AbsoluteTuneConfirmed -> {
                    mainHandler.removeCallbacks(tuneTimeout)
                    legacyTune = null
                    val logical = pendingLogicalMode ?: event.mode
                    pendingLogicalMode = null
                    val current = _state.value
                    _state.value = current.copy(
                        targetFrequencyHz = event.frequencyHz,
                        selectedMode = logical,
                        lastLowBandMode = if (logical.isLowBandMode) logical else current.lastLowBandMode,
                        tuneState = TuneState.Confirmed(event.frequencyHz, logical, event.mode),
                    )
                    scheduleVfoScanAfterConfirmedTune()
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
                    _state.value = current.copy(
                        status = event.value,
                        statusStream = StatusStreamState.Active,
                        targetFrequencyHz = if (sending) current.targetFrequencyHz else event.value.frequencyHz,
                        selectedMode = reportedLogicalMode,
                        lastLowBandMode = if (reportedLogicalMode.isLowBandMode) {
                            reportedLogicalMode
                        } else {
                            current.lastLowBandMode
                        },
                    )
                    if (zProbeFailed && _state.value.capability == CapabilityState.Checking) {
                        activateLegacyCapability(event.value)
                    }
                    advanceLegacyTune(event.value)
                }

                is AtsAdHocProtocol.Event.Error -> {
                    if (_state.value.capability == CapabilityState.Checking) {
                        mainHandler.removeCallbacks(capabilityTimeout)
                        appendLog("Z probe rejected; checking stock ATS protocol")
                        zProbeFailed = true
                        val status = _state.value.status
                        if (status != null) {
                            activateLegacyCapability(status)
                        } else {
                            mainHandler.postDelayed(legacyStatusTimeout, LEGACY_STATUS_TIMEOUT_MS)
                        }
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
        if (startupFlowActive && startupConnectingAddress != null) {
            fallBackToReceiverScan("Saved ATS Mini connection failed")
            return
        }
        if (_state.value.scanning) {
            appendLog("AUTO-CONNECT  $message; fallback scan is active")
            return
        }
        cancelStartupFlow()
        _state.value = _state.value.copy(link = LinkState.Failed(message))
        appendLog("ERROR  $message")
    }

    private fun scheduleVfoScanAfterConfirmedTune() {
        if (vfoScanStepHz == null) return
        mainHandler.removeCallbacks(vfoScanRunnable)
        mainHandler.postDelayed(vfoScanRunnable, scanDwellMs)
    }

    private fun setTuneFailure(message: String) {
        mainHandler.removeCallbacks(tuneTimeout)
        pendingLogicalMode = null
        legacyTune = null
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


    private fun cancelTimers() {
        mainHandler.removeCallbacks(capabilityTimeout)
        mainHandler.removeCallbacks(legacyStatusTimeout)
        mainHandler.removeCallbacks(tuneTimeout)
        mainHandler.removeCallbacks(monitorCheck)
        mainHandler.removeCallbacks(vfoScanRunnable)
    }

    private fun appendLog(message: String) {
        val next = (_state.value.log + message).takeLast(MAX_LOG_LINES)
        _state.value = _state.value.copy(log = next)
    }

    private companion object {
        const val MAX_LOG_LINES = 200
        const val RADIO_PREFERENCES = "mempuck-radio"
        const val LAST_VERIFIED_RADIO_ADDRESS = "lastVerifiedRadioAddress"
        const val SAVED_RADIO_SCAN_WINDOW_MS = 5_000L
        const val CAPABILITY_TIMEOUT_MS = 3_000L
        const val LEGACY_STATUS_TIMEOUT_MS = 5_000L
        const val Z_TUNE_TIMEOUT_MS = 3_000L
        const val LEGACY_TUNE_TIMEOUT_MS = 45_000L
        const val MONITOR_PRESENCE_WINDOW_MS = 1_200L
        const val DEFAULT_SCAN_DWELL_MS = 2_000L
        const val MIN_SCAN_DWELL_MS = 1_000L
        const val MAX_SCAN_DWELL_MS = 10_000L
        const val BLE_SAFE_WRITE_BYTES = 20
        const val MIN_VOLUME = 0
        const val MAX_VOLUME = 63
    }
}
