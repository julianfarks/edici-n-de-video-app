package com.vanta.studio.engine

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.effect.Crop
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.google.common.collect.ImmutableList
import com.vanta.studio.domain.anim.ClipTransitions
import com.vanta.studio.domain.model.AudioClip
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.timeline.Timeline
import com.vanta.studio.engine.audio.GainAudioProcessor
import com.vanta.studio.engine.audio.GainEnvelope
import com.vanta.studio.engine.audio.Silence
import com.vanta.studio.engine.effects.ClipMotionEffect
import com.vanta.studio.engine.effects.ClipSource
import com.vanta.studio.engine.effects.ColorGradeEffect
import com.vanta.studio.engine.effects.LightEventsEffect
import com.vanta.studio.engine.layers.LayerOverlay
import com.vanta.studio.engine.layers.LayerRenderer
import java.io.File
import kotlin.math.min

/**
 * Turns a [Project] into a Media3 [Composition]. The same graph feeds the preview player
 * and the exporter, so what is previewed is what gets exported. Differences between the
 * two modes are limited to output size, frame-rate capping and how layers are drawn
 * (the preview draws text/stickers in Compose on top of the video; the export burns them
 * in with an overlay effect using the same bitmaps and animation maths).
 */
class CompositionFactory(private val context: Context) {

    sealed interface Mode {
        val width: Int
        val height: Int

        data class Preview(override val width: Int, override val height: Int) : Mode

        data class Export(
            override val width: Int,
            override val height: Int,
            val fps: Int,
            val includeAudio: Boolean,
        ) : Mode
    }

    private val silenceDir: File get() = File(context.cacheDir, "silence")

    fun build(project: Project, mode: Mode, live: LiveProject, sync: PlaybackSync): Composition {
        require(project.clips.isNotEmpty()) { "The project has no clips" }
        val includeAudio = mode !is Mode.Export || mode.includeAudio
        val starts = Timeline.clipStarts(project)
        val transitions = Timeline.transitions(project)
        val total = Timeline.totalDurationUs(project)

        val videoItems = project.clips.mapIndexed { i, clip ->
            buildClipItem(project, clip, starts[i], transitions[i], mode, includeAudio, live, sync)
        }
        val sequences = mutableListOf(EditedMediaItemSequence(videoItems))
        if (includeAudio) sequences += buildAudioLanes(project, total, live, sync)

        return Composition.Builder(sequences)
            .experimentalSetForceAudioTrack(includeAudio)
            .build()
    }

