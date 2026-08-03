package com.n5nbd.mempuck.atsmini.img.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.n5nbd.mempuck.atsmini.img.model.DecodedImageFrame
import com.n5nbd.mempuck.atsmini.img.model.ImageAudioInput
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderSelection
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderSession
import com.n5nbd.mempuck.atsmini.img.model.ImageDecoderState
import com.n5nbd.mempuck.atsmini.img.model.ImageSignalState
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    sourceColorPreview: Boolean,
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

    val view = LocalView.current
    DisposableEffect(view, state.listening) {
        val previousKeepScreenOn = view.keepScreenOn
        if (state.listening) {
            view.keepScreenOn = true
        }
        onDispose {
            view.keepScreenOn = previousKeepScreenOn
        }
    }

    val context = LocalContext.current
    var openedFrame by remember { mutableStateOf<DecodedImageFrame?>(null) }
    val sourceFrame = openedFrame ?: state.image
    var acceptedCorrection by remember(sourceFrame?.revision) {
        mutableStateOf(ImageCorrection())
    }
    var correctionEditorOpen by remember {
        mutableStateOf(false)
    }
    var releasedFrameRevision by remember {
        mutableStateOf<Long?>(null)
    }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            loadPickedImage(context, uri)
        }.onSuccess { frame ->
            openedFrame = frame
            correctionEditorOpen = true
        }.onFailure { failure ->
            Toast.makeText(
                context,
                "IMAGE OPEN FAILED: ${failure.message ?: failure::class.java.simpleName}",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    LaunchedEffect(state.listening) {
        if (state.listening) {
            openedFrame = null
            correctionEditorOpen = false
        }
    }
    val workingFrame = remember(sourceFrame?.revision, acceptedCorrection) {
        sourceFrame?.let { frame -> applyImageCorrection(frame, acceptedCorrection) }
    }
    val savableFrame = workingFrame?.takeIf { frame ->
        frame.completedLines > 0
    }
    val completedSourceFrame = sourceFrame?.takeIf { frame ->
        openedFrame != null ||
            (state.signal == ImageSignalState.COMPLETE &&
                (frame.continuous || frame.completedLines >= frame.height))
    }
    LaunchedEffect(state.image?.revision) {
        releasedFrameRevision = null
    }
    val decoderSelectionLocked = shouldLockDecoderSelection(
        signal = state.signal,
        receiverFrameRevision = state.image?.revision,
        openedImageActive = openedFrame != null,
        releasedFrameRevision = releasedFrameRevision,
    )

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
            val frame = workingFrame
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
                    frame,
                    sourceColorPreview,
                    palette.background,
                    palette.foreground,
                ) {
                    if (sourceColorPreview) {
                        colorImage(frame, palette.background.toArgb())
                    } else {
                        monochromePreview(frame, palette)
                    }
                }
                DecodedImageViewport(
                    preview = preview,
                    frame = frame,
                    palette = palette,
                    contentDescription = state.detectedMode ?: "Decoded image",
                    onDoubleTap = if (completedSourceFrame != null && !state.listening) {
                        { correctionEditorOpen = true }
                    } else {
                        null
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(2.dp),
                )
            }
        }

        state.image?.takeIf {
            state.signal == ImageSignalState.DECODING && !it.continuous
        }?.let { frame ->
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
                    enabled = !decoderSelectionLocked,
                    onClick = { onSelectDecoder(decoder) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (decoderSelectionLocked) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "IMAGE LOCKED • SAVE, CLEAR, OR NEW LISTEN",
                modifier = Modifier.fillMaxWidth(),
                color = palette.muted,
                textAlign = TextAlign.Center,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
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
            onClick = if (state.listening) {
                onStop
            } else {
                {
                    releasedFrameRevision = state.image?.revision
                    openedFrame = null
                    correctionEditorOpen = false
                    onListen()
                }
            },
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
                onClick = {
                    if (openedFrame != null) {
                        openedFrame = null
                        correctionEditorOpen = false
                    } else {
                        onClear()
                    }
                },
                modifier = Modifier.weight(1f),
            )
            ImageButton(
                text = "SAVE",
                selected = false,
                palette = palette,
                enabled = savableFrame != null,
                onClick = {
                    savableFrame?.let { frame ->
                        if (saveCompletedImage(context, colorImage(frame)) && openedFrame == null) {
                            releasedFrameRevision = state.image?.revision
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
            ImageButton(
                text = "OPEN",
                selected = false,
                palette = palette,
                enabled = !state.listening,
                onClick = {
                    imagePicker.launch(arrayOf("image/*"))
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

    if (correctionEditorOpen && completedSourceFrame != null && !state.listening) {
        ImageCorrectionDialog(
            frame = completedSourceFrame,
            initialCorrection = acceptedCorrection,
            sourceColorPreview = sourceColorPreview,
            palette = palette,
            onCancel = { correctionEditorOpen = false },
            onAccept = { correction ->
                acceptedCorrection = correction
                correctionEditorOpen = false
            },
        )
    }
}


private enum class ImageViewMode {
    FIT,
    ZOOM,
}

private const val DetailZoomMultiplier = 3f
private const val ViewModeOverlayMillis = 850L

@Composable
private fun DecodedImageViewport(
    preview: Bitmap,
    frame: DecodedImageFrame,
    palette: ImageDecoderPalette,
    contentDescription: String,
    onDoubleTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val image = remember(preview) { preview.asImageBitmap() }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var viewMode by remember { mutableStateOf(ImageViewMode.FIT) }
    var zoomCenter by remember { mutableStateOf<ImagePoint?>(null) }
    var detailScale by remember { mutableStateOf<Float?>(null) }
    var overlayLabel by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(overlayLabel) {
        val shown = overlayLabel ?: return@LaunchedEffect
        delay(ViewModeOverlayMillis)
        if (overlayLabel == shown) {
            overlayLabel = null
        }
    }

    val viewportWidth = viewportSize.width.toFloat()
    val viewportHeight = viewportSize.height.toFloat()
    val imageWidth = preview.width
    val imageHeight = preview.height
    val placement = if (viewportSize != IntSize.Zero) {
        fitPlacement(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            bottomAligned = frame.continuous,
        )
    } else {
        null
    }
    val zoomScale = detailScale
        ?: placement?.scale?.times(DetailZoomMultiplier)
        ?: 1f

    val tapModifier = Modifier.pointerInput(
        viewMode,
        viewportSize,
        imageWidth,
        imageHeight,
        frame.continuous,
        onDoubleTap,
    ) {
        detectTapGestures(
            onDoubleTap = onDoubleTap?.let { callback ->
                { _ -> callback() }
            },
            onTap = { tap ->
                val currentPlacement = placement
                if (currentPlacement != null && viewMode == ImageViewMode.FIT) {
                    val selectedScale = currentPlacement.scale * DetailZoomMultiplier
                    detailScale = selectedScale
                    val requested = imagePointAt(
                        tapX = tap.x,
                        tapY = tap.y,
                        placement = currentPlacement,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                    )
                    zoomCenter = clampZoomCenter(
                        requested = requested,
                        viewportWidth = viewportWidth,
                        viewportHeight = viewportHeight,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        scale = selectedScale,
                    )
                    viewMode = ImageViewMode.ZOOM
                    overlayLabel = "ZOOM"
                } else if (viewMode == ImageViewMode.ZOOM) {
                    viewMode = ImageViewMode.FIT
                    detailScale = null
                    overlayLabel = "FIT"
                }
            },
        )
    }

    val panModifier = if (viewMode == ImageViewMode.ZOOM) {
        Modifier.pointerInput(
            viewportSize,
            imageWidth,
            imageHeight,
            zoomScale,
        ) {
            detectDragGestures { change, dragAmount ->
                change.consume()
                val current = zoomCenter ?: ImagePoint(
                    imageWidth / 2f,
                    imageHeight / 2f,
                )
                zoomCenter = clampZoomCenter(
                    requested = ImagePoint(
                        x = current.x - dragAmount.x / zoomScale,
                        y = current.y - dragAmount.y / zoomScale,
                    ),
                    viewportWidth = viewportWidth,
                    viewportHeight = viewportHeight,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    scale = zoomScale,
                )
            }
        }
    } else {
        // FIT deliberately leaves drags unconsumed so horizontal decoder-candidate
        // replay can own that gesture when buffered replay is implemented.
        Modifier
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewportSize = it }
                .then(tapModifier)
                .then(panModifier),
        ) {
            drawRect(palette.background)
            val currentPlacement = placement ?: return@Canvas
            if (viewMode == ImageViewMode.FIT) {
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(
                        currentPlacement.left.roundToInt(),
                        currentPlacement.top.roundToInt(),
                    ),
                    dstSize = IntSize(
                        currentPlacement.width.roundToInt().coerceAtLeast(1),
                        currentPlacement.height.roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Medium,
                )
            } else {
                val requestedCenter = zoomCenter ?: ImagePoint(
                    imageWidth / 2f,
                    imageHeight / 2f,
                )
                val effectiveCenter = clampZoomCenter(
                    requested = requestedCenter,
                    viewportWidth = size.width,
                    viewportHeight = size.height,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    scale = zoomScale,
                )
                val topLeft = zoomTopLeft(
                    center = effectiveCenter,
                    viewportWidth = size.width,
                    viewportHeight = size.height,
                    scale = zoomScale,
                )
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(
                        topLeft.x.roundToInt(),
                        topLeft.y.roundToInt(),
                    ),
                    dstSize = IntSize(
                        (imageWidth * zoomScale).roundToInt().coerceAtLeast(1),
                        (imageHeight * zoomScale).roundToInt().coerceAtLeast(1),
                    ),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }

        // Semantics remain on the shared viewport even though Canvas draws the bitmap.
        Text(
            text = contentDescription,
            modifier = Modifier.alpha(0f),
        )

        overlayLabel?.let { label ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(7.dp),
                color = palette.selectedBackground,
                contentColor = palette.selectedForeground,
                border = BorderStroke(1.dp, palette.foreground),
                shape = ImagePanelShape,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.7.sp,
                )
            }
        }
    }
}

@Composable
private fun ImageCorrectionDialog(
    frame: DecodedImageFrame,
    initialCorrection: ImageCorrection,
    sourceColorPreview: Boolean,
    palette: ImageDecoderPalette,
    onCancel: () -> Unit,
    onAccept: (ImageCorrection) -> Unit,
) {
    var draft by remember(frame.revision, initialCorrection) {
        mutableStateOf(initialCorrection)
    }
    val (previewFrame, previewScale) = remember(frame.revision) {
        correctionPreviewFrame(frame)
    }
    val correctedPreviewFrame = remember(previewFrame, previewScale, draft) {
        applyImageCorrection(previewFrame, draft.scaled(previewScale))
    }
    val previewBitmap = remember(
        correctedPreviewFrame,
        sourceColorPreview,
        palette.background,
        palette.foreground,
    ) {
        if (sourceColorPreview) {
            colorImage(correctedPreviewFrame, palette.background.toArgb())
        } else {
            monochromePreview(correctedPreviewFrame, palette)
        }
    }
    val skewLimit = expandedSkewLimit(frame.width)
    val offsetLimit = (frame.width / 2f).coerceAtLeast(1f)

    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp),
            color = palette.background,
            contentColor = palette.foreground,
            border = BorderStroke(1.dp, palette.foreground),
            shape = ImagePanelShape,
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "IMAGE CORRECTION",
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )

                Spacer(Modifier.height(8.dp))

                CorrectionPreview(
                    bitmap = previewBitmap,
                    palette = palette,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp),
                )

                Spacer(Modifier.height(6.dp))

                SkewCorrectionSlider(
                    skewPixels = draft.skewPixels,
                    skewLimit = skewLimit,
                    palette = palette,
                    onSkewChange = { value ->
                        draft = draft.copy(skewPixels = value.roundToInt().toFloat())
                    },
                )
                ImageCorrectionSlider(
                    label = "OFFSET",
                    valueText = "${draft.offsetPixels.roundToInt()} px",
                    value = draft.offsetPixels,
                    valueRange = -offsetLimit..offsetLimit,
                    palette = palette,
                    onValueChange = { value ->
                        draft = draft.copy(offsetPixels = value.roundToInt().toFloat())
                    },
                )
                ImageCorrectionSlider(
                    label = "BRIGHT",
                    valueText = signedValue(draft.brightness),
                    value = draft.brightness,
                    valueRange = -100f..100f,
                    palette = palette,
                    onValueChange = { value ->
                        draft = draft.copy(brightness = value.roundToInt().toFloat())
                    },
                )
                ImageCorrectionSlider(
                    label = "CONTRAST",
                    valueText = signedValue(draft.contrast),
                    value = draft.contrast,
                    valueRange = -80f..100f,
                    palette = palette,
                    onValueChange = { value ->
                        draft = draft.copy(contrast = value.roundToInt().toFloat())
                    },
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ImageButton(
                        text = "RESET",
                        selected = false,
                        palette = palette,
                        enabled = !draft.neutral,
                        onClick = { draft = ImageCorrection() },
                        modifier = Modifier.weight(1f),
                    )
                    ImageButton(
                        text = "CANCEL",
                        selected = false,
                        palette = palette,
                        enabled = true,
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                    )
                    ImageButton(
                        text = "OK",
                        selected = true,
                        palette = palette,
                        enabled = true,
                        onClick = { onAccept(draft) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CorrectionPreview(
    bitmap: Bitmap,
    palette: ImageDecoderPalette,
    modifier: Modifier = Modifier,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Canvas(
        modifier = modifier
            .background(palette.background)
            .border(1.dp, palette.foreground),
    ) {
        val placement = fitPlacement(
            viewportWidth = size.width,
            viewportHeight = size.height,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            bottomAligned = false,
        )
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(
                placement.left.roundToInt(),
                placement.top.roundToInt(),
            ),
            dstSize = IntSize(
                placement.width.roundToInt().coerceAtLeast(1),
                placement.height.roundToInt().coerceAtLeast(1),
            ),
            filterQuality = FilterQuality.Medium,
        )
    }
}

@Composable
private fun SkewCorrectionSlider(
    skewPixels: Float,
    skewLimit: Float,
    palette: ImageDecoderPalette,
    onSkewChange: (Float) -> Unit,
) {
    val sliderPosition = skewToSliderPosition(skewPixels, skewLimit)
    ImageCorrectionSlider(
        label = "SKEW",
        valueText = "${skewPixels.roundToInt()} px",
        value = sliderPosition,
        valueRange = -1f..1f,
        palette = palette,
        onValueChange = { position ->
            onSkewChange(sliderPositionToSkew(position, skewLimit))
        },
    )
}

@Composable
private fun ImageCorrectionSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    palette: ImageDecoderPalette,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = valueText,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = palette.foreground,
                activeTrackColor = palette.foreground,
                inactiveTrackColor = palette.muted,
            ),
        )
    }
}

