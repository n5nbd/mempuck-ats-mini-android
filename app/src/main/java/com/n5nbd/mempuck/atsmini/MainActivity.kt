package com.n5nbd.mempuck.atsmini

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.n5nbd.mempuck.atsmini.ble.hasBluetoothPermissions
import com.n5nbd.mempuck.atsmini.ble.requiredBluetoothPermissions
import com.n5nbd.mempuck.atsmini.ui.MainScreen
import com.n5nbd.mempuck.atsmini.ui.MainViewModel
import com.n5nbd.mempuck.atsmini.ui.ThemeChoice

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            var permissionsGranted by remember {
                mutableStateOf(hasBluetoothPermissions(context))
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                permissionsGranted = hasBluetoothPermissions(context)
            }

            val dark = themeChoice == ThemeChoice.Dark
            SideEffect {
                window.statusBarColor = (if (dark) Color.Black else Color.White).toArgb()
                window.navigationBarColor = Color.Black.toArgb()
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = false
                }
            }

            val app = application as MemPuckApplication
            val model: MainViewModel = viewModel(
                factory = MainViewModel.factory(app.radioRepository),
            )

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
            )
        }
    }
}
