package com.n5nbd.mempuck.atsmini.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.n5nbd.mempuck.atsmini.model.AtsFrequencyPlan
import com.n5nbd.mempuck.atsmini.model.AtsFrequencyRegion
import com.n5nbd.mempuck.atsmini.model.CapabilityState
import com.n5nbd.mempuck.atsmini.model.LinkState
import com.n5nbd.mempuck.atsmini.model.RadioMode
import com.n5nbd.mempuck.atsmini.model.RadioSnapshot
import com.n5nbd.mempuck.atsmini.model.StatusStreamState
import com.n5nbd.mempuck.atsmini.model.TuneState
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

private val PanelShape = RoundedCornerShape(5.dp)

enum class ThemeChoice {
    Dark,
    Light,
    Hue,
}

private enum class AppTab(val label: String) {
    Radio("RADIO"),
    List("LIST"),
    Source("SOURCE"),
    Config("CONFIG"),
}

private data class PuckColors(
    val background: Color,
    val foreground: Color,
    val selectedBackground: Color,
    val selectedForeground: Color,
    val muted: Color,
)

private fun colorsFor(theme: ThemeChoice, hueDegrees: Float): PuckColors = when (theme) {
    ThemeChoice.Dark -> PuckColors(
        background = Color.Black,
        foreground = Color.White,
        selectedBackground = Color.White,
        selectedForeground = Color.Black,
        muted = Color(0xFFBDBDBD),
    )

    ThemeChoice.Light -> PuckColors(
        background = Color.White,
        foreground = Color.Black,
        selectedBackground = Color.Black,
        selectedForeground = Color.White,
        muted = Color(0xFF555555),
    )

    ThemeChoice.Hue -> {
        val hue = Color.hsv(
            hue = hueDegrees.coerceIn(0f, 359.9f),
            saturation = 1f,
            value = 1f,
        )
        PuckColors(
            background = Color.Black,
            foreground = hue,
            selectedBackground = hue,
            selectedForeground = Color.Black,
            muted = hue,
        )
    }
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    permissionsGranted: Boolean,
    requestPermissions: () -> Unit,
    themeChoice: ThemeChoice,
    onThemeChoice: (ThemeChoice) -> Unit,
    hueDegrees: Float,
    onHueDegrees: (Float) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AppTab.Radio) }
    var interactionSequence by remember { mutableStateOf(0L) }
    var scanAfterPermission by rememberSaveable { mutableStateOf(false) }
    val colors = colorsFor(themeChoice, hueDegrees)
    val keepControllerAwake = state.link is LinkState.Ready

    ConnectedScreenGuard(
        connected = keepControllerAwake,
        interactionSequence = interactionSequence,
    )

    LaunchedEffect(permissionsGranted, scanAfterPermission) {
        if (permissionsGranted && scanAfterPermission) {
            scanAfterPermission = false
            viewModel.startScan()
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.vfoScanning) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                            interactionSequence += 1L
                            if (state.vfoScanning) {
                                down.consume()
                                viewModel.stopVfoScan()
                            }
                        }
                    }
                },
            color = colors.background,
            contentColor = colors.foreground,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Header(
                    state = state,
                    selectedTab = tab,
                    colors = colors,
                    onTabSelected = { tab = it },
                )

                when (tab) {
                    AppTab.Radio -> RadioScreen(
                        state = state,
                        colors = colors,
                        tuneFrequency = viewModel::tuneFrequency,
                        selectLowBandMode = viewModel::selectLowBandMode,
                        startVfoScan = viewModel::startVfoScan,
                        stopVfoScan = viewModel::stopVfoScan,
                        setVolume = viewModel::setVolume,
                        openConfigAndScan = {
                            tab = AppTab.Config
                            if (permissionsGranted) {
                                viewModel.startScan()
                            } else {
                                scanAfterPermission = true
                                requestPermissions()
                            }
                        },
                    )
                    AppTab.List -> PlaceholderScreen(
                        title = "LIST",
                        message = "Personal memories arrive after the live VFO control loop is proven.",
                        colors = colors,
                    )
                    AppTab.Source -> PlaceholderScreen(
                        title = "SOURCE",
                        message = "Curated libraries and temporary scan queues arrive in a later slice.",
                        colors = colors,
                    )
                    AppTab.Config -> ConfigScreen(
                        state = state,
                        colors = colors,
                        permissionsGranted = permissionsGranted,
                        requestPermissions = requestPermissions,
                        themeChoice = themeChoice,
                        onThemeChoice = onThemeChoice,
                        hueDegrees = hueDegrees,
                        onHueDegrees = onHueDegrees,
                        startScan = viewModel::startScan,
                        stopScan = viewModel::stopScan,
                        disconnect = viewModel::disconnect,
                        probeCapability = viewModel::probeCapability,
                        connect = viewModel::connect,
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(
    state: RadioSnapshot,
    selectedTab: AppTab,
    colors: PuckColors,
    onTabSelected: (AppTab) -> Unit,
) {
    PuckPanel(colors = colors) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text(
                    text = "MemPuck",
                    fontSize = 25.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    text = "ATS Mini Radio Controller",
                    fontSize = 16.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "ATS MINI",
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = headerLinkText(state),
                    fontSize = 14.sp,
                    color = colors.muted,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            AppTab.entries.forEach { tab ->
                PuckButton(
                    text = tab.label,
                    selected = selectedTab == tab,
                    colors = colors,
                    onClick = { onTabSelected(tab) },
                    modifier = Modifier.weight(1f),
                    height = 55.dp,
                )
            }
        }
    }
}

@Composable
private fun RadioScreen(
    state: RadioSnapshot,
    colors: PuckColors,
    tuneFrequency: (Long) -> Unit,
    selectLowBandMode: (RadioMode) -> Unit,
    startVfoScan: (Long) -> Unit,
    stopVfoScan: () -> Unit,
    setVolume: (Int) -> Unit,
    openConfigAndScan: () -> Unit,
) {
    var memoryMode by rememberSaveable { mutableStateOf(false) }
    val vfoEnabled = !memoryMode && canTune(state)

    DisposableEffect(Unit) {
        onDispose(stopVfoScan)
    }

    PuckPanel(colors = colors) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "OPERATE",
                fontSize = 21.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = state.status?.let { "S ${it.rssi} / N ${it.snr}" } ?: "S -- / N --",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(18.dp))

        FrequencyDigits(
            frequencyHz = state.targetFrequencyHz,
            enabled = vfoEnabled,
            colors = colors,
            onChange = tuneFrequency,
            onScanStart = startVfoScan,
        )

        Spacer(Modifier.height(16.dp))

        DirectFrequencyEntry(
            frequencyHz = state.targetFrequencyHz,
            enabled = vfoEnabled,
            colors = colors,
            onTune = tuneFrequency,
        )

        Spacer(Modifier.height(10.dp))

        if (AtsFrequencyPlan.regionFor(state.targetFrequencyHz) == AtsFrequencyRegion.LowBand) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LOW_BAND_MODES.forEach { mode ->
                    PuckButton(
                        text = mode.label,
                        selected = state.selectedMode == mode,
                        colors = colors,
                        enabled = vfoEnabled,
                        onClick = { selectLowBandMode(mode) },
                        modifier = Modifier.weight(1f),
                        height = 57.dp,
                        fontSize = 17.sp,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
        }

        StatusBlock(
            state = state,
            colors = colors,
            onDisconnectedClick = openConfigAndScan,
        )

        Spacer(Modifier.height(10.dp))

        val stepHz = statusStepHz(state.status?.step)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            PuckButton(
                text = "<<",
                colors = colors,
                enabled = vfoEnabled,
                onClick = { tuneOffset(state, -stepHz * 10L, tuneFrequency) },
                modifier = Modifier.weight(1f),
                height = 57.dp,
            )
            PuckButton(
                text = "<",
                colors = colors,
                enabled = vfoEnabled,
                onClick = { tuneOffset(state, -stepHz, tuneFrequency) },
                modifier = Modifier.weight(1f),
                height = 57.dp,
            )
            PuckButton(
                text = if (memoryMode) "MEM" else "VFO",
                selected = true,
                colors = colors,
                onClick = {
                    stopVfoScan()
                    memoryMode = !memoryMode
                },
                modifier = Modifier.weight(1.4f),
                height = 57.dp,
            )
            PuckButton(
                text = ">",
                colors = colors,
                enabled = vfoEnabled,
                onClick = { tuneOffset(state, stepHz, tuneFrequency) },
                modifier = Modifier.weight(1f),
                height = 57.dp,
            )
            PuckButton(
                text = ">>",
                colors = colors,
                enabled = vfoEnabled,
                onClick = { tuneOffset(state, stepHz * 10L, tuneFrequency) },
                modifier = Modifier.weight(1f),
                height = 57.dp,
            )
        }

        Spacer(Modifier.height(10.dp))

        VolumeControl(
            reportedVolume = state.status?.volume,
            enabled = state.link is LinkState.Ready && state.status != null,
            colors = colors,
            onVolumeSelected = setVolume,
        )
    }
}

