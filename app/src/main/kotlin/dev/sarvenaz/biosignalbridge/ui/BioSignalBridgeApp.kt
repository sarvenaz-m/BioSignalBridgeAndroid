package dev.sarvenaz.biosignalbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun BioSignalBridgeApp(state: BridgeUiState, actions: BridgeActions, modifier: Modifier = Modifier) {
    BioSignalBridgeTheme {
        Scaffold(
            modifier = modifier,
            containerColor = Porcelain,
            topBar = { BrandBar(state.mode) },
            bottomBar = {
                NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                    BridgeTab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = state.tab == tab,
                            onClick = { actions.onTab(tab) },
                            icon = { Glyph(tab.name.lowercase(), if (state.tab == tab) Iris else Muted) },
                            label = { Text(tab.name.lowercase().replaceFirstChar { it.titlecase() }) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = Color(0xFFE9E7FC)),
                        )
                    }
                }
            },
        ) { padding ->
            when (state.tab) {
                BridgeTab.MONITOR -> Monitor(state, actions, Modifier.padding(padding))
                BridgeTab.SESSIONS -> Sessions(state, actions, Modifier.padding(padding))
                BridgeTab.DEVICES -> Devices(state, actions, Modifier.padding(padding))
                BridgeTab.SETTINGS -> Settings(state, actions, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun BrandBar(mode: BridgeMode) {
    Surface(color = Midnight) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .1f)), contentAlignment = Alignment.Center) {
                Glyph("monitor", Ice, Modifier.size(25.dp))
            }
            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                Text("BioSignal Bridge", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Text("SIGNAL WORKSPACE", color = Color(0xFFC3CCE6), fontSize = 12.sp, letterSpacing = 1.7.sp)
            }
            SourceBadge(mode, dark = true)
        }
    }
}
