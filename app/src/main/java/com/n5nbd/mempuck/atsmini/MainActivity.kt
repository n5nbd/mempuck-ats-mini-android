package com.n5nbd.mempuck.atsmini

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.n5nbd.mempuck.atsmini.ble.hasBluetoothPermissions
import com.n5nbd.mempuck.atsmini.ble.requiredBluetoothPermissions
import com.n5nbd.mempuck.atsmini.ui.MainScreen
import com.n5nbd.mempuck.atsmini.ui.MainViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var permissionsGranted by remember {
                mutableStateOf(hasBluetoothPermissions(context))
            }
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) {
                permissionsGranted = hasBluetoothPermissions(context)
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
            )
        }
    }
}