private fun signedValue(value: Float): String {
    val rounded = value.roundToInt()
    return if (rounded > 0) "+$rounded" else rounded.toString()
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
    val renderedHeight = renderedImageHeight(frame)
    val output = IntArray(frame.width * renderedHeight)
    val completedLines = frame.completedLines.coerceIn(0, renderedHeight)

    // Preserve decoded luminance polarity in every theme: black signal content
    // remains black and white content remains bright. Light mode therefore uses
    // normal black-to-white grayscale instead of reversing the image. Dark mode
    // uses black-to-white, while hue mode uses black-to-selected-hue.
    for (y in 0 until renderedHeight) {
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
        renderedHeight,
        Bitmap.Config.ARGB_8888,
    )
}

private fun colorImage(
    frame: DecodedImageFrame,
    incompleteArgb: Int = Color.Black.toArgb(),
): Bitmap {
    val output = sourceColorPreviewPixels(frame, incompleteArgb)
    return Bitmap.createBitmap(
        output,
        frame.width,
        renderedImageHeight(frame),
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

private fun saveCompletedImage(context: android.content.Context, bitmap: Bitmap): Boolean {
    val result = runCatching {
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
    return result.isSuccess
}

private fun loadPickedImage(
    context: android.content.Context,
    uri: android.net.Uri,
): DecodedImageFrame {
    val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input)
    } ?: error("Could not decode the selected image")
    try {
        require(bitmap.width > 0 && bitmap.height > 0) {
            "Selected image has invalid dimensions"
        }
        val pixelCount = bitmap.width.toLong() * bitmap.height.toLong()
        require(pixelCount <= Int.MAX_VALUE) {
            "Selected image is too large"
        }
        val pixels = IntArray(pixelCount.toInt())
        bitmap.getPixels(
            pixels,
            0,
            bitmap.width,
            0,
            0,
            bitmap.width,
            bitmap.height,
        )
        return DecodedImageFrame(
            width = bitmap.width,
            height = bitmap.height,
            argbPixels = pixels,
            completedLines = bitmap.height,
            revision = System.nanoTime(),
        )
    } finally {
        bitmap.recycle()
    }
}

private fun compactStatus(state: ImageDecoderState): String {
    val session = if (
        state.signal == ImageSignalState.COMPLETE &&
        state.image?.let { it.continuous || it.completedLines >= it.height } == true
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
    state.autosaveError?.let { return "AUTOSAVE FAILED • $it" }
    val frame = state.image
    if (state.recoveredCheckpoint && frame != null) {
        val dimensions = if (frame.continuous) {
            "${frame.completedLines} LINES"
        } else {
            "${frame.completedLines}/${frame.height} LINES"
        }
        return "RECOVERED CHECKPOINT • $dimensions • SAVE OR LISTEN TO CONTINUE"
    }
    val adaptive = state.frequencyCorrectionHz?.let { correction ->
        val sign = if (correction >= 0) "+" else ""
        " • CORR $sign${correction} Hz • ${state.decoderConfidence}%"
    }.orEmpty()
    return when {
        state.signal == ImageSignalState.COMPLETE && frame?.continuous == true ->
            if (state.listening) {
                "${state.detectedMode ?: "WEFAX"} COMPLETE • ${frame.completedLines} LINES • LISTENING FOR NEXT PAGE"
            } else {
                "${state.detectedMode ?: "WEFAX"} COMPLETE • ${frame.completedLines} LINES"
            }

        state.signal == ImageSignalState.DECODING && frame?.continuous == true ->
            "${state.detectedMode ?: "WEFAX"} • ${frame.completedLines} LINES • LISTENING"

        state.signal == ImageSignalState.COMPLETE && frame != null ->
            if (state.listening) {
                "${state.detectedMode ?: "SSTV"} COMPLETE • ${frame.completedLines}/${frame.height} LINES • LISTENING FOR NEXT VIS$adaptive"
            } else {
                "${state.detectedMode ?: "SSTV"} COMPLETE • ${frame.completedLines}/${frame.height} LINES$adaptive"
            }

        state.signal == ImageSignalState.DECODING && frame != null ->
            "${state.detectedMode ?: "SSTV"} • ${frame.completedLines}/${frame.height} LINES$adaptive"

        state.listening && state.decoder == ImageDecoderSelection.WEFAX ->
            "WX IOC 576 / 120 LPM • PHASE ACQUIRE; STOP TO FINISH"
        state.listening && state.decoder == ImageDecoderSelection.SSTV ->
            "R36 LIVE MANUAL SYNC; TAP ??, M1, OR M2 TO SWITCH"
        state.listening && state.decoder == ImageDecoderSelection.MARTIN_M1 ->
            "M1 LIVE MANUAL SYNC; TAP ??, R36, OR M2 TO SWITCH"
        state.listening && state.decoder == ImageDecoderSelection.MARTIN_M2 ->
            "M2 LIVE MANUAL SYNC; TAP ??, R36, OR M1 TO SWITCH"
        state.listening -> "LISTENING FOR SSTV VIS; LIVE MANUAL SWITCH AVAILABLE"
        !microphonePermissionGranted -> "MIC PERMISSION REQUESTS ONLY AFTER LISTEN"
        state.decoder == ImageDecoderSelection.WEFAX -> "WX IOC 576 / 120 LPM MANUAL READY"
        else -> "R36 + M1 + M2 + S1 + S2 AUTO/LIVE MANUAL READY"
    }
}

