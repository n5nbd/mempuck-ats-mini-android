package com.n5nbd.mempuck.atsmini

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.n5nbd.mempuck.atsmini.ble.hasBluetoothPermissions
import com.n5nbd.mempuck.atsmini.ble.requiredBluetoothPermissions
import com.n5nbd.mempuck.atsmini.img.repository.ImageDecoderViewModel
import com.n5nbd.mempuck.atsmini.ui.HfVfoLargeStep
import com.n5nbd.mempuck.atsmini.ui.HfVfoSmallStep
import com.n5nbd.mempuck.atsmini.ui.MainScreen
import com.n5nbd.mempuck.atsmini.ui.MainViewModel
import com.n5nbd.mempuck.atsmini.ui.ScanDwell
import com.n5nbd.mempuck.atsmini.ui.ThemeChoice
import com.n5nbd.mempuck.atsmini.ui.VhfVfoStep

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val context = LocalContext.current
            val preferences = remember {
                context.getSharedPreferences("mempuck-ui", MODE_PRIVATE)
            }
            var themeChoice by remember {
                mutableStateOf(
                    runCatching {
                        ThemeChoice.valueOf(
                            preferences.getString("theme", ThemeChoice.Dark.name)
                                ?: ThemeChoice.Dark.name,
                        )
                    }.getOrDefault(ThemeChoice.Dark),
                )
            }
            var hueDegrees by remember {
                mutableFloatStateOf(preferences.getFloat("hueDegrees", 180f))
            }
            var vhfVfoStep by remember {
                mutableStateOf(
                    runCatching {
                        VhfVfoStep.valueOf(
                            preferences.getString("vhfVfoStep", VhfVfoStep.KHz200.name)
                                ?: VhfVfoStep.KHz200.name,
                        )
                    }.getOrDefault(VhfVfoStep.KHz200),
                )
            }
            var hfVfoSmallStep by remember {
                mutableStateOf(
                    runCatching {
                        HfVfoSmallStep.valueOf(
                            preferences.getString("hfVfoSmallStep", HfVfoSmallStep.KHz1.name)
                                ?: HfVfoSmallStep.KHz1.name,
                        )
                    }.getOrDefault(HfVfoSmallStep.KHz1),
                )
            }
            var hfVfoLargeStep by remember {
                mutableStateOf(
                    runCatching {
                        HfVfoLargeStep.valueOf(
                            preferences.getString("hfVfoLargeStep", HfVfoLargeStep.KHz10.name)
                                ?: HfVfoLargeStep.KHz10.name,
                        )
                    }.getOrDefault(HfVfoLargeStep.KHz10),
                )
            }
            var scanDwell by remember {
                mutableStateOf(
                    runCatching {
                        ScanDwell.valueOf(
                            preferences.getString("scanDwell", ScanDwell.Seconds2.name)
                                ?: ScanDwell.Seconds2.name,
                        )
                    }.getOrDefault(ScanDwell.Seconds2),
                )
            }
            var permissionsGranted by remember {
                mutableStateOf(hasBluetoothPermissions(context))
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                permissionsGranted = hasBluetoothPermissions(context)
            }

            val lightNavigationBar = themeChoice == ThemeChoice.Light
            SideEffect {
                window.statusBarColor = Color.Black.toArgb()
                window.navigationBarColor = if (lightNavigationBar) {
                    Color.White.toArgb()
                } else {
                    Color.Black.toArgb()
                }
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    show(WindowInsetsCompat.Type.statusBars())
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = lightNavigationBar
                }
            }

            val app = application as MemPuckApplication
            val model: MainViewModel = viewModel(
                factory = MainViewModel.factory(
                    app.radioRepository,
                    app.memoryRepository,
                    app.nowRepository,
                ),
            )
            val imageModel: ImageDecoderViewModel = viewModel(
                factory = ImageDecoderViewModel.factory(app.imageDecoderRepository),
            )
            val imageState by imageModel.state.collectAsStateWithLifecycle()
            var imageAudioPermissionGranted by remember {
                mutableStateOf(hasImageAudioPermission(context))
            }
            var startImageAfterPermission by remember { mutableStateOf(false) }
            val imagePermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { granted ->
                imageAudioPermissionGranted = granted && hasImageAudioPermission(context)
                if (startImageAfterPermission) {
                    startImageAfterPermission = false
                    if (imageAudioPermissionGranted) {
                        imageModel.startListening()
                    } else {
                        imageModel.microphonePermissionDenied()
                    }
                }
            }

            MainScreen(
                viewModel = model,
                permissionsGranted = permissionsGranted,
                requestPermissions = {
                    permissionLauncher.launch(requiredBluetoothPermissions())
                },
                themeChoice = themeChoice,
                onThemeChoice = { selected ->
                    themeChoice = selected
                    preferences.edit().putString("theme", selected.name).apply()
                },
                hueDegrees = hueDegrees,
                onHueDegrees = { selected ->
                    hueDegrees = selected.coerceIn(0f, 359f)
                    preferences.edit().putFloat("hueDegrees", hueDegrees).apply()
                },
                vhfVfoStep = vhfVfoStep,
                onVhfVfoStep = { selected ->
                    vhfVfoStep = selected
                    preferences.edit().putString("vhfVfoStep", selected.name).apply()
                },
                hfVfoSmallStep = hfVfoSmallStep,
                onHfVfoSmallStep = { selected ->
                    hfVfoSmallStep = selected
                    preferences.edit().putString("hfVfoSmallStep", selected.name).apply()
                },
                hfVfoLargeStep = hfVfoLargeStep,
                onHfVfoLargeStep = { selected ->
                    hfVfoLargeStep = selected
                    preferences.edit().putString("hfVfoLargeStep", selected.name).apply()
                },
                scanDwell = scanDwell,
                onScanDwell = { selected ->
                    scanDwell = selected
                    preferences.edit().putString("scanDwell", selected.name).apply()
                },
                imageState = imageState,
                imageAudioPermissionGranted = imageAudioPermissionGranted,
                onImageSelectDecoder = imageModel::selectDecoder,
                onImageSelectInput = imageModel::selectInput,
                onImageListen = {
                    imageAudioPermissionGranted = hasImageAudioPermission(context)
                    if (imageAudioPermissionGranted) {
                        imageModel.startListening()
                    } else {
                        startImageAfterPermission = true
                        imagePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                },
                onImageStop = imageModel::stopListening,
                onImageClear = imageModel::clearImage,
            )
        }
    }

    override fun onStop() {
        (application as? MemPuckApplication)?.imageDecoderRepository?.stopListening()
        super.onStop()
    }
}

private fun hasImageAudioPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED
