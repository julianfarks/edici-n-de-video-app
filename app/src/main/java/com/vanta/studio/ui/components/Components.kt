package com.vanta.studio.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.studio.R
import com.vanta.studio.ui.theme.Vanta
import java.util.Locale

/** The Vanta mark: a "V" of two light beams meeting at a point, with a playhead dot. */
@Composable
fun VantaMark(size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.16f
        val left = Path().apply {
            moveTo(w * 0.14f, h * 0.18f)
            lineTo(w * 0.5f, h * 0.84f)
        }
        val right = Path().apply {
            moveTo(w * 0.5f, h * 0.84f)
            lineTo(w * 0.86f, h * 0.18f)
        }
        drawPath(
            left,
            Brush.linearGradient(listOf(Vanta.AccentBright, Vanta.Accent), Offset(0f, 0f), Offset(w * 0.5f, h)),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawPath(
            right,
            Brush.linearGradient(listOf(Vanta.Accent, Color(0xFF5B3FE0)), Offset(w * 0.5f, h), Offset(w, 0f)),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawCircle(Vanta.Text, radius = w * 0.07f, center = Offset(w * 0.86f, h * 0.18f))
    }
}

@Composable
fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        VantaMark(26.dp)
        Spacer(Modifier.width(10.dp))
        Column {
            Text("VANTA", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 4.sp, fontWeight = FontWeight.Bold))
            Text(
                "STUDIO",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 3.sp),
                color = Vanta.TextFaint,
            )
        }
    }
}

/** Square tool button with icon + label, used in the editor's tool bars. */
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    tint: Color = Vanta.Text,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(120), label = "toolScale")
    val bg by animateColorAsState(if (selected) Vanta.AccentDim else Color.Transparent, tween(160), label = "toolBg")
    val color = when {
        !enabled -> Vanta.TextFaint
        selected -> Vanta.AccentBright
        else -> tint
    }
    Column(
        modifier
            .widthIn(min = 64.dp)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(interaction, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** Header + content container for a tool panel. */
@Composable
fun PanelScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(Vanta.Surface)
            .padding(bottom = 8.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            trailing?.invoke()
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.action_done), tint = Vanta.AccentBright)
            }
        }
        content()
    }
}

@Composable
fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onChangeFinished: (() -> Unit)? = null,
    steps: Int = 0,
    valueText: (Float) -> String = { String.format(Locale.US, "%.0f", it * 100) },
    neutral: Float? = null,
) {
    Column(modifier.padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Vanta.TextDim, modifier = Modifier.weight(1f))
            if (neutral != null && value != neutral) {
                Text(
                    stringResource(R.string.action_reset),
                    style = MaterialTheme.typography.labelSmall,
                    color = Vanta.AccentBright,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable {
                            onChange(neutral)
                            onChangeFinished?.invoke()
                        }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(valueText(value), style = MaterialTheme.typography.labelMedium, color = Vanta.Text)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onChangeFinished,
            colors = SliderDefaults.colors(
                thumbColor = Vanta.Text,
                activeTrackColor = Vanta.Accent,
                inactiveTrackColor = Vanta.Surface3,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val bg by animateColorAsState(if (selected) Vanta.Accent else Vanta.Surface2, tween(150), label = "chipBg")
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else Vanta.Outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (selected) Color.White else Vanta.Text)
    }
}

@Composable
fun ColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit, size: Dp = 30.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Vanta.AccentBright else Vanta.OutlineStrong, CircleShape)
            .padding(if (selected) 4.dp else 0.dp)
            .clip(CircleShape)
            .background(color)
            .clickable(onClick = onClick),
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vanta.Surface2,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = Vanta.TextDim) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = if (destructive) Vanta.Danger else Vanta.AccentBright)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Vanta.TextDim) }
        },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    singleLine: Boolean = true,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vanta.Surface2,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = singleLine,
                minLines = if (singleLine) 1 else 3,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Vanta.Accent,
                    unfocusedBorderColor = Vanta.Outline,
                    cursorColor = Vanta.AccentBright,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.action_ok), color = Vanta.AccentBright)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Vanta.TextDim) }
        },
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
        color = Vanta.TextFaint,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
fun CloseButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close), tint = Vanta.TextDim)
    }
}

val DefaultRowPadding = PaddingValues(horizontal = 12.dp)
val ToolSpacing = Arrangement.spacedBy(2.dp)

/** 00:12.34 style timecode (or 1:02:03.4 for long projects). */
fun formatTime(us: Long, fraction: Boolean = true): String {
    val totalCs = (us.coerceAtLeast(0) / 10_000)
    val cs = totalCs % 100
    val totalSec = totalCs / 100
    val s = totalSec % 60
    val m = (totalSec / 60) % 60
    val h = totalSec / 3600
    return when {
        h > 0 -> String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        fraction -> String.format(Locale.US, "%02d:%02d.%02d", m, s, cs)
        else -> String.format(Locale.US, "%d:%02d", m, s)
    }
}

fun formatSeconds(us: Long): String = String.format(Locale.US, "%.1fs", us / 1_000_000.0)
