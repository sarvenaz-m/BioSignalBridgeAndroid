package dev.sarvenaz.biosignalbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun Settings(state: BridgeUiState, actions: BridgeActions, modifier: Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("workspace-list"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { PageTitle("WORKSPACE", "Considered by design.", "Clear sources, local recordings and room to inspect the details.") }
        item {
            Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                    SectionTitle("Data source", "${state.mode.name.lowercase().replaceFirstChar { it.titlecase() }} mode")
                    SourceChoice("Public recording", "MIT-BIH record 100, MLII excerpt · resampled to 250 Hz", state.mode == BridgeMode.PREVIEW) { actions.onMode(BridgeMode.PREVIEW) }
                    HorizontalDivider(color = Line)
                    SourceChoice("Connected sensor", "Compatible BLE source · values and timing from the device", state.mode == BridgeMode.LIVE) { actions.onMode(BridgeMode.LIVE) }
                }
            }
        }
        item { InfoCard("Your recordings stay local", "Sessions are stored on this device. Export is a deliberate action; Bridge does not upload recordings in the background.", "sessions") }
        item { InfoCard("Source and context", "The preview is a public ECG excerpt from the MIT-BIH Arrhythmia Database on PhysioNet. It is included to explore the interface and data pipeline. The app does not interpret a waveform or provide a diagnosis.", "monitor") }
        item { InfoCard("Confidentiality and NDA", "Built independently with public data and an original sample protocol. Confidentiality and NDA boundaries are respected: no employer code, customer recordings, private designs or proprietary protocols are included.", "shield") }
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("BioSignal Bridge", style = MaterialTheme.typography.labelLarge, color = Midnight)
                Text("Version 1.1.0", style = MaterialTheme.typography.bodyMedium, color = Muted)
            }
        }
    }
}
