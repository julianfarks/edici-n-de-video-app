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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Deblur
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lens
import androidx.compose.material.icons.rounded.LineStyle
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tonality
import androidx.compose.material.icons.rounded.Vignette
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vanta.studio.R
import com.vanta.studio.data.Thumbnails
import com.vanta.studio.domain.effects.EffectCategory
import com.vanta.studio.domain.effects.FilterCategory
import com.vanta.studio.domain.effects.Grade
import com.vanta.studio.domain.effects.GradeParams
import com.vanta.studio.domain.model.AspectRatio
import com.vanta.studio.domain.model.CanvasSpec
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.Easing
import com.vanta.studio.domain.model.EffectType
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.domain.model.TransitionType
import com.vanta.studio.domain.timeline.Timeline
import com.vanta.studio.ui.components.Chip
import com.vanta.studio.ui.components.LabeledSlider
import com.vanta.studio.ui.components.PanelScaffold
import com.vanta.studio.ui.components.SectionLabel
import com.vanta.studio.ui.components.formatSeconds
import com.vanta.studio.ui.theme.Vanta
import java.util.Locale

private val PanelMaxHeight = 236.dp

@Composable
private fun Body(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier
            .heightIn(max = PanelMaxHeight)
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

// ============================================================================== filters

@Composable
fun filterName(id: String): String {
    val res = when (id) {
        "vivid" -> R.string.filter_vivid; "warm" -> R.string.filter_warm; "cool" -> R.string.filter_cool
        "pastel" -> R.string.filter_pastel; "punch" -> R.string.filter_punch
        "teal_orange" -> R.string.filter_teal_orange; "blockbuster" -> R.string.filter_blockbuster
        "night" -> R.string.filter_night; "golden" -> R.string.filter_golden
        "sepia" -> R.string.filter_sepia; "film70" -> R.string.filter_film70; "instant" -> R.string.filter_instant
        "vhs" -> R.string.filter_vhs; "faded" -> R.string.filter_faded; "retro80" -> R.string.filter_retro80
        "mono" -> R.string.filter_mono; "noir" -> R.string.filter_noir; "silver" -> R.string.filter_silver
        "dreamy" -> R.string.filter_dreamy; "moody" -> R.string.filter_moody; "sunset" -> R.string.filter_sunset
        else -> return id
    }
    return stringResource(res)
}

@Composable
private fun filterCategoryName(c: FilterCategory) = stringResource(
    when (c) {
        FilterCategory.COLOR -> R.string.cat_color
        FilterCategory.CINEMATIC -> R.string.cat_cinematic
        FilterCategory.VINTAGE -> R.string.cat_vintage
        FilterCategory.RETRO -> R.string.cat_retro
        FilterCategory.BW -> R.string.cat_bw
        FilterCategory.MOOD -> R.string.cat_mood
    },
)

@Composable
fun FiltersPanel(vm: EditorViewModel, state: EditorState, thumbs: Thumbnails, onClose: () -> Unit) {
    val clip = vm.targetClip()
    var category by remember { mutableStateOf<FilterCategory?>(null) }
    val frame by produceState<ImageBitmap?>(null, clip?.uri, clip?.trimStartUs) {
        value = clip?.let {
            val b = if (it.kind == MediaKind.VIDEO) thumbs.fullFrame(it.uri, it.localToSourceUs(0), 200) else thumbs.still(it.uri, 160)
            b?.asImageBitmap()
        }
    }
    PanelScaffold(stringResource(R.string.panel_filters), onClose) {
        if (clip == null) {
            EmptyHint(stringResource(R.string.hint_need_clip))
            return@PanelScaffold
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.cat_all), category == null, { category = null })
            FilterCategory.entries.forEach { c -> Chip(filterCategoryName(c), category == c, { category = c }) }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TileItem(
                label = stringResource(R.string.filter_none),
                selected = clip.filter == null,
                onClick = { vm.setFilter(null) },
            ) { Box(Modifier.fillMaxWidthSquare().background(Vanta.Surface3), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Block, null, tint = Vanta.TextDim) } }
            Grade.filters.filter { category == null || it.category == category }.forEach { f ->
                TileItem(
                    label = filterName(f.id),
                    selected = clip.filter?.id == f.id,
                    onClick = { vm.setFilter(f.id) },
                ) {
                    val img = frame
                    if (img != null) {
                        Image(
                            img, null, contentScale = ContentScale.Crop,
                            colorFilter = FilterPreview.colorFilter(f.params),
                            modifier = Modifier.fillMaxWidthSquare(),
                        )
                    } else {
                        Box(Modifier.fillMaxWidthSquare().background(Vanta.Surface3))
                    }
                }
            }
        }
        val f = clip.filter
        if (f != null) {
            Spacer(Modifier.height(6.dp))
            LabeledSlider(
                stringResource(R.string.label_intensity), f.intensity, 0f..1f,
                onChange = { vm.setFilterIntensity(it) }, onChangeFinished = vm::endGesture,
            )
        }
    }
}

