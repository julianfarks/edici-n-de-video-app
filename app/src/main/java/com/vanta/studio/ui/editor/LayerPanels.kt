package com.vanta.studio.ui.editor

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.FormatAlignCenter
import androidx.compose.material.icons.rounded.FormatAlignLeft
import androidx.compose.material.icons.rounded.FormatAlignRight
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vanta.studio.R
import com.vanta.studio.domain.model.FontChoice
import com.vanta.studio.domain.model.LayerAnim
import com.vanta.studio.domain.model.OverlayKind
import com.vanta.studio.domain.model.OverlayLayer
import com.vanta.studio.domain.model.TextAlign
import com.vanta.studio.domain.presets.StickerCatalog
import com.vanta.studio.domain.presets.TextPreset
import com.vanta.studio.domain.presets.TextPresets
import com.vanta.studio.engine.layers.LayerRenderer
import com.vanta.studio.ui.components.Chip
import com.vanta.studio.ui.components.ColorSwatch
import com.vanta.studio.ui.components.LabeledSlider
import com.vanta.studio.ui.components.PanelScaffold
import com.vanta.studio.ui.components.SectionLabel
import com.vanta.studio.ui.components.formatSeconds
import com.vanta.studio.ui.theme.Vanta
import java.util.Locale

@Composable
fun presetName(id: String) = stringResource(
    when (id) {
        "modern" -> R.string.text_modern
        "subtitle" -> R.string.text_subtitle
        "cinematic" -> R.string.text_cinematic
        "minimal" -> R.string.text_minimal
        "outline" -> R.string.text_outline
        "label" -> R.string.text_label
        else -> R.string.text_shorts
    },
)

