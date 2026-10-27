package com.vanta.studio.ui.editor

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.studio.R
import com.vanta.studio.domain.assistant.AssistantOp
import com.vanta.studio.domain.assistant.Ops
import com.vanta.studio.domain.assistant.Reason
import com.vanta.studio.domain.assistant.SpanishInterpreter
import com.vanta.studio.domain.assistant.Templates
import com.vanta.studio.ui.components.Chip
import com.vanta.studio.ui.components.ConfirmDialog
import com.vanta.studio.ui.components.PanelScaffold
import com.vanta.studio.ui.components.SectionLabel
import com.vanta.studio.ui.theme.Vanta
import java.util.Locale

/** One line in the user's language saying what an operation will do. */
internal fun describeOp(ctx: Context, o: AssistantOp): String {
    fun sec(v: Double?) = if (v == null) "" else String.format(Locale.US, "%.1f", v)
    val clip = o.clip?.let { ctx.getString(R.string.op_clip_n, it) } ?: ctx.getString(R.string.op_all_clips)
    return when (o.op) {
        Ops.SPLIT -> if (o.timeSec != null) ctx.getString(R.string.op_split_at, sec(o.timeSec)) else ctx.getString(R.string.op_split_playhead)
        Ops.DELETE_RANGE -> ctx.getString(R.string.op_delete_range, sec(o.timeSec), sec(o.endSec))
        Ops.SPEED -> ctx.getString(R.string.op_speed, clip, sec(o.value))
        Ops.VOLUME -> ctx.getString(R.string.op_volume, clip, ((o.value ?: 1.0) * 100).toInt())
        Ops.MUTE -> ctx.getString(if (o.flag == false) R.string.op_unmute else R.string.op_mute, clip)
        Ops.ADD_TEXT -> ctx.getString(R.string.op_add_text, o.text.orEmpty(), if (o.timeSec != null) sec(o.timeSec) + " s" else ctx.getString(R.string.op_at_playhead))
        Ops.ADD_FX -> ctx.getString(R.string.op_add_fx, o.name.orEmpty(), if (o.timeSec != null) sec(o.timeSec) + " s" else ctx.getString(R.string.op_at_playhead))
        Ops.FX_ON_BEATS -> ctx.getString(R.string.op_fx_beats, o.name.orEmpty())
        Ops.ASPECT -> ctx.getString(R.string.op_aspect, o.name.orEmpty())
        Ops.FILTER -> ctx.getString(R.string.op_filter, o.name.orEmpty(), clip)
        Ops.SUBTITLE_STYLE -> ctx.getString(R.string.op_sub_style, o.name.orEmpty())
        Ops.OPEN_TOOL -> ctx.getString(R.string.op_open_tool, toolLabel(ctx, o.name))
        else -> o.op
    }
}

private fun toolLabel(ctx: Context, tool: String?) = ctx.getString(
    when (tool) {
        "subtitles" -> R.string.panel_subtitles
        "silence" -> R.string.panel_silence
        "beat" -> R.string.panel_beat
        else -> R.string.panel_auto_edit
    },
)

private fun reasonRes(r: Reason) = when (r) {
    Reason.OUT_OF_RANGE -> R.string.reason_range
    Reason.NO_CLIP -> R.string.reason_clip
    Reason.NO_RHYTHM -> R.string.reason_rhythm
    Reason.UNKNOWN_NAME -> R.string.reason_name
    Reason.INVALID -> R.string.reason_invalid
    Reason.NOTHING_TO_DO -> R.string.reason_nothing
}

