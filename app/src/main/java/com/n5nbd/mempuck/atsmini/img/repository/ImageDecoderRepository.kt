package com.n5nbd.mempuck.atsmini.img.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.n5nbd.mempuck.atsmini.img.audio.ImageAudioSource
import com.n5nbd.mempuck.atsmini.img.audio.MicrophoneImageAudioSource
import com.n5nbd.mempuck.atsmini.img.audio.PcmRingBuffer
import com.n5nbd.mempuck.atsmini.img.decoder.robot36.MartinM1Decoder
import com.n5nbd.mempuck.atsmini.img.decoder.robot36.MartinM2Decoder
import com.n5nbd.mempuck.atsmini.img.decoder.robot36.Robot36Decoder
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
    }

    private val applicationContext = context.applicationContext
    private val sessionLock = Any()
    private val _state = MutableStateFlow(ImageDecoderState())
    private val diagnosticLogger = ImageDiagnosticLogger(applicationContext)

    private var robot36Decoder: Robot36Decoder? = null
    private var martinM1Decoder: MartinM1Decoder? = null
    private var martinM2Decoder: MartinM2Decoder? = null
    private var activeDecoder: ActiveSstvDecoder? = null
    private var autoRearmPending = false
    private var captureBuffer: PcmRingBuffer? = null
    private var activeSampleRateHz: Int? = null
    private var frameRevision = 0L
    private var diagnosticStarted = false
    private val imageReplacementProtection = ImageReplacementProtection()

    val state: StateFlow<ImageDecoderState> = _state.asStateFlow()

    fun selectDecoder(decoder: ImageDecoderSelection) {
        synchronized(sessionLock) {
            val current = state.value
            if (current.listening) {
                // WEFAX is not a live decoder in this slice. AUTO, R36, M1, and M2
                // can be hot-switched without interrupting AudioRecord.
                if (decoder == ImageDecoderSelection.WEFAX || decoder == current.decoder) return

                val sampleRateHz = activeSampleRateHz ?: current.sampleRateHz
                imageReplacementProtection.arm(current.image?.completedLines ?: 0)
                robot36Decoder = null
                martinM1Decoder = null
                martinM2Decoder = null
                activeDecoder = null
                autoRearmPending = false

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
            activeDecoder = null
            autoRearmPending = false
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
        if (current.decoder == ImageDecoderSelection.WEFAX) {
            return fail("WEFAX DECODER FOLLOWS THE SSTV HARDWARE TEST")
        }
        if (!hasMicrophonePermission()) {
            return ImageListenResult.PermissionRequired
        }

        synchronized(sessionLock) {
            imageReplacementProtection.arm(current.image?.completedLines ?: 0)
            robot36Decoder = null
            martinM1Decoder = null
            martinM2Decoder = null
            activeDecoder = null
            autoRearmPending = false
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
            autoRearmPending = false
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
        }
        finishDiagnostics("CLEAR")
        synchronized(sessionLock) {
            robot36Decoder = null
            martinM1Decoder = null
            martinM2Decoder = null
            activeDecoder = null
            autoRearmPending = false
            imageReplacementProtection.clear()
            captureBuffer?.clear()
            if (!state.value.listening) {
                captureBuffer = null
                activeSampleRateHz = null
            }
            frameRevision += 1
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
                null -> {
                    robot36Decoder?.process(samples, count)
                    if (activeDecoder == null) {
                        martinM1Decoder?.process(samples, count)
                    }
                    if (activeDecoder == null) {
                        martinM2Decoder?.process(samples, count)
                    }
                }
            }

            ImageDecoderSelection.SSTV -> robot36Decoder?.process(samples, count)
            ImageDecoderSelection.MARTIN_M1 -> martinM1Decoder?.process(samples, count)
            ImageDecoderSelection.MARTIN_M2 -> martinM2Decoder?.process(samples, count)
            ImageDecoderSelection.WEFAX -> Unit
        }

        // A completed AUTO frame must remain visible and savable, but the decoder
        // claim must not remain latched. Recreate all acquisition candidates only
        // after the callback stack returns so the next transmission can choose a
        // different VIS mode without CLEAR or leaving the IMG page.
        if (autoRearmPending) {
            rearmAutoDetectionLocked()
        }
    }

    private fun finishActiveDecoderLocked(reason: String) {
        when (state.value.decoder) {
            ImageDecoderSelection.AUTO -> when (activeDecoder) {
                ActiveSstvDecoder.ROBOT_36 -> robot36Decoder?.finishCapture(reason)
                ActiveSstvDecoder.MARTIN_M1 -> martinM1Decoder?.finishCapture(reason)
                ActiveSstvDecoder.MARTIN_M2 -> martinM2Decoder?.finishCapture(reason)
                null -> {
                    robot36Decoder?.finishCapture(reason)
                    martinM1Decoder?.finishCapture(reason)
                    martinM2Decoder?.finishCapture(reason)
                }
            }

            ImageDecoderSelection.SSTV -> robot36Decoder?.finishCapture(reason)
            ImageDecoderSelection.MARTIN_M1 -> martinM1Decoder?.finishCapture(reason)
            ImageDecoderSelection.MARTIN_M2 -> martinM2Decoder?.finishCapture(reason)
            ImageDecoderSelection.WEFAX -> Unit
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
        autoRearmPending = false
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
    }

    private fun decoderPipelineReady(): Boolean = when (state.value.decoder) {
        ImageDecoderSelection.AUTO ->
            robot36Decoder != null && martinM1Decoder != null && martinM2Decoder != null
        ImageDecoderSelection.SSTV -> robot36Decoder != null
        ImageDecoderSelection.MARTIN_M1 -> martinM1Decoder != null
        ImageDecoderSelection.MARTIN_M2 -> martinM2Decoder != null
        ImageDecoderSelection.WEFAX -> true
    }

    private fun rearmAutoDetectionLocked() {
        if (!autoRearmPending || state.value.decoder != ImageDecoderSelection.AUTO) {
            autoRearmPending = false
            return
        }
        val sampleRateHz = activeSampleRateHz ?: run {
            autoRearmPending = false
            return
        }

        autoRearmPending = false
        activeDecoder = null
        robot36Decoder = Robot36Decoder(
            sampleRateHz,
            robot36Listener(),
            false,
        )
        martinM1Decoder = MartinM1Decoder(
            sampleRateHz,
            martinM1Listener(),
            false,
        )
        martinM2Decoder = MartinM2Decoder(
            sampleRateHz,
            martinM2Listener(),
            false,
        )
        imageReplacementProtection.arm(state.value.image?.completedLines ?: 0)
        diagnosticLogger.decoder(
            "AUTO rearmed after complete frame; retained displayed image and PCM buffer",
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

    private fun modeDetected(candidate: ActiveSstvDecoder, modeName: String) {
        if (!claimDecoder(candidate)) return
        if (imageReplacementProtection.active) {
            imageReplacementProtection.recordMode(modeName)
            return
        }
        _state.update { current ->
            current.copy(
                signal = ImageSignalState.DECODING,
                detectedMode = modeName,
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
        if (complete && state.value.decoder == ImageDecoderSelection.AUTO) {
            autoRearmPending = true
        }
        frameRevision += 1
        val frame = DecodedImageFrame(
            width = width,
            height = height,
            argbPixels = argbPixels.copyOf(),
            completedLines = completedLines,
            revision = frameRevision,
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
                error = null,
            )
        }
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
    }
}
