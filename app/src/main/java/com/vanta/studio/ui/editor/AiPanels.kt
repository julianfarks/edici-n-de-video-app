package com.vanta.studio.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Highlight
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.studio.AppContainer
import com.vanta.studio.R
import com.vanta.studio.domain.audio.BeatMapping
import com.vanta.studio.domain.fx.AutoSyncOptions
import com.vanta.studio.domain.fx.FxAutoSync
import com.vanta.studio.domain.fx.FxLibrary
import com.vanta.studio.domain.fx.FxOps
import com.vanta.studio.domain.fx.SyncTarget
import com.vanta.studio.domain.model.FxType
import com.vanta.studio.ui.components.Chip
import com.vanta.studio.ui.components.ColorSwatch
import com.vanta.studio.ui.components.LabeledSlider
import com.vanta.studio.ui.components.PanelScaffold
import com.vanta.studio.ui.components.SectionLabel
import com.vanta.studio.ui.theme.Vanta
import java.util.Locale

// =============================================================================== shared pieces

/** How ready a tool is, shown honestly on its card. */
enum class ToolStatus { AVAILABLE, NEEDS_MODEL, NEEDS_CONNECTION, NOT_IMPLEMENTED }

@Composable
fun JobBar(ai: AiState, kind: AiJobKind, onCancel: () -> Unit) {
    val job = ai.job ?: return
    if (job.kind != kind) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ai_working, (job.progress * 100).toInt()),
                style = MaterialTheme.typography.labelMedium, color = Vanta.TextDim, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
        LinearProgressIndicator(progress = { job.progress }, modifier = Modifier.fillMaxWidth(), color = Vanta.Accent)
    }
}

@Composable
fun ErrorBanner(ai: AiState, onDismiss: () -> Unit) {
    val e = ai.error ?: return
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(10.dp)).background(Vanta.Danger.copy(alpha = 0.16f)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(e.res), style = MaterialTheme.typography.bodySmall, color = Vanta.Text)
            if (!e.detail.isNullOrBlank()) Text(e.detail, style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, maxLines = 3)
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
    }
}

@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (!enabled) Vanta.Surface3 else if (primary) Vanta.Accent else Vanta.Surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge,
            color = if (!enabled) Vanta.TextFaint else if (primary) Color.White else Vanta.Text,
        )
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Vanta.Accent))
    }
}

// =============================================================================== AI Studio hub

private class AiToolInfo(
    val icon: ImageVector,
    val name: Int,
    val about: Int,
    val status: ToolStatus,
    val open: (() -> Unit)?,
)