@Composable
fun TextPresetsPanel(vm: EditorViewModel, onClose: () -> Unit) {
    val sample = stringResource(R.string.text_sample)
    val default = stringResource(R.string.text_default)
    PanelScaffold(stringResource(R.string.panel_text), onClose) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextPresets.all.forEach { preset -> PresetTile(preset, sample, presetName(preset.id)) { vm.addText(preset, default) } }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun PresetTile(preset: TextPreset, sample: String, label: String, onClick: () -> Unit) {
    val bmp = remember(preset.id, sample) {
        // Rendered with the same code that burns titles into the export.
        LayerRenderer.renderText(sample, preset.style.copy(size = 0.3f), 360, 180).asImageBitmap()
    }
    Column(Modifier.width(110.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(110.dp, 70.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A3A47)),
            contentAlignment = Alignment.Center,
        ) {
            Image(bmp, null, contentScale = ContentScale.Fit, modifier = Modifier.padding(6.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Vanta.TextDim, maxLines = 1)
    }
}

// ============================================================================== text style

private enum class StyleTab { FONT, COLOR, STYLE, SPACING }

private fun fontLabel(f: FontChoice): String =
    f.name.lowercase().split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

@Composable
fun TextStylePanel(vm: EditorViewModel, state: EditorState, onClose: () -> Unit) {
    val sel = state.selection as? Selection.OfText ?: return
    val layer = state.project?.texts?.firstOrNull { it.id == sel.id } ?: return
    val s = layer.style
    var tab by remember { mutableStateOf(StyleTab.FONT) }
    PanelScaffold(stringResource(R.string.panel_text_style), onClose) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.tab_font), tab == StyleTab.FONT, { tab = StyleTab.FONT })
            Chip(stringResource(R.string.tab_color), tab == StyleTab.COLOR, { tab = StyleTab.COLOR })
            Chip(stringResource(R.string.tab_style), tab == StyleTab.STYLE, { tab = StyleTab.STYLE })
            Chip(stringResource(R.string.tab_spacing), tab == StyleTab.SPACING, { tab = StyleTab.SPACING })
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.heightIn(max = 190.dp).verticalScroll(rememberScrollState())) {
            when (tab) {
                StyleTab.FONT -> {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FontChoice.entries.forEach { f ->
                            val selected = s.font == f
                            Column(
                                Modifier
                                    .width(78.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) Vanta.AccentDim else Vanta.Surface2)
                                    .border(1.5.dp, if (selected) Vanta.Accent else Vanta.Outline, RoundedCornerShape(12.dp))
                                    .clickable { vm.updateTextStyle { it.copy(font = f) } }
                                    .padding(vertical = 10.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    "Aa",
                                    fontSize = 24.sp,
                                    fontFamily = when (f) {
                                        FontChoice.SERIF -> FontFamily.Serif
                                        FontChoice.MONO -> FontFamily.Monospace
                                        FontChoice.CURSIVE -> FontFamily.Cursive
                                        else -> FontFamily.SansSerif
                                    },
                                    color = if (selected) Vanta.AccentBright else Vanta.Text,
                                )
                                Text(fontLabel(f), style = MaterialTheme.typography.labelSmall, color = Vanta.TextDim, maxLines = 1)
                            }
                        }
                    }
                    LabeledSlider(
                        stringResource(R.string.label_size), s.size, 0.02f..0.3f,
                        { v -> vm.updateTextStyleLive { it.copy(size = v) } }, vm::endGesture,
                        valueText = { String.format(Locale.US, "%.0f", it * 100) },
                    )
                }
                StyleTab.COLOR -> {
                    SectionLabel(stringResource(R.string.label_text_color))
                    ColorRow(s.color, withNone = false) { c -> vm.updateTextStyle { it.copy(color = c ?: it.color) } }
                    SectionLabel(stringResource(R.string.label_background))
                    ColorRow(s.background, withNone = true) { c -> vm.updateTextStyle { it.copy(background = c) } }
                    SectionLabel(stringResource(R.string.label_outline))
                    ColorRow(s.strokeColor, withNone = true) { c -> vm.updateTextStyle { it.copy(strokeColor = c) } }
                    if (s.strokeColor != null) {
                        LabeledSlider(
                            stringResource(R.string.label_outline_width), s.strokeWidth, 0.02f..0.4f,
                            { v -> vm.updateTextStyleLive { it.copy(strokeWidth = v) } }, vm::endGesture,
                            valueText = { String.format(Locale.US, "%.0f", it * 100) },
                        )
                    }
                }
                StyleTab.STYLE -> {
                    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ToggleChip(Icons.Rounded.FormatBold, s.bold) { vm.updateTextStyle { it.copy(bold = !it.bold) } }
                        ToggleChip(Icons.Rounded.FormatItalic, s.italic) { vm.updateTextStyle { it.copy(italic = !it.italic) } }
                        ToggleChip(Icons.Rounded.FormatAlignLeft, s.align == TextAlign.LEFT) { vm.updateTextStyle { it.copy(align = TextAlign.LEFT) } }
                        ToggleChip(Icons.Rounded.FormatAlignCenter, s.align == TextAlign.CENTER) { vm.updateTextStyle { it.copy(align = TextAlign.CENTER) } }
                        ToggleChip(Icons.Rounded.FormatAlignRight, s.align == TextAlign.RIGHT) { vm.updateTextStyle { it.copy(align = TextAlign.RIGHT) } }
                    }
                    SwitchRow(stringResource(R.string.label_uppercase), s.uppercase) { vm.updateTextStyle { st -> st.copy(uppercase = !st.uppercase) } }
                    SwitchRow(stringResource(R.string.label_shadow), s.shadow) { vm.updateTextStyle { st -> st.copy(shadow = !st.shadow) } }
                }
                StyleTab.SPACING -> {
                    LabeledSlider(
                        stringResource(R.string.label_letter_spacing), s.letterSpacing, 0f..0.6f,
                        { v -> vm.updateTextStyleLive { it.copy(letterSpacing = v) } }, vm::endGesture, neutral = 0f,
                        valueText = { String.format(Locale.US, "%.0f", it * 100) },
                    )
                    LabeledSlider(
                        stringResource(R.string.label_line_spacing), s.lineSpacing, 0.7f..2f,
                        { v -> vm.updateTextStyleLive { it.copy(lineSpacing = v) } }, vm::endGesture, neutral = 1f,
                        valueText = { String.format(Locale.US, "%.0f", it * 100) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorRow(current: Long?, withNone: Boolean, onPick: (Long?) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (withNone) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .border(if (current == null) 2.dp else 1.dp, if (current == null) Vanta.AccentBright else Vanta.OutlineStrong, androidx.compose.foundation.shape.CircleShape)
                    .clickable { onPick(null) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Block, null, tint = Vanta.TextDim, modifier = Modifier.size(16.dp)) }
        }
        TextPresets.colors.forEach { c ->
            ColorSwatch(Color(c.toInt()), selected = current == c, onClick = { onPick(c) })
        }
    }
}

