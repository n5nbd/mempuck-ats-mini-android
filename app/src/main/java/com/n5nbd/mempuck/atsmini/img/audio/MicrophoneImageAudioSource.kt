package com.n5nbd.mempuck.atsmini.img.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MicrophoneImageAudioSource : ImageAudioSource {
    private val running = AtomicBoolean(false)
    private val lock = Any()

    private var audioRecord: AudioRecord? = null
    private var readerThread: Thread? = null

    override fun start(
        onSamples: (sampleRateHz: Int, samples: ShortArray, count: Int) -> Unit,
        onError: (String) -> Unit,
    ): Result<Int> = runCatching {
        synchronized(lock) {
            if (running.get()) return@runCatching audioRecord?.sampleRate ?: SAMPLE_RATE_HZ

            val minimumBufferBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBufferBytes > 0) {
                "MIC BUFFER UNAVAILABLE ($minimumBufferBytes)"
            }

            // Robot36's proven Android path consumes 50 frames per second. Keep AudioRecord's
            // internal buffer comfortably larger, but read and dispatch one exact 20 ms frame.
            val frameSamples = SAMPLE_RATE_HZ / READS_PER_SECOND
            val frameBytes = frameSamples * Short.SIZE_BYTES
            val recorderBufferBytes = max(minimumBufferBytes * 2, frameBytes * 8)
            val recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(recorderBufferBytes)
                .build()

            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                "MIC INITIALIZATION FAILED"
            }

            val actualSampleRateHz = recorder.sampleRate
            check(actualSampleRateHz > 0) {
                recorder.release()
                "MIC REPORTED INVALID SAMPLE RATE"
            }
            val actualFrameSamples = max(1, actualSampleRateHz / READS_PER_SECOND)

            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.release()
                "MIC DID NOT START"
            }

            audioRecord = recorder
            running.set(true)
            readerThread = Thread(
                {
                    readLoop(
                        recorder = recorder,
                        sampleRateHz = actualSampleRateHz,
                        sampleBufferSize = actualFrameSamples,
                        onSamples = onSamples,
                        onError = onError,
                    )
                },
                "mempuck-img-mic",
            ).apply {
                isDaemon = true
                start()
            }
            actualSampleRateHz
        }
    }

    override fun stop() {
        val recorder: AudioRecord?
        val thread: Thread?
        synchronized(lock) {
            if (!running.getAndSet(false) && audioRecord == null) return
            recorder = audioRecord
            thread = readerThread
            audioRecord = null
            readerThread = null
        }

        runCatching { recorder?.stop() }
        if (thread != null && thread !== Thread.currentThread()) {
            runCatching { thread.join(STOP_JOIN_MS) }
        }
        runCatching { recorder?.release() }
    }

    private fun readLoop(
        recorder: AudioRecord,
        sampleRateHz: Int,
        sampleBufferSize: Int,
        onSamples: (sampleRateHz: Int, samples: ShortArray, count: Int) -> Unit,
        onError: (String) -> Unit,
    ) {
        val samples = ShortArray(sampleBufferSize)
        var filled = 0
        var error: String? = null
        try {
            while (running.get()) {
                val count = recorder.read(
                    samples,
                    filled,
                    samples.size - filled,
                    AudioRecord.READ_BLOCKING,
                )
                when (count) {
                    AudioRecord.ERROR_INVALID_OPERATION -> {
                        error = "MIC READ INVALID OPERATION"
                        break
                    }

                    AudioRecord.ERROR_BAD_VALUE -> {
                        error = "MIC READ BAD VALUE"
                        break
                    }

                    AudioRecord.ERROR_DEAD_OBJECT -> {
                        error = "MIC DEVICE DISCONNECTED"
                        break
                    }

                    AudioRecord.ERROR -> {
                        error = "MIC READ FAILED"
                        break
                    }

                    else -> if (count > 0) {
                        filled += count
                        if (filled == samples.size) {
                            // Robot36's reference path processes one complete 20 ms frame per call.
                            // The repository consumes this reusable array synchronously.
                            onSamples(sampleRateHz, samples, samples.size)
                            filled = 0
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            error = failure.message?.takeIf(String::isNotBlank) ?: "MIC SESSION FAILED"
        } finally {
            if (error != null && running.getAndSet(false)) {
                onError(error)
            }
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            synchronized(lock) {
                if (audioRecord === recorder) audioRecord = null
                if (readerThread === Thread.currentThread()) readerThread = null
            }
        }
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 44_100
        const val READS_PER_SECOND = 50
        const val STOP_JOIN_MS = 500L
    }
}