private fun Modifier.fillMaxWidthSquare(): Modifier = this.then(Modifier.size(68.dp))

@Composable
private fun TileItem(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier.width(68.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(2.dp, if (selected) Vanta.Accent else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(if (selected) 2.dp else 0.dp)
                .clip(RoundedCornerShape(10.dp)),
        ) { content() }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Vanta.AccentBright else Vanta.TextDim,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Vanta.TextFaint, modifier = Modifier.padding(16.dp))
}

// ============================================================================== adjust

@Composable
fun AdjustPanel(vm: EditorViewModel, onClose: () -> Unit) {
    val clip = vm.targetClip()
    PanelScaffold(
        stringResource(R.string.panel_adjust), onClose,
        trailing = {
            TextButton(onClick = vm::resetAdjust) { Text(stringResource(R.string.action_reset), color = Vanta.AccentBright) }
        },
    ) {
        if (clip == null) {
            EmptyHint(stringResource(R.string.hint_need_clip))
            return@PanelScaffold
        }
        val a = clip.adjust
        Body {
            AdjustSlider(R.string.adj_brightness, a.brightness, -1f..1f) { v -> vm.setAdjustLive { it.copy(brightness = v) } }
            AdjustSlider(R.string.adj_contrast, a.contrast, -1f..1f) { v -> vm.setAdjustLive { it.copy(contrast = v) } }
            AdjustSlider(R.string.adj_saturation, a.saturation, -1f..1f) { v -> vm.setAdjustLive { it.copy(saturation = v) } }
            AdjustSlider(R.string.adj_exposure, a.exposure, -1f..1f) { v -> vm.setAdjustLive { it.copy(exposure = v) } }
            AdjustSlider(R.string.adj_temperature, a.temperature, -1f..1f) { v -> vm.setAdjustLive { it.copy(temperature = v) } }
            AdjustSlider(R.string.adj_tint, a.tint, -1f..1f) { v -> vm.setAdjustLive { it.copy(tint = v) } }
            AdjustSlider(R.string.adj_shadows, a.shadows, -1f..1f) { v -> vm.setAdjustLive { it.copy(shadows = v) } }
            AdjustSlider(R.string.adj_highlights, a.highlights, -1f..1f) { v -> vm.setAdjustLive { it.copy(highlights = v) } }
            AdjustSlider(R.string.adj_vignette, a.vignette, 0f..1f) { v -> vm.setAdjustLive { it.copy(vignette = v) } }
            AdjustSlider(R.string.adj_sharpen, a.sharpen, 0f..1f) { v -> vm.setAdjustLive { it.copy(sharpen = v) } }
            AdjustSlider(R.string.adj_grain, a.grain, 0f..1f) { v -> vm.setAdjustLive { it.copy(grain = v) } }
            TextButton(onClick = vm::applyColorToAll, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.action_apply_all), color = Vanta.AccentBright)
            }
        }
    }
}

@Composable
private fun AdjustSlider(label: Int, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val vm = LocalEditorVm.current
    LabeledSlider(
        stringResource(label), value, range, onChange,
        onChangeFinished = { vm.endGesture() },
        neutral = 0f,
        valueText = { String.format(Locale.US, "%.0f", it * 100) },
    )
}