@Composable
private fun ToggleChip(icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (on) Vanta.Accent else Vanta.Surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = if (on) Color.White else Vanta.TextDim) }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = { onToggle() }, colors = SwitchDefaults.colors(checkedTrackColor = Vanta.Accent))
    }
}

// ============================================================================== animation

@Composable
private fun animName(a: LayerAnim) = stringResource(
    when (a) {
        LayerAnim.NONE -> R.string.anim_none
        LayerAnim.FADE -> R.string.anim_fade
        LayerAnim.SLIDE_UP -> R.string.anim_slide_up
        LayerAnim.SLIDE_LEFT -> R.string.anim_slide_left
        LayerAnim.POP -> R.string.anim_pop
        LayerAnim.ZOOM -> R.string.anim_zoom
    },
)

@Composable
fun AnimationPanel(vm: EditorViewModel, state: EditorState, onClose: () -> Unit) {
    val project = state.project ?: return
    val sel = state.selection
    val (animIn, animOut, dur) = when (sel) {
        is Selection.OfText -> project.texts.firstOrNull { it.id == sel.id }?.let { Triple(it.animIn, it.animOut, it.animDurationUs) }
        is Selection.OfOverlay -> project.overlays.firstOrNull { it.id == sel.id }?.let { Triple(it.animIn, it.animOut, it.animDurationUs) }
        else -> null
    } ?: return
    PanelScaffold(stringResource(R.string.panel_animation), onClose) {
        SectionLabel(stringResource(R.string.label_anim_in))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayerAnim.entries.forEach { a -> Chip(animName(a), animIn == a, { vm.setLayerAnim(animIn = a) }) }
        }
        SectionLabel(stringResource(R.string.label_anim_out))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LayerAnim.entries.forEach { a -> Chip(animName(a), animOut == a, { vm.setLayerAnim(animOut = a) }) }
        }
        Spacer(Modifier.height(4.dp))
        LabeledSlider(
            stringResource(R.string.label_duration), dur.toFloat(), 100_000f..2_000_000f,
            { vm.setLayerAnimDurationLive(it.toLong()) }, vm::endGesture,
            valueText = { formatSeconds(it.toLong()) },
        )
    }
}

// ============================================================================== layers: stickers, shapes, frames, images, backgrounds

private enum class LayerTab { STICKERS, SHAPES, FRAMES, IMAGE, BACKGROUND }

