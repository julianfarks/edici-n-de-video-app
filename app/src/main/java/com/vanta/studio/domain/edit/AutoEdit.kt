package com.vanta.studio.domain.edit

import com.vanta.studio.domain.audio.BeatMapping
import com.vanta.studio.domain.fx.FxAutoSync
import com.vanta.studio.domain.fx.FxLibrary
import com.vanta.studio.domain.fx.SyncPoint
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.FxEvent
import com.vanta.studio.domain.model.FxType
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.TransitionRef
import com.vanta.studio.domain.model.TransitionType
import com.vanta.studio.domain.timeline.Timeline
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class EditMode { SOFT, CINEMATIC, DYNAMIC, INTENSE, VERY_FAST }

data class AutoEditOptions(
    val mode: EditMode = EditMode.DYNAMIC,
    /** false = cut on any pulse or hit; true = only on strong hits. */
    val onlyStrong: Boolean = false,
    /** Strength a transient needs to count as strong (the sensitivity control). */
    val hitThreshold: Float = 0.5f,
    /** Different values give different, equally valid versions of the same edit. */
    val variant: Int = 0,
    val withFx: Boolean = true,
    val withTransitions: Boolean = true,
    val safeMode: Boolean = true,
    /** When the music is separate from the footage, silence the footage's own sound. */
    val muteOriginal: Boolean = true,
)

/** One shot of the planned edit: where it plays and which piece of which source it shows. */
data class PlanPiece(val clipId: String, val srcStartUs: Long, val srcEndUs: Long)

data class PlanSegment(val outStartUs: Long, val outEndUs: Long, val pieces: List<PlanPiece>, val zoom: Float, val strongCut: Boolean)

data class AutoEditPlan(
    val segments: List<PlanSegment>,
    val fx: List<FxEvent>,
    /** True when footage was sampled across the whole source (music edit); false when it only cuts in place. */
    val resamples: Boolean,
    val endUs: Long,
) {
    val cuts: List<Long> get() = segments.drop(1).map { it.outStartUs }
}

/**
 * Turns the analysed rhythm into an editable cut of the footage: cut points chosen on pulses and
 * hits (spacing follows the loudness of the music and the chosen mode), plan changes by punching
 * in on alternate shots, and optional light accents on the cuts. The result is ordinary clips and
 * effect events, so everything stays editable and undoable.
 */
object AutoEdit {
    private class Params(val minHigh: Long, val minLow: Long, val zoom: Float)

    private fun params(m: EditMode) = when (m) {
        EditMode.SOFT -> Params(2_500_000, 5_000_000, 1f)
        EditMode.CINEMATIC -> Params(1_800_000, 4_000_000, 1.07f)
        EditMode.DYNAMIC -> Params(900_000, 2_200_000, 1.14f)
        EditMode.INTENSE -> Params(450_000, 1_200_000, 1.2f)
        EditMode.VERY_FAST -> Params(250_000, 600_000, 1.25f)
    }

    private const val MIN_SEGMENT_US = 200_000L