// ============================================================================== effects

@Composable
fun effectName(t: EffectType) = stringResource(
    when (t) {
        EffectType.BLUR -> R.string.fx_blur
        EffectType.PIXELATE -> R.string.fx_pixelate
        EffectType.GLITCH -> R.string.fx_glitch
        EffectType.CHROMATIC -> R.string.fx_chromatic
        EffectType.MIRROR -> R.string.fx_mirror
        EffectType.FILM_GRAIN -> R.string.fx_grain
        EffectType.SCANLINES -> R.string.fx_scanlines
        EffectType.LIGHT_LEAK -> R.string.fx_light_leak
        EffectType.SHARPEN -> R.string.fx_sharpen
        EffectType.VIGNETTE -> R.string.fx_vignette
    },
)

private fun effectIcon(t: EffectType): ImageVector = when (t) {
    EffectType.BLUR -> Icons.Rounded.BlurOn
    EffectType.PIXELATE -> Icons.Rounded.GridView
    EffectType.GLITCH -> Icons.Rounded.FlashOn
    EffectType.CHROMATIC -> Icons.Rounded.Lens
    EffectType.MIRROR -> Icons.Rounded.FlipCameraAndroid
    EffectType.FILM_GRAIN -> Icons.Rounded.Grain
    EffectType.SCANLINES -> Icons.Rounded.LineStyle
    EffectType.LIGHT_LEAK -> Icons.Rounded.WbSunny
    EffectType.SHARPEN -> Icons.Rounded.Deblur
    EffectType.VIGNETTE -> Icons.Rounded.Vignette
}

@Composable
private fun effectCategoryName(c: EffectCategory) = stringResource(
    when (c) {
        EffectCategory.GLITCH -> R.string.cat_glitch
        EffectCategory.DISTORTION -> R.string.cat_distortion
        EffectCategory.LIGHT -> R.string.cat_light
        EffectCategory.BLUR -> R.string.cat_blur
        EffectCategory.SHARPNESS -> R.string.cat_sharpness
        EffectCategory.RETRO -> R.string.cat_retro
        EffectCategory.MOOD -> R.string.cat_mood
    },
)

@Composable
fun EffectsPanel(vm: EditorViewModel, onClose: () -> Unit) {
    val clip = vm.targetClip()
    var category by remember { mutableStateOf<EffectCategory?>(null) }
    PanelScaffold(stringResource(R.string.panel_effects), onClose) {
        if (clip == null) {
            EmptyHint(stringResource(R.string.hint_need_clip))
            return@PanelScaffold
        }
        val cats = EffectCategory.entries.filter { c -> Grade.effectCategories.any { it.value == c } }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.cat_all), category == null, { category = null })
            cats.forEach { c -> Chip(effectCategoryName(c), category == c, { category = c }) }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EffectType.entries.filter { category == null || Grade.effectCategories[it] == category }.forEach { t ->
                val active = clip.effects.any { it.type == t }
                TileItem(
                    label = effectName(t),
                    selected = active,
                    onClick = { if (active) vm.removeEffect(t) else vm.addEffect(t) },
                ) {
                    Box(Modifier.size(68.dp).background(if (active) Vanta.AccentDim else Vanta.Surface3), contentAlignment = Alignment.Center) {
                        Icon(effectIcon(t), null, tint = if (active) Vanta.AccentBright else Vanta.TextDim, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
        Body(Modifier.heightIn(max = 110.dp)) {
            clip.effects.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (e.type == EffectType.MIRROR) {
                            Text(effectName(e.type), modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = Vanta.TextDim)
                        } else {
                            LabeledSlider(
                                effectName(e.type), e.intensity, 0f..1f,
                                onChange = { vm.setEffectIntensityLive(e.type, it) }, onChangeFinished = vm::endGesture,
                            )
                        }
                    }
                    IconButton(onClick = { vm.removeEffect(e.type) }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.action_remove), tint = Vanta.TextDim)
                    }
                }
            }
        }
    }
}

