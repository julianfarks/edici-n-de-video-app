package com.vanta.studio.ui.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.studio.AppContainer
import com.vanta.studio.R
import com.vanta.studio.data.MediaImporter
import com.vanta.studio.domain.model.FontChoice
import com.vanta.studio.domain.model.LayerAnim
import com.vanta.studio.domain.model.SubtitleKind
import com.vanta.studio.domain.model.SubtitleStyle
import com.vanta.studio.domain.subtitles.Srt
import com.vanta.studio.domain.subtitles.SubtitleOps
import com.vanta.studio.domain.subtitles.SubtitlePresets
import com.vanta.studio.ui.components.Chip
import com.vanta.studio.ui.components.ColorSwatch
import com.vanta.studio.ui.components.ConfirmDialog
import com.vanta.studio.ui.components.LabeledSlider
import com.vanta.studio.ui.components.PanelScaffold
import com.vanta.studio.ui.components.SectionLabel
import com.vanta.studio.ui.components.formatTime
import com.vanta.studio.ui.theme.Vanta

private enum class SubTab { GENERATE, EDIT, STYLE }

@Composable
fun SubtitlesPanel(vm: EditorViewModel, container: AppContainer, state: EditorState, close: () -> Unit) {
    val project = state.project ?: return
    val track = project.subtitles
    var tab by remember { mutableStateOf(if (track == null || track.cues.isEmpty()) SubTab.GENERATE else SubTab.EDIT) }
    // Selecting a cue on the timeline jumps to the editor.
    LaunchedEffect(state.selection) { if (state.selection is Selection.OfCue) tab = SubTab.EDIT }

    PanelScaffold(stringResource(R.string.panel_subtitles), close) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.sub_tab_generate), tab == SubTab.GENERATE, { tab = SubTab.GENERATE })
            if (track != null) {
                Chip(stringResource(R.string.sub_tab_edit), tab == SubTab.EDIT, { tab = SubTab.EDIT })
                Chip(stringResource(R.string.sub_tab_style), tab == SubTab.STYLE, { tab = SubTab.STYLE })
            }
        }
        when (tab) {
            SubTab.GENERATE -> GenerateTab(vm, container, state) { if (vm.project?.subtitles?.cues?.isNotEmpty() == true) tab = SubTab.EDIT }
            SubTab.EDIT -> if (track != null) EditTab(vm, state)
            SubTab.STYLE -> if (track != null) StyleTab(vm, track.style)
        }
    }
}

// =============================================================================== generate