@Composable
fun AiStudioPanel(vm: EditorViewModel, container: AppContainer, state: EditorState, close: () -> Unit) {
    val ai by vm.ai.state.collectAsStateWithLifecycle()
    val esModelReady = container.models.forLanguage("es")?.let { container.models.installed(it) != null } == true
    val tools = listOf(
        AiToolInfo(
            Icons.Rounded.Subtitles, R.string.ai_t_subtitles, R.string.ai_d_subtitles,
            if (esModelReady) ToolStatus.AVAILABLE else ToolStatus.NEEDS_MODEL,
        ) { vm.openPanel(Panel.SUBTITLES) },
        AiToolInfo(Icons.Rounded.MusicNote, R.string.ai_t_beat_edit, R.string.ai_d_beat_edit, ToolStatus.NOT_IMPLEMENTED, null),
        AiToolInfo(Icons.Rounded.Highlight, R.string.ai_t_moments, R.string.ai_d_moments, ToolStatus.NOT_IMPLEMENTED, null),
        AiToolInfo(Icons.Rounded.ContentCut, R.string.ai_t_silence, R.string.ai_d_silence, ToolStatus.NOT_IMPLEMENTED, null),
        AiToolInfo(Icons.Rounded.Chat, R.string.ai_t_assistant, R.string.ai_d_assistant, ToolStatus.NOT_IMPLEMENTED, null),
        AiToolInfo(Icons.Rounded.Translate, R.string.ai_t_translate, R.string.ai_d_translate, ToolStatus.NOT_IMPLEMENTED, null),
        AiToolInfo(Icons.Rounded.RecordVoiceOver, R.string.ai_t_tts, R.string.ai_d_tts, ToolStatus.NOT_IMPLEMENTED, null),
        AiToolInfo(Icons.Rounded.CenterFocusStrong, R.string.ai_t_tracking, R.string.ai_d_tracking, ToolStatus.NOT_IMPLEMENTED, null),
        AiToolInfo(Icons.Rounded.Layers, R.string.ai_t_bg, R.string.ai_d_bg, ToolStatus.NOT_IMPLEMENTED, null),
        AiToolInfo(Icons.Rounded.AspectRatio, R.string.ai_t_reframe, R.string.ai_d_reframe, ToolStatus.NOT_IMPLEMENTED, null),
        AiToolInfo(Icons.Rounded.HighQuality, R.string.ai_t_enhance, R.string.ai_d_enhance, ToolStatus.NOT_IMPLEMENTED, null),
        AiToolInfo(
            Icons.Rounded.AutoAwesome, R.string.ai_t_fx_assistant, R.string.ai_d_fx_assistant, ToolStatus.AVAILABLE,
        ) { vm.openPanel(Panel.BEAT_FX) },
    )
    PanelScaffold(stringResource(R.string.panel_ai), close) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.ai_hub_intro),
                style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            ErrorBanner(ai) { vm.ai.dismissError() }
            tools.forEach { t ->
                val running = ai.job != null && ((t.name == R.string.ai_t_subtitles && ai.job?.kind == AiJobKind.SUBTITLES) ||
                    (t.name == R.string.ai_t_fx_assistant && ai.job?.kind == AiJobKind.BEAT))
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = t.open != null) { t.open?.invoke() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(t.icon, null, tint = if (t.open != null) Vanta.AccentBright else Vanta.TextFaint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(t.name), style = MaterialTheme.typography.bodyMedium, color = if (t.open != null) Vanta.Text else Vanta.TextDim)
                        Text(stringResource(t.about), style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint)
                    }
                    Spacer(Modifier.width(8.dp))
                    StatusPill(if (running) null else t.status)
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: ToolStatus?) {
    val (text, color) = when (status) {
        null -> R.string.status_processing to Vanta.AccentBright
        ToolStatus.AVAILABLE -> R.string.status_available to Color(0xFF4ADE80)
        ToolStatus.NEEDS_MODEL -> R.string.status_needs_model to Color(0xFFFFB547)
        ToolStatus.NEEDS_CONNECTION -> R.string.status_needs_connection to Color(0xFFFFB547)
        ToolStatus.NOT_IMPLEMENTED -> R.string.status_not_implemented to Vanta.TextFaint
    }
    Text(
        stringResource(text), style = MaterialTheme.typography.labelSmall, color = color,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, color.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// =============================================================================== beat & light

@Composable
fun BeatFxPanel(vm: EditorViewModel, state: EditorState, close: () -> Unit) {
    val project = state.project ?: return
    val ai by vm.ai.state.collectAsStateWithLifecycle()
    val map = project.beat?.map
    val chosen = remember { mutableStateListOf(FxType.FLASH_WHITE) }
    var target by remember { mutableStateOf(SyncTarget.STRONG_HITS) }
    var every by remember { mutableStateOf(1f) }
    var intensity by remember { mutableStateOf(0.6f) }
    var safe by remember { mutableStateOf(true) }
    val flicker = FxAutoSync.flickerWarnings(project.fx)

    PanelScaffold(stringResource(R.string.panel_beat), close) {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            ErrorBanner(ai) { vm.ai.dismissError() }

            SectionLabel(stringResource(R.string.beat_section_rhythm))
            if (map == null) {
                Text(
                    stringResource(R.string.beat_none_hint),
                    style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            } else {
                val hits = BeatMapping.onsets(project, ai.hitThreshold).size
                Text(
                    stringResource(
                        R.string.beat_summary, map.bpm.toInt(), (map.tempoConfidence * 100).toInt(),
                        map.onsets.size, map.beats.size, map.sections.size, map.pauses.size,
                    ),
                    style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                LabeledSlider(
                    stringResource(R.string.beat_sensitivity), ai.hitThreshold, 0.05f..0.95f, vm.ai::setHitThreshold,
                    valueText = { String.format(Locale.US, "%d", hits) + " " + "▮" },
                )
            }
            JobBar(ai, AiJobKind.BEAT) { vm.ai.cancel() }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    stringResource(if (map == null) R.string.beat_analyze else R.string.beat_reanalyze),
                    { vm.ai.analyzeBeat() }, enabled = ai.job == null && vm.ai.beatCandidate() != null, primary = map == null,
                )
                ActionButton(stringResource(R.string.beat_mark), { vm.toggleMarkerAtPlayhead() })
                if (map != null || project.markers.isNotEmpty()) {
                    ActionButton(stringResource(R.string.beat_clear), { vm.ai.clearBeat(); vm.clearMarkers() })
                }
            }

            SectionLabel(stringResource(R.string.beat_section_fx))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FxLibrary.all.forEach { d ->
                    FxTile(d.type, selected = d.type in chosen, onTap = {
                        if (d.type in chosen) chosen.remove(d.type) else chosen.add(d.type)
                    }, onAdd = { vm.addFx(d.type) })
                }
            }
            Text(
                stringResource(R.string.beat_fx_hint),
                style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            SectionLabel(stringResource(R.string.beat_section_auto))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SyncTarget.entries.forEach { t ->
                    Chip(stringResource(syncTargetRes(t)), target == t, { target = t })
                }
            }
            LabeledSlider(stringResource(R.string.beat_every), every, 1f..4f, { every = it.toInt().toFloat() }, steps = 2, valueText = { "1/${it.toInt()}" })
            LabeledSlider(stringResource(R.string.beat_intensity), intensity, 0.1f..1.5f, { intensity = it })
            SwitchRow(stringResource(R.string.beat_safe), safe) { safe = it }
            if (flicker > 0) {
                Text(
                    stringResource(R.string.beat_flicker_warning, flicker),
                    style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB547), modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            val canSync = chosen.isNotEmpty() && (target != SyncTarget.MANUAL_MARKERS || project.markers.isNotEmpty()) &&
                (target == SyncTarget.MANUAL_MARKERS || map != null)
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.beat_apply), {
                    vm.ai.autoSync(
                        chosen.toList(),
                        AutoSyncOptions(target = target, hitThreshold = ai.hitThreshold, everyNth = every.toInt(), intensity = intensity, safeMode = safe),
                    )
                }, enabled = canSync, primary = true)
                if (project.fx.any { it.id.startsWith(FxAutoSync.AUTO_PREFIX) }) {
                    ActionButton(stringResource(R.string.beat_remove_auto), { vm.ai.removeAutoFx() })
                }
            }
            ai.lastAutoCount?.let {
                Text(
                    stringResource(R.string.beat_added, it),
                    style = MaterialTheme.typography.labelSmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
            if (!canSync) {
                Text(
                    stringResource(if (target == SyncTarget.MANUAL_MARKERS) R.string.beat_need_markers else R.string.beat_need_analysis),
                    style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

private fun syncTargetRes(t: SyncTarget) = when (t) {
    SyncTarget.STRONG_HITS -> R.string.sync_strong
    SyncTarget.ALL_PULSES -> R.string.sync_pulses
    SyncTarget.MANUAL_MARKERS -> R.string.sync_markers
    SyncTarget.SECTION_CHANGES -> R.string.sync_sections
    SyncTarget.ENERGY_PEAKS -> R.string.sync_peaks
}

/** A small static picture of what the effect does, its name, and a tap-to-select/long add. */
@Composable
private fun FxTile(type: FxType, selected: Boolean, onTap: () -> Unit, onAdd: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(10.dp))
            .border(2.dp, if (selected) Vanta.Accent else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onTap).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF262633))) { drawFxThumb(type) }
        Text(
            stringResource(fxNameRes(type)), style = MaterialTheme.typography.labelSmall, color = Vanta.Text,
            maxLines = 2, modifier = Modifier.padding(top = 3.dp),
        )
        Text(
            "+", style = MaterialTheme.typography.titleSmall, color = Vanta.AccentBright,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAdd).padding(horizontal = 14.dp),
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFxThumb(t: FxType) {
    val w = size.width
    val h = size.height
    val c = Offset(w / 2, h / 2)
    when (t) {
        FxType.FLASH_WHITE -> drawRect(Color.White)
        FxType.FLASH_BLACK -> drawRect(Color.Black)
        FxType.FLASH_COLOR -> drawRect(Brush.linearGradient(listOf(Color(0xFFFF4D8D), Color(0xFF6C5CFF))))
        FxType.RADIAL_BURST -> drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), c, w * 0.6f), w * 0.6f, c)
        FxType.GLOW -> drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE9A8), Color.Transparent), c, w * 0.5f), w * 0.5f, c)
        FxType.BLOOM -> {
            drawRect(Color(0xFF3A3A55))
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), Color.Transparent), c, w * 0.55f), w * 0.55f, c)
        }
        FxType.LIGHT_LEAK -> drawRect(Brush.horizontalGradient(listOf(Color(0xFFFF7A18), Color(0xFFFFD27A), Color.Transparent)))
        FxType.LENS_FLARE -> {
            drawCircle(Color.White, 5f, Offset(w * 0.25f, h * 0.3f))
            drawCircle(Color(0x66FFB347), 12f, Offset(w * 0.5f, h * 0.5f))
            drawCircle(Color(0x6647B3FF), 8f, Offset(w * 0.7f, h * 0.65f))
        }
        FxType.LIGHT_SWEEP -> drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White, Color.Transparent), Offset(0f, 0f), Offset(w, h * 0.6f)))
        FxType.CHROMATIC -> {
            drawRect(Color(0x88FF2D55), Offset(w * 0.2f, h * 0.3f), Size(w * 0.5f, h * 0.4f))
            drawRect(Color(0x8800E5FF), Offset(w * 0.3f, h * 0.3f), Size(w * 0.5f, h * 0.4f))
        }
        FxType.GLITCH -> {
            drawRect(Color(0xFF7DF0C1), Offset(0f, h * 0.2f), Size(w * 0.7f, h * 0.12f))
            drawRect(Color(0xFFFF5C7A), Offset(w * 0.3f, h * 0.5f), Size(w * 0.7f, h * 0.12f))
            drawRect(Color(0xFF9AD7FF), Offset(w * 0.1f, h * 0.75f), Size(w * 0.5f, h * 0.1f))
        }
        FxType.IMPACT_ZOOM -> {
            for (i in 1..4) drawRect(Color.White.copy(alpha = 0.25f * i), Offset(w * 0.1f * i, h * 0.1f * i), Size(w - w * 0.2f * i, h - h * 0.2f * i), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        }
        FxType.SHAKE -> {
            drawRect(Color.White.copy(alpha = 0.35f), Offset(w * 0.2f, h * 0.2f), Size(w * 0.5f, h * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
            drawRect(Color.White, Offset(w * 0.28f, h * 0.26f), Size(w * 0.5f, h * 0.5f), style = androidx.compose.ui.graphics.drawscope.Stroke(2f))
        }
        FxType.MOTION_BLUR -> for (i in 0..5) drawLine(Color.White.copy(alpha = 0.15f + 0.12f * i), Offset(w * 0.1f, h * (0.2f + 0.12f * i)), Offset(w * 0.9f, h * (0.2f + 0.12f * i)), 4f)
        FxType.INVERT -> {
            drawRect(Color.White, size = Size(w / 2, h))
            drawRect(Color.Black, Offset(w / 2, 0f), Size(w / 2, h))
        }
        FxType.EXPOSURE -> drawRect(Brush.verticalGradient(listOf(Color.White, Color(0xFF888888), Color.Black)))
        FxType.SATURATION_PULSE -> drawRect(Brush.horizontalGradient(listOf(Color(0xFFFF3B30), Color(0xFFFFCC00), Color(0xFF34C759), Color(0xFF0A84FF), Color(0xFFBF5AF2))))
        FxType.FLASH_ZOOM -> {
            drawRect(Color.White.copy(alpha = 0.85f))
            drawRect(Color(0xFF6C5CFF), Offset(w * 0.3f, h * 0.3f), Size(w * 0.4f, h * 0.4f))
        }
    }
}

// =============================================================================== edit one effect

@Composable
fun FxEditPanel(vm: EditorViewModel, state: EditorState, close: () -> Unit) {
    val project = state.project ?: return
    val sel = state.selection as? Selection.OfFx
    val e = sel?.let { s -> project.fx.firstOrNull { it.id == s.id } }
    if (e == null) {
        close()
        return
    }
    val def = FxLibrary.def(e.type)
    PanelScaffold(stringResource(fxNameRes(e.type)), close) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            LabeledSlider(
                stringResource(R.string.fx_intensity), e.intensity, 0f..2f,
                { v -> vm.updateFx(e.id) { it.copy(intensity = v) } }, vm::endGesture,
            )
            LabeledSlider(
                stringResource(R.string.fx_duration), (e.durationUs / 1_000_000f), 0.06f..2.5f,
                { v ->
                    vm.updateFx(e.id) {
                        val sync = it.syncUs
                        val d = (v * 1_000_000f).toLong()
                        it.copy(durationUs = d, startUs = sync - (d * it.peak).toLong())
                    }
                },
                vm::endGesture, valueText = { String.format(Locale.US, "%.2fs", it) },
            )
            LabeledSlider(
                stringResource(R.string.fx_sync_point), e.peak, 0.02f..0.9f,
                { v -> vm.updateFx(e.id) { val sync = it.syncUs; it.copy(peak = v, startUs = sync - (it.durationUs * v).toLong()) } },
                vm::endGesture,
            )
            if (def.usesColor) {
                SectionLabel(stringResource(R.string.fx_color))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(0xFFFFFFFF, 0xFFFFE27A, 0xFFFF4D8D, 0xFF5CE0FF, 0xFF8B6CFF, 0xFF4ADE80, 0xFFFF7A18).forEach { col ->
                        ColorSwatch(Color(col), e.color == col, { vm.mutate { p -> FxOps.update(p, e.id) { it.copy(color = col) } } })
                    }
                }
            }
            SwitchRow(stringResource(R.string.fx_enabled), e.enabled) { on -> vm.mutate { p -> FxOps.update(p, e.id) { it.copy(enabled = on) } } }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(stringResource(R.string.fx_preview), { vm.previewFrom(e.startUs - 500_000L) })
                val nearest = nearestBeat(project, e.syncUs)
                ActionButton(stringResource(R.string.fx_snap_beat), {
                    nearest?.let { t -> vm.mutate { p -> FxOps.moveSyncTo(p, e.id, t) } }
                }, enabled = nearest != null && nearest != e.syncUs)
            }
        }
    }
}

/** The pulse, hit or marker closest to [timeUs], within one second. */
private fun nearestBeat(p: com.vanta.studio.domain.model.Project, timeUs: Long): Long? {
    val cands = BeatMapping.onsets(p, 0.3f).map { it.timeUs } + BeatMapping.pulses(p).map { it.timeUs } + p.markers
    return cands.minByOrNull { kotlin.math.abs(it - timeUs) }?.takeIf { kotlin.math.abs(it - timeUs) <= 1_000_000L }
}
