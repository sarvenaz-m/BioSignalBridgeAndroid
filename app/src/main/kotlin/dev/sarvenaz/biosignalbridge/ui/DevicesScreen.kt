package dev.sarvenaz.biosignalbridge.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Devices(state: BridgeUiState, actions: BridgeActions, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("workspace-list"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("SENSOR CONNECTION", "Bring your signal in.", "Connect a compatible BLE source, then keep the waveform and packet history together.") }
        item {
            Surface(shape = RoundedCornerShape(22.dp), color = Midnight) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph("devices", Ice, Modifier.size(28.dp))
                        Text(if (state.mode == BridgeMode.LIVE) "Live sensor mode" else "Choose a source", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
                    }
                    Text(if (state.mode == BridgeMode.LIVE) "Scan for devices advertising the Bridge service. Connecting requires Bluetooth access on your phone." else "Preview uses a public recording. Live receives data from a sensor that implements the documented Bridge packet format.", color = Color(0xFFD6DCF0), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { if (state.mode != BridgeMode.LIVE) actions.onMode(BridgeMode.LIVE) else actions.onDeviceScan() },
                            enabled = !state.scanning,
                            colors = ButtonDefaults.buttonColors(containerColor = Ice, contentColor = Midnight),
                            modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp),
                        ) {
                            Text(if (state.mode != BridgeMode.LIVE) "Open Live mode" else if (state.scanning) "Scanning…" else "Scan for sensors")
                        }
                        if (state.mode != BridgeMode.PREVIEW) TextButton(onClick = { actions.onMode(BridgeMode.PREVIEW) }, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text("Use preview") }
                    }
                    if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Ice, trackColor = Color.White.copy(alpha = .12f))
                }
            }
        }
        if (state.message.isNotBlank()) item { Notice(state.message) }
        item { SectionTitle("Nearby sensors", if (state.devices.isEmpty()) "No devices listed" else "${state.devices.size} available") }
        if (state.devices.isEmpty()) item { EmptyCard("Ready when your sensor is", "Turn on your compatible device and scan. A device appears here only after the phone receives its advertisement.") }
        items(state.devices, key = { it.address }) { device ->
            Surface(color = if (device.connected) Color(0xFFEEEFFD) else Color.White, shape = RoundedCornerShape(20.dp), border = if (device.connected) androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF9A92D6)) else null) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph("devices", Iris, Modifier.size(25.dp))
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(device.name, color = Midnight, style = MaterialTheme.typography.titleMedium)
                            Text(device.address, color = Muted, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text(device.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        device.rssi?.let { Text("$it dBm", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                        Button(onClick = { if (device.connected) actions.onDisconnect() else actions.onConnect(device.address) }, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(13.dp)) { Text(if (device.connected) "Disconnect" else "Connect") }
                    }
                }
            }
        }
        item {
            Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("A defined connection", color = Midnight, style = MaterialTheme.typography.titleMedium)
                    Text("Bridge receives a timestamp, sequence number and sample block from each packet. Sensors with other formats need a matching adapter. Pairing alone does not make their data compatible.", color = Muted, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
