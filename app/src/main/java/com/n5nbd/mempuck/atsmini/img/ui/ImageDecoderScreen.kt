package com.n5nbd.mempuck.atsmini.img.ui

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.FileProvider
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame
import com.n5nbd.mempuck.atsmini.img.model.ImageAudioInput
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderSelection
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderSession
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderState
import com.n5nbd.mempuck.atsmini.img.model.ImageSignalState
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val ImagePanelShape = RoundedCornerShape(5.dp)

private val Bayer4x4 = intArrayOf(
    0, 8, 2, 10,
    12, 4, 14, 6,
    3, 11, 1, 9,
    15, 7, 13, 5,
)

data class ImageDecoderPalette(
    val background: Color,
    val foreground: Color,
    val selectedBackground: Color,
    val selectedForeground: Color,
    val muted: Color,
)

@Composable
fun ImageDecoderScreen(
    state: ImageDecoderState,
    microphonePermissionGranted: Boolean,
    palette: ImageDecoderPalette,
    onSelectDecoder: (ImageDecoderSelection) -> Unit,
    onSelectInput: (ImageAudioInput) -> Unit,
    onListen: () -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    DisposableEffect(Unit) {
        onDispose(onStop)
    }

    val context = LocalContext.current
    val savableFrame = state.image?.takeIf { frame ->
        frame.completedLines > 0
    }
    val completedFrame = state.image?.takeIf { frame ->
        state.signal == ImageSignalState.COMPLETE &&
            frame.completedLines >= frame.height
    }

    ImagePanel(palette = palette) {
        Text(
            text = "IMG DECODER",
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )

        Spacer(Modifier.height(10.dp))

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f),
            color = palette.background,
            contentColor = palette.foreground,
            border = BorderStroke(1.dp, palette.foreground),
            shape = ImagePanelShape,
        ) {
            val frame = state.image
            if (frame == null) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "NO IMAGE",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "SSTV / WEFAX",
                            color = palette.muted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            } else {
                val preview = remember(
                    frame.revision,
                    palette.background,
                    palette.foreground,
                ) {
                    monochromePreview(frame, palette)
                }
                Image(
                    bitmap = preview.asImageBitmap(),
                    contentDescription = state.detectedMode ?: "Decoded image",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                    contentScale = ContentScale.Fit,
                )
            }
        }

        state.image?.takeIf { state.signal == ImageSignalState.DECODING }?.let { frame ->
            val progress = (frame.completedLines.toFloat() / frame.height.toFloat())
                .coerceIn(0f, 1f)
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .border(1.dp, palette.foreground),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .height(3.dp)
                        .background(palette.foreground),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ImageDecoderSelection.entries.forEach { decoder ->
                ImageButton(
                    text = decoder.label,
                    selected = state.decoder == decoder,
                    palette = palette,
                    enabled = !state.listening || decoder != ImageDecoderSelection.WEFAX,
                    onClick = { onSelectDecoder(decoder) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ImageAudioInput.entries.forEach { input ->
                ImageButton(
                    text = input.label,
                    selected = state.input == input,
                    palette = palette,
                    enabled = !state.listening && input.available,
                    onClick = { onSelectInput(input) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        ImageButton(
            text = if (state.listening) "STOP" else "LISTEN",
            selected = state.listening,
            palette = palette,
            enabled = state.input.available,
            onClick = if (state.listening) onStop else onListen,
            modifier = Modifier.fillMaxWidth(),
            height = 48,
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ImageButton(
                text = "CLEAR",
                selected = false,
                palette = palette,
                enabled = true,
                onClick = onClear,
                modifier = Modifier.weight(1f),
            )
            ImageButton(
                text = "SAVE",
                selected = false,
                palette = palette,
                enabled = savableFrame != null,
                onClick = {
                    savableFrame?.let { frame ->
                        saveCompletedImage(context, colorImage(frame))
                    }
                },
                modifier = Modifier.weight(1f),
            )
            ImageButton(
                text = "SHARE",
                selected = false,
                palette = palette,
                enabled = completedFrame != null,
                onClick = {
                    completedFrame?.let { frame ->
                        shareCompletedImage(context, monochromePreview(frame, palette))
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = palette.background,
            contentColor = palette.foreground,
            border = BorderStroke(1.dp, palette.foreground),
            shape = ImagePanelShape,
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = compactStatus(state),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = statusDetail(state, microphonePermissionGranted),
                    color = palette.muted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun ImagePanel(
    palette: ImageDecoderPalette,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = palette.background,
        contentColor = palette.foreground,
        border = BorderStroke(1.dp, palette.foreground),
        shape = ImagePanelShape,
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            content()
        }
    }
}

@Composable
private fun ImageButton(
    text: String,
    selected: Boolean,
    palette: ImageDecoderPalette,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Int = 41,
) {
    Surface(
        modifier = modifier
            .height(height.dp)
            .alpha(if (enabled || selected) 1f else 0.42f),
        color = if (selected) palette.selectedBackground else palette.background,
        contentColor = if (selected) palette.selectedForeground else palette.foreground,
        border = BorderStroke(1.dp, palette.foreground),
        shape = ImagePanelShape,
        onClick = onClick,
        enabled = enabled,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 0.5.sp,
                maxLines = 1,
            )
        }
    }
}

private fun monochromePreview(
    frame: DecodedImageFrame,
    palette: ImageDecoderPalette,
): Bitmap {
    val panelBackground = palette.background.toArgb()
    val paletteForeground = palette.foreground.toArgb()
    val semanticBlack = Color.Black.toArgb()
    val semanticWhite = Color.White.toArgb()
    val lightTheme = argbLuma(panelBackground) > argbLuma(paletteForeground)
    val imageDark = semanticBlack
    val imageBright = if (lightTheme) semanticWhite else paletteForeground
    val output = IntArray(frame.width * frame.height)
    val completedLines = frame.completedLines.coerceIn(0, frame.height)

    // Preserve decoded luminance polarity in every theme: black signal content
    // remains black and white content remains bright. Light mode therefore uses
    // normal black-to-white grayscale instead of reversing the image. Dark mode
    // uses black-to-white, while hue mode uses black-to-selected-hue.
    for (y in 0 until frame.height) {
        val row = y * frame.width
        if (y >= completedLines) {
            output.fill(panelBackground, row, row + frame.width)
            continue
        }
        for (x in 0 until frame.width) {
            val source = frame.argbPixels[row + x]
            val level = argbLuma(source).coerceIn(0, 255)
            output[row + x] = interpolateArgb(imageDark, imageBright, level)
        }
    }

    return Bitmap.createBitmap(
        output,
        frame.width,
        frame.height,
        Bitmap.Config.ARGB_8888,
    )
}

private fun colorImage(frame: DecodedImageFrame): Bitmap {
    require(frame.argbPixels.size == frame.width * frame.height) {
        "Decoded image buffer does not match its dimensions"
    }
    val output = frame.argbPixels.copyOf()
    val completedPixels = frame.completedLines.coerceIn(0, frame.height) * frame.width
    if (completedPixels < output.size) {
        output.fill(Color.Black.toArgb(), completedPixels, output.size)
    }
    return Bitmap.createBitmap(
        output,
        frame.width,
        frame.height,
        Bitmap.Config.ARGB_8888,
    )
}

private fun interpolateArgb(background: Int, foreground: Int, level: Int): Int {
    val inverse = 255 - level
    val alpha = (((background ushr 24) * inverse + (foreground ushr 24) * level) / 255) and 0xff
    val red = ((((background shr 16) and 0xff) * inverse + ((foreground shr 16) and 0xff) * level) / 255) and 0xff
    val green = ((((background shr 8) and 0xff) * inverse + ((foreground shr 8) and 0xff) * level) / 255) and 0xff
    val blue = (((background and 0xff) * inverse + (foreground and 0xff) * level) / 255) and 0xff
    return alpha shl 24 or (red shl 16) or (green shl 8) or blue
}

private fun argbLuma(argb: Int): Int {
    val red = argb shr 16 and 0xff
    val green = argb shr 8 and 0xff
    val blue = argb and 0xff
    return (77 * red + 150 * green + 29 * blue) shr 8
}


private val ImageTimestampFormat = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

private fun imageFileName(): String =
    "MemPuck-IMG-${LocalDateTime.now().format(ImageTimestampFormat)}.png"

private fun saveCompletedImage(context: android.content.Context, bitmap: Bitmap) {
    runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, imageFileName())
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/MemPuck")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("MediaStore did not create an image entry")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } ?: error("Could not open image output")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            check(resolver.update(uri, values, null, null) == 1) {
                "MediaStore did not finalize the image entry"
            }
            resolver.notifyChange(uri, null)
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }.onSuccess {
        Toast.makeText(context, "SAVED TO CAMERA ROLL • DCIM/MEMPUCK", Toast.LENGTH_SHORT).show()
    }.onFailure {
        Toast.makeText(
            context,
            "IMAGE SAVE FAILED: ${it.message ?: it::class.java.simpleName}",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun shareCompletedImage(context: android.content.Context, bitmap: Bitmap) {
    runCatching {
        val shareDirectory = File(context.cacheDir, "shared-images").apply { mkdirs() }
        val file = File(shareDirectory, imageFileName())
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "SHARE MEMPUCK IMAGE"))
    }.onFailure {
        Toast.makeText(context, "IMAGE SHARE FAILED", Toast.LENGTH_SHORT).show()
    }
}

private fun compactStatus(state: ImageDecoderState): String {
    val session = if (
        state.signal == ImageSignalState.COMPLETE &&
        state.image?.let { it.completedLines >= it.height } == true
    ) {
        "COMPLETE"
    } else {
        when (state.session) {
            ImageDecoderSession.IDLE -> "IDLE"
            ImageDecoderSession.STARTING -> "STARTING"
            ImageDecoderSession.LISTENING -> "LISTENING"
            ImageDecoderSession.ERROR -> "ERROR"
        }
    }
    val detected = state.detectedMode?.let { " • $it" }.orEmpty()
    return "${state.input.label} • ${state.decoder.label} • $session$detected"
}

private fun statusDetail(
    state: ImageDecoderState,
    microphonePermissionGranted: Boolean,
): String {
    state.error?.let { return it }
    val frame = state.image
    val adaptive = state.frequencyCorrectionHz?.let { correction ->
        val sign = if (correction >= 0) "+" else ""
        " • CORR $sign${correction} Hz • ${state.decoderConfidence}%"
    }.orEmpty()
    return when {
        state.signal == ImageSignalState.COMPLETE && frame != null ->
            "${state.detectedMode ?: "SSTV"} COMPLETE • ${frame.completedLines}/${frame.height} LINES$adaptive"

        state.signal == ImageSignalState.DECODING && frame != null ->
            "${state.detectedMode ?: "SSTV"} • ${frame.completedLines}/${frame.height} LINES$adaptive"

        state.listening && state.decoder == ImageDecoderSelection.SSTV ->
            "R36 LIVE MANUAL SYNC; TAP AUTO OR M1 TO SWITCH"
        state.listening && state.decoder == ImageDecoderSelection.MARTIN_M1 ->
            "M1 LIVE MANUAL SYNC; TAP AUTO OR R36 TO SWITCH"
        state.listening -> "LISTENING FOR R36 OR M1 VIS; MANUAL SWITCH AVAILABLE"
        !microphonePermissionGranted -> "MIC PERMISSION REQUESTS ONLY AFTER LISTEN"
        state.decoder == ImageDecoderSelection.WEFAX -> "WEFAX ENGINE FOLLOWS SSTV HARDWARE TEST"
        else -> "R36 + M1 AUTO/LIVE MANUAL READY"
    }
}

