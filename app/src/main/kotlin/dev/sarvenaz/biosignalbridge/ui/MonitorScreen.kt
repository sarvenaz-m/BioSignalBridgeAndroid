package dev.sarvenaz.biosignalbridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import dev.sarvenaz.biosignalbridge.signal.WindowInspection
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Monitor(state: BridgeUiState, actions: BridgeActions, modifier: Modifier) {
    var markerDialog by remember { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().testTag("workspace-list"), contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Eyebrow(if (state.mode == BridgeMode.REVIEW) "SESSION REVIEW" else "MONITOR")
                    Text("Every sample,\nin context.", style = MaterialTheme.typography.headlineLarge, color = Midnight, modifier = Modifier.padding(top = 5.dp))
                }
                StatusPill(state.phase, state.recording, Modifier.padding(top = 4.dp))
            }
        }
        item { SignalPanel(state, actions) }
        item {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onPlayPause, enabled = state.mode != BridgeMode.LIVE, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                    Glyph(if (state.running) "pause" else "play", Color.White, Modifier.size(20.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (state.mode == BridgeMode.LIVE) "Live stream" else if (state.running) "Pause" else if (state.mode == BridgeMode.REVIEW) "Play session" else "Start stream")
                }
                OutlinedButton(onClick = actions.onRecord, enabled = state.mode != BridgeMode.REVIEW && (state.mode != BridgeMode.LIVE || state.recording || (state.running && state.totalSamples > 0)), modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                    Glyph(if (state.recording) "stop" else "record", if (state.recording) MaterialTheme.colorScheme.error else Iris, Modifier.size(19.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (state.recording) "Save session" else "Record")
                }
                OutlinedButton(onClick = { markerDialog = true }, enabled = state.recording, modifier = Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) {
                    Glyph("marker", Iris, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Mark event")
                }
            }
        }
        if (state.message.isNotBlank()) item { Notice(state.message) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric("Sample rate", if (state.rateHz > 0) "${state.rateHz}" else "—", "Hz", Modifier.weight(1f))
                Metric("Received", grouped(state.totalSamples), "samples", Modifier.weight(1f))
                Metric("Missing", grouped(state.lostPackets), "packets", Modifier.weight(1f), state.lostPackets > 0)
            }
        }
        item { CaptureIntegrity(state) }
        state.windowInspection?.let { summary -> item { WindowInspectionCard(summary, state.unit) } }
        if (state.markers.isNotEmpty()) {
            item { SectionTitle("Event markers", "${state.markers.size} in this session") }
            items(state.markers.takeLast(12).asReversed(), key = { "marker-${it.id}" }) { marker ->
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable(enabled = state.mode != BridgeMode.LIVE && !state.recording) { actions.onSeek(marker.timeSec) }) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Glyph("marker", Copper)
                        Text(marker.label, Modifier.weight(1f).padding(horizontal = 10.dp), style = MaterialTheme.typography.titleSmall)
                        Text(timeLabel(marker.timeSec), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = Muted)
                    }
                }
            }
        }
        item {
            Text(
                when (state.mode) {
                    BridgeMode.PREVIEW -> "Public ECG excerpt from MIT-BIH record 100. Explore the waveform and recording tools without connecting a sensor."
                    BridgeMode.LIVE -> "The monitor keeps the latest 5,000 samples per channel. Saved sessions retain every recorded sample. Live values are recorded as raw ADC counts. Calibration is not configured in this workspace."
                    BridgeMode.REVIEW -> "You are reviewing saved samples. Markers are annotations; they do not identify a medical condition."
                },
                color = Muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
    if (markerDialog) TextEntryDialog(
        title = "Mark this moment", description = "Annotate the latest recorded sample at ${timeLabel(state.durationSec)}. Your note stays with the session.",
        initial = "", label = "Event note", confirm = "Add marker", onDismiss = { markerDialog = false }, maxLength = 240,
        onConfirm = { actions.onMarker(it); markerDialog = false },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignalPanel(state: BridgeUiState, actions: BridgeActions) {
    var channelsOpen by remember { mutableStateOf(false) }
    Surface(color = Midnight, shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.channelName.uppercase(), color = Color(0xFFC3CCE6), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
                    Text(state.activeSessionName, color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 5.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(state.unit, color = Ice, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
            }
            Text("Amplitude auto-scaled for this window", color = Color(0xFFC3CCE6), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            if (state.channelOptions.size > 1) {
                Box {
                    TextButton(onClick = { channelsOpen = true }, modifier = Modifier.heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColors(contentColor = Ice)) { Text("Choose channel") }
                    DropdownMenu(expanded = channelsOpen, onDismissRequest = { channelsOpen = false }) {
                        state.channelOptions.forEach { (id, label) ->
                            DropdownMenuItem(text = { Text(if (id == state.selectedChannel) "$label · selected" else label) }, onClick = { actions.onChannel(id); channelsOpen = false }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(15.dp))
            SignalChart(state, actions)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(timeLabel(max(0f, state.windowEndSec - state.showWindowSec)), color = Color(0xFFC3CCE6), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text("${number(state.showWindowSec)} s window", color = Color(0xFFC3CCE6), style = MaterialTheme.typography.bodySmall)
                Text(timeLabel(max(state.windowEndSec, state.showWindowSec)), color = Color(0xFFC3CCE6), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
            HorizontalDivider(Modifier.padding(vertical = 14.dp), color = Color.White.copy(alpha = .12f))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(4f, 8f, 16f, 32f).forEach { seconds ->
                    val selected = state.showWindowSec == seconds
                    Surface(
                        onClick = { actions.onWindow(seconds) },
                        color = if (selected) Ice else Color.White.copy(alpha = .06f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 54.dp),
                    ) {
                        Box(Modifier.padding(horizontal = 13.dp), contentAlignment = Alignment.Center) {
                            Text("${seconds.toInt()} s", color = if (selected) Midnight else Color.White, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            val seekMax = if (state.durationSec > 0f) state.durationSec else state.showWindowSec
            Slider(
                value = state.windowEndSec.coerceIn(0f, seekMax),
                onValueChange = actions.onSeek,
                enabled = state.mode != BridgeMode.LIVE && !state.recording,
                valueRange = 0f..seekMax,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Signal timeline" },
                colors = SliderDefaults.colors(thumbColor = Ice, activeTrackColor = Ice, inactiveTrackColor = Color(0xFF435171)),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph("drag", Color(0xFFC3CCE6), Modifier.size(16.dp))
                Text(if (state.mode == BridgeMode.LIVE || state.recording) "Following incoming samples" else "Drag to move · ${timeLabel(state.windowEndSec)}", color = Color(0xFFC3CCE6), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 5.dp))
            }
        }
    }
}

@Composable
private fun SignalChart(state: BridgeUiState, actions: BridgeActions) {
    val currentState by rememberUpdatedState(state)
    val currentActions by rememberUpdatedState(actions)
    val start = max(0f, state.windowEndSec - state.showWindowSec)
    val end = start + state.showWindowSec
    val points = remember(state.points, start, end) { state.points.filter { it.timeSec >= start && it.timeSec <= end } }
    val low = points.minOfOrNull { it.value } ?: -1f
    val high = points.maxOfOrNull { it.value } ?: 1f
    val spread = max(if (state.unit == "mV") .4f else 1f, high - low)
    val bottom = low - spread * .2f
    val top = high + spread * .2f
    Box {
        Canvas(
            Modifier.fillMaxWidth().height(192.dp)
                .semantics { contentDescription = "${state.unit} signal waveform, ${number(state.showWindowSec)} second window. Gaps break the line." }
                .pointerInput(state.showWindowSec, state.durationSec, state.mode, state.recording) {
                    if (state.mode == BridgeMode.LIVE || state.recording) return@pointerInput
                    detectHorizontalDragGestures { change, amount ->
                        change.consume()
                        val fresh = currentState
                        currentActions.onSeek((fresh.windowEndSec - amount / size.width * fresh.showWindowSec).coerceIn(0f, max(fresh.durationSec, fresh.showWindowSec)))
                    }
                },
        ) {
            repeat(17) { i ->
                val x = size.width * i / 16
                drawLine(Color.White.copy(alpha = if (i % 4 == 0) .12f else .045f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
            }
            repeat(9) { i ->
                val y = size.height * i / 8
                drawLine(Color.White.copy(alpha = if (i % 4 == 0) .12f else .045f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            val path = Path()
            var started = false
            points.forEach { point ->
                val x = (point.timeSec - start) / state.showWindowSec * size.width
                val y = size.height - (point.value - bottom) / (top - bottom) * size.height
                if (!started || point.gapBefore) {
                    path.moveTo(x, y)
                    if (point.gapBefore) drawLine(Color(0xFFF0AA94).copy(alpha = .6f), Offset(x, 0f), Offset(x, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                } else path.lineTo(x, y)
                started = true
            }
            drawPath(path, Ice.copy(alpha = .09f), style = Stroke(8.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, Ice, style = Stroke(1.8.dp.toPx(), cap = StrokeCap.Round))
            state.markers.filter { it.timeSec in start..end }.forEach { marker ->
                val x = (marker.timeSec - start) / state.showWindowSec * size.width
                drawLine(Color(0xFFF0AA94), Offset(x, 10.dp.toPx()), Offset(x, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
                drawCircle(Color(0xFFF0AA94), 4.dp.toPx(), Offset(x, 7.dp.toPx()))
            }
        }
        if (points.isEmpty()) {
            Box(Modifier.fillMaxWidth().height(192.dp), contentAlignment = Alignment.Center) {
                Text(if (state.mode == BridgeMode.LIVE) "Waiting for sensor samples" else "Start the stream to see samples", color = Color(0xFFC3CCE6), style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Column(Modifier.align(Alignment.TopStart).height(192.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(amplitudeLabel(top, state.unit), color = Color(0xFFC3CCE6), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.background(Midnight.copy(alpha = .9f)).padding(end = 4.dp))
                Text(amplitudeLabel(bottom, state.unit), color = Color(0xFFC3CCE6), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.background(Midnight.copy(alpha = .9f)).padding(end = 4.dp))
            }
        }
    }
}

@Composable
private fun WindowInspectionCard(summary: WindowInspection, unit: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionTitle("Window details", "${grouped(summary.sampleCount)} samples")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InspectionValue("Minimum", summary.minimum?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—", unit, Modifier.weight(1f))
                InspectionValue("Maximum", summary.maximum?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—", unit, Modifier.weight(1f))
                InspectionValue("RMS", summary.rms?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "—", unit, Modifier.weight(1f))
            }
            HorizontalDivider(color = Line)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                InspectionValue("Observed rate", summary.observedRateHz?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—", "Hz", Modifier.weight(1f))
                InspectionValue("Segments", grouped(summary.segments), "in view", Modifier.weight(1f))
                InspectionValue("ADC limits", grouped(summary.railSamples), "samples", Modifier.weight(1f))
            }
            Text("Calculated from the samples in view. Timing excludes marked gaps and repeated timestamps.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun InspectionValue(label: String, value: String, unit: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, color = Midnight, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
        Text(unit, color = Iris, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CaptureIntegrity(state: BridgeUiState) {
    Surface(color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Capture integrity", "Transport counters")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IntegrityValue("Duplicates", state.duplicates, Modifier.weight(1f))
                IntegrityValue("Out of order", state.outOfOrder, Modifier.weight(1f))
                IntegrityValue("Clock resets", state.clockResets, Modifier.weight(1f))
            }
            HorizontalDivider(color = Line)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IntegrityValue("Malformed", state.malformed, Modifier.weight(1f))
                IntegrityValue("Buffer drops", state.overflow, Modifier.weight(1f))
                Column(Modifier.weight(1f)) {
                    Text(timeLabel(state.durationSec), color = Midnight, style = MaterialTheme.typography.titleMedium)
                    Text("Session time", color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun IntegrityValue(label: String, value: Long, modifier: Modifier) {
    Column(modifier) {
        Text(grouped(value), color = if (value > 0) Copper else Midnight, style = MaterialTheme.typography.titleMedium)
        Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
