package com.vanta.studio.domain.subtitles

import com.vanta.studio.domain.anim.LayerAnimator
import com.vanta.studio.domain.anim.LayerFrame
import com.vanta.studio.domain.model.Cue
import com.vanta.studio.domain.model.LayerTransform
import com.vanta.studio.domain.model.SubtitleKind
import com.vanta.studio.domain.model.SubtitleStyle
import com.vanta.studio.domain.model.SubtitleTrack
import kotlin.math.cos
import kotlin.math.exp

/**
 * What the subtitle looks like at one instant. Both the preview and the exporter call
 * [SubtitleRender.stateAt], so the burnt-in subtitles match what was previewed.
 */
data class SubState(
    val cue: Cue,
    /** Index of the word being spoken, or -1 between words / for cues without word timings. */
    val activeWord: Int,
    /** Number of words visible (all of them except for the progressive style). */
    val shown: Int,
    val frame: LayerFrame,
)

object SubtitleRender {
    private const val ANIM_US = 220_000L

    fun stateAt(track: SubtitleTrack?, timeUs: Long): SubState? {
        if (track == null || !track.enabled) return null
        val cue = SubtitleOps.cueAt(track, timeUs) ?: return null
        return stateFor(track.style, cue, timeUs)
    }

    fun stateFor(style: SubtitleStyle, cue: Cue, timeUs: Long): SubState? {
        val base = LayerAnimator.evaluate(
            cue.startUs, cue.durationUs, LayerTransform(0.5f, style.positionY), emptyList(),
            style.animIn, style.animOut, ANIM_US, timeUs,
        ) ?: return null
        val words = cue.words
        val active = if (words.isEmpty()) -1 else activeWord(words, timeUs)
        val shown = if (style.kind == SubtitleKind.PROGRESSIVE && words.isNotEmpty()) {
            words.count { it.startUs <= timeUs }.coerceAtLeast(1)
        } else {
            words.size
        }
        var frame = base
        if (active >= 0) {
            val dt = (timeUs - words[active].startUs).coerceAtLeast(0) / 1_000_000f
            when (style.kind) {
                SubtitleKind.BOUNCE -> frame = frame.copy(scale = frame.scale * (1f + 0.22f * exp(-9f * dt) * cos(22f * dt)))
                SubtitleKind.SCALE_SYNC -> frame = frame.copy(scale = frame.scale * (1f + 0.14f * exp(-6f * dt)))
                else -> Unit
            }
        }
        return SubState(cue, active, shown, frame)
    }

    /** The word being spoken: the last one whose start has passed, until a short tail after it ends. */
    fun activeWord(words: List<com.vanta.studio.domain.model.SubWord>, timeUs: Long): Int {
        var idx = -1
        for (i in words.indices) if (words[i].startUs <= timeUs) idx = i else break
        if (idx < 0) return -1
        // keep the last word lit until it has finished, but not forever
        val w = words[idx]
        val next = words.getOrNull(idx + 1)
        return if (timeUs <= w.endUs + 120_000L || next != null) idx else -1
    }

    /** Colours used to draw a cue at a given state; shared by the bitmap renderer. */
    fun colorForWord(style: SubtitleStyle, index: Int, active: Int): Long = when {
        !style.highlightWord || active < 0 -> style.color
        style.kind == SubtitleKind.KARAOKE -> if (index <= active) style.highlightColor else style.color
        style.kind == SubtitleKind.CINEMATIC || style.kind == SubtitleKind.MINIMAL -> style.color
        else -> if (index == active) style.highlightColor else style.color
    }
}
