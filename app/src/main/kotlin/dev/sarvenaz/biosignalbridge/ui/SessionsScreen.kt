package dev.sarvenaz.biosignalbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
internal fun Sessions(state: BridgeUiState, actions: BridgeActions, modifier: Modifier) {
    var rename by remember { mutableStateOf<UiSession?>(null) }
    var deleting by remember { mutableStateOf<UiSession?>(null) }
    LazyColumn(modifier.fillMaxSize().testTag("workspace-list"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageTitle("YOUR RECORDINGS", "Session library", "A clear record of samples, timing and the moments you marked.") }
        item {
            Surface(color = Midnight, shape = RoundedCornerShape(22.dp)) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Glyph("sessions", Ice, Modifier.size(32.dp))
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("${state.sessions.size} saved sessions", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        Text("Stored on this device", color = Color(0xFFC3CCE6), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        if (state.sessions.isEmpty()) item {
            EmptyCard("Your next capture starts here", "Record a preview or connect a compatible sensor. Save the recording when you finish; it will appear in this library.", "Open monitor") { actions.onTab(BridgeTab.MONITOR) }
        }
        items(state.sessions, key = { it.id }) { session ->
            var menuOpen by remember(session.id) { mutableStateOf(false) }
            Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFEEECFC)), contentAlignment = Alignment.Center) { Glyph("monitor", Iris) }
                        Column(Modifier.weight(1f).padding(start = 11.dp)) {
                            Text(session.name, style = MaterialTheme.typography.titleMedium, color = Midnight)
                            Text(session.startedAt, color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(when (session.source.uppercase()) { "PREVIEW" -> "Public ECG preview"; "LIVE" -> "Live sensor recording"; else -> session.source }, color = Muted, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(timeLabel(session.durationSec), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                        Text("${grouped(session.sampleCount)} samples", style = MaterialTheme.typography.bodySmall, color = Muted)
                        Text("${session.markerCount} notes", style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                    HorizontalDivider(color = Line)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = { actions.onSessionOpen(session.id) }, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Review") }
                        TextButton(onClick = { actions.onExport(session.id) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Export") }
                        Spacer(Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) { Glyph("more", Muted, Modifier.semantics { contentDescription = "Actions for ${session.name}" }) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Rename session") }, onClick = { rename = session; menuOpen = false }, modifier = Modifier.heightIn(min = 48.dp))
                                DropdownMenuItem(text = { Text("Delete session", color = MaterialTheme.colorScheme.error) }, onClick = { deleting = session; menuOpen = false }, modifier = Modifier.heightIn(min = 48.dp))
                            }
                        }
                    }
                }
            }
        }
        item { Text("Exports include sample timestamps, transport counters and event notes for your analysis workflow.", color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
    rename?.let { session ->
        TextEntryDialog("Rename session", "Choose a name you will recognise later.", session.name, "Session name", "Save name", { rename = null }) {
            actions.onRename(session.id, it); rename = null
        }
    }
    deleting?.let { session ->
        BridgeDialog(onDismiss = { deleting = null }) {
            Text("Delete this session?", style = MaterialTheme.typography.titleLarge, color = Midnight)
            Text("“${session.name}” will be removed from this device. Export it first if you want to keep a copy.", color = Muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { deleting = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep session") }
                Button(onClick = { actions.onDelete(session.id); deleting = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error), modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
            }
        }
    }
}
