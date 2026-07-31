package com.n5nbd.mempuck.atsmini.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsTopHeight
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.n5nbd.mempuck.atsmini.BuildConfig
import com.n5nbd.mempuck.atsmini.model.AtsFrequencyPlan
import com.n5nbd.mempuck.atsmini.model.AtsFrequencyRegion
import com.n5nbd.mempuck.atsmini.model.CapabilityState
import com.n5nbd.mempuck.atsmini.model.FrequencySourceFile
import com.n5nbd.mempuck.atsmini.model.FrequencySourceState
import com.n5nbd.mempuck.atsmini.model.LinkState
import com.n5nbd.mempuck.atsmini.model.MemoryEntry
import com.n5nbd.mempuck.atsmini.model.memoryTagTokens
import com.n5nbd.mempuck.atsmini.model.RadioMode
import com.n5nbd.mempuck.atsmini.model.RadioSnapshot
import com.n5nbd.mempuck.atsmini.model.StatusStreamState
import com.n5nbd.mempuck.atsmini.model.StartupReconnectStage
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

enum class VhfVfoStep(
    val stepHz: Long,
    val label: String,
) {
    KHz200(200_000L, "200 KHZ"),
    KHz100(100_000L, "100 KHZ"),
    KHz50(50_000L, "50 KHZ"),
}

enum class HfVfoSmallStep(
    val stepHz: Long,
    val label: String,
) {
    KHz1(1_000L, "1 KHZ"),
    Hz100(100L, "100 HZ"),
    Hz10(10L, "10 HZ"),
    Hz1(1L, "1 HZ"),
}

enum class HfVfoLargeStep(
    val stepHz: Long,
    val label: String,
) {
    MHz1(1_000_000L, "1 MHZ"),
    KHz100(100_000L, "100 KHZ"),
    KHz10(10_000L, "10 KHZ"),
}

enum class ScanDwell(
    val millis: Long,
    val label: String,
) {
    Seconds1(1_000L, "1"),
    Seconds2(2_000L, "2"),
    Seconds5(5_000L, "5"),
    Seconds10(10_000L, "10"),
}

private enum class ConfigSection {
    Display,
    TuningSteps,
    RadioLink,
    Scan,
    Debug,
    About,
}

private enum class AppTab(val label: String) {
    Radio("RADIO"),
    List("LIST"),
    Source("SRC"),
    Config("CFG"),
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
    vhfVfoStep: VhfVfoStep,
    onVhfVfoStep: (VhfVfoStep) -> Unit,
    hfVfoSmallStep: HfVfoSmallStep,
    onHfVfoSmallStep: (HfVfoSmallStep) -> Unit,
    hfVfoLargeStep: HfVfoLargeStep,
    onHfVfoLargeStep: (HfVfoLargeStep) -> Unit,
    scanDwell: ScanDwell,
    onScanDwell: (ScanDwell) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val frequencySources by viewModel.frequencySources.collectAsStateWithLifecycle()
    val memoryScanDirection by viewModel.memoryScanDirection.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(AppTab.Radio) }
    var memoryMode by rememberSaveable { mutableStateOf(false) }
    var selectedMemoryTagsValue by rememberSaveable { mutableStateOf("") }
    var favoriteMemorySelected by rememberSaveable { mutableStateOf(false) }
    var memoryMatchAll by rememberSaveable { mutableStateOf(false) }
    var interactionSequence by remember { mutableStateOf(0L) }
    var scanAfterPermission by rememberSaveable { mutableStateOf(false) }
    val colors = colorsFor(themeChoice, hueDegrees)
    val keepControllerAwake = state.link is LinkState.Ready
    val tuneScanActive = state.vfoScanning || memoryScanDirection != 0
    val availableMemoryTags = memoryTagCloud(memories)
    val selectedMemoryTags = selectedMemoryTagsValue
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
    val visibleMemories = memories.filter { entry ->
        memoryMatchesFilters(
            entry = entry,
            selectedTags = selectedMemoryTags,
            favoriteSelected = favoriteMemorySelected,
            matchAll = memoryMatchAll,
        )
    }
    val visibleMemoryIds = visibleMemories.mapTo(linkedSetOf(), MemoryEntry::id)

    fun setSelectedMemoryTags(tags: Set<String>) {
        selectedMemoryTagsValue = tags.sorted().joinToString(",")
    }

    ConnectedScreenGuard(
        connected = keepControllerAwake,
        interactionSequence = interactionSequence,
    )

    LaunchedEffect(permissionsGranted, scanAfterPermission) {
        if (!permissionsGranted) return@LaunchedEffect
        if (scanAfterPermission) {
            scanAfterPermission = false
            viewModel.startScan()
        } else {
            viewModel.startAutoConnect()
        }
    }

    LaunchedEffect(scanDwell) {
        viewModel.setScanDwellMs(scanDwell.millis)
    }

    LaunchedEffect(availableMemoryTags) {
        val validSelection = selectedMemoryTags.intersect(availableMemoryTags.toSet())
        if (validSelection != selectedMemoryTags) {
            setSelectedMemoryTags(validSelection)
        }
    }

    MaterialTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Spacer(
                modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars),
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(tuneScanActive) {
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(
                                    requireUnconsumed = false,
                                    pass = PointerEventPass.Initial,
                                )
                                interactionSequence += 1L
                                if (tuneScanActive) {
                                    down.consume()
                                    viewModel.stopTuneScan()
                                }
                            }
                        }
                    },
                color = colors.background,
                contentColor = colors.foreground,
            ) {
                if (state.startupReconnectStage != StartupReconnectStage.Idle) {
                    StartupReconnectScreen(
                        stage = state.startupReconnectStage,
                        colors = colors,
                    )
                } else {
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
                            memories = memories,
                            visibleMemories = visibleMemories,
                            memoryMode = memoryMode,
                            onMemoryModeChanged = { memoryMode = it },
                            colors = colors,
                            tuneFrequency = viewModel::tuneFrequency,
                            selectLowBandMode = viewModel::selectLowBandMode,
                            startVfoScan = viewModel::startVfoScan,
                            memoryScanDirection = memoryScanDirection,
                            stepMemory = { direction ->
                                viewModel.stepMemory(direction, visibleMemoryIds)
                            },
                            startMemoryScan = { direction ->
                                viewModel.startMemoryScan(direction, visibleMemoryIds)
                            },
                            stopTuneScan = viewModel::stopTuneScan,
                            vhfVfoStep = vhfVfoStep,
                            hfVfoSmallStep = hfVfoSmallStep,
                            hfVfoLargeStep = hfVfoLargeStep,
                            setVolume = viewModel::setVolume,
                            createMemory = viewModel::createMemory,
                            updateMemory = viewModel::updateMemory,
                            deleteMemory = viewModel::deleteMemory,
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
                        AppTab.List -> MemoryListScreen(
                            memories = memories,
                            selectedTags = selectedMemoryTags,
                            favoriteSelected = favoriteMemorySelected,
                            matchAll = memoryMatchAll,
                            colors = colors,
                            onSelectedTagsChanged = ::setSelectedMemoryTags,
                            onFavoriteSelectedChanged = { favoriteMemorySelected = it },
                            onMatchAllChanged = { memoryMatchAll = it },
                            onLoadInMemoryMode = { entry ->
                                viewModel.recallMemory(entry)
                                memoryMode = true
                                tab = AppTab.Radio
                            },
                            updateMemory = viewModel::updateMemory,
                            deleteMemory = viewModel::deleteMemory,
                        )
                        AppTab.Source -> SourceScreen(
                            state = frequencySources,
                            colors = colors,
                            selectDirectory = viewModel::selectFrequencyDirectory,
                            refresh = viewModel::refreshFrequencySources,
                            downloadTemplate = viewModel::downloadFrequencyTemplate,
                            importPack = viewModel::importFrequencyPack,
                            exportFile = viewModel::exportFrequencyFile,
                            deleteFile = viewModel::deleteFrequencyFile,
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
                            vhfVfoStep = vhfVfoStep,
                            onVhfVfoStep = onVhfVfoStep,
                            hfVfoSmallStep = hfVfoSmallStep,
                            onHfVfoSmallStep = onHfVfoSmallStep,
                            hfVfoLargeStep = hfVfoLargeStep,
                            onHfVfoLargeStep = onHfVfoLargeStep,
                            scanDwell = scanDwell,
                            onScanDwell = onScanDwell,
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

            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsBottomHeight(WindowInsets.navigationBars)
                    .background(colors.background),
            )
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
                    height = 41.dp,
                )
            }
        }
    }
}