    private fun buildClipItem(
        project: Project,
        clip: Clip,
        startUs: Long,
        transitions: ClipTransitions,
        mode: Mode,
        includeAudio: Boolean,
        live: LiveProject,
        sync: PlaybackSync,
    ): EditedMediaItem {
        val durationUs = clip.durationUs
        val mediaItem = MediaItem.Builder().setUri(Uri.parse(clip.uri)).apply {
            if (clip.kind == MediaKind.VIDEO) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartUs / 1000)
                        .setEndPositionMs(clip.trimEndUs / 1000)
                        .build(),
                )
            } else {
                setImageDurationMs(durationUs / 1000)
            }
        }.build()

        val clock = ClipClock(startUs, durationUs, sync)
        val source = if (mode is Mode.Export) {
            val frozen = clip
            ClipSource { frozen }
        } else {
            ClipSource { live.clip(clip.id) }
        }

        val audioProcessors = mutableListOf<AudioProcessor>()
        val videoEffects = mutableListOf<Effect>()

        if (clip.kind == MediaKind.VIDEO && clip.speed != 1f) {
            val pair = Effects.createExperimentalSpeedChangingEffect(ConstantSpeed(clip.speed))
            audioProcessors += pair.first
            videoEffects += pair.second
        }
        if (mode is Mode.Export && clip.kind == MediaKind.VIDEO) {
            // Caps high-frame-rate sources at the chosen output rate (after the speed change).
            videoEffects += FrameDropEffect.createDefaultFrameDropEffect(mode.fps.toFloat())
        }
        if (clip.rotation % 360 != 0 || clip.flipH || clip.flipV) {
            videoEffects += ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(-clip.rotation.toFloat())
                .setScale(if (clip.flipH) -1f else 1f, if (clip.flipV) -1f else 1f)
                .build()
        }
        if (!clip.crop.isIdentity) {
            val c = clip.crop
            videoEffects += Crop(-1f + 2f * c.left, 1f - 2f * c.right, -1f + 2f * c.bottom, 1f - 2f * c.top)
        }
        videoEffects += ColorGradeEffect(source, clock)
        videoEffects += Presentation.createForWidthAndHeight(mode.width, mode.height, Presentation.LAYOUT_SCALE_TO_FIT)
        videoEffects += ClipMotionEffect(source, clock, transitions)
        // The effect exists only if the project has light events when the pipeline is built;
        // adding the first one rebuilds the preview (see PreviewEngine.structuralKey), later edits are live.
        if (project.fx.isNotEmpty()) videoEffects += LightEventsEffect(live, clock)
        if (mode is Mode.Export) {
            buildOverlays(project, startUs, durationUs, mode, clock)?.let { videoEffects += it }
        }

        if (includeAudio) {
            val audioClock = AudioClock(startUs, durationUs, sync)
            val gainSource = if (mode is Mode.Export) {
                val c = clip
                com.vanta.studio.engine.audio.GainSource { t ->
                    GainEnvelope.gainAt(t, durationUs, c.volume, c.muted, c.fadeInUs, c.fadeOutUs)
                }
            } else {
                com.vanta.studio.engine.audio.GainSource { t ->
                    val c = live.clip(clip.id) ?: clip
                    GainEnvelope.gainAt(t, durationUs, c.volume, c.muted, c.fadeInUs, c.fadeOutUs)
                }
            }
            audioProcessors += GainAudioProcessor(gainSource, audioClock)
        }

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, videoEffects))
            .setRemoveAudio(!includeAudio)
            .apply {
                if (clip.isStill) {
                    setDurationUs(durationUs)
                    setFrameRate(if (mode is Mode.Export) mode.fps else project.canvas.fps)
                }
            }
            .build()
    }

    /** Text and overlay layers that intersect this clip, burnt in for export. */
    private fun buildOverlays(project: Project, startUs: Long, durationUs: Long, mode: Mode.Export, clock: ClipClock): OverlayEffect? {
        val endUs = startUs + durationUs
        val layers = ArrayList<Pair<Int, TextureOverlay>>()
        project.texts.filter { it.startUs < endUs && it.endUs > startUs }.forEach { t ->
            val bmp = LayerRenderer.text(t, mode.width, mode.height)
            layers += t.z to LayerOverlay.forText(t, bmp, clock)
        }
        project.overlays.filter { it.startUs < endUs && it.endUs > startUs }.forEach { o ->
            val bmp = LayerRenderer.overlay(context, o, mode.width, mode.height) ?: return@forEach
            layers += o.z to LayerOverlay.forOverlay(o, bmp, clock)
        }
        project.subtitles?.takeIf { t -> t.enabled && t.cues.any { it.startUs < endUs && it.endUs > startUs } }?.let { t ->
            // Subtitles sit above stickers and below nothing: highest z.
            layers += Int.MAX_VALUE to com.vanta.studio.engine.layers.SubtitleRenderer.Overlay(t, mode.width, mode.height, clock)
        }
        if (layers.isEmpty()) return null
        return OverlayEffect(ImmutableList.copyOf(layers.sortedBy { it.first }.map { it.second }))
    }

    /** One audio-only sequence per lane, with silent spacers between clips. */
    private fun buildAudioLanes(project: Project, totalUs: Long, live: LiveProject, sync: PlaybackSync): List<EditedMediaItemSequence> {
        if (project.audio.isEmpty()) return emptyList()
        Silence.trim(silenceDir)
        return project.audio.groupBy { it.lane }.toSortedMap().values.mapNotNull { laneClips ->
            val items = ArrayList<EditedMediaItem>()
            var cursor = 0L
            for (a in laneClips.sortedBy { it.startUs }) {
                if (a.startUs >= totalUs) break
                val start = maxOf(a.startUs, cursor)
                val visibleDur = min(a.endUs, totalUs) - start
                if (visibleDur < MIN_SEGMENT_US) continue
                if (start - cursor >= MIN_SEGMENT_US) items += silenceItem(start - cursor)
                items += audioItem(a, start, visibleDur, live, sync)
                cursor = start + visibleDur
            }
            if (items.isEmpty()) {
                null
            } else {
                if (totalUs - cursor >= MIN_SEGMENT_US) items += silenceItem(totalUs - cursor)
                EditedMediaItemSequence(items)
            }
        }
    }

    private fun audioItem(a: AudioClip, timelineStartUs: Long, durationUs: Long, live: LiveProject, sync: PlaybackSync): EditedMediaItem {
        // When an earlier clip on the lane overlaps, skip the hidden head of this one.
        val skippedUs = timelineStartUs - a.startUs
        val trimStart = a.trimStartUs + skippedUs
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(a.uri))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(trimStart / 1000)
                    .setEndPositionMs((trimStart + durationUs) / 1000)
                    .build(),
            )
            .build()
        val clock = AudioClock(timelineStartUs, durationUs, sync)
        val gain = GainAudioProcessor({ t ->
            val c = live.audio(a.id) ?: a
            GainEnvelope.gainAt(t + skippedUs, c.durationUs, c.volume, c.muted, c.fadeInUs, c.fadeOutUs)
        }, clock)
        return EditedMediaItem.Builder(mediaItem)
            .setRemoveVideo(true)
            .setEffects(Effects(listOf<AudioProcessor>(gain), emptyList()))
            .build()
    }

    private fun silenceItem(durationUs: Long): EditedMediaItem {
        val f = Silence.file(silenceDir, durationUs)
        return EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(f))).build()
    }

    private class ConstantSpeed(private val speed: Float) : SpeedProvider {
        override fun getSpeed(timeUs: Long): Float = speed
        override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
    }

    companion object {
        private const val MIN_SEGMENT_US = 20_000L
    }
}