@Composable
fun AssistantPanel(vm: EditorViewModel, state: EditorState, close: () -> Unit) {
    val ctx = LocalContext.current
    val ai by vm.ai.state.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var askCloud by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val session = ai.assistant
    val applied = session?.result

    PanelScaffold(stringResource(R.string.panel_assistant), {
        // Closing with a preview on screen keeps it; it is one undo step.
        vm.ai.acceptAssistant()
        close()
    }) {
        Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
            ErrorBanner(ai) { vm.ai.dismissError() }
            Text(
                stringResource(R.string.assist_hint),
                style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text(stringResource(R.string.assist_input)) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Vanta.Accent, unfocusedBorderColor = Vanta.Outline, cursorColor = Vanta.AccentBright),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                minLines = 2,
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpanishInterpreter.EXAMPLES.forEach { ex -> Chip(ex, false, { text = ex }) }
            }
            SectionLabel(stringResource(R.string.tpl_section))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Templates.all.forEach { t ->
                    val label = ctx.resources.getIdentifier("tpl_" + t.id, "string", ctx.packageName).takeIf { it != 0 }?.let { ctx.getString(it) } ?: t.id
                    Chip(label, state.project?.templateId == t.id, { vm.ai.proposeTemplate(t) })
                }
            }
            JobBar(ai, AiJobKind.ASSISTANT) { vm.ai.cancel() }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton(
                    stringResource(R.string.assist_understand), { vm.ai.interpretLocal(text) },
                    enabled = text.isNotBlank() && ai.job == null, primary = true,
                )
                ActionButton(
                    stringResource(R.string.assist_ask_cloud), { askCloud = true },
                    enabled = text.isNotBlank() && ai.job == null && ai.llmConfigured,
                )
            }

            if (session != null) {
                val ops = session.interpretation.ops
                SectionLabel(stringResource(if (session.viaCloud) R.string.assist_proposal_cloud else R.string.assist_proposal))
                if (ops.isEmpty()) {
                    Text(
                        stringResource(R.string.assist_nothing),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB547), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                ops.forEachIndexed { i, o ->
                    val skipped = applied?.skipped?.firstOrNull { it.first === o }
                    Text(
                        "${i + 1}. " + describeOp(ctx, o) + (skipped?.let { " — " + stringResource(reasonRes(it.second)) } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (skipped != null) Color(0xFFFFB547) else Vanta.Text,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
                if (session.interpretation.unclear.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.assist_unclear))
                    session.interpretation.unclear.forEach {
                        Text("• $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB547), modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
                    }
                    Text(
                        stringResource(if (session.viaCloud) R.string.assist_alt_cloud else R.string.assist_alt_local),
                        style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                val tools = ops.filter { it.op == Ops.OPEN_TOOL }.mapNotNull { it.name }.distinct() + (applied?.tools ?: emptyList())
                if (tools.isNotEmpty()) {
                    Text(stringResource(R.string.assist_tools), style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 16.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tools.distinct().forEach { t ->
                            Chip(toolLabel(ctx, t), false, {
                                vm.ai.acceptAssistant()
                                vm.openPanel(
                                    when (t) {
                                        "subtitles" -> Panel.SUBTITLES
                                        "silence" -> Panel.SILENCE
                                        "beat" -> Panel.BEAT_FX
                                        else -> Panel.AUTO_EDIT
                                    },
                                )
                            })
                        }
                    }
                }
                if (applied == null) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(
                            stringResource(R.string.assist_apply), { vm.ai.applyAssistant() },
                            primary = true, enabled = ops.any { it.op != Ops.OPEN_TOOL },
                        )
                        ActionButton(stringResource(R.string.assist_discard), { vm.ai.acceptAssistant() })
                    }
                } else {
                    Text(
                        stringResource(R.string.assist_applied, applied.applied.size, applied.skipped.size),
                        style = MaterialTheme.typography.labelMedium, color = Vanta.AccentBright, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.fx_preview), { vm.previewFrom(0) })
                        ActionButton(stringResource(R.string.assist_keep), { vm.ai.acceptAssistant(); close() }, primary = true)
                        ActionButton(stringResource(R.string.assist_cancel), { vm.ai.cancelAssistantPreview() })
                    }
                }
            }

            SectionLabel(stringResource(R.string.assist_cloud_section))
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(
                    stringResource(if (ai.llmConfigured) R.string.assist_usage else R.string.assist_cloud_off, vm.ai.meter.used, vm.ai.meter.limit),
                    style = MaterialTheme.typography.labelMedium, color = Vanta.TextDim, modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(onClick = { showSettings = !showSettings }) { Text(stringResource(R.string.assist_configure)) }
            }
            if (showSettings) LlmSettings(vm, ai.llmConfigured)
        }
    }

    if (askCloud) {
        val (url, _) = vm.ai.llmSettings()
        ConfirmDialog(
            stringResource(R.string.assist_cloud_title),
            stringResource(R.string.assist_cloud_text, runCatching { java.net.URI(url).host }.getOrNull() ?: url, vm.ai.llmPreview(text)),
            stringResource(R.string.assist_cloud_confirm),
            { askCloud = false; vm.ai.interpretCloud(text) }, { askCloud = false },
        )
    }
}

@Composable
private fun LlmSettings(vm: EditorViewModel, configured: Boolean) {
    val (savedUrl, savedModel) = remember { vm.ai.llmSettings() }
    var url by remember { mutableStateOf(savedUrl) }
    var model by remember { mutableStateOf(savedModel) }
    var key by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf(vm.ai.meter.limit.toString()) }
    Text(
        stringResource(R.string.assist_llm_hint),
        style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    @Composable
    fun field(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) = OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true, label = { Text(label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Vanta.Accent, unfocusedBorderColor = Vanta.Outline, cursorColor = Vanta.AccentBright),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
    )
    field(stringResource(R.string.sub_cloud_url), url, { url = it })
    field(stringResource(R.string.sub_cloud_model), model, { model = it })
    field(stringResource(if (configured) R.string.sub_cloud_key_saved else R.string.sub_cloud_key), key, { key = it }, secret = true)
    field(stringResource(R.string.assist_limit), limit, { limit = it.filter(Char::isDigit).take(5) })
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ActionButton(stringResource(R.string.sub_cloud_save), {
            vm.ai.saveLlmSettings(url, model, key, limit.toIntOrNull() ?: 0)
            key = ""
        })
        if (configured) ActionButton(stringResource(R.string.assist_forget_key), { vm.ai.forgetLlmKey() })
    }
}