@Composable
private fun RadioScreen(
    state: RadioSnapshot,
    memories: List<MemoryEntry>,
    visibleMemories: List<MemoryEntry>,
    memoryMode: Boolean,
    onMemoryModeChanged: (Boolean) -> Unit,
    colors: PuckColors,
    tuneFrequency: (Long) -> Unit,
    selectLowBandMode: (RadioMode) -> Unit,
    startVfoScan: (Long) -> Unit,
    memoryScanDirection: Int,
    stepMemory: (Int) -> Unit,
    startMemoryScan: (Int) -> Unit,
    stopTuneScan: () -> Unit,
    vhfVfoStep: VhfVfoStep,
    hfVfoSmallStep: HfVfoSmallStep,
    hfVfoLargeStep: HfVfoLargeStep,
    setVolume: (Int) -> Unit,
    createMemory: (Long, RadioMode, String, String, String, Boolean, Boolean) -> Unit,
    updateMemory: (MemoryEntry) -> Unit,
    deleteMemory: (Long) -> Unit,
    openConfigAndScan: () -> Unit,
) {
    var memoryEditorVisible by rememberSaveable { mutableStateOf(false) }
    var editingMemoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val tuneEnabled = canTune(state)
    val vfoEnabled = !memoryMode && tuneEnabled
    val memoryStepEnabled = memoryMode && tuneEnabled && memories.isNotEmpty()
    val memoryScanEnabled = memoryMode && tuneEnabled && memories.any(MemoryEntry::scanEnabled)
    val currentMemory = memories.firstOrNull { it.frequencyHz == state.targetFrequencyHz }
    val currentMemoryPosition = currentMemory?.let { memory ->
        visibleMemories.indexOfFirst { it.id == memory.id }
            .takeIf { it >= 0 }
            ?.plus(1)
    }
    val infoDoubleClick: (() -> Unit)? = if (memoryMode) {
        currentMemory?.let { memory ->
            {
                editingMemoryId = memory.id
                memoryEditorVisible = true
            }
        }
    } else {
        {
            editingMemoryId = currentMemory?.id
            memoryEditorVisible = true
        }
    }

    DisposableEffect(Unit) {
        onDispose(stopTuneScan)
    }

    PuckPanel(colors = colors) {
        Text(
            text = "FREQUENCY",
            fontSize = 21.sp,
            fontWeight = FontWeight.Black,
        )

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
            memoryMode = memoryMode,
            currentMemory = currentMemory,
            currentMemoryPosition = currentMemoryPosition,
            memoryTotal = visibleMemories.size,
            colors = colors,
            onDisconnectedClick = openConfigAndScan,
            onInfoDoubleClick = infoDoubleClick,
        )

        Spacer(Modifier.height(10.dp))

        val receiverStepHz = statusStepHz(state.status?.step)
        val singleArrowStepHz = vfoSingleArrowStepHz(
            frequencyHz = state.targetFrequencyHz,
            receiverStepHz = receiverStepHz,
            vhfVfoStep = vhfVfoStep,
            hfVfoSmallStep = hfVfoSmallStep,
        )
        val largeArrowStepHz = vfoLargeArrowStepHz(
            frequencyHz = state.targetFrequencyHz,
            receiverStepHz = receiverStepHz,
            hfVfoLargeStep = hfVfoLargeStep,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            PuckButton(
                text = "<<",
                selected = memoryMode && memoryScanDirection < 0,
                colors = colors,
                enabled = if (memoryMode) memoryScanEnabled else vfoEnabled,
                onClick = {
                    if (memoryMode) startMemoryScan(-1)
                    else tuneOffset(state, -largeArrowStepHz, tuneFrequency)
                },
                modifier = Modifier.weight(1f),
                height = 57.dp,
            )
            PuckButton(
                text = "<",
                colors = colors,
                enabled = if (memoryMode) memoryStepEnabled else vfoEnabled,
                onClick = {
                    if (memoryMode) stepMemory(-1)
                    else tuneOffset(state, -singleArrowStepHz, tuneFrequency)
                },
                modifier = Modifier.weight(1f),
                height = 57.dp,
            )
            PuckButton(
                text = if (memoryMode) "MEM" else "VFO",
                selected = true,
                colors = colors,
                onClick = {
                    stopTuneScan()
                    onMemoryModeChanged(!memoryMode)
                },
                modifier = Modifier.weight(1.4f),
                height = 57.dp,
            )
            PuckButton(
                text = ">",
                colors = colors,
                enabled = if (memoryMode) memoryStepEnabled else vfoEnabled,
                onClick = {
                    if (memoryMode) stepMemory(1)
                    else tuneOffset(state, singleArrowStepHz, tuneFrequency)
                },
                modifier = Modifier.weight(1f),
                height = 57.dp,
            )
            PuckButton(
                text = ">>",
                selected = memoryMode && memoryScanDirection > 0,
                colors = colors,
                enabled = if (memoryMode) memoryScanEnabled else vfoEnabled,
                onClick = {
                    if (memoryMode) startMemoryScan(1)
                    else tuneOffset(state, largeArrowStepHz, tuneFrequency)
                },
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

    if (memoryEditorVisible) {
        MemoryEditorDialog(
            currentFrequencyHz = state.targetFrequencyHz,
            currentMode = state.selectedMode,
            existing = memories.firstOrNull { it.id == editingMemoryId },
            allMemories = memories,
            colors = colors,
            onDismiss = {
                memoryEditorVisible = false
                editingMemoryId = null
            },
            onCreate = { frequencyHz, mode, name, tags, notes, favorite, skip ->
                createMemory(frequencyHz, mode, name, tags, notes, favorite, skip)
                memoryEditorVisible = false
                editingMemoryId = null
            },
            onUpdate = { entry ->
                updateMemory(entry)
                memoryEditorVisible = false
                editingMemoryId = null
            },
            onDelete = { id ->
                deleteMemory(id)
                memoryEditorVisible = false
                editingMemoryId = null
            },
        )
    }
}

@Composable
private fun MemoryListScreen(
    memories: List<MemoryEntry>,
    selectedTags: Set<String>,
    favoriteSelected: Boolean,
    matchAll: Boolean,
    colors: PuckColors,
    onSelectedTagsChanged: (Set<String>) -> Unit,
    onFavoriteSelectedChanged: (Boolean) -> Unit,
    onMatchAllChanged: (Boolean) -> Unit,
    onLoadInMemoryMode: (MemoryEntry) -> Unit,
    updateMemory: (MemoryEntry) -> Unit,
    deleteMemory: (Long) -> Unit,
) {
    var expandedMemoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingMemoryId by rememberSaveable { mutableStateOf<Long?>(null) }

    val availableTags = memoryTagCloud(memories)

    val visibleMemories = memories.filter { entry ->
        memoryMatchesFilters(
            entry = entry,
            selectedTags = selectedTags,
            favoriteSelected = favoriteSelected,
            matchAll = matchAll,
        )
    }

    LaunchedEffect(visibleMemories.map { it.id }) {
        if (expandedMemoryId != null && visibleMemories.none { it.id == expandedMemoryId }) {
            expandedMemoryId = null
        }
    }

    PuckPanel(colors = colors) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "MEMORY FILTER",
                fontSize = 21.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = "${visibleMemories.size} / ${memories.size}",
                color = colors.muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }

        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MemoryTagChip(
                text = "ALL TAGS",
                selected = selectedTags.isEmpty(),
                colors = colors,
                onClick = {
                    onSelectedTagsChanged(emptySet())
                    expandedMemoryId = null
                },
            )
            MemoryTagChip(
                text = "FAV",
                selected = favoriteSelected,
                colors = colors,
                onClick = {
                    onFavoriteSelectedChanged(!favoriteSelected)
                    expandedMemoryId = null
                },
            )
            MemoryTagChip(
                text = if (matchAll) "AND" else "OR",
                selected = true,
                colors = colors,
                onClick = {
                    onMatchAllChanged(!matchAll)
                    expandedMemoryId = null
                },
            )
            availableTags.forEach { tag ->
                MemoryTagChip(
                    text = tag,
                    selected = tag in selectedTags,
                    colors = colors,
                    onClick = {
                        onSelectedTagsChanged(
                            if (tag in selectedTags) {
                                selectedTags - tag
                            } else {
                                selectedTags + tag
                            },
                        )
                        expandedMemoryId = null
                    },
                )
            }
        }
    }

    if (memories.isEmpty()) {
        PuckPanel(colors = colors) {
            Text(
                text = "NO MEMORIES YET.",
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = "DOUBLE-TAP THE RADIO INFORMATION PANEL TO CREATE ONE.",
                color = colors.muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    } else if (visibleMemories.isEmpty()) {
        PuckPanel(colors = colors) {
            Text(
                text = "NO MATCHING MEMORIES.",
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
            )
        }
    } else {
        visibleMemories.forEach { entry ->
            MemoryListRow(
                entry = entry,
                expanded = expandedMemoryId == entry.id,
                colors = colors,
                onToggleExpanded = {
                    expandedMemoryId = if (expandedMemoryId == entry.id) null else entry.id
                },
                onLoadInMemoryMode = { onLoadInMemoryMode(entry) },
                onToggleFavorite = {
                    updateMemory(entry.copy(favorite = !entry.favorite))
                },
                onToggleSkip = {
                    updateMemory(entry.copy(skip = !entry.skip))
                },
                onEdit = { editingMemoryId = entry.id },
            )
        }
    }

    val editingMemory = memories.firstOrNull { it.id == editingMemoryId }
    if (editingMemory != null) {
        MemoryEditorDialog(
            currentFrequencyHz = editingMemory.frequencyHz,
            currentMode = editingMemory.mode,
            existing = editingMemory,
            allMemories = memories,
            colors = colors,
            onDismiss = { editingMemoryId = null },
            onCreate = { _, _, _, _, _, _, _ -> Unit },
            onUpdate = { entry ->
                updateMemory(entry)
                editingMemoryId = null
            },
            onDelete = { id ->
                deleteMemory(id)
                editingMemoryId = null
                if (expandedMemoryId == id) expandedMemoryId = null
            },
        )
    }
}

@Composable
private fun MemoryTagChip(
    text: String,
    selected: Boolean,
    colors: PuckColors,
    onClick: () -> Unit,
) {
    val background = if (selected) colors.selectedBackground else colors.background
    val foreground = if (selected) colors.selectedForeground else colors.foreground
    Text(
        text = text,
        color = foreground,
        fontSize = 12.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .background(background, PanelShape)
            .border(2.dp, colors.foreground, PanelShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

@Composable
private fun MemoryListRow(
    entry: MemoryEntry,
    expanded: Boolean,
    colors: PuckColors,
    onToggleExpanded: () -> Unit,
    onLoadInMemoryMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleSkip: () -> Unit,
    onEdit: () -> Unit,
) {
    PuckPanel(
        colors = colors,
        modifier = Modifier
            .combinedClickable(
                onClick = onToggleExpanded,
                onDoubleClick = onLoadInMemoryMode,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatFrequencyHz(entry.frequencyHz),
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
            )
            Text(
                text = entry.name,
                modifier = Modifier.weight(1f),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))

            MemoryListDetail(
                label = "MODE",
                value = entry.mode.label,
                colors = colors,
            )
            MemoryListDetail(
                label = "TAGS",
                value = entry.tags.ifBlank { "—" },
                colors = colors,
            )
            MemoryListDetail(
                label = "NOTES",
                value = entry.notes.ifBlank { "—" },
                colors = colors,
            )

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PuckButton(
                    text = "FAV",
                    selected = entry.favorite,
                    colors = colors,
                    onClick = onToggleFavorite,
                    horizontalPadding = 12.dp,
                    height = 42.dp,
                    fontSize = 14.sp,
                )
                PuckButton(
                    text = "SKIP",
                    selected = entry.skip,
                    colors = colors,
                    onClick = onToggleSkip,
                    horizontalPadding = 12.dp,
                    height = 42.dp,
                    fontSize = 14.sp,
                )
                PuckButton(
                    text = "EDIT",
                    colors = colors,
                    onClick = onEdit,
                    modifier = Modifier.weight(1f),
                    height = 42.dp,
                    fontSize = 14.sp,
                )
            }

            Spacer(Modifier.height(7.dp))
            Text(
                text = "DOUBLE-TAP TO LOAD IN MEM MODE",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun MemoryListDetail(
    label: String,
    value: String,
    colors: PuckColors,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            modifier = Modifier.width(58.dp),
            color = colors.muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

internal fun memoryTagCloud(memories: List<MemoryEntry>): List<String> = memories
    .flatMap(::memoryTags)
    .filterNot { it == "#FAV" }
    .distinct()
    .sorted()

internal fun memoryTags(entry: MemoryEntry): List<String> = memoryTagTokens(entry.tags)

internal fun memoryMatchesFilters(
    entry: MemoryEntry,
    selectedTags: Set<String>,
    favoriteSelected: Boolean,
    matchAll: Boolean,
): Boolean {
    val entryTags = memoryTags(entry).toSet()
    val tagsMatch = when {
        selectedTags.isEmpty() -> true
        matchAll -> selectedTags.all { it in entryTags }
        else -> selectedTags.any { it in entryTags }
    }
    return tagsMatch && (!favoriteSelected || entry.favorite)
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
    enabled: Boolean,
    colors: PuckColors,
    onTune: (Long) -> Unit,
) {
    var dialogVisible by rememberSaveable { mutableStateOf(false) }
    var value by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val validEntry = parseFrequencyText(value) != null

    LaunchedEffect(dialogVisible) {
        if (dialogVisible) {
            delay(100)
            focusRequester.requestFocus()
        }
    }

    fun dismiss() {
        focusManager.clearFocus()
        value = ""
        dialogVisible = false
    }

    fun submit() {
        parseFrequencyText(value)?.let { frequencyHz ->
            onTune(frequencyHz)
            dismiss()
        }
    }

    PuckButton(
        text = "DIRECT ENTRY",
        colors = colors,
        enabled = enabled,
        onClick = {
            value = ""
            dialogVisible = true
        },
        modifier = Modifier.fillMaxWidth(),
        height = 38.dp,
        fontSize = 14.sp,
    )

    if (dialogVisible) {
        Dialog(onDismissRequest = ::dismiss) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background, PanelShape)
                    .border(3.dp, colors.foreground, PanelShape)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "DIRECT ENTRY",
                    color = colors.foreground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(62.dp)
                        .border(2.dp, colors.foreground, PanelShape)
                        .padding(horizontal = 13.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = value,
                        onValueChange = { input ->
                            value = input.filter { it.isDigit() || it == '.' || it == ',' || it == ' ' }
                        },
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
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    PuckButton(
                        text = "CANCEL",
                        colors = colors,
                        onClick = ::dismiss,
                        modifier = Modifier.weight(1f),
                        height = 48.dp,
                        fontSize = 16.sp,
                    )
                    PuckButton(
                        text = "ENTER",
                        selected = validEntry,
                        colors = colors,
                        enabled = validEntry,
                        onClick = ::submit,
                        modifier = Modifier.weight(1f),
                        height = 48.dp,
                        fontSize = 16.sp,
                    )
                }
            }
        }
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
    memoryMode: Boolean,
    currentMemory: MemoryEntry?,
    currentMemoryPosition: Int?,
    memoryTotal: Int,
    colors: PuckColors,
    onDisconnectedClick: () -> Unit,
    onInfoDoubleClick: (() -> Unit)?,
) {
    val disconnected = state.link is LinkState.Disconnected
    val showMemoryInformation = memoryMode && state.link is LinkState.Ready
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, colors.foreground, PanelShape)
            .pointerInput(disconnected, onInfoDoubleClick) {
                detectTapGestures(
                    onTap = {
                        if (disconnected) onDisconnectedClick()
                    },
                    onDoubleTap = {
                        onInfoDoubleClick?.invoke()
                    },
                )
            }
            .padding(
                horizontal = 12.dp,
                vertical = if (showMemoryInformation) 9.dp else 12.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(
            if (showMemoryInformation) 4.dp else 5.dp,
        ),
    ) {
        if (showMemoryInformation) {
            MemoryStatusContent(
                memory = currentMemory,
                position = currentMemoryPosition,
                total = memoryTotal,
                colors = colors,
            )
        } else {
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
}

@Composable
private fun MemoryStatusContent(
    memory: MemoryEntry?,
    position: Int?,
    total: Int,
    colors: PuckColors,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = memory?.name ?: if (total > 0) "NO MEMORY SELECTED" else "NO MEMORIES AVAILABLE",
            modifier = Modifier.weight(1f),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = memoryPositionLabel(position, total),
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            fontWeight = FontWeight.Black,
        )
    }
    Text(
        text = memory?.tags?.ifBlank { "NO TAGS" }
            ?: if (total > 0) "USE < OR > TO SELECT A MEMORY" else "ADD MEMORIES ON LIST OR SRC",
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = memoryFlagsAndDescription(memory),
        fontSize = 13.sp,
        color = colors.muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

internal fun memoryFlagsAndDescription(memory: MemoryEntry?): String {
    if (memory == null) return "—"
    return buildList {
        if (memory.favorite) add("FAV")
        if (memory.skip) add("SKIP")
        add(memory.notes.ifBlank { "NO DESCRIPTION" })
    }.joinToString(" • ")
}

internal fun memoryPositionLabel(position: Int?, total: Int): String =
    if (position == null) "— / $total" else "$position / $total"

@Composable
private fun MemoryEditorDialog(
    currentFrequencyHz: Long,
    currentMode: RadioMode,
    existing: MemoryEntry?,
    allMemories: List<MemoryEntry>,
    colors: PuckColors,
    onDismiss: () -> Unit,
    onCreate: (Long, RadioMode, String, String, String, Boolean, Boolean) -> Unit,
    onUpdate: (MemoryEntry) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val initialFrequency = existing?.frequencyHz ?: currentFrequencyHz
    val initialMode = existing?.mode ?: currentMode
    var frequencyText by remember(existing?.id, currentFrequencyHz) {
        mutableStateOf(formatFrequencyHz(initialFrequency))
    }
    var mode by remember(existing?.id, currentMode) { mutableStateOf(initialMode) }
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var tags by remember(existing?.id) { mutableStateOf(existing?.tags.orEmpty()) }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes.orEmpty()) }
    var favorite by remember(existing?.id) { mutableStateOf(existing?.favorite ?: false) }
    var skip by remember(existing?.id) { mutableStateOf(existing?.skip ?: false) }
    var deleteConfirmationVisible by remember(existing?.id) { mutableStateOf(false) }

    val parsedFrequency = parseFrequencyText(frequencyText)
    val normalizedFrequency = parsedFrequency?.let(AtsFrequencyPlan::normalizeReceiverFrequency)
    val region = normalizedFrequency?.let(AtsFrequencyPlan::regionFor)
    val duplicateFrequency = normalizedFrequency != null && allMemories.any { entry ->
        entry.id != existing?.id && entry.frequencyHz == normalizedFrequency
    }
    val validMode = when (region) {
        AtsFrequencyRegion.LowBand -> mode.isLowBandMode
        AtsFrequencyRegion.BroadcastFm -> mode == RadioMode.FM
        else -> false
    }
    val canSave = normalizedFrequency != null &&
        region != AtsFrequencyRegion.Unsupported &&
        validMode &&
        name.isNotBlank() &&
        !duplicateFrequency

    fun updateFrequency(input: String) {
        frequencyText = input.filter { it.isDigit() || it == '.' || it == ',' || it == ' ' }
        val parsed = parseFrequencyText(frequencyText) ?: return
        when (AtsFrequencyPlan.regionFor(parsed)) {
            AtsFrequencyRegion.BroadcastFm -> mode = RadioMode.FM
            AtsFrequencyRegion.LowBand -> if (!mode.isLowBandMode) {
                mode = currentMode.takeIf(RadioMode::isLowBandMode) ?: RadioMode.AM
            }
            AtsFrequencyRegion.Unsupported -> Unit
        }
    }

    fun save() {
        val frequencyHz = normalizedFrequency ?: return
        if (!canSave) return
        if (existing == null) {
            onCreate(frequencyHz, mode, name, tags, notes, favorite, skip)
        } else {
            onUpdate(
                existing.copy(
                    frequencyHz = frequencyHz,
                    mode = mode,
                    name = name,
                    tags = tags,
                    notes = notes,
                    favorite = favorite,
                    skip = skip,
                ),
            )
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 720.dp)
                .background(colors.background, PanelShape)
                .border(3.dp, colors.foreground, PanelShape)
                .verticalScroll(rememberScrollState())
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (existing == null) "CREATE MEMORY" else "EDIT MEMORY",
                color = colors.foreground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
            )

            MemoryEditorField(
                label = "FREQUENCY",
                value = frequencyText,
                colors = colors,
                keyboardType = KeyboardType.Decimal,
                onValueChange = ::updateFrequency,
            )

            Text("MODE", fontSize = 13.sp, fontWeight = FontWeight.Black)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val modes = if (region == AtsFrequencyRegion.BroadcastFm) {
                    listOf(RadioMode.FM)
                } else {
                    LOW_BAND_MODES
                }
                modes.forEach { candidate ->
                    PuckButton(
                        text = candidate.label,
                        selected = mode == candidate,
                        colors = colors,
                        enabled = region != null && region != AtsFrequencyRegion.Unsupported,
                        onClick = { mode = candidate },
                        modifier = Modifier.weight(1f),
                        height = 44.dp,
                        fontSize = 14.sp,
                    )
                }
            }

            MemoryEditorField(
                label = "NAME",
                value = name,
                colors = colors,
                onValueChange = { name = it },
            )
            MemoryEditorField(
                label = "TAGS",
                value = tags,
                colors = colors,
                onValueChange = { tags = it },
            )
            MemoryEditorField(
                label = "NOTES",
                value = notes,
                colors = colors,
                singleLine = false,
                onValueChange = { notes = it },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PuckButton(
                    text = "FAV",
                    selected = favorite,
                    colors = colors,
                    onClick = { favorite = !favorite },
                    modifier = Modifier.weight(1f),
                    height = 46.dp,
                    fontSize = 13.sp,
                )
                PuckButton(
                    text = "SKIP",
                    selected = skip,
                    colors = colors,
                    onClick = { skip = !skip },
                    modifier = Modifier.weight(1f),
                    height = 46.dp,
                    fontSize = 13.sp,
                )
            }
            Text(
                text = "SKIP KEEPS THE MEMORY AVAILABLE BUT EXCLUDES IT FROM SCANNING.",
                color = colors.muted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )

            val validationText = when {
                parsedFrequency == null -> "ENTER A FREQUENCY"
                region == AtsFrequencyRegion.Unsupported -> AtsFrequencyPlan.validationMessage(parsedFrequency)
                duplicateFrequency -> "A MEMORY ALREADY EXISTS AT THIS FREQUENCY"
                name.isBlank() -> "NAME IS REQUIRED"
                else -> null
            }
            if (validationText != null) {
                Text(
                    text = validationText.uppercase(),
                    color = colors.muted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (existing != null) {
                    PuckButton(
                        text = "DELETE",
                        colors = colors,
                        onClick = { deleteConfirmationVisible = true },
                        modifier = Modifier.weight(1f),
                        height = 48.dp,
                        fontSize = 14.sp,
                    )
                }
                PuckButton(
                    text = "CANCEL",
                    colors = colors,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    height = 48.dp,
                    fontSize = 14.sp,
                )
                PuckButton(
                    text = if (existing == null) "CREATE" else "UPDATE",
                    selected = canSave,
                    colors = colors,
                    enabled = canSave,
                    onClick = ::save,
                    modifier = Modifier.weight(1f),
                    height = 48.dp,
                    fontSize = 14.sp,
                )
            }
        }
    }

    if (deleteConfirmationVisible && existing != null) {
        Dialog(onDismissRequest = { deleteConfirmationVisible = false }) {
            Surface(
                color = colors.background,
                contentColor = colors.foreground,
                shape = PanelShape,
                border = BorderStroke(3.dp, colors.foreground),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "DELETE MEMORY?",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                    )
                    Text(
                        text = existing.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatFrequencyHz(existing.frequencyHz),
                        color = colors.muted,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 15.sp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PuckButton(
                            text = "CANCEL",
                            colors = colors,
                            onClick = { deleteConfirmationVisible = false },
                            modifier = Modifier.weight(1f),
                            height = 46.dp,
                        )
                        PuckButton(
                            text = "DELETE",
                            colors = colors,
                            selected = true,
                            onClick = {
                                deleteConfirmationVisible = false
                                onDelete(existing.id)
                            },
                            modifier = Modifier.weight(1f),
                            height = 46.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryEditorField(
    label: String,
    value: String,
    colors: PuckColors,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Black)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (singleLine) 50.dp else 92.dp)
                .border(2.dp, colors.foreground, PanelShape)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = TextStyle(
                    color = colors.foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 17.sp,
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = keyboardType,
                    imeAction = if (singleLine) ImeAction.Next else ImeAction.Default,
                ),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SourceScreen(
    state: FrequencySourceState,
    colors: PuckColors,
    selectDirectory: (Uri) -> Unit,
    refresh: () -> Unit,
    downloadTemplate: () -> Unit,
    importPack: (Uri) -> Unit,
    exportFile: (String, Uri) -> Unit,
    deleteFile: (String) -> Unit,
) {
    var pendingExport by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val directoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) selectDirectory(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) importPack(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val fileName = pendingExport
        pendingExport = null
        if (uri != null && fileName != null) exportFile(fileName, uri)
    }

    PuckPanel(colors = colors) {
        SectionTitle("FREQUENCY SOURCES")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "USER.json LOADS FIRST. PACK FILES REMAIN PRISTINE.",
            color = colors.muted,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = state.directoryName?.uppercase() ?: "NO FREQUENCY DIRECTORY SELECTED",
            fontFamily = FontFamily.Monospace,
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PuckButton(
                text = "SET PATH",
                colors = colors,
                enabled = !state.busy,
                onClick = { directoryLauncher.launch(null) },
                modifier = Modifier.weight(1f),
                height = 46.dp,
                fontSize = 14.sp,
            )
            PuckButton(
                text = "REFRESH",
                colors = colors,
                enabled = state.directorySelected && !state.busy,
                onClick = refresh,
                modifier = Modifier.weight(1f),
                height = 46.dp,
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PuckButton(
                text = "TEMPLATE",
                colors = colors,
                enabled = state.directorySelected && !state.busy,
                onClick = downloadTemplate,
                modifier = Modifier.weight(1f),
                height = 46.dp,
                fontSize = 13.sp,
            )
            PuckButton(
                text = "IMPORT PACK",
                colors = colors,
                enabled = state.directorySelected && !state.busy,
                onClick = { importLauncher.launch(arrayOf("application/json", "text/json", "text/plain")) },
                modifier = Modifier.weight(1f),
                height = 46.dp,
                fontSize = 14.sp,
            )
        }
        state.message?.takeIf(String::isNotBlank)?.let { message ->
            Spacer(Modifier.height(9.dp))
            Text(
                text = if (state.busy) "..." else message.uppercase(),
                color = colors.muted,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    if (state.directorySelected) {
        PuckPanel(colors = colors) {
            SectionTitle("SOURCE FILES")
            Spacer(Modifier.height(8.dp))
            if (state.files.isEmpty()) {
                Text(
                    text = if (state.busy) "..." else "NO JSON FILES",
                    color = colors.muted,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                state.files.forEachIndexed { index, file ->
                    if (index > 0) Spacer(Modifier.height(9.dp))
                    FrequencySourceFileRow(
                        file = file,
                        colors = colors,
                        enabled = !state.busy,
                        onExport = {
                            pendingExport = file.name
                            exportLauncher.launch(file.name)
                        },
                        onDelete = { pendingDelete = file.name },
                    )
                }
            }
        }
    }

    pendingDelete?.let { fileName ->
        Dialog(onDismissRequest = { pendingDelete = null }) {
            Surface(
                color = colors.background,
                contentColor = colors.foreground,
                shape = PanelShape,
                border = BorderStroke(3.dp, colors.foreground),
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("DELETE SOURCE FILE?", fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Text(fileName, fontFamily = FontFamily.Monospace, fontSize = 15.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PuckButton(
                            text = "CANCEL",
                            colors = colors,
                            onClick = { pendingDelete = null },
                            modifier = Modifier.weight(1f),
                            height = 46.dp,
                        )
                        PuckButton(
                            text = "DELETE",
                            colors = colors,
                            selected = true,
                            onClick = {
                                pendingDelete = null
                                deleteFile(fileName)
                            },
                            modifier = Modifier.weight(1f),
                            height = 46.dp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrequencySourceFileRow(
    file: FrequencySourceFile,
    colors: PuckColors,
    enabled: Boolean,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, colors.foreground, PanelShape)
            .padding(9.dp),
    ) {
        Text(
            text = file.name,
            fontFamily = FontFamily.Monospace,
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        val detail = when {
            file.error != null -> "ERROR • ${file.error.uppercase()}"
            file.isUser -> "${file.memoryCount} USER OVERRIDES • MANAGED"
            file.isTemplate -> "EMPTY TEMPLATE"
            file.duplicateCount > 0 -> "${file.memoryCount} MEMORIES • ${file.duplicateCount} OVERRIDDEN"
            else -> "${file.memoryCount} MEMORIES"
        }
        Text(
            text = detail,
            color = colors.muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PuckButton(
                text = "EXPORT",
                colors = colors,
                enabled = enabled,
                onClick = onExport,
                modifier = Modifier.weight(1f),
                height = 42.dp,
                fontSize = 14.sp,
            )
            if (!file.isUser) {
                PuckButton(
                    text = "DELETE",
                    colors = colors,
                    enabled = enabled,
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    height = 42.dp,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

private fun startupReconnectText(stage: StartupReconnectStage): String = when (stage) {
    StartupReconnectStage.Idle -> ""
    StartupReconnectStage.Looking -> "LOOKING FOR SAVED ATS MINI..."
    StartupReconnectStage.Connecting -> "CONNECTING TO ATS MINI..."
    StartupReconnectStage.Verifying -> "VERIFYING MEMPUCK FIRMWARE..."
}

@Composable
private fun StartupReconnectScreen(
    stage: StartupReconnectStage,
    colors: PuckColors,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        AboutContent(
            colors = colors,
            statusText = startupReconnectText(stage),
            compact = false,
        )
    }
}

@Composable
private fun AboutContent(
    colors: PuckColors,
    statusText: String? = null,
    compact: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
    ) {
        PuckSilhouette(
            colors = colors,
            compact = compact,
        )
        Text(
            text = "MEMPUCK",
            fontSize = if (compact) 24.sp else 31.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp,
        )
        Text(
            text = "FOR ATS MINI",
            fontSize = if (compact) 16.sp else 20.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
        )
        Text(
            text = "VERSION ${BuildConfig.VERSION_NAME}",
            fontFamily = FontFamily.Monospace,
            fontSize = if (compact) 13.sp else 15.sp,
        )
        Text(
            text = "BLE CONTROLLER • VFO • MEMORY",
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.Bold,
        )
        if (!statusText.isNullOrBlank()) {
            Spacer(Modifier.height(if (compact) 2.dp else 8.dp))
            Text(
                text = statusText,
                fontFamily = FontFamily.Monospace,
                fontSize = if (compact) 13.sp else 16.sp,
                fontWeight = FontWeight.Black,
            )
        }
        Text(
            text = "Mark Zimmerman, N5NBD",
            color = colors.muted,
            fontSize = if (compact) 11.sp else 12.sp,
        )
        Text(
            text = "Polyform Noncommercial Licence",
            color = colors.muted,
            fontSize = if (compact) 11.sp else 12.sp,
        )
    }
}

@Composable
private fun PuckSilhouette(
    colors: PuckColors,
    compact: Boolean,
) {
    Canvas(
        modifier = Modifier
            .width(if (compact) 132.dp else 184.dp)
            .height(if (compact) 78.dp else 108.dp),
    ) {
        val puckLeft = size.width * 0.08f
        val puckTop = size.height * 0.15f
        val puckWidth = size.width * 0.70f
        val puckHeight = size.height * 0.68f
        val ellipseHeight = size.height * 0.32f
        val bodyTop = puckTop + ellipseHeight * 0.45f

        drawRoundRect(
            color = colors.foreground,
            topLeft = Offset(puckLeft, bodyTop),
            size = Size(puckWidth, puckHeight - ellipseHeight * 0.25f),
            cornerRadius = CornerRadius(ellipseHeight * 0.35f, ellipseHeight * 0.35f),
        )
        drawOval(
            color = colors.foreground,
            topLeft = Offset(puckLeft, puckTop),
            size = Size(puckWidth, ellipseHeight),
        )

        val cableY = puckTop + puckHeight * 0.58f
        val cableStartX = puckLeft + puckWidth * 0.92f
        val cableEndX = size.width * 0.92f
        drawLine(
            color = colors.foreground,
            start = Offset(cableStartX, cableY),
            end = Offset(cableEndX, cableY),
            strokeWidth = size.height * 0.10f,
            cap = StrokeCap.Round,
        )
        drawRoundRect(
            color = colors.foreground,
            topLeft = Offset(cableEndX - size.width * 0.01f, cableY - size.height * 0.11f),
            size = Size(size.width * 0.08f, size.height * 0.22f),
            cornerRadius = CornerRadius(size.height * 0.03f, size.height * 0.03f),
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
    vhfVfoStep: VhfVfoStep,
    onVhfVfoStep: (VhfVfoStep) -> Unit,
    hfVfoSmallStep: HfVfoSmallStep,
    onHfVfoSmallStep: (HfVfoSmallStep) -> Unit,
    hfVfoLargeStep: HfVfoLargeStep,
    onHfVfoLargeStep: (HfVfoLargeStep) -> Unit,
    scanDwell: ScanDwell,
    onScanDwell: (ScanDwell) -> Unit,
    startScan: () -> Unit,
    stopScan: () -> Unit,
    disconnect: () -> Unit,
    probeCapability: () -> Unit,
    connect: (com.n5nbd.mempuck.atsmini.model.AtsDevice) -> Unit,
) {
    var expandedSectionName by remember {
        mutableStateOf("")
    }
    val protocolScroll = rememberScrollState()
    val debugExpanded = expandedSectionName == ConfigSection.Debug.name

    fun toggleSection(section: ConfigSection) {
        expandedSectionName = if (expandedSectionName == section.name) "" else section.name
    }

    LaunchedEffect(debugExpanded, state.log.size) {
        if (debugExpanded) {
            protocolScroll.scrollTo(protocolScroll.maxValue)
        }
    }

    ConfigWindow(
        title = "RADIO LINK",
        expanded = expandedSectionName == ConfigSection.RadioLink.name,
        colors = colors,
        onToggle = { toggleSection(ConfigSection.RadioLink) },
    ) {
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
                    text = if (state.scanning) "..." else "SCAN",
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

    Spacer(Modifier.height(1.dp))

    ConfigWindow(
        title = "DISPLAY",
        expanded = expandedSectionName == ConfigSection.Display.name,
        colors = colors,
        onToggle = { toggleSection(ConfigSection.Display) },
    ) {
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

    Spacer(Modifier.height(1.dp))

    ConfigWindow(
        title = "TUNING STEPS",
        expanded = expandedSectionName == ConfigSection.TuningSteps.name,
        colors = colors,
        onToggle = { toggleSection(ConfigSection.TuningSteps) },
    ) {
        Text(
            text = "HF LARGE STEP",
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HfVfoLargeStep.entries.forEach { step ->
                PuckButton(
                    text = step.label,
                    selected = hfVfoLargeStep == step,
                    colors = colors,
                    onClick = { onHfVfoLargeStep(step) },
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "HF SMALL STEP",
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HfVfoSmallStep.entries.forEach { step ->
                PuckButton(
                    text = step.label,
                    selected = hfVfoSmallStep == step,
                    colors = colors,
                    onClick = { onHfVfoSmallStep(step) },
                    modifier = Modifier.weight(1f),
                    fontSize = 12.sp,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "VHF VFO STEP",
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VhfVfoStep.entries.forEach { step ->
                PuckButton(
                    text = step.label,
                    selected = vhfVfoStep == step,
                    colors = colors,
                    onClick = { onVhfVfoStep(step) },
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                )
            }
        }
    }

    Spacer(Modifier.height(1.dp))

    ConfigWindow(
        title = "SCAN",
        expanded = expandedSectionName == ConfigSection.Scan.name,
        colors = colors,
        onToggle = { toggleSection(ConfigSection.Scan) },
    ) {
        Text(
            text = "VFO AND MEMORY DWELL (SECONDS)",
            fontSize = 15.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ScanDwell.entries.forEach { dwell ->
                PuckButton(
                    text = dwell.label,
                    selected = scanDwell == dwell,
                    colors = colors,
                    onClick = { onScanDwell(dwell) },
                    modifier = Modifier.weight(1f),
                    fontSize = 14.sp,
                )
            }
        }
    }

    Spacer(Modifier.height(1.dp))

    ConfigWindow(
        title = "DEBUG",
        expanded = debugExpanded,
        colors = colors,
        onToggle = { toggleSection(ConfigSection.Debug) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .verticalScroll(protocolScroll),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (state.log.isEmpty()) {
                Text(
                    text = "No protocol traffic yet",
                    color = colors.muted,
                    fontSize = 13.sp,
                )
            } else {
                state.log.takeLast(80).forEach { line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(1.dp))

    ConfigWindow(
        title = "ABOUT",
        expanded = expandedSectionName == ConfigSection.About.name,
        colors = colors,
        onToggle = { toggleSection(ConfigSection.About) },
    ) {
        AboutContent(
            colors = colors,
            compact = true,
        )
    }
}

@Composable
private fun ConfigWindow(
    title: String,
    expanded: Boolean,
    colors: PuckColors,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(2.dp, colors.foreground), PanelShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.selectedBackground)
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                color = colors.selectedForeground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "−" else "+",
                color = colors.selectedForeground,
                fontFamily = FontFamily.Monospace,
                fontSize = 21.sp,
                fontWeight = FontWeight.Black,
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                content = content,
            )
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
    horizontalPadding: androidx.compose.ui.unit.Dp = 0.dp,
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
            modifier = Modifier.padding(horizontal = horizontalPadding),
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

private fun primaryStatusLine(state: RadioSnapshot): String {
    val tuneFailure = state.tuneState as? TuneState.Failed
    if (tuneFailure != null) return "TUNE ERROR: ${tuneFailure.message}"

    state.status?.let {
        return "${it.bandName} • S ${it.rssi} / N ${it.snr}"
    }

    return when (state.link) {
        LinkState.Disconnected -> "YOU'RE DISCONNECTED. TAP HERE TO CONNECT."
        LinkState.Connecting -> "CONNECTING TO ATS MINI"
        is LinkState.Ready -> "ATS MINI READY • S -- / N --"
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

internal fun vfoSingleArrowStepHz(
    frequencyHz: Long,
    receiverStepHz: Long,
    vhfVfoStep: VhfVfoStep,
    hfVfoSmallStep: HfVfoSmallStep,
): Long = when (AtsFrequencyPlan.regionFor(frequencyHz)) {
    AtsFrequencyRegion.BroadcastFm -> vhfVfoStep.stepHz
    AtsFrequencyRegion.LowBand -> hfVfoSmallStep.stepHz
    else -> receiverStepHz
}

internal fun vfoLargeArrowStepHz(
    frequencyHz: Long,
    receiverStepHz: Long,
    hfVfoLargeStep: HfVfoLargeStep,
): Long = if (AtsFrequencyPlan.regionFor(frequencyHz) == AtsFrequencyRegion.LowBand) {
    hfVfoLargeStep.stepHz
} else {
    receiverStepHz * 10L
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