// ============================================================================== transitions

@Composable
fun transitionName(t: TransitionType) = stringResource(
    when (t) {
        TransitionType.FADE -> R.string.tr_fade
        TransitionType.FLASH -> R.string.tr_flash
        TransitionType.ZOOM -> R.string.tr_zoom
        TransitionType.SLIDE -> R.string.tr_slide
        TransitionType.WIPE -> R.string.tr_wipe
        TransitionType.BLUR -> R.string.tr_blur
    },
)

private fun transitionIcon(t: TransitionType): ImageVector = when (t) {
    TransitionType.FADE -> Icons.Rounded.Tonality
    TransitionType.FLASH -> Icons.Rounded.FlashOn
    TransitionType.ZOOM -> Icons.Rounded.ZoomIn
    TransitionType.SLIDE -> Icons.Rounded.SwapHoriz
    TransitionType.WIPE -> Icons.Rounded.Contrast
    TransitionType.BLUR -> Icons.Rounded.BlurOn
}

@Composable
fun TransitionPanel(vm: EditorViewModel, state: EditorState, onClose: () -> Unit) {
    val project = state.project ?: return
    val clip = vm.targetClip()
    var outroMode by remember { mutableStateOf(false) }
    PanelScaffold(stringResource(R.string.panel_transitions), onClose) {
        if (clip == null) {
            EmptyHint(stringResource(R.string.hint_need_clip))
            return@PanelScaffold
        }
        val isFirst = project.clips.firstOrNull()?.id == clip.id
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(
                stringResource(if (isFirst) R.string.tr_mode_start else R.string.tr_mode_join),
                !outroMode, { outroMode = false },
            )
            Chip(stringResource(R.string.tr_mode_end), outroMode, { outroMode = true })
        }
        Spacer(Modifier.height(10.dp))
        val current = if (outroMode) project.outro else clip.transitionIn
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TileItem(stringResource(R.string.tr_none), current == null, {
                if (outroMode) vm.setOutro(null) else vm.setTransition(clip.id, null)
            }) {
                Box(Modifier.size(68.dp).background(Vanta.Surface3), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Block, null, tint = Vanta.TextDim) }
            }
            TransitionType.entries.forEach { t ->
                val active = current?.type == t
                TileItem(transitionName(t), active, {
                    if (outroMode) vm.setOutro(t) else vm.setTransition(clip.id, t)
                }) {
                    Box(Modifier.size(68.dp).background(if (active) Vanta.AccentDim else Vanta.Surface3), contentAlignment = Alignment.Center) {
                        Icon(transitionIcon(t), null, tint = if (active) Vanta.AccentBright else Vanta.TextDim, modifier = Modifier.size(28.dp))
                    }
                }
            }
        }
        if (current != null) {
            Spacer(Modifier.height(6.dp))
            val maxDur = (clip.durationUs).coerceAtLeast(300_000L).coerceAtMost(3_000_000L)
            LabeledSlider(
                stringResource(R.string.label_duration), current.durationUs.toFloat(), 200_000f..maxDur.toFloat(),
                onChange = { v -> if (outroMode) vm.setOutroDurationLive(v.toLong()) else vm.setTransitionDurationLive(clip.id, v.toLong()) },
                onChangeFinished = vm::endGesture,
                valueText = { formatSeconds(it.toLong()) },
            )
        }
    }
}

// ============================================================================== speed