@Composable
private fun GenerateTab(vm: EditorViewModel, container: AppContainer, state: EditorState, done: () -> Unit) {
    val ai by vm.ai.state.collectAsStateWithLifecycle()
    val models = container.models
    var lang by remember { mutableStateOf(vm.project?.subtitles?.language ?: "es") }
    var cloud by remember { mutableStateOf(false) }
    var askDownload by remember { mutableStateOf(false) }
    var askUpload by remember { mutableStateOf(false) }
    val model = models.forLanguage(lang)
    val installed = model?.let { models.installed(it) != null } == true
    val (savedUrl, savedModel) = remember { vm.ai.cloudSettings() }
    var url by remember { mutableStateOf(savedUrl) }
    var cloudModel by remember { mutableStateOf(savedModel) }
    var key by remember { mutableStateOf("") }
    // When a generation finishes, move on to the editor.
    LaunchedEffect(ai.lastSubtitleCount) {
        if (ai.lastSubtitleCount != null) {
            vm.ai.consumeSubtitleResult()
            done()
        }
    }

    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        ErrorBanner(ai) { vm.ai.dismissError() }
        SectionLabel(stringResource(R.string.sub_language))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            models.catalog.forEach { m -> Chip(m.label, lang == m.language, { lang = m.language }) }
        }
        SectionLabel(stringResource(R.string.sub_engine))
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.sub_engine_local), !cloud, { cloud = false })
            Chip(stringResource(R.string.sub_engine_cloud), cloud, { cloud = true })
        }
        if (!cloud) {
            Text(
                stringResource(R.string.sub_local_hint),
                style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            if (model != null && !installed) {
                ActionButton(
                    stringResource(R.string.sub_download_model, model.sizeMb), { askDownload = true },
                    Modifier.padding(horizontal = 16.dp), enabled = ai.job == null,
                )
                JobBar(ai, AiJobKind.MODEL) { vm.ai.cancel() }
            } else if (model != null) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.sub_model_ready), style = MaterialTheme.typography.labelMedium, color = Color(0xFF4ADE80), modifier = Modifier.weight(1f))
                    androidx.compose.material3.TextButton(onClick = { vm.ai.deleteModel(model) }) { Text(stringResource(R.string.sub_delete_model)) }
                }
            }
        } else {
            Text(
                stringResource(R.string.sub_cloud_hint),
                style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            FieldBox(stringResource(R.string.sub_cloud_url), url, { url = it })
            FieldBox(stringResource(R.string.sub_cloud_model), cloudModel, { cloudModel = it })
            FieldBox(
                stringResource(if (ai.cloudConfigured) R.string.sub_cloud_key_saved else R.string.sub_cloud_key),
                key, { key = it }, secret = true,
            )
            ActionButton(stringResource(R.string.sub_cloud_save), {
                vm.ai.saveCloudSettings(url, cloudModel)
                if (key.isNotBlank()) vm.ai.saveCloudKey(key)
                key = ""
            }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        JobBar(ai, AiJobKind.SUBTITLES) { vm.ai.cancel() }
        ActionButton(
            stringResource(R.string.sub_generate),
            { if (cloud) askUpload = true else vm.ai.generateSubtitles(lang, false) },
            Modifier.padding(16.dp).fillMaxWidth(), primary = true,
            enabled = ai.job == null && (if (cloud) ai.cloudConfigured else installed),
        )
    }

    if (askDownload && model != null) {
        ConfirmDialog(
            stringResource(R.string.sub_download_title),
            stringResource(R.string.sub_download_text, model.label, model.sizeMb, java.net.URI(model.url).host),
            stringResource(R.string.sub_download_confirm),
            { askDownload = false; vm.ai.installModel(model) }, { askDownload = false },
        )
    }
    if (askUpload) {
        ConfirmDialog(
            stringResource(R.string.sub_upload_title),
            stringResource(R.string.sub_upload_text, runCatching { java.net.URI(url).host }.getOrNull() ?: url),
            stringResource(R.string.sub_upload_confirm),
            { askUpload = false; vm.ai.generateSubtitles(lang, true) }, { askUpload = false },
        )
    }
}

@Composable
private fun FieldBox(label: String, value: String, onChange: (String) -> Unit, secret: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, singleLine = true,
        label = { Text(label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Vanta.Accent, unfocusedBorderColor = Vanta.Outline, cursorColor = Vanta.AccentBright),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
    )
}

// =============================================================================== edit

@Composable
private fun EditTab(vm: EditorViewModel, state: EditorState) {
    val ctx = LocalContext.current
    val track = state.project?.subtitles ?: return
    val cues = track.cues
    val problems = remember(cues) { SubtitleOps.problems(track).toSet() }
    var query by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var replaced by remember { mutableStateOf<Int?>(null) }
    val hits = remember(cues, query) { SubtitleOps.search(track, query).toSet() }
    val sorted = remember(cues) { cues.sortedBy { it.startUs } }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        uri?.let { runCatching { ctx.contentResolver.openOutputStream(it)?.use { o -> o.write(Srt.format(sorted).toByteArray()) } } }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val text = runCatching { ctx.contentResolver.openInputStream(it)?.bufferedReader()?.readText() }.getOrNull()
            val parsed = text?.let { t -> Srt.parse(t) { MediaImporter.newId() } }
            if (!parsed.isNullOrEmpty()) vm.importCues(parsed)
        }
    }

    LazyColumn(Modifier.heightIn(max = 400.dp)) {
        item {
            Column {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionButton(stringResource(R.string.sub_export_srt), { exporter.launch("subtitulos.srt") })
                    ActionButton(stringResource(R.string.sub_import_srt), { importer.launch(arrayOf("*/*")) })
                    ActionButton(stringResource(if (track.enabled) R.string.sub_hide else R.string.sub_show), { vm.editSubtitles { it.copy(enabled = !it.enabled) } })
                }
                if (track.cues.any { it.estimated }) {
                    Text(
                        stringResource(R.string.sub_estimated_note),
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB547), modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (problems.isNotEmpty()) {
                    Text(
                        stringResource(R.string.sub_problems, problems.size),
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB547), modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                FieldBox(stringResource(R.string.sub_search), query, { query = it; replaced = null })
                if (query.isNotBlank()) {
                    FieldBox(stringResource(R.string.sub_replace_with), replace, { replace = it })
                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ActionButton(stringResource(R.string.sub_replace_all), {
                            var n = 0
                            vm.editSubtitles { t -> SubtitleOps.replaceAll(t, query, replace).also { n = it.second }.first }
                            replaced = n
                        })
                        Text(
                            replaced?.let { stringResource(R.string.sub_replaced, it) } ?: stringResource(R.string.sub_matches, hits.size),
                            style = MaterialTheme.typography.labelMedium, color = Vanta.TextDim,
                        )
                    }
                }
            }
        }
        items(sorted.size, key = { sorted[it].id }) { i ->
            val c = sorted[i]
            val selected = state.selection == Selection.OfCue(c.id)
            if (selected) {
                CueEditor(vm, state, c, hasNext = i < sorted.lastIndex)
            } else {
                Row(
                    Modifier.fillMaxWidth().clickable {
                        vm.select(Selection.OfCue(c.id))
                        vm.seekTo(c.startUs + 1)
                    }.background(if (i in hits) Vanta.Accent.copy(alpha = 0.18f) else Color.Transparent).padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(formatTime(c.startUs), style = com.vanta.studio.ui.theme.TimecodeStyle, modifier = Modifier.padding(end = 10.dp))
                    Text(c.text, style = MaterialTheme.typography.bodySmall, color = Vanta.Text, maxLines = 2, modifier = Modifier.weight(1f))
                    if (c.id in problems) Text("⚠", color = Color(0xFFFFB547), modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun CueEditor(vm: EditorViewModel, state: EditorState, c: com.vanta.studio.domain.model.Cue, hasNext: Boolean) {
    var text by remember(c.id, c.text) { mutableStateOf(c.text) }
    val pos = vm.positionUs
    Column(Modifier.fillMaxWidth().background(Vanta.Surface2).padding(vertical = 8.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Vanta.Accent, unfocusedBorderColor = Vanta.Outline, cursorColor = Vanta.AccentBright),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), minLines = 2,
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ActionButton(stringResource(R.string.sub_apply_text), {
                vm.editSubtitles { t -> SubtitleOps.update(t, c.id) { SubtitleOps.setText(it, text) } }
            }, enabled = text.isNotBlank() && text != c.text, primary = true)
            ActionButton(stringResource(R.string.sub_play), { vm.previewFrom(c.startUs - 300_000L) })
            Text(
                formatTime(c.startUs) + " → " + formatTime(c.endUs),
                style = com.vanta.studio.ui.theme.TimecodeStyle,
            )
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ActionButton("◀ −0.1", { vm.editSubtitles { t -> SubtitleOps.update(t, c.id) { SubtitleOps.retime(it, it.startUs - 100_000, it.endUs) } } })
            ActionButton("▶ +0.1", { vm.editSubtitles { t -> SubtitleOps.update(t, c.id) { SubtitleOps.retime(it, it.startUs + 100_000, it.endUs) } } })
            ActionButton("−0.1 ◀", { vm.editSubtitles { t -> SubtitleOps.update(t, c.id) { SubtitleOps.retime(it, it.startUs, it.endUs - 100_000) } } })
            ActionButton("+0.1 ▶", { vm.editSubtitles { t -> SubtitleOps.update(t, c.id) { SubtitleOps.retime(it, it.startUs, it.endUs + 100_000) } } })
        }
        if (c.estimated) {
            Text(
                stringResource(R.string.sub_cue_estimated),
                style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFB547), modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionButton(stringResource(R.string.sub_split), {
                vm.editSubtitles { t -> SubtitleOps.split(t, c.id, pos) { MediaImporter.newId() } ?: t }
            }, enabled = pos > c.startUs + 150_000 && pos < c.endUs - 150_000)
            ActionButton(stringResource(R.string.sub_merge), {
                vm.editSubtitles { t -> SubtitleOps.mergeWithNext(t, c.id) ?: t }
            }, enabled = hasNext)
            ActionButton(stringResource(R.string.action_delete), { vm.ai.deleteCue(c.id) })
        }
    }
}

// =============================================================================== style

@Composable
private fun StyleTab(vm: EditorViewModel, s: SubtitleStyle) {
    val textColors = listOf(0xFFFFFFFF, 0xFFFFE27A, 0xFFFF4D8D, 0xFF5CE0FF, 0xFF8B6CFF, 0xFF4ADE80, 0xFF000000)
    val hiColors = listOf(0xFFFFD84A, 0xFFFF5C7A, 0xFF5CE0FF, 0xFF8B6CFF, 0xFF4ADE80, 0xFFFF7A18, 0xFFFFFFFF)
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        SectionLabel(stringResource(R.string.sub_preset))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubtitleKind.entries.forEach { k ->
                Chip(stringResource(subKindRes(k)), s.kind == k, { vm.editSubtitles { it.copy(style = SubtitlePresets.forKind(k)) } })
            }
        }
        LabeledSlider(stringResource(R.string.sub_size), s.size, 0.02f..0.14f, { v -> vm.liveSubtitles { it.copy(style = it.style.copy(size = v)) } }, vm::endGesture)
        LabeledSlider(stringResource(R.string.sub_position), s.positionY, 0.08f..0.95f, { v -> vm.liveSubtitles { it.copy(style = it.style.copy(positionY = v)) } }, vm::endGesture)
        SectionLabel(stringResource(R.string.sub_text_color))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            textColors.forEach { c -> ColorSwatch(Color(c), s.color == c, { vm.editSubtitles { it.copy(style = it.style.copy(color = c)) } }) }
        }
        SectionLabel(stringResource(R.string.sub_highlight_color))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            hiColors.forEach { c -> ColorSwatch(Color(c), s.highlightColor == c, { vm.editSubtitles { it.copy(style = it.style.copy(highlightColor = c)) } }) }
        }
        SwitchRow(stringResource(R.string.sub_highlight_word), s.highlightWord) { on -> vm.editSubtitles { it.copy(style = it.style.copy(highlightWord = on)) } }
        SwitchRow(stringResource(R.string.sub_uppercase), s.uppercase) { on -> vm.editSubtitles { it.copy(style = it.style.copy(uppercase = on)) } }
        SwitchRow(stringResource(R.string.sub_outline), s.strokeColor != null) { on ->
            vm.editSubtitles { it.copy(style = it.style.copy(strokeColor = if (on) 0xFF000000 else null)) }
        }
        if (s.strokeColor != null) {
            LabeledSlider(stringResource(R.string.sub_outline_width), s.strokeWidth, 0.02f..0.3f, { v -> vm.liveSubtitles { it.copy(style = it.style.copy(strokeWidth = v)) } }, vm::endGesture)
        }
        SwitchRow(stringResource(R.string.sub_shadow), s.shadow) { on -> vm.editSubtitles { it.copy(style = it.style.copy(shadow = on)) } }
        SwitchRow(stringResource(R.string.sub_background), s.background != null) { on ->
            vm.editSubtitles { it.copy(style = it.style.copy(background = if (on) 0x99000000 else null)) }
        }
        SectionLabel(stringResource(R.string.sub_font))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FontChoice.entries.forEach { f -> Chip(fontLabel(f), s.font == f, { vm.editSubtitles { it.copy(style = it.style.copy(font = f)) } }) }
        }
        SectionLabel(stringResource(R.string.label_anim_in))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayerAnim.entries.forEach { a -> Chip(animName(a), s.animIn == a, { vm.editSubtitles { it.copy(style = it.style.copy(animIn = a)) } }) }
        }
        SectionLabel(stringResource(R.string.label_anim_out))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayerAnim.entries.forEach { a -> Chip(animName(a), s.animOut == a, { vm.editSubtitles { it.copy(style = it.style.copy(animOut = a)) } }) }
        }
    }
}

private fun subKindRes(k: SubtitleKind) = when (k) {
    SubtitleKind.CINEMATIC -> R.string.subkind_cinematic
    SubtitleKind.MINIMAL -> R.string.subkind_minimal
    SubtitleKind.HIGHLIGHT -> R.string.subkind_highlight
    SubtitleKind.CREATOR -> R.string.subkind_creator
    SubtitleKind.KARAOKE -> R.string.subkind_karaoke
    SubtitleKind.BOUNCE -> R.string.subkind_bounce
    SubtitleKind.PROGRESSIVE -> R.string.subkind_progressive
    SubtitleKind.SCALE_SYNC -> R.string.subkind_scale
}