@Composable
private fun FrequencyDigits(
    frequencyHz: Long,
    enabled: Boolean,
    colors: PuckColors,
    onChange: (Long) -> Unit,
    onScanStart: (Long) -> Unit,
) {
    val wheel = frequencyWheelSpec(frequencyHz)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        wheel.digits.forEachIndexed { index, digit ->
            val place = wheel.placesHz[index]
            DigitControl(
                digit = digit,
                enabled = enabled,
                showArrows = true,
                colors = colors,
                onUp = {
                    onChange(
                        AtsFrequencyPlan.normalizeInteractiveFrequency(
                            currentFrequencyHz = frequencyHz,
                            candidateFrequencyHz = frequencyHz + place,
                        ),
                    )
                },
                onDown = {
                    onChange(
                        AtsFrequencyPlan.normalizeInteractiveFrequency(
                            currentFrequencyHz = frequencyHz,
                            candidateFrequencyHz = frequencyHz - place,
                        ),
                    )
                },
                onScanUp = { onScanStart(place) },
                onScanDown = { onScanStart(-place) },
                modifier = Modifier.weight(1f),
            )
            if (index in wheel.separatorAfter) {
                Text(
                    text = ".",
                    modifier = Modifier.width(13.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 25.sp,
                )
            }
        }
    }
}