@Composable
fun SpeedPanel(vm: EditorViewModel, onClose: () -> Unit) {
    val clip = vm.selectedClip()
    PanelScaffold(stringResource(R.string.panel_speed), onClose) {
        if (clip == null) return@PanelScaffold
        if (clip.isStill) {
            EmptyHint(stringResource(R.string.hint_speed_video_only))
            return@PanelScaffold
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f, 3f, 4f, 8f).forEach { s ->
                Chip("${trimF(s)}x", clip.speed == s, { vm.setSpeed(s); vm.endGesture() })
            }
        }
        Spacer(Modifier.height(8.dp))
        LabeledSlider(
            stringResource(R.string.label_speed), clip.speed, 0.1f..8f,
            onChange = { vm.setSpeed((it * 20).toInt() / 20f) },
            onChangeFinished = vm::endGesture,
            valueText = { String.format(Locale.US, "%.2fx", it) },
            neutral = 1f,
        )
        Text(
            stringResource(R.string.speed_result, formatSeconds(clip.durationUs)),
            style = MaterialTheme.typography.bodySmall, color = Vanta.TextFaint,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            stringResource(R.string.speed_curves_note),
            style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

private fun trimF(v: Float) = if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

// ============================================================================== volume

@Composable
fun VolumePanel(vm: EditorViewModel, onClose: () -> Unit) {
    val clip = vm.selectedClip()
    PanelScaffold(stringResource(R.string.panel_volume), onClose) {
        if (clip == null) return@PanelScaffold
        if (clip.kind != MediaKind.VIDEO || !clip.hasAudio) {
            EmptyHint(stringResource(R.string.hint_no_audio_track))
            return@PanelScaffold
        }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.label_mute), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = clip.muted, onCheckedChange = { vm.toggleMute() },
                colors = SwitchDefaults.colors(checkedTrackColor = Vanta.Accent),
            )
        }
        LabeledSlider(
            stringResource(R.string.label_volume), clip.volume, 0f..2f,
            onChange = { v -> vm.updateSelectedClipLive { it.copy(volume = v) } },
            onChangeFinished = vm::endGesture, neutral = 1f,
        )
        val maxFade = (clip.durationUs / 2).coerceAtMost(5_000_000L).toFloat().coerceAtLeast(100_000f)
        LabeledSlider(
            stringResource(R.string.label_fade_in), clip.fadeInUs.toFloat().coerceAtMost(maxFade), 0f..maxFade,
            onChange = { v -> vm.updateSelectedClipLive { it.copy(fadeInUs = v.toLong()) } },
            onChangeFinished = vm::endGesture, valueText = { formatSeconds(it.toLong()) }, neutral = 0f,
        )
        LabeledSlider(
            stringResource(R.string.label_fade_out), clip.fadeOutUs.toFloat().coerceAtMost(maxFade), 0f..maxFade,
            onChange = { v -> vm.updateSelectedClipLive { it.copy(fadeOutUs = v.toLong()) } },
            onChangeFinished = vm::endGesture, valueText = { formatSeconds(it.toLong()) }, neutral = 0f,
        )
    }
}

// ============================================================================== transform + keyframes

