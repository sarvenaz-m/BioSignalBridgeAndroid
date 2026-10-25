package dev.sarvenaz.biosignalbridge.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale
import kotlin.math.max

@Composable
internal fun SourceChoice(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Midnight)
            Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun InfoCard(title: String, text: String, icon: String) {
    Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Glyph(icon, Iris, Modifier.size(25.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, color = Midnight)
            Text(text, color = Muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun Metric(label: String, value: String, suffix: String, modifier: Modifier, warning: Boolean = false) {
    Surface(modifier = modifier, color = Color.White, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, color = Muted, style = MaterialTheme.typography.bodySmall)
            Text(value, color = if (warning) Copper else Midnight, fontSize = 23.sp, lineHeight = 29.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(suffix, color = Muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun PageTitle(kicker: String, title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Eyebrow(kicker)
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Midnight)
        Text(description, color = Muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun Eyebrow(text: String) { Text(text, color = Iris, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.6.sp) }

@Composable
internal fun SectionTitle(title: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = Midnight, style = MaterialTheme.typography.titleMedium)
        Text(detail, color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun SourceBadge(mode: BridgeMode, dark: Boolean = false) {
    val label = mode.name.lowercase().replaceFirstChar { it.titlecase() }
    Surface(color = if (dark) Color.White.copy(alpha = .1f) else Color(0xFFEEEFFD), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (dark) Ice else Iris, CircleShape))
            Text(label, color = if (dark) Color.White else Midnight, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
internal fun StatusPill(phase: String, recording: Boolean, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = if (recording) Color(0xFFF9E6E3) else Color(0xFFEEECFC)) {
        Row(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(if (recording) Color(0xFFA62F38) else Iris, CircleShape))
            Text(if (recording) "Recording" else phase, color = if (recording) Color(0xFF8F2630) else Midnight, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp), maxLines = 2)
        }
    }
}

@Composable
internal fun Notice(text: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFEEECFC)) {
        Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
            Glyph("info", Iris, Modifier.size(19.dp))
            Text(text, color = Ink, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
internal fun EmptyCard(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Surface(color = Color.White, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = Midnight)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = Muted)
            if (action != null) FilledTonalButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

@Composable
internal fun TextEntryDialog(title: String, description: String, initial: String, label: String, confirm: String, onDismiss: () -> Unit, maxLength: Int = 120, onConfirm: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    BridgeDialog(onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = Midnight)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = Muted)
        OutlinedTextField(value = text, onValueChange = { text = it.take(maxLength) }, singleLine = true, label = { Text(label) }, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(13.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            Button(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text(confirm) }
        }
    }
}

@Composable
internal fun BridgeDialog(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.padding(20.dp).widthIn(max = 460.dp).fillMaxWidth().heightIn(max = 480.dp), shape = RoundedCornerShape(25.dp), color = Color.White) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(21.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
internal fun Glyph(kind: String, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = 1.8.dp.toPx()
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(w * x1, h * y1), Offset(w * x2, h * y2), stroke, StrokeCap.Round)
        when (kind) {
            "monitor" -> {
                val p = Path().apply { moveTo(0f, h * .52f); lineTo(w * .2f, h * .52f); lineTo(w * .32f, h * .3f); lineTo(w * .46f, h * .78f); lineTo(w * .61f, h * .14f); lineTo(w * .73f, h * .52f); lineTo(w, h * .52f) }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            "sessions" -> {
                drawRoundRect(tint, Offset(w * .2f, h * .12f), androidx.compose.ui.geometry.Size(w * .64f, h * .78f), CornerRadius(w * .1f), style = Stroke(stroke))
                line(.34f, .35f, .67f, .35f); line(.34f, .52f, .67f, .52f); line(.34f, .69f, .55f, .69f)
            }
            "devices" -> {
                val p = Path().apply { moveTo(w * .46f, h * .08f); lineTo(w * .76f, h * .31f); lineTo(w * .24f, h * .71f); moveTo(w * .24f, h * .29f); lineTo(w * .76f, h * .69f); lineTo(w * .46f, h * .92f); lineTo(w * .46f, h * .08f) }
                drawPath(p, tint, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            "settings" -> {
                line(.1f, .25f, .9f, .25f); line(.1f, .5f, .9f, .5f); line(.1f, .75f, .9f, .75f)
                listOf(Offset(w * .35f, h * .25f), Offset(w * .69f, h * .5f), Offset(w * .43f, h * .75f)).forEach { drawCircle(Color.White, w * .1f, it); drawCircle(tint, w * .1f, it, style = Stroke(stroke)) }
            }
            "play" -> drawPath(Path().apply { moveTo(w * .28f, h * .14f); lineTo(w * .83f, h * .5f); lineTo(w * .28f, h * .86f); close() }, tint)
            "pause" -> { line(.33f, .18f, .33f, .82f); line(.68f, .18f, .68f, .82f) }
            "record" -> drawCircle(tint, w * .29f, center)
            "more" -> listOf(.22f, .5f, .78f).forEach { drawCircle(tint, w * .065f, Offset(w * .5f, h * it)) }
            "stop" -> drawRoundRect(tint, Offset(w * .2f, h * .2f), androidx.compose.ui.geometry.Size(w * .6f, h * .6f), CornerRadius(w * .08f))
            "marker" -> { line(.25f, .12f, .25f, .9f); drawPath(Path().apply { moveTo(w * .25f, h * .12f); lineTo(w * .83f, h * .12f); lineTo(w * .68f, h * .37f); lineTo(w * .25f, h * .37f) }, tint, style = Stroke(stroke)) }
            "drag" -> { line(.08f, .5f, .92f, .5f); line(.08f, .5f, .28f, .3f); line(.08f, .5f, .28f, .7f); line(.92f, .5f, .72f, .3f); line(.92f, .5f, .72f, .7f) }
            "edit" -> { line(.2f, .8f, .7f, .2f); line(.7f, .2f, .85f, .35f); line(.2f, .8f, .42f, .74f) }
            "delete" -> { line(.2f, .27f, .8f, .27f); line(.35f, .12f, .65f, .12f); line(.28f, .27f, .33f, .87f); line(.33f, .87f, .69f, .87f); line(.69f, .87f, .74f, .27f); line(.44f, .4f, .44f, .72f); line(.58f, .4f, .58f, .72f) }
            "shield" -> {
                drawPath(Path().apply { moveTo(w * .5f, h * .08f); lineTo(w * .85f, h * .22f); lineTo(w * .8f, h * .62f); quadraticTo(w * .7f, h * .84f, w * .5f, h * .94f); quadraticTo(w * .3f, h * .84f, w * .2f, h * .62f); lineTo(w * .15f, h * .22f); close() }, tint, style = Stroke(stroke))
                line(.33f, .5f, .46f, .63f); line(.46f, .63f, .7f, .36f)
            }
            else -> { drawCircle(tint, w * .4f, center, style = Stroke(stroke)); line(.5f, .46f, .5f, .72f); drawCircle(tint, w * .04f, Offset(w * .5f, h * .29f)) }
        }
    }
}

internal fun timeLabel(seconds: Float): String {
    val safe = seconds.coerceAtLeast(0f)
    return String.format(Locale.US, "%02d:%04.1f", safe.toInt() / 60, safe % 60)
}
internal fun number(value: Float): String = if (value % 1f == 0f) value.toInt().toString() else String.format(Locale.US, "%.1f", value)
internal fun grouped(value: Long): String = String.format(Locale.US, "%,d", value)
internal fun amplitudeLabel(value: Float, unit: String): String = if (unit == "ADC counts") String.format(Locale.US, "%.0f", value) else String.format(Locale.US, "%.2f", value)
