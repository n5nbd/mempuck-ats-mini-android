package com.n5nbd.mempuck.atsmini.img.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.n5nbd.mempuck.atsmini.img.audio.ImageAudioSource
import com.n5nbd.mempuck.atsmini.img.audio.MicrophoneImageAudioSource
import com.n5nbd.mempuck.atsmini.img.audio.PcmRingBuffer
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
    private val applicationContext = context.applicationContext
    private val sessionLock = Any()
    private val _state = MutableStateFlow(ImageDecoderState())
    private val diagnosticLogger = ImageDiagnosticLogger(applicationContext)

    private var robot36Decoder: Robot36Decoder? = null
    private var captureBuffer: PcmRingBuffer? = null
    private var activeSampleRateHz: Int? = null
    private var frameRevision = 0L
    private var diagnosticStarted = false

    val state: StateFlow<ImageDecoderState> = _state.asStateFlow()

    fun selectDecoder(decoder: ImageDecoderSelection) {
        if (state.value.listening) stopListening()
        _state.update { current ->
            current.copy(
                decoder = decoder,
                signal = ImageSignalState.WAITING,
                error = null,
            )
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
            robot36Decoder = null
            captureBuffer = null
            activeSampleRateHz = null
            diagnosticStarted = false
            frameRevision += 1
        }
        _state.update {
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
            robot36Decoder?.finishCapture("STOP_OR_LIFECYCLE")
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
            robot36Decoder?.finishCapture("CLEAR")
        }
        finishDiagnostics("CLEAR")
        synchronized(sessionLock) {
            robot36Decoder = null
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

    /** Reserved for the manual swipe/replay slice once additional SSTV modes are present. */
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
            robot36Decoder?.process(samples, safeCount)
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

    private fun ensurePipeline(sampleRateHz: Int) {
        synchronized(sessionLock) {
            ensurePipelineLocked(sampleRateHz)
        }
    }

    private fun ensurePipelineLocked(sampleRateHz: Int) {
        if (
            activeSampleRateHz == sampleRateHz &&
            captureBuffer != null &&
            robot36Decoder != null
        ) {
            return
        }
        activeSampleRateHz = sampleRateHz
        captureBuffer = PcmRingBuffer(sampleRateHz * SSTV_CAPTURE_SECONDS)
        if (!diagnosticStarted) {
            diagnosticLogger.begin(
                sampleRateHz = sampleRateHz,
                decoder = state.value.decoder.label,
                input = state.value.input.label,
            )
            diagnosticStarted = true
        }
        robot36Decoder = Robot36Decoder(
            sampleRateHz,
            object : Robot36Decoder.Listener {
                override fun onModeDetected(modeName: String) {
                    _state.update { current ->
                        current.copy(
                            signal = ImageSignalState.DECODING,
                            detectedMode = modeName,
                            error = null,
                        )
                    }
                }


                override fun onAdaptiveStatus(
                    modeName: String,
                    correctionHz: Int,
                    confidence: Int,
                ) {
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

                override fun onFrame(
                    width: Int,
                    height: Int,
                    argbPixels: IntArray,
                    completedLines: Int,
                    complete: Boolean,
                ) {
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
                            signal = if (complete) {
                                ImageSignalState.COMPLETE
                            } else {
                                ImageSignalState.DECODING
                            },
                            detectedMode = current.detectedMode ?: "ROBOT 36",
                            image = frame,
                            error = null,
                        )
                    }
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
                    diagnosticLogger.writeLineTrace(
                        lineNumber,
                        sampleIndices,
                        rawFrequenciesHz,
                        correctedFrequenciesHz,
                        grayValues,
                    ).onSuccess { files ->
                        diagnosticLogger.decoder("ROBOT36 line_trace_saved line=$lineNumber files=${files.joinToString(",")}")
                    }.onFailure { error ->
                        diagnosticLogger.decoder(
                            "ROBOT36 line_trace_failed line=$lineNumber error=${error.message ?: error::class.java.simpleName}",
                        )
                    }
                }

                override fun onTimeline(text: String) {
                    diagnosticLogger.writeTimeline(text)
                        .onSuccess { fileName ->
                            diagnosticLogger.decoder("ROBOT36 timeline_saved file=$fileName")
                        }
                        .onFailure { error ->
                            diagnosticLogger.decoder(
                                "ROBOT36 timeline_failed error=${error.message ?: error::class.java.simpleName}",
                            )
                        }
                }

                override fun onRawGrayscaleFrame(
                    width: Int,
                    height: Int,
                    grayPixels: ByteArray,
                    complete: Boolean,
                ) {
                    if (!complete) return
                    diagnosticLogger.writeRawPgm(width, height, grayPixels)
                        .onSuccess { fileName ->
                            diagnosticLogger.decoder("ROBOT36 raw_pgm_saved file=$fileName width=$width height=$height")
                        }
                        .onFailure { error ->
                            diagnosticLogger.decoder(
                                "ROBOT36 raw_pgm_failed error=${error.message ?: error::class.java.simpleName}",
                            )
                        }
                }
            },
            state.value.decoder == ImageDecoderSelection.SSTV,
        )
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
        const val SSTV_CAPTURE_SECONDS = 60
    }
}