@Composable
fun TransformPanel(vm: EditorViewModel, state: EditorState, positionUs: Long, onClose: () -> Unit) {
    val pose = vm.currentPose()
    val keys = vm.selectionKeyframes()
    val local = vm.localTimeInSelection()
    val fpsTol = 1_000_000L / (state.project?.canvas?.fps ?: 30)
    val onKey = local != null && com.vanta.studio.domain.anim.Keyframes.indexNear(keys, local, fpsTol) >= 0
    val isClip = state.selection is Selection.OfClip
    PanelScaffold(
        stringResource(R.string.panel_transform), onClose,
        trailing = {
            TextButton(onClick = vm::resetTransform) { Text(stringResource(R.string.action_reset), color = Vanta.AccentBright) }
        },
    ) {
        if (pose == null) {
            EmptyHint(stringResource(R.string.hint_select_item))
            return@PanelScaffold
        }
        // keyframe controls
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                stringResource(R.string.label_keyframes) + if (keys.isNotEmpty()) " (${keys.size})" else "",
                style = MaterialTheme.typography.labelMedium, color = Vanta.TextDim, modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = { vm.jumpToKeyframe(false) }, enabled = keys.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_prev_keyframe), tint = if (keys.isNotEmpty()) Vanta.Text else Vanta.TextFaint)
            }
            IconButton(onClick = vm::toggleKeyframe) {
                DiamondIcon(
                    filled = onKey,
                    tint = if (onKey) Vanta.Warning else Vanta.AccentBright,
                    description = stringResource(R.string.action_toggle_keyframe),
                )
            }
            IconButton(onClick = { vm.jumpToKeyframe(true) }, enabled = keys.isNotEmpty()) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, stringResource(R.string.action_next_keyframe), tint = if (keys.isNotEmpty()) Vanta.Text else Vanta.TextFaint)
            }
            if (keys.isNotEmpty()) {
                IconButton(onClick = vm::clearKeyframes) {
                    Icon(Icons.Rounded.Delete, stringResource(R.string.action_clear_keyframes), tint = Vanta.TextDim)
                }
            }
        }
        if (onKey) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val cur = local?.let { l -> keys.firstOrNull { kotlin.math.abs(it.timeUs - l) <= fpsTol }?.easing }
                listOf(
                    Easing.LINEAR to R.string.ease_linear, Easing.EASE_IN to R.string.ease_in,
                    Easing.EASE_OUT to R.string.ease_out, Easing.EASE_IN_OUT to R.string.ease_in_out,
                    Easing.HOLD to R.string.ease_hold,
                ).forEach { (e, label) -> Chip(stringResource(label), cur == e, { vm.setKeyframeEasing(e) }) }
            }
        }
        Body(Modifier.heightIn(max = 190.dp)) {
            LabeledSlider(stringResource(R.string.label_pos_x), pose.x, -1f..1f, { v -> vm.applyPose { it.copy(x = v) } }, vm::endGesture, neutral = 0f)
            LabeledSlider(stringResource(R.string.label_pos_y), pose.y, -1f..1f, { v -> vm.applyPose { it.copy(y = v) } }, vm::endGesture, neutral = 0f)
            LabeledSlider(
                stringResource(R.string.label_scale), pose.scale, 0.1f..4f, { v -> vm.applyPose { it.copy(scale = v) } }, vm::endGesture,
                neutral = 1f, valueText = { String.format(Locale.US, "%.0f%%", it * 100) },
            )
            LabeledSlider(
                stringResource(R.string.label_rotation), pose.rotation, -180f..180f, { v -> vm.applyPose { it.copy(rotation = v) } }, vm::endGesture,
                neutral = 0f, valueText = { String.format(Locale.US, "%.0f°", it) },
            )
            LabeledSlider(
                stringResource(R.string.label_opacity), pose.opacity, 0f..1f, { v -> vm.applyPose { it.copy(opacity = v) } }, vm::endGesture,
                neutral = 1f, valueText = { String.format(Locale.US, "%.0f%%", it * 100) },
            )
            if (isClip) {
                Text(
                    stringResource(R.string.hint_transform_gesture),
                    style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(16.dp, 4.dp),
                )
            }
        }
    }
}

// ============================================================================== crop & trim

@Composable
fun CropPanel(vm: EditorViewModel, onClose: () -> Unit) {
    val clip = vm.selectedClip()
    PanelScaffold(stringResource(R.string.panel_crop), onClose) {
        if (clip == null) return@PanelScaffold
        val c = clip.crop
        LabeledSlider(stringResource(R.string.crop_left), c.left, 0f..0.45f, { v -> vm.setCropLive { it.copy(left = v) } }, vm::endGesture, neutral = 0f)
        LabeledSlider(stringResource(R.string.crop_right), c.right, 0f..0.45f, { v -> vm.setCropLive { it.copy(right = v) } }, vm::endGesture, neutral = 0f)
        LabeledSlider(stringResource(R.string.crop_top), c.top, 0f..0.45f, { v -> vm.setCropLive { it.copy(top = v) } }, vm::endGesture, neutral = 0f)
        LabeledSlider(stringResource(R.string.crop_bottom), c.bottom, 0f..0.45f, { v -> vm.setCropLive { it.copy(bottom = v) } }, vm::endGesture, neutral = 0f)
    }
}

