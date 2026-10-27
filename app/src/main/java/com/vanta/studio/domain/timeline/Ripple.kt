package com.vanta.studio.domain.timeline

import com.vanta.studio.domain.model.Cue
import com.vanta.studio.domain.model.FxEvent
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.SubWord

/**
 * Removes a stretch of the timeline and closes the gap on every track, so cutting silence out of a
 * talking clip also moves captions, effects, text, music and markers that come after it.
 */
object Ripple {
    /** Maps a timeline time across a removed span [a, a+gap). */
    private fun map(t: Long, a: Long, gap: Long): Long = when {
        t <= a -> t
        t >= a + gap -> t - gap
        else -> a
    }

    /**
     * Removes [aUs, bUs). Slivers shorter than the minimum clip length at the edges are left in
     * place, so the removed amount can be slightly less than requested; the actual amount is
     * available through [removedUs] on the result.
     */
    fun removeSpan(p: Project, aUs: Long, bUs: Long, newId: () -> String): Project {
        if (bUs <= aUs) return p
        val before = Timeline.totalDurationUs(p)
        var q = p
        // 1) main track: cut at both ends, drop what lies inside.
        Timeline.split(q, bUs, newId())?.let { q = it.first }
        Timeline.split(q, aUs, newId())?.let { q = it.first }
        val starts = Timeline.clipStarts(q)
        val eps = Timeline.MIN_CLIP_US
        val kept = q.clips.filterIndexed { i, c ->
            val s = starts[i]
            val e = s + c.durationUs
            !(s >= aUs - eps && e <= bUs + eps)
        }
        q = q.copy(clips = kept)
        val gap = before - Timeline.totalDurationUs(q)
        if (gap <= 0) return p
        val b2 = aUs + gap

        // 2) music/audio: split at the span edges, drop inside, pull later ones back.
        for (a in q.audio.map { it.id }) {
            q = Timeline.splitAudio(q, a, b2, newId())
            q = Timeline.splitAudio(q, a, aUs, newId())
        }
        q = q.copy(
            audio = q.audio.filterNot { it.startUs >= aUs && it.endUs <= b2 }
                .map { if (it.startUs >= b2) it.copy(startUs = it.startUs - gap) else it },
        )

        // 3) layers, effects, captions, markers.
        fun span(s: Long, d: Long): Pair<Long, Long>? {
            val ns = map(s, aUs, gap)
            val ne = map(s + d, aUs, gap)
            return if (ne - ns <= 0) null else ns to (ne - ns)
        }
        q = q.copy(
            texts = q.texts.mapNotNull { t -> span(t.startUs, t.durationUs)?.let { (s, d) -> t.copy(startUs = s, durationUs = d) } },
            overlays = q.overlays.mapNotNull { o -> span(o.startUs, o.durationUs)?.let { (s, d) -> o.copy(startUs = s, durationUs = d) } },
            fx = q.fx.mapNotNull { e -> span(e.startUs, e.durationUs)?.let { (s, d) -> e.copy(startUs = s, durationUs = d) } },
            markers = q.markers.filterNot { it > aUs && it < b2 }.map { map(it, aUs, gap) }.distinct(),
            subtitles = q.subtitles?.let { t ->
                t.copy(
                    cues = t.cues.mapNotNull { c ->
                        val ns = map(c.startUs, aUs, gap)
                        val ne = map(c.endUs, aUs, gap)
                        if (ne - ns < 1_000) null else c.copy(
                            startUs = ns,
                            endUs = ne,
                            words = c.words.map { w -> SubWord(w.text, map(w.startUs, aUs, gap), map(w.endUs, aUs, gap)) },
                        )
                    },
                )
            },
        )
        return q
    }

    /** Total timeline time removed between two states of a project. */
    fun removedUs(before: Project, after: Project) = Timeline.totalDurationUs(before) - Timeline.totalDurationUs(after)

    /** Removes several spans (source order does not matter); later ones first so earlier times stay valid. */
    fun removeSpans(p: Project, spans: List<Pair<Long, Long>>, newId: () -> String): Project =
        spans.sortedByDescending { it.first }.fold(p) { acc, (a, b) -> removeSpan(acc, a, b, newId) }
}