    fun plan(project: Project, o: AutoEditOptions, newId: () -> String): AutoEditPlan? {
        val beat = project.beat ?: return null
        val clips = project.clips
        if (clips.isEmpty()) return null
        val total = Timeline.totalDurationUs(project)
        val musicEnd = BeatMapping.musicEndUs(project)
        // A separate music track defines the length of the edit; footage's own sound keeps the footage length.
        val resamples = !beat.isClip && musicEnd != null
        val end = if (resamples) musicEnd!! else total
        if (end < 2 * MIN_SEGMENT_US) return null

        val strong = BeatMapping.onsets(project, o.hitThreshold)
        val candidates: List<SyncPoint> = (
            strong.map { SyncPoint(it.timeUs, it.strength) } +
                (if (o.onlyStrong) emptyList() else BeatMapping.pulses(project).map { SyncPoint(it.timeUs, it.strength.coerceAtLeast(0.3f)) })
            ).filter { it.timeUs in MIN_SEGMENT_US until end - MIN_SEGMENT_US }
            .sortedBy { it.timeUs }
        val p = params(o.mode)
        val cuts = pickCuts(project, candidates, p, o.variant, end)

        // segments between cuts
        val bounds = listOf(0L) + cuts.map { it.timeUs } + end
        val strongSet = cuts.associate { it.timeUs to (it.strength >= o.hitThreshold) }
        val poolTotal = total
        val ratio = if (resamples && poolTotal > end) poolTotal.toDouble() / end else 1.0
        val poolOffset = if (resamples) (poolTotal * 0.137 * (o.variant % 7)).toLong() else 0L
        val segments = ArrayList<PlanSegment>()
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            val poolStart = if (resamples) ((a * ratio).toLong() + poolOffset) else a
            val pieces = slice(clips, poolStart, b - a, wrap = resamples)
            // plan change: alternate wide and punched-in shots
            val zoom = if ((i + o.variant) % 2 == 1) p.zoom else 1f
            segments += PlanSegment(a, b, pieces, zoom, strongSet[a] == true)
        }
        val fx = if (o.withFx) accents(o, cuts, project, newId) else emptyList()
        return AutoEditPlan(segments, fx, resamples, end)
    }

    private fun pickCuts(project: Project, cand: List<SyncPoint>, p: Params, variant: Int, end: Long): List<SyncPoint> {
        val out = ArrayList<SyncPoint>()
        var last = 0L
        var skip = variant % 3
        var idx = 0
        while (idx < cand.size) {
            val c = cand[idx]
            val e = BeatMapping.energyAt(project, c.timeUs)
            // louder music -> shorter shots
            val minGap = (p.minLow + (p.minHigh - p.minLow) * e).toLong()
            if (c.timeUs - last < minGap) { idx++; continue }
            if (skip > 0 && out.isEmpty()) { skip--; idx++; continue }
            // choose within a short window: the strongest (odd variants) or the first (even variants)
            val windowEnd = c.timeUs + minGap / 2
            var best = c
            var j = idx
            if (variant % 2 == 1) {
                while (j < cand.size && cand[j].timeUs <= windowEnd) {
                    if (cand[j].strength > best.strength) best = cand[j]
                    j++
                }
            }
            if (end - best.timeUs >= MIN_SEGMENT_US * 2) {
                out += best
                last = best.timeUs
            }
            idx = max(j, idx + 1)
            while (idx < cand.size && cand[idx].timeUs <= best.timeUs) idx++
        }
        return out
    }

    /** Pieces covering [length] of timeline time starting at [poolStart] of the concatenated clips. */
    internal fun slice(clips: List<Clip>, poolStart: Long, length: Long, wrap: Boolean): List<PlanPiece> {
        val total = clips.sumOf { it.durationUs }
        if (total <= 0) return emptyList()
        val pieces = ArrayList<PlanPiece>()
        var pos = if (wrap) poolStart.mod(total) else poolStart.coerceIn(0, total)
        var left = length
        var guard = 0
        while (left > 0 && guard++ < 1000) {
            if (pos >= total) {
                if (!wrap) break
                pos = 0
            }
            var start = 0L
            for (c in clips) {
                val d = c.durationUs
                if (pos < start + d) {
                    val local = pos - start
                    val take = min(left, d - local)
                    val s = c.trimStartUs + (local * c.speed).toLong()
                    val e = min(c.trimEndUs, s + (take * c.speed).toLong())
                    if (e > s) pieces += PlanPiece(c.id, s, e)
                    pos += take
                    left -= take
                    break
                }
                start += d
            }
        }
        return pieces
    }

    private fun accents(o: AutoEditOptions, cuts: List<SyncPoint>, project: Project, newId: () -> String): List<FxEvent> {
        val recipe: List<Pair<FxType, Float>> = when (o.mode) {
            EditMode.SOFT -> listOf(FxType.GLOW to 0.5f)
            EditMode.CINEMATIC -> listOf(FxType.BLOOM to 0.5f)
            EditMode.DYNAMIC -> listOf(FxType.FLASH_WHITE to 0.5f, FxType.IMPACT_ZOOM to 0.6f)
            EditMode.INTENSE -> listOf(FxType.FLASH_ZOOM to 0.6f, FxType.CHROMATIC to 0.5f)
            EditMode.VERY_FAST -> listOf(FxType.FLASH_WHITE to 0.45f, FxType.SHAKE to 0.5f)
        }
        val flashing = recipe.any { FxLibrary.def(it.first).isFlashing }
        val gap = if (o.safeMode && flashing) FxAutoSync.SAFE_FLASH_GAP_US else 120_000L
        val pts = FxAutoSync.thin(cuts, gap).let { if (o.mode == EditMode.SOFT || o.mode == EditMode.CINEMATIC) it.filter { p -> p.strength >= o.hitThreshold || it.indexOf(p) % 2 == 0 } else it }
        val out = ArrayList<FxEvent>()
        for (pt in pts) for ((type, inten) in recipe) {
            val d = FxLibrary.def(type)
            var intensity = inten
            if (o.safeMode && d.isFlashing) intensity = min(intensity, FxAutoSync.SAFE_FLASH_MAX_INTENSITY)
            val start = (pt.timeUs - (d.defaultDurationUs * d.defaultPeak).toLong()).coerceAtLeast(0)
            out += FxLibrary.create(FxAutoSync.AUTO_PREFIX + newId(), type, start, intensity)
        }
        return out
    }

    /** Builds the edited project. The original [project] is not modified. */
    fun apply(project: Project, plan: AutoEditPlan, o: AutoEditOptions, newId: () -> String): Project {
        val byId = project.clips.associateBy { it.id }
        val clips = ArrayList<Clip>()
        for (seg in plan.segments) {
            for ((k, piece) in seg.pieces.withIndex()) {
                val src = byId[piece.clipId] ?: continue
                val first = clips.isEmpty()
                clips += src.copy(
                    id = newId(),
                    trimStartUs = piece.srcStartUs,
                    trimEndUs = piece.srcEndUs,
                    keyframes = emptyList(),
                    fadeInUs = 0,
                    fadeOutUs = 0,
                    muted = if (plan.resamples && o.muteOriginal) true else src.muted,
                    transform = src.transform.copy(scale = src.transform.scale * seg.zoom),
                    transitionIn = if (!first && k == 0 && o.withTransitions && (o.mode == EditMode.SOFT || o.mode == EditMode.CINEMATIC)) {
                        TransitionRef(TransitionType.FADE, min(400_000L, (piece.srcEndUs - piece.srcStartUs) / 4))
                    } else {
                        null
                    },
                )
            }
        }
        val base = project.copy(clips = clips)
        val fx = (base.fx.filterNot { it.id.startsWith(FxAutoSync.AUTO_PREFIX) } + plan.fx).sortedBy { it.startUs }
        return base.copy(fx = fx)
    }
}