@Composable
fun TrimPanel(vm: EditorViewModel, onClose: () -> Unit) {
    val clip = vm.selectedClip()
    PanelScaffold(stringResource(R.string.panel_trim), onClose) {
        if (clip == null) return@PanelScaffold
        Text(
            stringResource(R.string.trim_duration, formatSeconds(clip.durationUs)),
            style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(8.dp))
        if (clip.isStill) {
            LabeledSlider(
                stringResource(R.string.label_duration), clip.durationUs.toFloat(), 100_000f..30_000_000f,
                onChange = { vm.setStillDuration(clip.id, (it / 100_000).toLong() * 100_000) },
                onChangeFinished = vm::endGesture, valueText = { formatSeconds(it.toLong()) },
            )
        } else {
            NudgeRow(stringResource(R.string.trim_start), onMinus10 = { vm.nudgeTrim(true, -10) }, onMinus1 = { vm.nudgeTrim(true, -1) }, onPlus1 = { vm.nudgeTrim(true, 1) }, onPlus10 = { vm.nudgeTrim(true, 10) })
            NudgeRow(stringResource(R.string.trim_end), onMinus10 = { vm.nudgeTrim(false, -10) }, onMinus1 = { vm.nudgeTrim(false, -1) }, onPlus1 = { vm.nudgeTrim(false, 1) }, onPlus10 = { vm.nudgeTrim(false, 10) })
        }
        Text(
            stringResource(R.string.trim_hint),
            style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(16.dp, 8.dp),
        )
    }
}

@Composable
private fun NudgeRow(label: String, onMinus10: () -> Unit, onMinus1: () -> Unit, onPlus1: () -> Unit, onPlus10: () -> Unit) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            NudgeButton("−10f", onMinus10)
            NudgeButton("−1f", onMinus1)
            NudgeButton("+1f", onPlus1)
            NudgeButton("+10f", onPlus10)
        }
    }
}

@Composable
private fun NudgeButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Vanta.Surface3)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
    ) { Text(text, style = MaterialTheme.typography.labelMedium) }
}

// ============================================================================== format

@Composable
fun FormatPanel(vm: EditorViewModel, state: EditorState, onClose: () -> Unit) {
    val canvas = state.project?.canvas ?: return
    PanelScaffold(stringResource(R.string.panel_format), onClose) {
        SectionLabel(stringResource(R.string.new_project_aspect))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(AspectRatio.R16_9 to "16:9", AspectRatio.R9_16 to "9:16", AspectRatio.R1_1 to "1:1", AspectRatio.R4_5 to "4:5").forEach { (a, label) ->
                Chip(label, canvas.aspect == a, { vm.setCanvas(canvas.copy(aspect = a)) })
            }
            Chip("21:9", canvas.aspect == AspectRatio.CUSTOM && canvas.customW == 21 && canvas.customH == 9, {
                vm.setCanvas(canvas.copy(aspect = AspectRatio.CUSTOM, customW = 21, customH = 9))
            })
            Chip("3:4", canvas.aspect == AspectRatio.CUSTOM && canvas.customW == 3 && canvas.customH == 4, {
                vm.setCanvas(canvas.copy(aspect = AspectRatio.CUSTOM, customW = 3, customH = 4))
            })
        }
        SectionLabel(stringResource(R.string.new_project_fps))
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(24, 25, 30, 60).forEach { f -> Chip("$f fps", canvas.fps == f, { vm.setCanvas(canvas.copy(fps = f)) }) }
        }
    }
}

/** Keyframe marker (filled when the playhead sits on a keyframe). */
@Composable
fun DiamondIcon(filled: Boolean, tint: Color, description: String, size: androidx.compose.ui.unit.Dp = 20.dp) {
    androidx.compose.foundation.Canvas(
        Modifier
            .size(size)
            .androidx_semantics(description),
    ) {
        val w = this.size.width
        val h = this.size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w / 2f, h * 0.08f)
            lineTo(w * 0.92f, h / 2f)
            lineTo(w / 2f, h * 0.92f)
            lineTo(w * 0.08f, h / 2f)
            close()
        }
        if (filled) {
            drawPath(path, tint)
        } else {
            drawPath(path, tint, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f))
        }
    }
}

private fun Modifier.androidx_semantics(description: String): Modifier =
    this.then(Modifier.semantics { contentDescription = description })
