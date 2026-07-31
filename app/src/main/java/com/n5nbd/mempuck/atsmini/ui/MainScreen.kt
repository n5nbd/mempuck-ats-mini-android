package com.n5nbd.mempuck.atsmini.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs
import kotlin.math.roundToInt

private val PanelShape = RoundedCornerShape(5.dp)

enum class ThemeChoice {
    Dark,
    Light,
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

private fun colorsFor(theme: ThemeChoice): PuckColors = when (theme) {
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
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    permissionsGranted: Boolean,
    requestPermissions: () -> Unit,
    themeChoice: ThemeChoice,
    onThemeChoice: (ThemeChoice) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AppTab.Radio) }
    val colors = colorsFor(themeChoice)

    MaterialTheme {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state.vfoScanning) {
                    if (!state.vfoScanning) return@pointerInput
                    awaitPointerEventScope {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial,
                        )
                        down.consume()
                        viewModel.stopVfoScan()
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

        StatusBlock(state, colors)

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
    val digits = frequencyDigits(frequencyHz)
    val fmMode = AtsFrequencyPlan.regionFor(frequencyHz) == AtsFrequencyRegion.BroadcastFm
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        digits.forEachIndexed { index, digit ->
            val place = POWERS_OF_TEN[digits.length - index - 1]
            val showArrows = !fmMode || place >= AtsFrequencyPlan.FM_TUNING_RESOLUTION_HZ
            DigitControl(
                digit = digit,
                enabled = enabled,
                showArrows = showArrows,
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
            val digitsRemaining = digits.length - index - 1
            if (digitsRemaining > 0 && digitsRemaining % 3 == 0) {
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
        Slider(
            value = sliderValue,
            onValueChange = {
                dragging = true
                sliderValue = it
            },
            onValueChangeFinished = {
                dragging = false
                onVolumeSelected(sliderValue.roundToInt())
            },
            enabled = enabled,
            valueRange = 0f..63f,
            colors = SliderDefaults.colors(
                thumbColor = colors.foreground,
                activeTrackColor = colors.foreground,
                inactiveTrackColor = colors.muted,
                disabledThumbColor = colors.muted,
                disabledActiveTrackColor = colors.muted,
                disabledInactiveTrackColor = colors.muted.copy(alpha = 0.45f),
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = sliderValue.roundToInt().toString().padStart(2, '0'),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            fontSize = 16.sp,
        )
    }
}

@Composable
private fun StatusBlock(state: RadioSnapshot, colors: PuckColors) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, colors.foreground, PanelShape)
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
    startScan: () -> Unit,
    stopScan: () -> Unit,
    disconnect: () -> Unit,
    probeCapability: () -> Unit,
    connect: (com.n5nbd.mempuck.atsmini.model.AtsDevice) -> Unit,
) {
    PuckPanel(colors = colors) {
        SectionTitle("DISPLAY")
        Text("MemPuck layout and interaction remain identical across themes.")
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
                text = "DISCONNECT",
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

    PuckPanel(colors = colors) {
        SectionTitle("PROTOCOL LOG")
        if (state.log.isEmpty()) {
            Text("No protocol traffic yet", color = colors.muted)
        } else {
            state.log.takeLast(40).forEach { line ->
                Text(
                    text = line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
            }
        }
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
        LinkState.Disconnected -> "DISCONNECTED — OPEN CONFIG"
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
        CapabilityState.NotChecked -> "Connect in CONFIG to begin"
    }
}

private fun canTune(state: RadioSnapshot): Boolean =
    state.link is LinkState.Ready && state.capability is CapabilityState.Supported

private fun frequencyDigits(frequencyHz: Long): String =
    frequencyHz.toString().padStart(if (frequencyHz >= 100_000_000L) 9 else 8, '0')

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

private val LOW_BAND_MODES = listOf(
    RadioMode.LSB,
    RadioMode.USB,
    RadioMode.CW,
    RadioMode.AM,
)
