package com.n5nbd.mempuck.atsmini.img.diagnostics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Session-scoped IMG diagnostics. No PCM is persisted: only compact signal and decoder metrics
 * are written to IMG-DEBUG.txt in the user-selected MemPuck SAF directory.
 */
class ImageDiagnosticLogger(context: Context) {
    private val applicationContext = context.applicationContext
    private val resolver = applicationContext.contentResolver
    private val lock = Any()
    private val lines = mutableListOf<String>()

    private var sampleRateHz = 0
    private var totalSamples = 0L
    private var intervalSamples = 0
    private var intervalBlocks = 0
    private var intervalMinBlock = Int.MAX_VALUE
    private var intervalMaxBlock = 0
    private var intervalPeak = 0
    private var intervalSum = 0L
    private var intervalSquares = 0.0
    private var intervalClipped = 0
    private var intervalZeroCrossings = 0
    private var previousSample = 0
    private var havePreviousSample = false

    fun begin(sampleRateHz: Int, decoder: String, input: String) {
        synchronized(lock) {
        this.sampleRateHz = sampleRateHz
        totalSamples = 0L
        previousSample = 0
        havePreviousSample = false
        resetInterval()
        lines.clear()
        lines += "MemPuck IMG diagnostic slice 13"
        lines += "started=${timestamp()}"
        lines += "input=$input decoder=$decoder sample_rate_hz=$sampleRateHz frame_samples=${sampleRateHz / 50}"
        lines += "audio_persistence=none"
        }
    }

    fun audio(samples: ShortArray, count: Int): Unit {
        synchronized(lock) {
            if (sampleRateHz <= 0 || count <= 0) return@synchronized
            val safeCount = count.coerceIn(0, samples.size)
        intervalBlocks += 1
        intervalMinBlock = minOf(intervalMinBlock, safeCount)
        intervalMaxBlock = maxOf(intervalMaxBlock, safeCount)
        for (index in 0 until safeCount) {
            val value = samples[index].toInt()
            val magnitude = if (value == Short.MIN_VALUE.toInt()) 32768 else abs(value)
            intervalPeak = maxOf(intervalPeak, magnitude)
            intervalSum += value.toLong()
            intervalSquares += value.toDouble() * value.toDouble()
            if (magnitude >= 32760) intervalClipped += 1
            if (havePreviousSample && ((previousSample < 0 && value >= 0) || (previousSample >= 0 && value < 0))) {
                intervalZeroCrossings += 1
            }
            previousSample = value
            havePreviousSample = true
        }
        intervalSamples += safeCount
        totalSamples += safeCount
        while (intervalSamples >= sampleRateHz) {
            emitAudioInterval()
            // AudioRecord blocks are small fixed frames in this slice. If a future source supplies
            // more than one second at once, the excess is represented in the next aggregate.
            intervalSamples -= sampleRateHz
            resetInterval(keepSamples = true)
        }
        }
    }

    fun decoder(message: String) = synchronized(lock) {
        lines += "DSP t=${seconds(totalSamples)}s $message"
    }


    fun writeRawPgm(width: Int, height: Int, grayPixels: ByteArray): Result<String> = synchronized(lock) {
        require(width > 0 && height > 0) { "Invalid PGM dimensions" }
        require(grayPixels.size >= width * height) { "Not enough grayscale pixels" }
        val header = "P5\n$width $height\n255\n".toByteArray(Charsets.US_ASCII)
        val bytes = ByteArray(header.size + width * height)
        header.copyInto(bytes, 0)
        grayPixels.copyInto(bytes, header.size, 0, width * height)
        writeBinary(RAW_PGM_FILE_NAME, PGM_MIME_TYPE, bytes)
    }


    fun writeTimeline(text: String): Result<String> = synchronized(lock) {
        writeText(TIMELINE_FILE_NAME, text)
    }

    fun writeLineTrace(
        lineNumber: Int,
        sampleIndices: IntArray,
        rawFrequenciesHz: FloatArray,
        correctedFrequenciesHz: FloatArray,
        grayValues: IntArray,
    ): Result<List<String>> = synchronized(lock) {
        require(sampleIndices.size == rawFrequenciesHz.size)
        require(sampleIndices.size == correctedFrequenciesHz.size)
        require(sampleIndices.size == grayValues.size)
        require(sampleIndices.isNotEmpty())

        val csvName = "IMG-ROBOT36-LINE-${lineNumber}.csv"
        val pngName = "IMG-ROBOT36-LINE-${lineNumber}.png"
        val csv = buildString {
            appendLine("pixel,sample_index,raw_frequency_hz,corrected_frequency_hz,gray")
            for (index in sampleIndices.indices) {
                append(index)
                append(',').append(sampleIndices[index])
                append(',').append(String.format(Locale.US, "%.3f", rawFrequenciesHz[index]))
                append(',').append(String.format(Locale.US, "%.3f", correctedFrequenciesHz[index]))
                append(',').append(grayValues[index])
                append('\n')
            }
        }
        writeText(csvName, csv).getOrThrow()
        writeLineGraphPng(pngName, correctedFrequenciesHz).getOrThrow()
        Result.success(listOf(csvName, pngName))
    }

