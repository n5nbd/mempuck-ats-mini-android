package com.n5nbd.mempuck.atsmini.img.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.n5nbd.mempuck.atsmini.img.audio.ImageAudioSource
import com.n5nbd.mempuck.atsmini.img.audio.MicrophoneImageAudioSource
import com.n5nbd.mempuck.atsmini.img.audio.PcmRingBuffer
import com.n5nbd.mempuck.atsmini.img.decoder.robot36.MartinM1Decoder
import com.n5nbd.mempuck.atsmini.img.decoder.robot36.MartinM2Decoder
import com.n5nbd.mempuck.atsmini.img.decoder.robot36.Robot36Decoder
import com.n5nbd.mempuck.atsmini.img.decoder.robot36.ScottieS1Decoder
import com.n5nbd.mempuck.atsmini.img.decoder.robot36.ScottieS2Decoder
import com.n5nbd.mempuck.atsmini.img.decoder.wefax.WefaxIoc576Decoder
import com.n5nbd.mempuck.atsmini.img.diagnostics.ImageDiagnosticLogger
import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame
import com.n5nbd.mempuck.atsmini.img.model.ImageAudioInput
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderSelection
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderSession
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderState
import com.n5nbd.mempuck.atsmini.img.model.ImageListenResult
import com.n5nbd.mempuck.atsmini.img.model.ImageSignalState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ImageDecoderRepository(
    context: Context,
    private val microphoneSource: ImageAudioSource = MicrophoneImageAudioSource(),
) {
    private enum class ActiveSstvDecoder {
        ROBOT_36,
        MARTIN_M1,
        MARTIN_M2,
        SCOTTIE_S1,
        SCOTTIE_S2,
    }

    private val applicationContext = context.applicationContext
    private val sessionLock = Any()
    private val captureStore = ImageCaptureStore(applicationContext)
    private val recoveredCapture = captureStore.loadRecovery()
    private val _state = MutableStateFlow(
        recoveredCapture?.let { recovered ->
            ImageDecoderState(
                decoder = recovered.decoder,
                signal = if (recovered.complete) {
                    ImageSignalState.COMPLETE
                } else {
                    ImageSignalState.DECODING
                },
                detectedMode = recovered.detectedMode,
                image = recovered.toFrame(revision = 1L),
                recoveredCheckpoint = true,
            )
        } ?: ImageDecoderState(),
    )
    private val diagnosticLogger = ImageDiagnosticLogger(applicationContext)

    private var robot36Decoder: Robot36Decoder? = null
    private var martinM1Decoder: MartinM1Decoder? = null
    private var martinM2Decoder: MartinM2Decoder? = null
    private var scottieS1Decoder: ScottieS1Decoder? = null
    private var scottieS2Decoder: ScottieS2Decoder? = null
    private var wefaxDecoder: WefaxIoc576Decoder? = null
    private var activeDecoder: ActiveSstvDecoder? = null
    private var sstvRearmPending = false
    private var captureBuffer: PcmRingBuffer? = null
    private var activeSampleRateHz: Int? = null
    private var frameRevision = if (recoveredCapture == null) 0L else 1L
    private var captureIdSequence = maxOf(
        System.currentTimeMillis(),
        recoveredCapture?.captureId ?: 0L,
    )
    private var currentCaptureId = recoveredCapture?.captureId ?: 0L
    private var pendingCaptureId: Long? = null
    private var lastCheckpointElapsedMs = 0L
    private val autosavePendingCaptureIds = mutableSetOf<Long>()
    private val autosavedCaptureIds = mutableSetOf<Long>()
    private var diagnosticStarted = false
    private val imageReplacementProtection = ImageReplacementProtection()

    val state: StateFlow<ImageDecoderState> = _state.asStateFlow()

    fun selectDecoder(decoder: ImageDecoderSelection) {
        synchronized(sessionLock) {
            val current = state.value
            if (current.listening) {
                // Every manual decoder, including WX, can be hot-switched without
                // interrupting AudioRecord. The new decoder starts on the next PCM block.
                if (decoder == current.decoder) return

                val sampleRateHz = activeSampleRateHz ?: current.sampleRateHz
                autosaveCurrentFrameLocked("LIVE_DECODER_SWITCH")
                imageReplacementProtection.arm(current.image?.completedLines ?: 0)
                pendingCaptureId = nextCaptureIdLocked()
                currentCaptureId = 0L
                lastCheckpointElapsedMs = 0L
                robot36Decoder = null
                martinM1Decoder = null
                martinM2Decoder = null
                scottieS1Decoder = null
                scottieS2Decoder = null
                wefaxDecoder = null
                activeDecoder = null
                sstvRearmPending = false

                _state.update {
                    if (imageReplacementProtection.active) {
                        it.copy(
                            decoder = decoder,
                            error = null,
                        )
                    } else {
                        it.copy(
                            decoder = decoder,
                            signal = ImageSignalState.WAITING,
                            detectedMode = null,
                            frequencyCorrectionHz = null,
                            decoderConfidence = 0,
                            image = null,
                            error = null,
                        )
                    }
                }

                if (sampleRateHz != null) {
                    installDecoderPipelineLocked(decoder, sampleRateHz)
                }
                diagnosticLogger.decoder(
                    "LIVE decoder_switch from=${current.decoder.label} to=${decoder.label} " +
                        "received_samples=${current.receivedSamples} " +
                        "protected_image=${imageReplacementProtection.active}",
                )
                return
            }

            imageReplacementProtection.arm(current.image?.completedLines ?: 0)
            robot36Decoder = null
            martinM1Decoder = null
            martinM2Decoder = null
            scottieS1Decoder = null
            scottieS2Decoder = null
            wefaxDecoder = null
            activeDecoder = null
            sstvRearmPending = false
            activeSampleRateHz = null

            _state.update {
                if (imageReplacementProtection.active) {
                    it.copy(
                        decoder = decoder,
                        error = null,
                    )
                } else {
                    it.copy(
                        decoder = decoder,
                        signal = ImageSignalState.WAITING,
                        detectedMode = null,
                        frequencyCorrectionHz = null,
                        decoderConfidence = 0,
                        image = null,
                        error = null,
                    )
                }
            }
        }
    }

    fun selectInput(input: ImageAudioInput) {
        if (state.value.listening) stopListening()
        _state.update { current ->
            current.copy(
                input = input,
                session = if (input.available) {
                    ImageDecoderSession.IDLE
                } else {
                    ImageDecoderSession.ERROR
                },
                error = if (input.available) null else "USB AUDIO IS NOT AVAILABLE YET",
            )
        }
    }

    fun startListening(): ImageListenResult {
        val current = state.value
        if (current.listening) return ImageListenResult.Started
        if (current.input != ImageAudioInput.MIC) {
            return fail("SELECT MIC FOR THIS TEST SLICE")
        }
        if (!hasMicrophonePermission()) {
            return ImageListenResult.PermissionRequired
        }

        synchronized(sessionLock) {
            autosaveCurrentFrameLocked("NEW_LISTEN_SESSION")
            imageReplacementProtection.arm(current.image?.completedLines ?: 0)
            pendingCaptureId = nextCaptureIdLocked()
            currentCaptureId = 0L
            lastCheckpointElapsedMs = 0L
            robot36Decoder = null
            martinM1Decoder = null
            martinM2Decoder = null
            scottieS1Decoder = null
            scottieS2Decoder = null
            wefaxDecoder = null
            activeDecoder = null
            sstvRearmPending = false
            captureBuffer = null
            activeSampleRateHz = null
            diagnosticStarted = false
            if (!imageReplacementProtection.active) {
                frameRevision += 1
            }
        }
        _state.update {
            if (imageReplacementProtection.active) {
                it.copy(
                    session = ImageDecoderSession.STARTING,
                    sampleRateHz = null,
                    receivedSamples = 0L,
                    bufferedSamples = 0,
                    recoveredCheckpoint = false,
                    error = null,
                )
            } else {
                it.copy(
                    session = ImageDecoderSession.STARTING,
                    signal = ImageSignalState.WAITING,
                    sampleRateHz = null,
                    receivedSamples = 0L,
                    bufferedSamples = 0,
                    detectedMode = null,
                    frequencyCorrectionHz = null,
                    decoderConfidence = 0,
                    image = null,
                    recoveredCheckpoint = false,
                    error = null,
                )
            }
        }

        val start = microphoneSource.start(
            onSamples = ::handleSamples,
            onError = ::handleAudioError,
        )

        return start.fold(
            onSuccess = { sampleRate ->
                ensurePipeline(sampleRate)
                _state.update {
                    it.copy(
                        session = ImageDecoderSession.LISTENING,
                        sampleRateHz = sampleRate,
                        error = null,
                    )
                }
                ImageListenResult.Started
            },
            onFailure = { failure ->
                fail(
                    failure.message?.takeIf(String::isNotBlank)
                        ?: "MIC SESSION COULD NOT START",
                )
            },
        )
    }

    fun stopListening() {
        microphoneSource.stop()
        synchronized(sessionLock) {
            finishActiveDecoderLocked("STOP_OR_LIFECYCLE")
            autosaveCurrentFrameLocked("STOP_OR_LIFECYCLE")
            sstvRearmPending = false
        }
        finishDiagnostics("STOP_OR_LIFECYCLE")
        _state.update { current ->
            val completeFrame = current.image?.let { frame ->
                frame.completedLines >= frame.height
            } == true
            current.copy(
                session = ImageDecoderSession.IDLE,
                signal = if (completeFrame) ImageSignalState.COMPLETE else current.signal,
                error = null,
            )
        }
    }

    fun clearImage() {
        synchronized(sessionLock) {
            finishActiveDecoderLocked("CLEAR")
            autosaveCurrentFrameLocked("CLEAR")
        }
        finishDiagnostics("CLEAR")
        synchronized(sessionLock) {
            robot36Decoder = null
            martinM1Decoder = null
            martinM2Decoder = null
            scottieS1Decoder = null
            scottieS2Decoder = null
            wefaxDecoder = null
            activeDecoder = null
            sstvRearmPending = false
            imageReplacementProtection.clear()
            captureBuffer?.clear()
            if (!state.value.listening) {
                captureBuffer = null
                activeSampleRateHz = null
            }
            frameRevision += 1
            currentCaptureId = 0L
            pendingCaptureId = if (state.value.listening) nextCaptureIdLocked() else null
        }
        _state.update { current ->
            current.copy(
                session = if (current.listening) current.session else ImageDecoderSession.IDLE,
                signal = ImageSignalState.WAITING,
                sampleRateHz = if (current.listening) current.sampleRateHz else null,
                receivedSamples = 0L,
                bufferedSamples = 0,
                detectedMode = null,
                frequencyCorrectionHz = null,
                decoderConfidence = 0,
                image = null,
                recoveredCheckpoint = false,
                error = null,
            )
        }
    }

    fun microphonePermissionDenied() {
        _state.update { current ->
            current.copy(
                session = ImageDecoderSession.ERROR,
                error = "MIC PERMISSION DENIED; ENABLE IT IN ANDROID APP SETTINGS",
            )
        }
    }

    /** Temporary session-local PCM retained only in memory; live mode switching does not replay it. */
    internal fun capturedPcmSnapshot(): ShortArray = synchronized(sessionLock) {
        captureBuffer?.snapshot() ?: ShortArray(0)
    }

    private fun handleSamples(
        sampleRateHz: Int,
        samples: ShortArray,
        count: Int,
    ) {
        val safeCount = count.coerceIn(0, samples.size)
        if (safeCount == 0 || !state.value.listening) return

        val bufferedSamples: Int
        synchronized(sessionLock) {
            ensurePipelineLocked(sampleRateHz)
            diagnosticLogger.audio(samples, safeCount)
            captureBuffer?.append(samples, safeCount)
            bufferedSamples = captureBuffer?.size ?: 0
            processSelectedDecodersLocked(samples, safeCount)
        }

        _state.update { active ->
            if (!active.listening) {
                active
            } else {
                active.copy(
                    session = ImageDecoderSession.LISTENING,
                    sampleRateHz = sampleRateHz,
                    receivedSamples = active.receivedSamples + safeCount,
                    bufferedSamples = bufferedSamples,
                )
            }
        }
    }

    private fun processSelectedDecodersLocked(samples: ShortArray, count: Int) {
        when (state.value.decoder) {
            ImageDecoderSelection.AUTO -> when (activeDecoder) {
                ActiveSstvDecoder.ROBOT_36 -> robot36Decoder?.process(samples, count)
                ActiveSstvDecoder.MARTIN_M1 -> martinM1Decoder?.process(samples, count)
                ActiveSstvDecoder.MARTIN_M2 -> martinM2Decoder?.process(samples, count)
                ActiveSstvDecoder.SCOTTIE_S1 -> scottieS1Decoder?.process(samples, count)
                ActiveSstvDecoder.SCOTTIE_S2 -> scottieS2Decoder?.process(samples, count)
                null -> {
                    robot36Decoder?.process(samples, count)
                    if (activeDecoder == null) {
                        martinM1Decoder?.process(samples, count)
                    }
                    if (activeDecoder == null) {
                        martinM2Decoder?.process(samples, count)
                    }
                    if (activeDecoder == null) {
                        scottieS1Decoder?.process(samples, count)
                    }
                    if (activeDecoder == null) {
                        scottieS2Decoder?.process(samples, count)
                    }
                }
            }

            ImageDecoderSelection.SSTV -> robot36Decoder?.process(samples, count)
            ImageDecoderSelection.MARTIN_M1 -> martinM1Decoder?.process(samples, count)
            ImageDecoderSelection.MARTIN_M2 -> martinM2Decoder?.process(samples, count)
            ImageDecoderSelection.SCOTTIE_S1 -> scottieS1Decoder?.process(samples, count)
            ImageDecoderSelection.SCOTTIE_S2 -> scottieS2Decoder?.process(samples, count)
            ImageDecoderSelection.WEFAX -> wefaxDecoder?.process(samples, count)
        }

        // A completed SSTV frame must remain visible and savable, but the decoder
        // claim must not remain latched. Recreate all acquisition candidates only
        // after the callback stack returns so the next transmission can choose a
        // different VIS mode without CLEAR or leaving the IMG page.
        if (sstvRearmPending) {
            rearmSstvDetectionLocked()
        }
    }

    private fun finishActiveDecoderLocked(reason: String) {
        when (state.value.decoder) {
            ImageDecoderSelection.AUTO -> when (activeDecoder) {
                ActiveSstvDecoder.ROBOT_36 -> robot36Decoder?.finishCapture(reason)
                ActiveSstvDecoder.MARTIN_M1 -> martinM1Decoder?.finishCapture(reason)
                ActiveSstvDecoder.MARTIN_M2 -> martinM2Decoder?.finishCapture(reason)
                ActiveSstvDecoder.SCOTTIE_S1 -> scottieS1Decoder?.finishCapture(reason)
                ActiveSstvDecoder.SCOTTIE_S2 -> scottieS2Decoder?.finishCapture(reason)
                null -> {
                    robot36Decoder?.finishCapture(reason)
                    martinM1Decoder?.finishCapture(reason)
                    martinM2Decoder?.finishCapture(reason)
                    scottieS1Decoder?.finishCapture(reason)
                    scottieS2Decoder?.finishCapture(reason)
                }
            }

            ImageDecoderSelection.SSTV -> robot36Decoder?.finishCapture(reason)
            ImageDecoderSelection.MARTIN_M1 -> martinM1Decoder?.finishCapture(reason)
            ImageDecoderSelection.MARTIN_M2 -> martinM2Decoder?.finishCapture(reason)
            ImageDecoderSelection.SCOTTIE_S1 -> scottieS1Decoder?.finishCapture(reason)
            ImageDecoderSelection.SCOTTIE_S2 -> scottieS2Decoder?.finishCapture(reason)
            ImageDecoderSelection.WEFAX -> wefaxDecoder?.finishCapture(reason)
        }
    }

    private fun ensurePipeline(sampleRateHz: Int) {
        synchronized(sessionLock) {
            ensurePipelineLocked(sampleRateHz)
        }
    }

    private fun ensurePipelineLocked(sampleRateHz: Int) {
        if (activeSampleRateHz == sampleRateHz && captureBuffer != null && decoderPipelineReady()) {
            return
        }
        activeSampleRateHz = sampleRateHz
        captureBuffer = PcmRingBuffer(sampleRateHz * SSTV_CAPTURE_SECONDS)
        sstvRearmPending = false
        if (!diagnosticStarted) {
            diagnosticLogger.begin(
                sampleRateHz = sampleRateHz,
                decoder = state.value.decoder.label,
                input = state.value.input.label,
            )
            diagnosticStarted = true
        }
        installDecoderPipelineLocked(state.value.decoder, sampleRateHz)
    }


    private fun installDecoderPipelineLocked(
        selection: ImageDecoderSelection,
        sampleRateHz: Int,
    ) {
        activeDecoder = when (selection) {
            ImageDecoderSelection.SSTV -> ActiveSstvDecoder.ROBOT_36
            ImageDecoderSelection.MARTIN_M1 -> ActiveSstvDecoder.MARTIN_M1
            ImageDecoderSelection.MARTIN_M2 -> ActiveSstvDecoder.MARTIN_M2
            ImageDecoderSelection.SCOTTIE_S1 -> ActiveSstvDecoder.SCOTTIE_S1
            ImageDecoderSelection.SCOTTIE_S2 -> ActiveSstvDecoder.SCOTTIE_S2
            else -> null
        }
        robot36Decoder = when (selection) {
            ImageDecoderSelection.AUTO,
            ImageDecoderSelection.SSTV,
            -> Robot36Decoder(
                sampleRateHz,
                robot36Listener(),
                selection == ImageDecoderSelection.SSTV,
            )

            else -> null
        }
        martinM1Decoder = when (selection) {
            ImageDecoderSelection.AUTO,
            ImageDecoderSelection.MARTIN_M1,
            -> MartinM1Decoder(
                sampleRateHz,
                martinM1Listener(),
                selection == ImageDecoderSelection.MARTIN_M1,
            )

            else -> null
        }
        martinM2Decoder = when (selection) {
            ImageDecoderSelection.AUTO,
            ImageDecoderSelection.MARTIN_M2,
            -> MartinM2Decoder(
                sampleRateHz,
                martinM2Listener(),
                selection == ImageDecoderSelection.MARTIN_M2,
            )

            else -> null
        }
        scottieS1Decoder = when (selection) {
            ImageDecoderSelection.AUTO,
            ImageDecoderSelection.SCOTTIE_S1,
            -> ScottieS1Decoder(
                sampleRateHz,
                scottieS1Listener(),
                selection == ImageDecoderSelection.SCOTTIE_S1,
            )

            else -> null
        }
        scottieS2Decoder = when (selection) {
            ImageDecoderSelection.AUTO,
            ImageDecoderSelection.SCOTTIE_S2,
            -> ScottieS2Decoder(
                sampleRateHz,
                scottieS2Listener(),
                selection == ImageDecoderSelection.SCOTTIE_S2,
            )

            else -> null
        }
        wefaxDecoder = when (selection) {
            ImageDecoderSelection.WEFAX -> WefaxIoc576Decoder(
                sampleRateHz,
                wefaxListener(),
            )

            else -> null
        }
    }

    private fun decoderPipelineReady(): Boolean = when (state.value.decoder) {
        ImageDecoderSelection.AUTO ->
            robot36Decoder != null && martinM1Decoder != null &&
                martinM2Decoder != null && scottieS1Decoder != null &&
                scottieS2Decoder != null
        ImageDecoderSelection.SSTV -> robot36Decoder != null
        ImageDecoderSelection.MARTIN_M1 -> martinM1Decoder != null
        ImageDecoderSelection.MARTIN_M2 -> martinM2Decoder != null
        ImageDecoderSelection.SCOTTIE_S1 -> scottieS1Decoder != null
        ImageDecoderSelection.SCOTTIE_S2 -> scottieS2Decoder != null
        ImageDecoderSelection.WEFAX -> wefaxDecoder != null
    }

    private fun rearmSstvDetectionLocked() {
        if (!sstvRearmPending || state.value.decoder == ImageDecoderSelection.WEFAX) {
            sstvRearmPending = false
            return
        }
        val sampleRateHz = activeSampleRateHz ?: run {
            sstvRearmPending = false
            return
        }

        sstvRearmPending = false
        imageReplacementProtection.arm(state.value.image?.completedLines ?: 0)
        installDecoderPipelineLocked(state.value.decoder, sampleRateHz)
        diagnosticLogger.decoder(
            "${state.value.decoder.label} rearmed after complete frame; retained displayed image and PCM buffer",
        )
    }

    private fun claimDecoder(candidate: ActiveSstvDecoder): Boolean = when (state.value.decoder) {
        ImageDecoderSelection.AUTO -> {
            if (activeDecoder == null) activeDecoder = candidate
            activeDecoder == candidate
        }

        ImageDecoderSelection.SSTV -> candidate == ActiveSstvDecoder.ROBOT_36
        ImageDecoderSelection.MARTIN_M1 -> candidate == ActiveSstvDecoder.MARTIN_M1
        ImageDecoderSelection.MARTIN_M2 -> candidate == ActiveSstvDecoder.MARTIN_M2
        ImageDecoderSelection.SCOTTIE_S1 -> candidate == ActiveSstvDecoder.SCOTTIE_S1
        ImageDecoderSelection.SCOTTIE_S2 -> candidate == ActiveSstvDecoder.SCOTTIE_S2
        ImageDecoderSelection.WEFAX -> false
    }

    private fun robot36Listener(): Robot36Decoder.Listener = object : Robot36Decoder.Listener {
        override fun onModeDetected(modeName: String) {
            modeDetected(ActiveSstvDecoder.ROBOT_36, modeName)
        }

        override fun onAdaptiveStatus(modeName: String, correctionHz: Int, confidence: Int) {
            adaptiveStatus(ActiveSstvDecoder.ROBOT_36, modeName, correctionHz, confidence)
        }

        override fun onFrame(
            width: Int,
            height: Int,
            argbPixels: IntArray,
            completedLines: Int,
            complete: Boolean,
        ) {
            decodedFrame(
                ActiveSstvDecoder.ROBOT_36,
                "ROBOT 36",
                width,
                height,
                argbPixels,
                completedLines,
                complete,
            )
        }

        override fun onDiagnostic(message: String) {
            diagnosticLogger.decoder(message)
        }

        override fun onLineTrace(
            lineNumber: Int,
            sampleIndices: IntArray,
            rawFrequenciesHz: FloatArray,
            correctedFrequenciesHz: FloatArray,
            grayValues: IntArray,
        ) {
            // The decoder invokes this callback synchronously on the AudioRecord reader
            // thread. Rendering and writing the diagnostic CSV/PNG here can overrun the
            // microphone buffer and remove samples from the live stream. Keep the compact
            // line_probe metrics in IMG-DEBUG.txt, but never perform SAF or bitmap I/O in
            // the live callback.
            diagnosticLogger.decoder(
                "ROBOT36 line_trace_skipped line=$lineNumber reason=protect_live_audio",
            )
        }

        override fun onTimeline(text: String) {
            writeTimeline("ROBOT36", text)
        }

        override fun onRawGrayscaleFrame(
            width: Int,
            height: Int,
            grayPixels: ByteArray,
            complete: Boolean,
        ) {
            writeRawFrame("ROBOT36", width, height, grayPixels, complete)
        }
    }

    private fun martinM1Listener(): MartinM1Decoder.Listener = object : MartinM1Decoder.Listener {
        override fun onModeDetected(modeName: String) {
            modeDetected(ActiveSstvDecoder.MARTIN_M1, modeName)
        }

        override fun onAdaptiveStatus(modeName: String, correctionHz: Int, confidence: Int) {
            adaptiveStatus(ActiveSstvDecoder.MARTIN_M1, modeName, correctionHz, confidence)
        }

        override fun onFrame(
            width: Int,
            height: Int,
            argbPixels: IntArray,
            completedLines: Int,
            complete: Boolean,
        ) {
            decodedFrame(
                ActiveSstvDecoder.MARTIN_M1,
                "MARTIN M1",
                width,
                height,
                argbPixels,
                completedLines,
                complete,
            )
        }

        override fun onDiagnostic(message: String) {
            diagnosticLogger.decoder(message)
        }

        override fun onTimeline(text: String) {
            writeTimeline("MARTIN1", text)
        }

        override fun onRawGrayscaleFrame(
            width: Int,
            height: Int,
            grayPixels: ByteArray,
            complete: Boolean,
        ) {
            writeRawFrame("MARTIN1", width, height, grayPixels, complete)
        }
    }

    private fun martinM2Listener(): MartinM2Decoder.Listener = object : MartinM2Decoder.Listener {
        override fun onModeDetected(modeName: String) {
            modeDetected(ActiveSstvDecoder.MARTIN_M2, modeName)
        }

        override fun onAdaptiveStatus(modeName: String, correctionHz: Int, confidence: Int) {
            adaptiveStatus(ActiveSstvDecoder.MARTIN_M2, modeName, correctionHz, confidence)
        }

        override fun onFrame(
            width: Int,
            height: Int,
            argbPixels: IntArray,
            completedLines: Int,
            complete: Boolean,
        ) {
            decodedFrame(
                ActiveSstvDecoder.MARTIN_M2,
                "MARTIN M2",
                width,
                height,
                argbPixels,
                completedLines,
                complete,
            )
        }

        override fun onDiagnostic(message: String) {
            diagnosticLogger.decoder(message)
        }

        override fun onTimeline(text: String) {
            writeTimeline("MARTIN2", text)
        }

        override fun onRawGrayscaleFrame(
            width: Int,
            height: Int,
            grayPixels: ByteArray,
            complete: Boolean,
        ) {
            writeRawFrame("MARTIN2", width, height, grayPixels, complete)
        }
    }

    private fun scottieS1Listener(): ScottieS1Decoder.Listener = object : ScottieS1Decoder.Listener {
        override fun onModeDetected(modeName: String) {
            modeDetected(ActiveSstvDecoder.SCOTTIE_S1, modeName)
        }

        override fun onAdaptiveStatus(modeName: String, correctionHz: Int, confidence: Int) {
            adaptiveStatus(ActiveSstvDecoder.SCOTTIE_S1, modeName, correctionHz, confidence)
        }

        override fun onFrame(
            width: Int,
            height: Int,
            argbPixels: IntArray,
            completedLines: Int,
            complete: Boolean,
        ) {
            decodedFrame(
                ActiveSstvDecoder.SCOTTIE_S1,
                "SCOTTIE S1",
                width,
                height,
                argbPixels,
                completedLines,
                complete,
            )
        }

        override fun onDiagnostic(message: String) {
            diagnosticLogger.decoder(message)
        }

        override fun onTimeline(text: String) {
            writeTimeline("SCOTTIE1", text)
        }

        override fun onRawGrayscaleFrame(
            width: Int,
            height: Int,
            grayPixels: ByteArray,
            complete: Boolean,
        ) {
            writeRawFrame("SCOTTIE1", width, height, grayPixels, complete)
        }
    }

    private fun scottieS2Listener(): ScottieS2Decoder.Listener = object : ScottieS2Decoder.Listener {
        override fun onModeDetected(modeName: String) {
            modeDetected(ActiveSstvDecoder.SCOTTIE_S2, modeName)
        }

        override fun onAdaptiveStatus(modeName: String, correctionHz: Int, confidence: Int) {
            adaptiveStatus(ActiveSstvDecoder.SCOTTIE_S2, modeName, correctionHz, confidence)
        }

        override fun onFrame(
            width: Int,
            height: Int,
            argbPixels: IntArray,
            completedLines: Int,
            complete: Boolean,
        ) {
            decodedFrame(
                ActiveSstvDecoder.SCOTTIE_S2,
                "SCOTTIE S2",
                width,
                height,
                argbPixels,
                completedLines,
                complete,
            )
        }

        override fun onDiagnostic(message: String) {
            diagnosticLogger.decoder(message)
        }

        override fun onTimeline(text: String) {
            writeTimeline("SCOTTIE2", text)
        }

        override fun onRawGrayscaleFrame(
            width: Int,
            height: Int,
            grayPixels: ByteArray,
            complete: Boolean,
        ) {
            writeRawFrame("SCOTTIE2", width, height, grayPixels, complete)
        }
    }

    private fun wefaxListener(): WefaxIoc576Decoder.Listener =
        object : WefaxIoc576Decoder.Listener {
            override fun onModeDetected(modeName: String) {
                if (state.value.decoder != ImageDecoderSelection.WEFAX) return
                if (imageReplacementProtection.active) {
                    imageReplacementProtection.recordMode(modeName)
                    return
                }
                _state.update { current ->
                    current.copy(
                        signal = ImageSignalState.DECODING,
                        detectedMode = modeName,
                        frequencyCorrectionHz = null,
                        decoderConfidence = 0,
                        error = null,
                    )
                }
            }

            override fun onFrame(
                width: Int,
                height: Int,
                argbPixels: IntArray,
                completedLines: Int,
                complete: Boolean,
            ) {
                decodedWefaxFrame(
                    width = width,
                    height = height,
                    argbPixels = argbPixels,
                    completedLines = completedLines,
                    complete = complete,
                )
            }

            override fun onDiagnostic(message: String) {
                diagnosticLogger.decoder(message)
            }

            override fun onPageStarted(reason: String) {
                if (state.value.decoder != ImageDecoderSelection.WEFAX) return
                beginWefaxPageLocked("WEFAX_$reason")
            }

            override fun onStopSignal(reason: String) {
                if (state.value.decoder != ImageDecoderSelection.WEFAX) return
                diagnosticLogger.decoder("WEFAX stop_signal reason=$reason LISTEN remains active")
            }
        }

    private fun modeDetected(candidate: ActiveSstvDecoder, modeName: String) {
        if (!claimDecoder(candidate)) return
        autosaveCurrentFrameLocked("NEW_VIS_HEADER")
        imageReplacementProtection.arm(state.value.image?.completedLines ?: 0)
        pendingCaptureId = nextCaptureIdLocked()
        currentCaptureId = 0L
        lastCheckpointElapsedMs = 0L
        if (imageReplacementProtection.active) {
            imageReplacementProtection.recordMode(modeName)
            return
        }
        _state.update { current ->
            current.copy(
                signal = ImageSignalState.DECODING,
                detectedMode = modeName,
                recoveredCheckpoint = false,
                error = null,
            )
        }
    }

    private fun adaptiveStatus(
        candidate: ActiveSstvDecoder,
        modeName: String,
        correctionHz: Int,
        confidence: Int,
    ) {
        if (!claimDecoder(candidate)) return
        if (imageReplacementProtection.active) {
            imageReplacementProtection.recordAdaptiveStatus(
                modeName = modeName,
                correctionHz = correctionHz,
                confidence = confidence,
            )
            return
        }
        _state.update { current ->
            current.copy(
                signal = ImageSignalState.DECODING,
                detectedMode = modeName,
                frequencyCorrectionHz = correctionHz,
                decoderConfidence = confidence.coerceIn(0, 100),
                error = null,
            )
        }
    }

    private fun decodedFrame(
        candidate: ActiveSstvDecoder,
        fallbackModeName: String,
        width: Int,
        height: Int,
        argbPixels: IntArray,
        completedLines: Int,
        complete: Boolean,
    ) {
        if (!claimDecoder(candidate)) return
        if (imageReplacementProtection.holdsFrame(completedLines)) return
        val replacementMetadata = imageReplacementProtection.releaseForFrame(completedLines)
        if (replacementMetadata != null) {
            diagnosticLogger.decoder(
                "DISPLAY protected_image_replaced candidate=$fallbackModeName " +
                    "completed_lines=$completedLines",
            )
        }
        if (complete) {
            sstvRearmPending = true
        }
        val captureId = pendingCaptureId
            ?: currentCaptureId.takeIf { it > 0L }
            ?: nextCaptureIdLocked()
        currentCaptureId = captureId
        pendingCaptureId = null
        frameRevision += 1
        val frame = DecodedImageFrame(
            width = width,
            height = height,
            argbPixels = argbPixels.copyOf(),
            completedLines = completedLines,
            revision = frameRevision,
            captureId = captureId,
        )
        _state.update { current ->
            current.copy(
                signal = if (complete) ImageSignalState.COMPLETE else ImageSignalState.DECODING,
                detectedMode = if (replacementMetadata != null) {
                    replacementMetadata.detectedMode ?: fallbackModeName
                } else {
                    current.detectedMode ?: fallbackModeName
                },
                frequencyCorrectionHz = if (replacementMetadata != null) {
                    replacementMetadata.frequencyCorrectionHz
                } else {
                    current.frequencyCorrectionHz
                },
                decoderConfidence = if (replacementMetadata != null) {
                    replacementMetadata.decoderConfidence
                } else {
                    current.decoderConfidence
                },
                image = frame,
                recoveredCheckpoint = false,
                error = null,
            )
        }
        checkpointCurrentFrameLocked(force = complete)
        if (complete) {
            autosaveCurrentFrameLocked("SSTV_COMPLETE")
        }
    }

    private fun decodedWefaxFrame(
        width: Int,
        height: Int,
        argbPixels: IntArray,
        completedLines: Int,
        complete: Boolean,
    ) {
        if (state.value.decoder != ImageDecoderSelection.WEFAX) return
        if (imageReplacementProtection.holdsFrame(completedLines)) return
        val replacementMetadata = imageReplacementProtection.releaseForFrame(completedLines)
        if (replacementMetadata != null) {
            diagnosticLogger.decoder(
                "DISPLAY protected_image_replaced candidate=WEFAX completed_lines=$completedLines",
            )
        }
        val captureId = pendingCaptureId
            ?: currentCaptureId.takeIf { it > 0L }
            ?: nextCaptureIdLocked()
        currentCaptureId = captureId
        pendingCaptureId = null
        frameRevision += 1
        val frame = DecodedImageFrame(
            width = width,
            height = height,
            // The WEFAX decoder writes only rows beyond completedLines. Sharing its
            // grow-only backing buffer avoids copying a multi-megabyte fax every 0.5 s.
            argbPixels = argbPixels,
            completedLines = completedLines,
            revision = frameRevision,
            continuous = true,
            captureId = captureId,
        )
        _state.update { current ->
            current.copy(
                signal = if (complete) ImageSignalState.COMPLETE else ImageSignalState.DECODING,
                detectedMode = replacementMetadata?.detectedMode
                    ?: current.detectedMode
                    ?: "WEFAX IOC 576 / 120 LPM",
                frequencyCorrectionHz = null,
                decoderConfidence = 0,
                image = frame,
                recoveredCheckpoint = false,
                error = null,
            )
        }
        checkpointCurrentFrameLocked(force = complete)
        if (complete) {
            autosaveCurrentFrameLocked("WEFAX_COMPLETE")
        }
    }

    private fun beginWefaxPageLocked(reason: String) {
        autosaveCurrentFrameLocked(reason)
        imageReplacementProtection.arm(state.value.image?.completedLines ?: 0)
        pendingCaptureId = nextCaptureIdLocked()
        currentCaptureId = 0L
        lastCheckpointElapsedMs = 0L
        diagnosticLogger.decoder(
            "WEFAX page_rollover reason=$reason protected_image=${imageReplacementProtection.active}",
        )
    }

    private fun nextCaptureIdLocked(): Long {
        captureIdSequence += 1L
        return captureIdSequence
    }

    private fun checkpointCurrentFrameLocked(force: Boolean) {
        val frame = state.value.image ?: return
        if (frame.completedLines <= 0 || frame.captureId <= 0L) return
        val now = SystemClock.elapsedRealtime()
        val intervalMs = if (!frame.continuous) {
            SSTV_CHECKPOINT_INTERVAL_MS
        } else when {
            frame.completedLines < 500 -> WEFAX_CHECKPOINT_INTERVAL_MS
            frame.completedLines < 1_500 -> WEFAX_LARGE_CHECKPOINT_INTERVAL_MS
            else -> WEFAX_VERY_LARGE_CHECKPOINT_INTERVAL_MS
        }
        if (!force && now - lastCheckpointElapsedMs < intervalMs) return
        val snapshot = snapshotCurrentFrameLocked() ?: return
        lastCheckpointElapsedMs = now
        captureStore.scheduleCheckpoint(snapshot)
        diagnosticLogger.decoder(
            "RECOVERY checkpoint_queued capture=${snapshot.captureId} lines=${snapshot.completedLines}",
        )
    }

    private fun autosaveCurrentFrameLocked(reason: String) {
        val frame = state.value.image ?: return
        if (!shouldAutosaveFrame(frame)) {
            diagnosticLogger.decoder(
                "AUTOSAVE discarded reason=$reason capture=${frame.captureId} " +
                    "lines=${frame.completedLines} minimum=$MinimumWefaxAutosaveLines",
            )
            return
        }
        val snapshot = snapshotCurrentFrameLocked() ?: return
        val captureId = snapshot.captureId
        if (captureId in autosavedCaptureIds || captureId in autosavePendingCaptureIds) return
        autosavePendingCaptureIds += captureId
        captureStore.scheduleCheckpoint(snapshot)
        captureStore.autosave(
            snapshot = snapshot,
            onSuccess = { result ->
                synchronized(sessionLock) {
                    autosavePendingCaptureIds -= result.captureId
                    autosavedCaptureIds += result.captureId
                    if (autosavedCaptureIds.size > 128) {
                        autosavedCaptureIds.remove(autosavedCaptureIds.minOrNull())
                    }
                }
                _state.update { current ->
                    current.copy(
                        lastAutosaveFileName = result.fileName,
                        autosaveError = null,
                    )
                }
                diagnosticLogger.decoder(
                    "AUTOSAVE complete reason=$reason capture=${result.captureId} file=${result.fileName}",
                )
            },
            onFailure = { failedCaptureId, failure ->
                synchronized(sessionLock) {
                    autosavePendingCaptureIds -= failedCaptureId
                }
                val message = failure.message ?: failure::class.java.simpleName
                _state.update { current -> current.copy(autosaveError = message) }
                diagnosticLogger.decoder(
                    "AUTOSAVE failed reason=$reason capture=$failedCaptureId error=$message",
                )
            },
        )
        diagnosticLogger.decoder(
            "AUTOSAVE queued reason=$reason capture=$captureId lines=${snapshot.completedLines}",
        )
    }

    private fun snapshotCurrentFrameLocked(): ImageCaptureSnapshot? {
        val current = state.value
        val frame = current.image ?: return null
        if (frame.completedLines <= 0 || frame.width <= 0 || frame.height <= 0) return null
        val storedHeight = if (frame.continuous) {
            frame.completedLines.coerceAtLeast(1)
        } else {
            frame.height
        }
        val pixelCountLong = frame.width.toLong() * storedHeight.toLong()
        if (pixelCountLong <= 0L || pixelCountLong > frame.argbPixels.size.toLong()) return null
        val pixelCount = pixelCountLong.toInt()
        return ImageCaptureSnapshot(
            captureId = frame.captureId.takeIf { it > 0L } ?: return null,
            decoder = current.decoder,
            detectedMode = current.detectedMode,
            width = frame.width,
            height = storedHeight,
            completedLines = frame.completedLines.coerceIn(1, storedHeight),
            continuous = frame.continuous,
            complete = current.signal == ImageSignalState.COMPLETE,
            argbPixels = frame.argbPixels.copyOf(pixelCount),
        )
    }

    private fun writeTimeline(prefix: String, text: String) {
        diagnosticLogger.writeTimeline(text)
            .onSuccess { fileName ->
                diagnosticLogger.decoder("$prefix timeline_saved file=$fileName")
            }
            .onFailure { error ->
                diagnosticLogger.decoder(
                    "$prefix timeline_failed error=${error.message ?: error::class.java.simpleName}",
                )
            }
    }

    private fun writeRawFrame(
        prefix: String,
        width: Int,
        height: Int,
        grayPixels: ByteArray,
        complete: Boolean,
    ) {
        if (!complete) return
        diagnosticLogger.writeRawPgm(width, height, grayPixels)
            .onSuccess { fileName ->
                diagnosticLogger.decoder("$prefix raw_pgm_saved file=$fileName width=$width height=$height")
            }
            .onFailure { error ->
                diagnosticLogger.decoder(
                    "$prefix raw_pgm_failed error=${error.message ?: error::class.java.simpleName}",
                )
            }
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    private fun handleAudioError(message: String) {
        microphoneSource.stop()
        synchronized(sessionLock) {
            finishActiveDecoderLocked("AUDIO_ERROR")
            autosaveCurrentFrameLocked("AUDIO_ERROR")
            sstvRearmPending = false
        }
        finishDiagnostics("AUDIO_ERROR:$message")
        _state.update { current ->
            current.copy(
                session = ImageDecoderSession.ERROR,
                error = message,
            )
        }
    }

    private fun finishDiagnostics(reason: String) {
        val shouldFinish = synchronized(sessionLock) {
            if (!diagnosticStarted) {
                false
            } else {
                diagnosticStarted = false
                true
            }
        }
        if (shouldFinish) {
            diagnosticLogger.finish(reason)
        }
    }

    private fun fail(message: String): ImageListenResult.Failed {
        _state.update { current ->
            current.copy(
                session = ImageDecoderSession.ERROR,
                error = message,
            )
        }
        return ImageListenResult.Failed(message)
    }

    private companion object {
        // Keep the existing session-local rolling capture in RAM only. This slice
        // performs no replay; live manual recovery starts with the next PCM block.
        const val SSTV_CAPTURE_SECONDS = 130
        const val SSTV_CHECKPOINT_INTERVAL_MS = 4_000L
        const val WEFAX_CHECKPOINT_INTERVAL_MS = 15_000L
        const val WEFAX_LARGE_CHECKPOINT_INTERVAL_MS = 30_000L
        const val WEFAX_VERY_LARGE_CHECKPOINT_INTERVAL_MS = 60_000L
    }
}