@Composable
private fun DigitControl(
    digit: Char,
    enabled: Boolean,
    showArrows: Boolean,
    colors: PuckColors,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onScanUp: () -> Unit,
    onScanDown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .height(116.dp)
            .border(2.dp, colors.foreground, PanelShape)
            .alpha(if (enabled) 1f else 0.55f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showArrows) {
            DigitArea(
                text = "▲",
                colors = colors,
                enabled = enabled,
                onClick = onUp,
                onHoldStart = onScanUp,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.foreground),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.45f),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = digit.toString(),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 28.sp,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.foreground),
        )
        if (showArrows) {
            DigitArea(
                text = "▼",
                colors = colors,
                enabled = enabled,
                onClick = onDown,
                onHoldStart = onScanDown,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun DigitArea(
    text: String,
    colors: PuckColors,
    enabled: Boolean,
    onClick: () -> Unit,
    onHoldStart: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(enabled, onClick, onHoldStart) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onLongPress = { onHoldStart() },
                    onTap = { onClick() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = colors.foreground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun DirectFrequencyEntry(
    frequencyHz: Long,
    enabled: Boolean,
    colors: PuckColors,
    onTune: (Long) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(formatFrequencyHz(frequencyHz)) }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(frequencyHz) {
        value = formatFrequencyHz(frequencyHz)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .border(2.dp, colors.foreground, PanelShape)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { input ->
                value = input.filter { it.isDigit() || it == '.' || it == ',' || it == ' ' }
            },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(
                color = colors.foreground,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Black,
                fontSize = 29.sp,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    parseFrequencyText(value)?.let(onTune)
                    focusManager.clearFocus()
                },
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun VolumeControl(
    reportedVolume: Int?,
    enabled: Boolean,
    colors: PuckColors,
    onVolumeSelected: (Int) -> Unit,
) {
    var sliderValue by remember { mutableFloatStateOf((reportedVolume ?: 0).toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(reportedVolume, dragging) {
        if (!dragging && reportedVolume != null) {
            sliderValue = reportedVolume.toFloat()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "VOL",
            fontWeight = FontWeight.Black,
            fontSize = 15.sp,
        )
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val pointerId = down.id
                        val edge = 10.dp.toPx()
                        val usableWidth = (size.width - edge * 2f).coerceAtLeast(1f)
                        var pendingVolume = sliderValue.roundToInt().coerceIn(0, 63)

                        fun updateFromX(x: Float) {
                            val fraction = ((x - edge) / usableWidth).coerceIn(0f, 1f)
                            pendingVolume = (fraction * 63f).roundToInt().coerceIn(0, 63)
                            dragging = true
                            sliderValue = pendingVolume.toFloat()
                        }

                        updateFromX(down.position.x)
                        down.consume()

                        var released = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break
                            updateFromX(change.position.x)
                            change.consume()
                            if (!change.pressed) {
                                released = true
                                break
                            }
                        }

                        dragging = false
                        if (released) {
                            onVolumeSelected(pendingVolume)
                        }
                    }
                },
        ) {
            val edge = 10.dp.toPx()
            val centerY = size.height / 2f
            val startX = edge
            val endX = (size.width - edge).coerceAtLeast(startX)
            val fraction = (sliderValue / 63f).coerceIn(0f, 1f)
            val thumbX = startX + ((endX - startX) * fraction)
            val trackWidth = 4.dp.toPx()

            drawLine(
                color = colors.muted,
                start = Offset(startX, centerY),
                end = Offset(endX, centerY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = colors.foreground,
                start = Offset(startX, centerY),
                end = Offset(thumbX, centerY),
                strokeWidth = trackWidth,
                cap = StrokeCap.Round,
            )
            drawCircle(
                color = colors.foreground,
                radius = 9.dp.toPx(),
                center = Offset(thumbX, centerY),
            )
        }
        Text(
            text = sliderValue.roundToInt().toString().padStart(2, '0'),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun StatusBlock(
    state: RadioSnapshot,
    colors: PuckColors,
    onDisconnectedClick: () -> Unit,
) {
    val disconnected = state.link is LinkState.Disconnected
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, colors.foreground, PanelShape)
            .clickable(enabled = disconnected, onClick = onDisconnectedClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = primaryStatusLine(state),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = secondaryStatusLine(state),
            fontSize = 15.sp,
            color = colors.muted,
        )
    }
}

@Composable
private fun ConfigScreen(
    state: RadioSnapshot,
    colors: PuckColors,
    permissionsGranted: Boolean,
    requestPermissions: () -> Unit,
    themeChoice: ThemeChoice,
    onThemeChoice: (ThemeChoice) -> Unit,
    hueDegrees: Float,
    onHueDegrees: (Float) -> Unit,
    startScan: () -> Unit,
    stopScan: () -> Unit,
    disconnect: () -> Unit,
    probeCapability: () -> Unit,
    connect: (com.n5nbd.mempuck.atsmini.model.AtsDevice) -> Unit,
) {
    PuckPanel(colors = colors) {
        SectionTitle("DISPLAY")
        Spacer(Modifier.height(9.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PuckButton(
                text = "DARK",
                selected = themeChoice == ThemeChoice.Dark,
                colors = colors,
                onClick = { onThemeChoice(ThemeChoice.Dark) },
                modifier = Modifier.weight(1f),
            )
            PuckButton(
                text = "LIGHT",
                selected = themeChoice == ThemeChoice.Light,
                colors = colors,
                onClick = { onThemeChoice(ThemeChoice.Light) },
                modifier = Modifier.weight(1f),
            )
            PuckButton(
                text = "HUE",
                selected = themeChoice == ThemeChoice.Hue,
                colors = colors,
                onClick = { onThemeChoice(ThemeChoice.Hue) },
                modifier = Modifier.weight(1f),
            )
        }

        if (themeChoice == ThemeChoice.Hue) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "HUE",
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                )
                Slider(
                    value = hueDegrees,
                    onValueChange = onHueDegrees,
                    valueRange = 0f..359f,
                    colors = SliderDefaults.colors(
                        thumbColor = colors.foreground,
                        activeTrackColor = colors.foreground,
                        inactiveTrackColor = colors.foreground,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${hueDegrees.roundToInt().toString().padStart(3, '0')}°",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 15.sp,
                )
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    PuckPanel(colors = colors) {
        SectionTitle("ATS MINI")
        Text("Receiver: Settings → Bluetooth → Ad hoc")
        Spacer(Modifier.height(7.dp))
        Text("LINK: ${headerLinkText(state)}", fontWeight = FontWeight.Bold)
        Text("Z: ${capabilityText(state.capability)}")
        Text("STATUS: ${state.statusStream.name.uppercase()}")

        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (!permissionsGranted) {
                PuckButton(
                    text = "GRANT BLE",
                    colors = colors,
                    onClick = requestPermissions,
                    modifier = Modifier.weight(1f),
                )
            } else {
                PuckButton(
                    text = if (state.scanning) "SCANNING" else "SCAN",
                    colors = colors,
                    enabled = !state.scanning,
                    onClick = startScan,
                    modifier = Modifier.weight(1f),
                )
                PuckButton(
                    text = "STOP",
                    colors = colors,
                    enabled = state.scanning,
                    onClick = stopScan,
                    modifier = Modifier.weight(1f),
                )
            }
            PuckButton(
                text = "DISCO",
                colors = colors,
                enabled = state.link !is LinkState.Disconnected,
                onClick = disconnect,
                modifier = Modifier.weight(1.2f),
            )
        }

        if (state.link is LinkState.Ready) {
            Spacer(Modifier.height(7.dp))
            PuckButton(
                text = "PROBE Z?",
                colors = colors,
                onClick = probeCapability,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (state.devices.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("DISCOVERED", fontWeight = FontWeight.Black)
            Spacer(Modifier.height(5.dp))
            state.devices.forEach { device ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(device.name ?: "ATS Mini", fontWeight = FontWeight.Bold)
                        Text("${device.address}  ${device.rssi} dBm", color = colors.muted, fontSize = 12.sp)
                    }
                    PuckButton(
                        text = "CONNECT",
                        colors = colors,
                        onClick = { connect(device) },
                        modifier = Modifier.width(116.dp),
                        height = 45.dp,
                        fontSize = 14.sp,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))

    var debugExpanded by rememberSaveable { mutableStateOf(false) }
    val protocolScroll = rememberScrollState()

    LaunchedEffect(debugExpanded, state.log.size) {
        if (debugExpanded) {
            protocolScroll.scrollTo(protocolScroll.maxValue)
        }
    }

    PuckPanel(colors = colors) {
        PuckButton(
            text = "DEBUG",
            selected = debugExpanded,
            colors = colors,
            onClick = { debugExpanded = !debugExpanded },
            modifier = Modifier.fillMaxWidth(),
        )

        if (debugExpanded) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .verticalScroll(protocolScroll),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (state.log.isEmpty()) {
                    Text("No protocol traffic yet", color = colors.muted)
                } else {
                    state.log.takeLast(80).forEach { line ->
                        Text(
                            text = line,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectedScreenGuard(
    connected: Boolean,
    interactionSequence: Long,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    DisposableEffect(activity, connected) {
        val window = activity?.window
        if (window != null && connected) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.setMemPuckBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }

        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.setMemPuckBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        }
    }

    LaunchedEffect(activity, connected, interactionSequence) {
        val window = activity?.window ?: return@LaunchedEffect
        if (!connected) {
            window.setMemPuckBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            return@LaunchedEffect
        }

        window.setMemPuckBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        delay(CONNECTED_SCREEN_DIM_DELAY_MS)
        window.setMemPuckBrightness(CONNECTED_SCREEN_DIM_BRIGHTNESS)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun android.view.Window.setMemPuckBrightness(brightness: Float) {
    attributes = attributes.apply {
        screenBrightness = brightness
    }
}

@Composable
private fun PlaceholderScreen(title: String, message: String, colors: PuckColors) {
    PuckPanel(colors = colors) {
        SectionTitle(title)
        Spacer(Modifier.height(12.dp))
        Text(message, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        Text("NOT YET IN THIS BUILD", color = colors.muted, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 21.sp,
        fontWeight = FontWeight.Black,
    )
}

@Composable
private fun PuckPanel(
    colors: PuckColors,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(BorderStroke(2.dp, colors.foreground), PanelShape)
            .padding(10.dp),
        content = content,
    )
}

@Composable
private fun PuckButton(
    text: String,
    colors: PuckColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 50.dp,
    fontSize: androidx.compose.ui.unit.TextUnit = 18.sp,
) {
    val background = if (selected) colors.selectedBackground else colors.background
    val foreground = if (selected) colors.selectedForeground else colors.foreground
    Box(
        modifier = modifier
            .height(height)
            .background(background, PanelShape)
            .border(2.dp, colors.foreground, PanelShape)
            .alpha(if (enabled || selected) 1f else 0.42f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = foreground,
            fontSize = fontSize,
            fontWeight = FontWeight.Black,
        )
    }
}

private fun headerLinkText(state: RadioSnapshot): String = when (val link = state.link) {
    LinkState.Disconnected -> "DISCONNECTED"
    LinkState.Connecting -> "CONNECTING"
    is LinkState.Ready -> when (val capability = state.capability) {
        is CapabilityState.Supported -> "READY • Z${capability.version}"
        CapabilityState.Checking -> "READY • CHECKING"
        else -> "READY"
    }
    is LinkState.Failed -> "ERROR"
}

private fun capabilityText(capability: CapabilityState): String = when (capability) {
    CapabilityState.NotChecked -> "NOT CHECKED"
    CapabilityState.Checking -> "CHECKING"
    is CapabilityState.Supported -> "SUPPORTED V${capability.version}"
    is CapabilityState.Unsupported -> "NOT DETECTED"
}

private fun primaryStatusLine(state: RadioSnapshot): String = when (val tune = state.tuneState) {
    is TuneState.Sending -> "TUNING ${formatFrequencyHz(tune.frequencyHz)} ${tune.logicalMode.label}"
    is TuneState.Confirmed -> "${formatFrequencyHz(tune.frequencyHz)} ${tune.logicalMode.label}"
    is TuneState.Failed -> "TUNE ERROR: ${tune.message}"
    TuneState.Idle -> state.status?.let {
        "${it.bandName} • ${formatFrequencyHz(it.frequencyHz)} ${state.selectedMode.label}"
    } ?: when (state.link) {
        LinkState.Disconnected -> "YOU'RE DISCONNECTED. TAP HERE TO CONNECT."
        LinkState.Connecting -> "CONNECTING TO ATS MINI"
        is LinkState.Ready -> "ATS MINI READY"
        is LinkState.Failed -> "CONNECTION ERROR"
    }
}

private fun secondaryStatusLine(state: RadioSnapshot): String {
    val status = state.status
    if (status != null) {
        return "${status.bandwidth} • STEP ${status.step} • VOL ${status.volume} • ${"%.2f".format(status.voltage)} V • FW ${status.firmwareVersion}"
    }
    return when (val capability = state.capability) {
        is CapabilityState.Supported -> "Absolute tuning supported • Z protocol v${capability.version}"
        is CapabilityState.Unsupported -> capability.detail
        CapabilityState.Checking -> "Checking patched-firmware capability"
        CapabilityState.NotChecked -> if (state.link is LinkState.Disconnected) {
            "OPENS CONFIG AND STARTS THE ATS MINI SCAN"
        } else {
            "Connect in CONFIG to begin"
        }
    }
}

private fun canTune(state: RadioSnapshot): Boolean =
    state.link is LinkState.Ready && state.capability is CapabilityState.Supported

internal data class FrequencyWheelSpec(
    val digits: String,
    val placesHz: List<Long>,
    val separatorAfter: Set<Int>,
)

internal fun frequencyWheelSpec(frequencyHz: Long): FrequencyWheelSpec {
    if (AtsFrequencyPlan.regionFor(frequencyHz) == AtsFrequencyRegion.BroadcastFm) {
        val digits = (frequencyHz / AtsFrequencyPlan.FM_TUNING_RESOLUTION_HZ).toString()
        val places = digits.indices.map { index ->
            POWERS_OF_TEN[digits.length - index - 1] * AtsFrequencyPlan.FM_TUNING_RESOLUTION_HZ
        }
        return FrequencyWheelSpec(
            digits = digits,
            placesHz = places,
            separatorAfter = setOf(digits.length - 3),
        )
    }

    val digits = frequencyHz.toString().padStart(if (frequencyHz >= 100_000_000L) 9 else 8, '0')
    val places = digits.indices.map { index -> POWERS_OF_TEN[digits.length - index - 1] }
    val separators = digits.indices.filterTo(mutableSetOf()) { index ->
        val digitsRemaining = digits.length - index - 1
        digitsRemaining > 0 && digitsRemaining % 3 == 0
    }
    return FrequencyWheelSpec(
        digits = digits,
        placesHz = places,
        separatorAfter = separators,
    )
}

private fun tuneOffset(
    state: RadioSnapshot,
    offsetHz: Long,
    tuneFrequency: (Long) -> Unit,
) {
    val target = AtsFrequencyPlan.normalizeInteractiveFrequency(
        currentFrequencyHz = state.targetFrequencyHz,
        candidateFrequencyHz = state.targetFrequencyHz + offsetHz,
    )
    tuneFrequency(target)
}

private fun statusStepHz(step: String?): Long {
    if (step.isNullOrBlank()) return 1_000L
    val normalized = step.trim().lowercase().replace("hz", "")
    val multiplier = when {
        normalized.endsWith("m") -> 1_000_000L
        normalized.endsWith("k") -> 1_000L
        else -> 1L
    }
    val number = normalized.trimEnd('m', 'k').toDoubleOrNull() ?: return 1_000L
    return abs((number * multiplier).toLong()).coerceAtLeast(1L)
}

private val POWERS_OF_TEN = longArrayOf(
    1L,
    10L,
    100L,
    1_000L,
    10_000L,
    100_000L,
    1_000_000L,
    10_000_000L,
    100_000_000L,
)

private const val CONNECTED_SCREEN_DIM_DELAY_MS = 30_000L
private const val CONNECTED_SCREEN_DIM_BRIGHTNESS = 0.06f

private val LOW_BAND_MODES = listOf(
    RadioMode.LSB,
    RadioMode.USB,
    RadioMode.CW,
    RadioMode.AM,
)
