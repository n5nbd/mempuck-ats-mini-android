package com.n5nbd.mempuck.atsmini.img.repository

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.AtomicFile
import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderSelection
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

internal data class ImageCaptureSnapshot(
    val captureId: Long,
    val decoder: ImageDecoderSelection,
    val detectedMode: String?,
    val width: Int,
    val height: Int,
    val completedLines: Int,
    val continuous: Boolean,
    val complete: Boolean,
    val argbPixels: IntArray,
) {
    val storedHeight: Int
        get() = if (continuous) completedLines.coerceAtLeast(1) else height

    fun toFrame(revision: Long): DecodedImageFrame = DecodedImageFrame(
        width = width,
        height = storedHeight,
        argbPixels = argbPixels.copyOf(),
        completedLines = completedLines.coerceIn(0, storedHeight),
        revision = revision,
        continuous = continuous,
        captureId = captureId,
    )
}

internal data class ImageArchiveResult(
    val captureId: Long,
    val fileName: String,
)

/**
 * Owns IMG persistence outside the Compose screen.
 *
 * Public autosaves are queued to MediaStore. Recovery uses exactly one private
 * AtomicFile, so progressive checkpoints never appear as gallery clutter and a
 * torn write cannot replace the last usable checkpoint.
 */
internal class ImageCaptureStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mempuck-img-store").apply { isDaemon = true }
    }
    private val recoveryDirectory = File(applicationContext.filesDir, "img-recovery")
    private val recoveryFile = AtomicFile(File(recoveryDirectory, "current.imgchk"))
    private val checkpointLock = Any()

    private var pendingCheckpoint: ImageCaptureSnapshot? = null
    private var checkpointWorkerRunning = false

    fun loadRecovery(): ImageCaptureSnapshot? = runCatching {
        if (!recoveryFile.baseFile.exists()) return null
        DataInputStream(BufferedInputStream(recoveryFile.openRead())).use { input ->
            val magic = input.readInt()
            require(magic == RECOVERY_MAGIC) { "Invalid IMG recovery magic" }
            val version = input.readInt()
            require(version == RECOVERY_VERSION) { "Unsupported IMG recovery version $version" }
            val captureId = input.readLong()
            val decoderOrdinal = input.readInt()
            val decoder = ImageDecoderSelection.entries.getOrNull(decoderOrdinal)
                ?: ImageDecoderSelection.AUTO
            val detectedMode = input.readUTF().takeIf(String::isNotBlank)
            val width = input.readInt()
            val height = input.readInt()
            val completedLines = input.readInt()
            val continuous = input.readBoolean()
            val complete = input.readBoolean()
            val pixelCount = input.readInt()
            require(width in 1..MAX_RECOVERY_DIMENSION)
            require(height in 1..MAX_RECOVERY_DIMENSION)
            require(completedLines in 1..height)
            require(pixelCount == width * height)
            require(pixelCount in 1..MAX_RECOVERY_PIXELS)
            val pixels = readPixels(input, pixelCount)
            ImageCaptureSnapshot(
                captureId = captureId,
                decoder = decoder,
                detectedMode = detectedMode,
                width = width,
                height = height,
                completedLines = completedLines,
                continuous = continuous,
                complete = complete,
                argbPixels = pixels,
            )
        }
    }.getOrNull()

    fun scheduleCheckpoint(snapshot: ImageCaptureSnapshot) {
        synchronized(checkpointLock) {
            pendingCheckpoint = snapshot
            if (checkpointWorkerRunning) return
            checkpointWorkerRunning = true
        }
        executor.execute(::drainCheckpoints)
    }

    fun autosave(
        snapshot: ImageCaptureSnapshot,
        onSuccess: (ImageArchiveResult) -> Unit,
        onFailure: (Long, Throwable) -> Unit,
    ) {
        executor.execute {
            runCatching {
                saveToGallery(snapshot).also {
                    clearRecoveryIfCapture(snapshot.captureId)
                }
            }
                .onSuccess(onSuccess)
                .onFailure { onFailure(snapshot.captureId, it) }
        }
    }

    private fun clearRecoveryIfCapture(captureId: Long) {
        val current = loadRecovery()
        if (current?.captureId == captureId) {
            recoveryFile.delete()
        }
    }

    private fun drainCheckpoints() {
        while (true) {
            val snapshot = synchronized(checkpointLock) {
                val next = pendingCheckpoint
                pendingCheckpoint = null
                if (next == null) {
                    checkpointWorkerRunning = false
                }
                next
            } ?: return
            runCatching { writeRecovery(snapshot) }
        }
    }

    private fun writeRecovery(snapshot: ImageCaptureSnapshot) {
        recoveryDirectory.mkdirs()
        val output = recoveryFile.startWrite()
        val data = DataOutputStream(BufferedOutputStream(output))
        try {
            data.writeInt(RECOVERY_MAGIC)
            data.writeInt(RECOVERY_VERSION)
            data.writeLong(snapshot.captureId)
            data.writeInt(snapshot.decoder.ordinal)
            data.writeUTF(snapshot.detectedMode.orEmpty())
            data.writeInt(snapshot.width)
            data.writeInt(snapshot.storedHeight)
            data.writeInt(snapshot.completedLines.coerceIn(1, snapshot.storedHeight))
            data.writeBoolean(snapshot.continuous)
            data.writeBoolean(snapshot.complete)
            data.writeInt(snapshot.argbPixels.size)
            writePixels(data, snapshot.argbPixels)
            data.flush()
            recoveryFile.finishWrite(output)
        } catch (failure: Throwable) {
            recoveryFile.failWrite(output)
            throw failure
        }
    }


    private fun writePixels(output: DataOutputStream, pixels: IntArray) {
        val bytes = ByteArray(PIXEL_IO_CHUNK_BYTES)
        var pixelIndex = 0
        while (pixelIndex < pixels.size) {
            val chunkPixels = minOf(bytes.size / Int.SIZE_BYTES, pixels.size - pixelIndex)
            var byteIndex = 0
            repeat(chunkPixels) {
                val argb = pixels[pixelIndex++]
                bytes[byteIndex++] = (argb ushr 24).toByte()
                bytes[byteIndex++] = (argb ushr 16).toByte()
                bytes[byteIndex++] = (argb ushr 8).toByte()
                bytes[byteIndex++] = argb.toByte()
            }
            output.write(bytes, 0, byteIndex)
        }
    }

    private fun readPixels(input: DataInputStream, pixelCount: Int): IntArray {
        val pixels = IntArray(pixelCount)
        val bytes = ByteArray(PIXEL_IO_CHUNK_BYTES)
        var pixelIndex = 0
        while (pixelIndex < pixelCount) {
            val chunkPixels = minOf(bytes.size / Int.SIZE_BYTES, pixelCount - pixelIndex)
            val byteCount = chunkPixels * Int.SIZE_BYTES
            input.readFully(bytes, 0, byteCount)
            var byteIndex = 0
            repeat(chunkPixels) {
                pixels[pixelIndex++] =
                    ((bytes[byteIndex++].toInt() and 0xff) shl 24) or
                    ((bytes[byteIndex++].toInt() and 0xff) shl 16) or
                    ((bytes[byteIndex++].toInt() and 0xff) shl 8) or
                    (bytes[byteIndex++].toInt() and 0xff)
            }
        }
        return pixels
    }

    private fun saveToGallery(snapshot: ImageCaptureSnapshot): ImageArchiveResult {
        val fileName = autosaveFileName(snapshot)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/MemPuck")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = applicationContext.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore did not create an IMG autosave entry")
        try {
            val bitmap = Bitmap.createBitmap(
                snapshot.argbPixels,
                snapshot.width,
                snapshot.storedHeight,
                Bitmap.Config.ARGB_8888,
            )
            try {
                resolver.openOutputStream(uri)?.use { output ->
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                } ?: error("Could not open IMG autosave output")
            } finally {
                bitmap.recycle()
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) {
                "MediaStore did not finalize IMG autosave"
            }
            resolver.notifyChange(uri, null)
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
        return ImageArchiveResult(snapshot.captureId, fileName)
    }

    private fun autosaveFileName(snapshot: ImageCaptureSnapshot): String {
        val tag = decoderTag(snapshot.decoder, snapshot.detectedMode)
        return "MemPuck-$tag-${LocalDateTime.now().format(TIMESTAMP_FORMAT)}.png"
    }

    private fun decoderTag(
        decoder: ImageDecoderSelection,
        detectedMode: String?,
    ): String = when {
        detectedMode?.contains("WEFAX", ignoreCase = true) == true -> "WX"
        detectedMode?.contains("ROBOT", ignoreCase = true) == true -> "R36"
        detectedMode?.contains("MARTIN M1", ignoreCase = true) == true -> "M1"
        detectedMode?.contains("MARTIN M2", ignoreCase = true) == true -> "M2"
        detectedMode?.contains("SCOTTIE S1", ignoreCase = true) == true -> "S1"
        detectedMode?.contains("SCOTTIE S2", ignoreCase = true) == true -> "S2"
        decoder == ImageDecoderSelection.WEFAX -> "WX"
        decoder == ImageDecoderSelection.SSTV -> "R36"
        decoder == ImageDecoderSelection.MARTIN_M1 -> "M1"
        decoder == ImageDecoderSelection.MARTIN_M2 -> "M2"
        decoder == ImageDecoderSelection.SCOTTIE_S1 -> "S1"
        decoder == ImageDecoderSelection.SCOTTIE_S2 -> "S2"
        else -> "IMG"
    }

    private companion object {
        const val RECOVERY_MAGIC = 0x4d504936 // MPI6
        const val RECOVERY_VERSION = 1
        const val MAX_RECOVERY_DIMENSION = 8192
        const val MAX_RECOVERY_PIXELS = 40_000_000
        const val PIXEL_IO_CHUNK_BYTES = 256 * 1024
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
