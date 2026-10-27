package com.vanta.studio.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.studio.R
import com.vanta.studio.domain.audio.SilenceDetector
import com.vanta.studio.domain.edit.AutoEditOptions
import com.vanta.studio.domain.edit.EditMode
import com.vanta.studio.ui.components.Chip
import com.vanta.studio.ui.components.LabeledSlider
import com.vanta.studio.ui.components.PanelScaffold
import com.vanta.studio.ui.components.SectionLabel
import com.vanta.studio.ui.components.formatSeconds
import com.vanta.studio.ui.components.formatTime
import com.vanta.studio.ui.theme.TimecodeStyle
import com.vanta.studio.ui.theme.Vanta
import java.util.Locale

// =============================================================================== silence

@Composable
fun SilencePanel(vm: EditorViewModel, state: EditorState, close: () -> Unit) {
    val ai by vm.ai.state.collectAsStateWithLifecycle()
    var minPause by remember { mutableStateOf(0.6f) }
    var margin by remember { mutableStateOf(0.15f) }
    var sensitivity by remember { mutableStateOf(0.5f) }
    val prop = ai.silence
    val chosen = ai.silenceChosen
    PanelScaffold(stringResource(R.string.panel_silence), {
        vm.ai.clearSilence()
        close()
    }) {
        Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
            ErrorBanner(ai) { vm.ai.dismissError() }
            Text(
                stringResource(R.string.silence_hint),
                style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LabeledSlider(stringResource(R.string.silence_min_pause), minPause, 0.3f..2.0f, { minPause = it }, valueText = { String.format(Locale.US, "%.1fs", it) })
            LabeledSlider(stringResource(R.string.silence_margin), margin, 0.05f..0.4f, { margin = it }, valueText = { String.format(Locale.US, "%.2fs", it) })
            LabeledSlider(stringResource(R.string.silence_sensitivity), sensitivity, 0f..1f, { sensitivity = it })
            JobBar(ai, AiJobKind.SILENCE) { vm.ai.cancel() }
            ActionButton(
                stringResource(R.string.silence_analyze),
                { vm.ai.analyzeSilence(SilenceDetector.Options((minPause * 1_000_000).toLong(), (margin * 1_000_000).toLong(), sensitivity)) },
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), enabled = ai.job == null, primary = prop == null,
            )
            if (prop != null) {
                if (!prop.separable) {
                    Text(
                        stringResource(R.string.silence_not_separable),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB547), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                } else if (prop.cuts.isEmpty()) {
                    Text(
                        stringResource(R.string.silence_none),
                        style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                } else {
                    val total = prop.cuts.filterIndexed { i, _ -> i in chosen }.sumOf { it.durationUs }
                    SectionLabel(stringResource(R.string.silence_proposed, prop.cuts.size))
                    prop.cuts.forEachIndexed { i, c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { vm.seekTo(c.startUs) }.padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Chip(if (i in chosen) "✓" else "–", i in chosen, { vm.ai.toggleSilenceCut(i) })
                            Text(
                                formatTime(c.startUs) + " · " + formatSeconds(c.durationUs),
                                style = TimecodeStyle, modifier = Modifier.padding(start = 10.dp),
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.silence_total, formatSeconds(total), chosen.size),
                        style = MaterialTheme.typography.labelMedium, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.silence_apply), { vm.ai.applySilence(); close() }, enabled = chosen.isNotEmpty(), primary = true)
                        ActionButton(stringResource(R.string.silence_discard), { vm.ai.clearSilence() })
                    }
                }
            }
        }
    }
}

// =============================================================================== auto edit

private fun modeRes(m: EditMode) = when (m) {
    EditMode.SOFT -> R.string.mode_soft
    EditMode.CINEMATIC -> R.string.mode_cinematic
    EditMode.DYNAMIC -> R.string.mode_dynamic
    EditMode.INTENSE -> R.string.mode_intense
    EditMode.VERY_FAST -> R.string.mode_very_fast
}

@Composable
fun AutoEditPanel(vm: EditorViewModel, state: EditorState, close: () -> Unit) {
    val project = state.project ?: return
    val ai by vm.ai.state.collectAsStateWithLifecycle()
    var mode by remember { mutableStateOf(EditMode.DYNAMIC) }
    var onlyStrong by remember { mutableStateOf(false) }
    var withFx by remember { mutableStateOf(true) }
    var withTransitions by remember { mutableStateOf(true) }
    var safe by remember { mutableStateOf(true) }
    var mute by remember { mutableStateOf(true) }
    var variant by remember { mutableStateOf(0) }
    val session = ai.autoEdit
    val hasBeat = project.beat != null

    fun options() = AutoEditOptions(
        mode = mode, onlyStrong = onlyStrong, hitThreshold = ai.hitThreshold, variant = variant,
        withFx = withFx, withTransitions = withTransitions, safeMode = safe, muteOriginal = mute,
    )

    PanelScaffold(stringResource(R.string.panel_auto_edit), {
        // Leaving with a version on screen keeps it (it is one undo step away).
        vm.ai.acceptAutoEdit()
        close()
    }) {
        Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            ErrorBanner(ai) { vm.ai.dismissError() }
            if (!hasBeat) {
                Text(
                    stringResource(R.string.auto_need_analysis),
                    style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                ActionButton(stringResource(R.string.auto_go_analyze), { vm.openPanel(Panel.BEAT_FX) }, Modifier.padding(horizontal = 16.dp), primary = true)
            } else {
                Text(
                    stringResource(R.string.auto_hint),
                    style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                SectionLabel(stringResource(R.string.auto_style))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditMode.entries.forEach { m -> Chip(stringResource(modeRes(m)), mode == m, { mode = m }) }
                }
                LabeledSlider(
                    stringResource(R.string.beat_sensitivity), ai.hitThreshold, 0.05f..0.95f, vm.ai::setHitThreshold,
                    valueText = { String.format(Locale.US, "%.0f%%", it * 100) },
                )
                AiSwitchRow(stringResource(R.string.auto_only_strong), onlyStrong) { onlyStrong = it }
                AiSwitchRow(stringResource(R.string.auto_fx), withFx) { withFx = it }
                AiSwitchRow(stringResource(R.string.auto_transitions), withTransitions) { withTransitions = it }
                AiSwitchRow(stringResource(R.string.beat_safe), safe) { safe = it }
                if (project.beat?.isClip == false) AiSwitchRow(stringResource(R.string.auto_mute), mute) { mute = it }

                if (session != null) {
                    Text(
                        stringResource(R.string.auto_result, session.segments, session.cuts, session.fx),
                        style = MaterialTheme.typography.labelMedium, color = Vanta.AccentBright, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Text(
                        stringResource(if (session.resamples) R.string.auto_resamples else R.string.auto_in_place),
                        style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.fx_preview), { vm.previewFrom(0) })
                        ActionButton(stringResource(R.string.auto_another), { variant++; vm.ai.previewAutoEdit(options().copy(variant = variant)) })
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.auto_accept), { vm.ai.acceptAutoEdit(); close() }, primary = true)
                        ActionButton(stringResource(R.string.auto_restore), { vm.ai.cancelAutoEdit() })
                    }
                } else {
                    ActionButton(
                        stringResource(R.string.auto_generate), { variant = 0; vm.ai.previewAutoEdit(options()) },
                        Modifier.padding(16.dp).fillMaxWidth(), primary = true, enabled = ai.job == null,
                    )
                }
            }
        }
    }
}
