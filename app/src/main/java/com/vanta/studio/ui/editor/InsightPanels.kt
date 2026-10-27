package com.vanta.studio.ui.editor

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.studio.R
import com.vanta.studio.domain.enhance.RecKind
import com.vanta.studio.domain.enhance.Recommendation
import com.vanta.studio.domain.enhance.Why
import com.vanta.studio.ui.components.Chip
import com.vanta.studio.ui.components.PanelScaffold
import com.vanta.studio.ui.components.SectionLabel
import com.vanta.studio.ui.components.formatSeconds
import com.vanta.studio.ui.components.formatTime
import com.vanta.studio.ui.theme.TimecodeStyle
import com.vanta.studio.ui.theme.Vanta
import kotlin.math.roundToInt

private fun recRes(k: RecKind) = when (k) {
    RecKind.BRIGHTEN -> R.string.rec_brighten
    RecKind.DARKEN -> R.string.rec_darken
    RecKind.CONTRAST -> R.string.rec_contrast
    RecKind.SATURATION -> R.string.rec_saturation
    RecKind.COOL_CAST -> R.string.rec_cool
    RecKind.WARM_CAST -> R.string.rec_warm
    RecKind.SHARPEN -> R.string.rec_sharpen
    RecKind.LIFT_SHADOWS -> R.string.rec_shadows
    RecKind.TAME_HIGHLIGHTS -> R.string.rec_highlights
    RecKind.VOLUME_UP -> R.string.rec_volume_up
    RecKind.VOLUME_DOWN -> R.string.rec_volume_down
    RecKind.AUDIO_CLIPPING -> R.string.rec_clipping
    RecKind.ORIENTATION -> R.string.rec_orientation
}

@Composable
private fun recText(r: Recommendation): String {
    val m = r.measured
    val n = when (r.kind) {
        RecKind.VOLUME_UP, RecKind.VOLUME_DOWN -> m.roundToInt()
        else -> (m * 100).roundToInt()
    }
    return stringResource(recRes(r.kind), n)
}

@Composable
fun EnhancePanel(vm: EditorViewModel, state: EditorState, close: () -> Unit) {
    val ai by vm.ai.state.collectAsStateWithLifecycle()
    val s = ai.enhance
    PanelScaffold(stringResource(R.string.panel_enhance), { vm.ai.closeEnhance(); close() }) {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
            ErrorBanner(ai) { vm.ai.dismissError() }
            Text(
                stringResource(R.string.enhance_hint),
                style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            JobBar(ai, AiJobKind.ENHANCE) { vm.ai.cancel() }
            ActionButton(
                stringResource(if (s == null) R.string.enhance_analyze else R.string.enhance_reanalyze), { vm.ai.analyzeEnhance() },
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), enabled = ai.job == null, primary = s == null,
            )
            if (s != null) {
                Text(
                    stringResource(R.string.enhance_measured, s.report.framesUsed),
                    style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 16.dp),
                )
                if (s.recs.isEmpty()) {
                    Text(
                        stringResource(R.string.enhance_all_good),
                        style = MaterialTheme.typography.bodyMedium, color = Vanta.Success, modifier = Modifier.padding(16.dp),
                    )
                }
                SectionLabel(stringResource(R.string.enhance_suggestions))
                s.recs.forEachIndexed { i, r ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = r.actionable && s.base == null) { vm.ai.toggleRec(i) }.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            if (!r.actionable) "ℹ" else if (i in s.chosen) "✓" else "–",
                            style = MaterialTheme.typography.titleSmall, color = if (i in s.chosen) Vanta.AccentBright else Vanta.TextDim,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                        Text(recText(r), style = MaterialTheme.typography.bodySmall, color = Vanta.Text)
                    }
                }
                Text(
                    stringResource(R.string.enhance_limits),
                    style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (s.base == null) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.enhance_apply), { vm.ai.applyEnhance() }, primary = true, enabled = s.chosen.isNotEmpty())
                        ActionButton(stringResource(R.string.enhance_ignore), { vm.ai.closeEnhance() })
                    }
                } else {
                    Text(
                        stringResource(if (s.showingAfter) R.string.enhance_showing_after else R.string.enhance_showing_before),
                        style = MaterialTheme.typography.labelMedium, color = Vanta.AccentBright, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.enhance_before), { vm.ai.enhanceCompare(false) }, enabled = s.showingAfter)
                        ActionButton(stringResource(R.string.enhance_after), { vm.ai.enhanceCompare(true) }, enabled = !s.showingAfter)
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.enhance_keep), { vm.ai.closeEnhance(); close() }, primary = true, enabled = s.showingAfter)
                        ActionButton(stringResource(R.string.enhance_undo), { vm.ai.cancelEnhanceView() })
                    }
                }
            }
        }
    }
}

@Composable
fun HighlightsPanel(vm: EditorViewModel, state: EditorState, close: () -> Unit) {
    val ctx = LocalContext.current
    val ai by vm.ai.state.collectAsStateWithLifecycle()
    val h = ai.highlights
    PanelScaffold(stringResource(R.string.panel_moments), { vm.ai.closeHighlights(); close() }) {
        Column(Modifier.heightIn(max = 450.dp).verticalScroll(rememberScrollState())) {
            ErrorBanner(ai) { vm.ai.dismissError() }
            Text(
                stringResource(R.string.moments_hint),
                style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            JobBar(ai, AiJobKind.HIGHLIGHTS) { vm.ai.cancel() }
            ActionButton(
                stringResource(if (h == null) R.string.moments_analyze else R.string.enhance_reanalyze), { vm.ai.analyzeHighlights() },
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), enabled = ai.job == null && h?.base == null, primary = h == null,
            )
            if (h != null) {
                SectionLabel(stringResource(R.string.moments_target))
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60).forEach { sec -> Chip("$sec s", h.targetSec == sec, { vm.ai.setHighlightTarget(sec) }) }
                }
                SectionLabel(stringResource(R.string.moments_proposed, h.picks.size))
                h.picks.forEachIndexed { i, p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.seekTo(p.startUs) }.padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Chip(if (i in h.chosen) "✓" else "–", i in h.chosen, { vm.ai.toggleHighlight(i) })
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(formatTime(p.startUs) + " · " + formatSeconds(p.durationUs), style = TimecodeStyle)
                            Text(
                                p.why.joinToString(" · ") { w ->
                                    ctx.getString(when (w) { Why.LOUD -> R.string.why_loud; Why.MOTION -> R.string.why_motion; Why.SPEECH -> R.string.why_speech })
                                }.ifEmpty { ctx.getString(R.string.why_mixed) },
                                style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.moments_limits),
                    style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                if (h.base == null) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.moments_apply), { vm.ai.applyHighlights() }, primary = true, enabled = h.chosen.isNotEmpty())
                        ActionButton(stringResource(R.string.enhance_ignore), { vm.ai.closeHighlights() })
                    }
                } else {
                    Text(
                        stringResource(R.string.moments_applied, formatSeconds(h.removedUs)),
                        style = MaterialTheme.typography.labelMedium, color = Vanta.AccentBright, modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.fx_preview), { vm.previewFrom(0) })
                        ActionButton(stringResource(R.string.assist_keep), { vm.ai.closeHighlights(); close() }, primary = true)
                        ActionButton(stringResource(R.string.moments_restore), { vm.ai.restoreHighlights() })
                    }
                }
            }
        }
    }
}
