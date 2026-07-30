package com.n5nbd.mempuck.atsmini.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.n5nbd.mempuck.atsmini.model.CapabilityState
import com.n5nbd.mempuck.atsmini.model.LinkState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    permissionsGranted: Boolean,
    requestPermissions: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("MemPuck for ATS Mini") }) },
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text("Receiver setup", style = MaterialTheme.typography.titleMedium)
                    Text("ATS Mini: Settings → Bluetooth → Ad hoc")
                }

                item {
                    PermissionCard(permissionsGranted, requestPermissions)
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (permissionsGranted) viewModel.startScan() else requestPermissions()
                            },
                            enabled = !state.scanning,
                        ) { Text("Scan") }
                        OutlinedButton(
                            onClick = viewModel::stopScan,
                            enabled = state.scanning,
                        ) { Text("Stop") }
                        OutlinedButton(
                            onClick = viewModel::disconnect,
                            enabled = state.link !is LinkState.Disconnected,
                        ) { Text("Disconnect") }
                    }
                }

                item {
                    StatusCard(state.link, state.capability, viewModel::probeCapability)
                }

                item {
                    Text("Discovered UART devices", style = MaterialTheme.typography.titleMedium)
                }

                if (state.devices.isEmpty()) {
                    item { Text(if (state.scanning) "Scanning…" else "No devices found yet") }
                } else {
                    items(state.devices, key = { it.address }) { device ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(device.name ?: "Unnamed Nordic UART device")
                                    Text(device.address, style = MaterialTheme.typography.bodySmall)
                                    Text("RSSI ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                                }
                                Button(onClick = { viewModel.connect(device) }) {
                                    Text("Connect")
                                }
                            }
                        }
                    }
                }

                item {
                    Text("Protocol log", style = MaterialTheme.typography.titleMedium)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            state.log.forEach {
                                Text(it, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionCard(granted: Boolean, requestPermissions: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Nearby devices permission", style = MaterialTheme.typography.titleSmall)
            Text(if (granted) "Granted" else "Required for BLE discovery and connection")
            if (!granted) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = requestPermissions) { Text("Grant permission") }
            }
        }
    }
}

@Composable
private fun StatusCard(
    link: LinkState,
    capability: CapabilityState,
    probeCapability: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleSmall)
            Text(
                when (link) {
                    LinkState.Disconnected -> "Disconnected"
                    LinkState.Connecting -> "Connecting…"
                    is LinkState.Ready -> "Ready: ${link.device.name ?: link.device.address}"
                    is LinkState.Failed -> "Error: ${link.message}"
                },
            )
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Absolute tuning capability", style = MaterialTheme.typography.titleSmall)
            Text(
                when (capability) {
                    CapabilityState.NotChecked -> "Not checked"
                    CapabilityState.Checking -> "Checking with Z?…"
                    is CapabilityState.Supported -> "Supported: Z protocol v${capability.version}"
                    is CapabilityState.Unsupported -> "Not detected: ${capability.detail}"
                },
            )
            if (link is LinkState.Ready) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = probeCapability) { Text("Send Z?") }
            }
        }
    }
}