@Composable
fun LayersPanel(vm: EditorViewModel, onPickImage: () -> Unit, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(LayerTab.STICKERS) }
    val context = LocalContext.current
    PanelScaffold(stringResource(R.string.panel_layers), onClose) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.tab_stickers), tab == LayerTab.STICKERS, { tab = LayerTab.STICKERS })
            Chip(stringResource(R.string.tab_shapes), tab == LayerTab.SHAPES, { tab = LayerTab.SHAPES })
            Chip(stringResource(R.string.tab_frames), tab == LayerTab.FRAMES, { tab = LayerTab.FRAMES })
            Chip(stringResource(R.string.tab_image), tab == LayerTab.IMAGE, { tab = LayerTab.IMAGE })
            Chip(stringResource(R.string.tab_background), tab == LayerTab.BACKGROUND, { tab = LayerTab.BACKGROUND })
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            LayerTab.STICKERS -> Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StickerCatalog.emoji.forEach { e ->
                    Box(
                        Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(Vanta.Surface2)
                            .clickable { vm.addOverlay(OverlayKind.STICKER, StickerCatalog.emojiId(e), width = 0.22f) },
                        contentAlignment = Alignment.Center,
                    ) { Text(e, fontSize = 28.sp) }
                }
            }
            LayerTab.SHAPES -> Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StickerCatalog.shapes.forEach { id ->
                    val bmp = remember(id) {
                        LayerRenderer.renderOverlay(
                            context,
                            OverlayLayer("t", OverlayKind.SHAPE, id, 0, 1, color = 0xFFB9A6FF, width = 1f),
                            96, 96,
                        )?.asImageBitmap()
                    }
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Vanta.Surface2)
                            .clickable { vm.addOverlay(OverlayKind.SHAPE, id, width = 0.25f, color = 0xFFB9A6FF) },
                        contentAlignment = Alignment.Center,
                    ) { bmp?.let { Image(it, null, Modifier.size(34.dp)) } }
                }
            }
            LayerTab.FRAMES -> Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StickerCatalog.frames.forEach { id ->
                    val bmp = remember(id) {
                        LayerRenderer.renderOverlay(
                            context,
                            OverlayLayer("t", OverlayKind.FRAME, id, 0, 1, color = 0xFFFFFFFF),
                            160, 100,
                        )?.asImageBitmap()
                    }
                    Box(
                        Modifier.size(84.dp, 56.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF3A3A47))
                            .clickable { vm.addOverlay(OverlayKind.FRAME, id, width = 1f, color = 0xFFFFFFFF) },
                        contentAlignment = Alignment.Center,
                    ) { bmp?.let { Image(it, null, Modifier.fillMaxWidth()) } }
                }
            }
            LayerTab.IMAGE -> Column(Modifier.padding(horizontal = 16.dp)) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Vanta.Surface2)
                        .clickable(onClick = onPickImage)
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AddPhotoAlternate, null, tint = Vanta.AccentBright)
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.action_choose_image), style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    stringResource(R.string.hint_png_transparency),
                    style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(top = 8.dp),
                )
            }
            LayerTab.BACKGROUND -> Column {
                Text(
                    stringResource(R.string.hint_color_background),
                    style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    (listOf(0xFF000000L, 0xFFFFFFFFL) + TextPresets.colors.drop(2)).forEach { c ->
                        ColorSwatch(Color(c.toInt()), false, { vm.addColorClip(c) }, size = 38.dp)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
fun OverlayColorPanel(vm: EditorViewModel, state: EditorState, onClose: () -> Unit) {
    val sel = state.selection as? Selection.OfOverlay ?: return
    val layer = state.project?.overlays?.firstOrNull { it.id == sel.id } ?: return
    PanelScaffold(stringResource(R.string.panel_overlay), onClose) {
        if (layer.kind == OverlayKind.SHAPE || layer.kind == OverlayKind.FRAME) {
            SectionLabel(stringResource(R.string.label_color))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextPresets.colors.forEach { c -> ColorSwatch(Color(c.toInt()), layer.color == c, { vm.setOverlayColor(c) }) }
            }
        }
        if (layer.kind != OverlayKind.FRAME) {
            LabeledSlider(
                stringResource(R.string.label_size), layer.width, 0.05f..1f,
                { vm.setOverlayWidthLive(it) }, vm::endGesture,
                valueText = { String.format(Locale.US, "%.0f%%", it * 100) },
            )
        }
    }
}

// ============================================================================== audio

@Composable
fun AudioPanel(vm: EditorViewModel, state: EditorState, onClose: () -> Unit) {
    val sel = state.selection as? Selection.OfAudio ?: return
    val clip = state.project?.audio?.firstOrNull { it.id == sel.id } ?: return
    PanelScaffold(stringResource(R.string.panel_audio), onClose) {
        SwitchRow(stringResource(R.string.label_mute), clip.muted) { vm.updateSelectedAudio { it.copy(muted = !it.muted) } }
        LabeledSlider(
            stringResource(R.string.label_volume), clip.volume, 0f..2f,
            { v -> vm.updateSelectedAudioLive { it.copy(volume = v) } }, vm::endGesture, neutral = 1f,
        )
        val maxFade = (clip.durationUs / 2).coerceAtMost(8_000_000L).toFloat().coerceAtLeast(100_000f)
        LabeledSlider(
            stringResource(R.string.label_fade_in), clip.fadeInUs.toFloat().coerceAtMost(maxFade), 0f..maxFade,
            { v -> vm.updateSelectedAudioLive { it.copy(fadeInUs = v.toLong()) } }, vm::endGesture,
            valueText = { formatSeconds(it.toLong()) }, neutral = 0f,
        )
        LabeledSlider(
            stringResource(R.string.label_fade_out), clip.fadeOutUs.toFloat().coerceAtMost(maxFade), 0f..maxFade,
            { v -> vm.updateSelectedAudioLive { it.copy(fadeOutUs = v.toLong()) } }, vm::endGesture,
            valueText = { formatSeconds(it.toLong()) }, neutral = 0f,
        )
    }
}