    private fun writeLineGraphPng(fileName: String, frequenciesHz: FloatArray): Result<String> = runCatching {
        val width = 1280
        val height = 640
        val marginLeft = 80f
        val marginRight = 30f
        val marginTop = 30f
        val marginBottom = 60f
        val plotWidth = width - marginLeft - marginRight
        val plotHeight = height - marginTop - marginBottom
        val minHz = 1100f
        val maxHz = 2500f
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 2f
            textSize = 24f
        }
        val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }
        val tracePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }
        fun yFor(hz: Float): Float = marginTop + (maxHz - hz).coerceIn(0f, maxHz - minHz) / (maxHz - minHz) * plotHeight
        for (hz in intArrayOf(1200, 1500, 1900, 2300)) {
            val y = yFor(hz.toFloat())
            canvas.drawLine(marginLeft, y, width - marginRight, y, guidePaint)
            canvas.drawText("${hz} Hz", 5f, y + 8f, axisPaint)
        }
        canvas.drawLine(marginLeft, marginTop, marginLeft, height - marginBottom, axisPaint)
        canvas.drawLine(marginLeft, height - marginBottom, width - marginRight, height - marginBottom, axisPaint)
        var previousX = marginLeft
        var previousY = yFor(frequenciesHz[0])
        frequenciesHz.forEachIndexed { index, hz ->
            val x = marginLeft + index.toFloat() / (frequenciesHz.size - 1).coerceAtLeast(1) * plotWidth
            val y = yFor(hz)
            if (index > 0) canvas.drawLine(previousX, previousY, x, y, tracePaint)
            previousX = x
            previousY = y
        }
        canvas.drawText("pixel 0", marginLeft, height - 18f, axisPaint)
        canvas.drawText("pixel ${frequenciesHz.lastIndex}", width - marginRight - 130f, height - 18f, axisPaint)
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            output.toByteArray()
        }
        bitmap.recycle()
        writeBinary(fileName, PNG_MIME_TYPE, bytes).getOrThrow()
        fileName
    }

    fun finish(reason: String): Result<String> = synchronized(lock) {
        if (sampleRateHz > 0 && intervalSamples > 0) emitAudioInterval(partial = true)
        lines += "stopped=${timestamp()} reason=$reason total_seconds=${seconds(totalSamples)}"
        val logResult = writeLog(lines.joinToString(separator = "\n", postfix = "\n"))
        if (logResult.isSuccess) {
            writeDiagnosticZip()
        }
        logResult
    }

    private fun writeDiagnosticZip(): Result<String> = runCatching {
        val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val treeUri = preferences.getString(KEY_DIRECTORY_URI, null)?.let(Uri::parse)
            ?: error("No MemPuck directory is selected")
        val wanted = linkedSetOf(
            LOG_FILE_NAME,
            RAW_PGM_FILE_NAME,
            TIMELINE_FILE_NAME,
        )
        val output = java.io.ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            wanted.forEach { fileName ->
                val child = findChild(treeUri, fileName) ?: return@forEach
                resolver.openInputStream(child).use { input ->
                    if (input == null) return@forEach
                    zip.putNextEntry(ZipEntry(fileName))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
            }
        }
        writeBinary(DIAGNOSTIC_ZIP_FILE_NAME, ZIP_MIME_TYPE, output.toByteArray()).getOrThrow()
        DIAGNOSTIC_ZIP_FILE_NAME
    }

    private fun emitAudioInterval(partial: Boolean = false) {
        val count = intervalSamples.coerceAtLeast(1)
        val rms = sqrt(intervalSquares / count)
        val mean = intervalSum.toDouble() / count
        val peakDb = dbfs(intervalPeak.toDouble())
        val rmsDb = dbfs(rms)
        val duration = count.toDouble() / sampleRateHz
        val zeroCrossHz = if (duration > 0.0) intervalZeroCrossings / (2.0 * duration) else 0.0
        lines += buildString {
            append("AUDIO t=${seconds(totalSamples)}s")
            if (partial) append(" partial")
            append(" blocks=$intervalBlocks")
            append(" block=${if (intervalMinBlock == Int.MAX_VALUE) 0 else intervalMinBlock}..$intervalMaxBlock")
            append(" peak_dbfs=${format(peakDb)}")
            append(" rms_dbfs=${format(rmsDb)}")
            append(" dc=${format(mean)}")
            append(" zero_cross_hz=${format(zeroCrossHz)}")
            append(" clipped=$intervalClipped")
        }
    }

    private fun resetInterval(keepSamples: Boolean = false) {
        if (!keepSamples) intervalSamples = 0
        intervalBlocks = 0
        intervalMinBlock = Int.MAX_VALUE
        intervalMaxBlock = 0
        intervalPeak = 0
        intervalSum = 0L
        intervalSquares = 0.0
        intervalClipped = 0
        intervalZeroCrossings = 0
    }

    private fun writeText(fileName: String, text: String): Result<String> = runCatching {
        val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val treeUri = preferences.getString(KEY_DIRECTORY_URI, null)?.let(Uri::parse)
            ?: error("No MemPuck directory is selected")
        val documentUri = findChild(treeUri, fileName)
            ?: DocumentsContract.createDocument(
                resolver,
                rootDocumentUri(treeUri),
                TEXT_MIME_TYPE,
                fileName,
            )
            ?: error("Unable to create $fileName")
        resolver.openOutputStream(documentUri, "wt").use { output ->
            requireNotNull(output) { "Unable to open $fileName" }
            output.bufferedWriter().use { writer -> writer.write(text) }
        }
        fileName
    }

    private fun writeLog(text: String): Result<String> = runCatching {
        val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val treeUri = preferences.getString(KEY_DIRECTORY_URI, null)?.let(Uri::parse)
            ?: error("No MemPuck directory is selected")
        val documentUri = findChild(treeUri, LOG_FILE_NAME)
            ?: DocumentsContract.createDocument(
                resolver,
                rootDocumentUri(treeUri),
                TEXT_MIME_TYPE,
                LOG_FILE_NAME,
            )
            ?: error("Unable to create $LOG_FILE_NAME")
        resolver.openOutputStream(documentUri, "wt").use { output ->
            requireNotNull(output) { "Unable to open $LOG_FILE_NAME" }
            output.bufferedWriter().use { writer -> writer.write(text) }
        }
        LOG_FILE_NAME
    }


    private fun writeBinary(fileName: String, mimeType: String, bytes: ByteArray): Result<String> = runCatching {
        val preferences = applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val treeUri = preferences.getString(KEY_DIRECTORY_URI, null)?.let(Uri::parse)
            ?: error("No MemPuck directory is selected")
        val documentUri = findChild(treeUri, fileName)
            ?: DocumentsContract.createDocument(
                resolver,
                rootDocumentUri(treeUri),
                mimeType,
                fileName,
            )
            ?: error("Unable to create $fileName")
        resolver.openOutputStream(documentUri, "wt").use { output ->
            requireNotNull(output) { "Unable to open $fileName" }
            output.write(bytes)
        }
        fileName
    }

    private fun findChild(treeUri: Uri, fileName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        return resolver.query(childrenUri, projection, null, null, null).use { cursor ->
            if (cursor == null) return null
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex).equals(fileName, ignoreCase = true)) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idIndex))
                }
            }
            null
        }
    }

    private fun rootDocumentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )

    private fun seconds(samples: Long): String = if (sampleRateHz > 0) {
        format(samples.toDouble() / sampleRateHz)
    } else {
        "0.00"
    }

    private fun dbfs(value: Double): Double = if (value <= 0.0) -120.0 else 20.0 * log10(value / 32768.0)

    private fun format(value: Double): String = String.format(Locale.US, "%.2f", value)

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())

    private companion object {
        const val PREFERENCES_NAME = "mempuck-memory-library"
        const val KEY_DIRECTORY_URI = "frequency-directory-uri"
        const val LOG_FILE_NAME = "IMG-DEBUG.txt"
        const val RAW_PGM_FILE_NAME = "IMG-ROBOT36-RAW.pgm"
        const val TIMELINE_FILE_NAME = "IMG-ROBOT36-TIMELINE.txt"
        const val TEXT_MIME_TYPE = "text/plain"
        const val PGM_MIME_TYPE = "image/x-portable-graymap"
        const val PNG_MIME_TYPE = "image/png"
        const val ZIP_MIME_TYPE = "application/zip"
        const val DIAGNOSTIC_ZIP_FILE_NAME = "IMG-DIAGNOSTICS.zip"
    }
}
