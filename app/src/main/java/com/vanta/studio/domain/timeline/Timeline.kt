package com.vanta.studio.domain.timeline

import com.vanta.studio.domain.anim.ClipTransitions
import com.vanta.studio.domain.anim.Keyframes
import com.vanta.studio.domain.anim.toPose
import com.vanta.studio.domain.model.AudioClip
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.OverlayLayer
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.TextLayer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Editing operations on a [Project]. Every function is pure: it returns a new project and
 * never mutates its input, so callers can keep the previous value for undo.
 *
 * The main track is a contiguous sequence (like a magnetic timeline): a clip starts where
 * the previous one ends. Text, overlay and audio items are positioned freely in time.
 */
object Timeline {
    /** Shortest clip the editor allows (0.1 s). */
    const val MIN_CLIP_US = 100_000L
    const val MAX_STILL_US = 10 * 60 * 1_000_000L
    const val MIN_SPEED = 0.1f
    const val MAX_SPEED = 8f

    fun totalDurationUs(p: Project): Long = p.clips.sumOf { it.durationUs }

    /** Start time of every clip on the main track. */
    fun clipStarts(p: Project): LongArray {
        val out = LongArray(p.clips.size)
        var t = 0L
        p.clips.forEachIndexed { i, c ->
            out[i] = t
            t += c.durationUs
        }
        return out
    }

    fun clipStartUs(p: Project, index: Int): Long {
        var t = 0L
        for (i in 0 until index.coerceAtMost(p.clips.size)) t += p.clips[i].durationUs
        return t
    }

    fun indexOf(p: Project, clipId: String): Int = p.clips.indexOfFirst { it.id == clipId }

    data class ClipHit(val index: Int, val clip: Clip, val startUs: Long, val localUs: Long)

    /** The clip under [timeUs]; the end of the timeline maps to the last clip. */
    fun clipAt(p: Project, timeUs: Long): ClipHit? {
        if (p.clips.isEmpty()) return null
        var start = 0L
        p.clips.forEachIndexed { i, c ->
            val end = start + c.durationUs
            if (timeUs < end || i == p.clips.lastIndex) {
                return ClipHit(i, c, start, (timeUs - start).coerceIn(0, c.durationUs))
            }
            start = end
        }
        return null
    }

    fun updateClip(p: Project, clipId: String, f: (Clip) -> Clip): Project =
        p.copy(clips = p.clips.map { if (it.id == clipId) f(it) else it })

    fun insertClips(p: Project, index: Int, clips: List<Clip>): Project {
        val list = p.clips.toMutableList()
        list.addAll(index.coerceIn(0, list.size), clips)
        return p.copy(clips = list)
    }

    /**
     * Splits the clip under [timeUs]. Returns the new project and the id of the right-hand
     * part, or null when the split point is too close to an edge.
     */
    fun split(p: Project, timeUs: Long, newId: String): Pair<Project, String>? {
        val hit = clipAt(p, timeUs) ?: return null
        val c = hit.clip
        if (hit.localUs < MIN_CLIP_US || c.durationUs - hit.localUs < MIN_CLIP_US) return null
        val splitSource = c.localToSourceUs(hit.localUs)
        val (leftKeys, rightKeys) = Keyframes.split(c.keyframes, hit.localUs, c.transform.toPose())
        val left = c.copy(trimEndUs = splitSource, keyframes = leftKeys, fadeOutUs = 0)
        val right = c.copy(
            id = newId,
            trimStartUs = splitSource,
            keyframes = rightKeys,
            transitionIn = null,
            fadeInUs = 0,
        )
        val list = p.clips.toMutableList()
        list[hit.index] = left
        list.add(hit.index + 1, right)
        return p.copy(clips = list) to newId
    }

    /** Moves the in point of a clip. For stills, the duration shrinks/grows instead. */
    fun trimStart(p: Project, clipId: String, newTrimStartUs: Long): Project = updateClip(p, clipId) { c ->
        if (c.isStill) {
            val delta = newTrimStartUs - c.trimStartUs
            val dur = (c.trimEndUs - c.trimStartUs - delta).coerceIn(MIN_CLIP_US, MAX_STILL_US)
            c.copy(trimStartUs = 0, trimEndUs = dur, keyframes = shiftKeys(c, delta))
        } else {
            val maxStart = c.trimEndUs - (MIN_CLIP_US * c.speed).toLong()
            val start = newTrimStartUs.coerceIn(0, max(0, maxStart))
            val shiftLocal = ((start - c.trimStartUs) / c.speed.toDouble()).toLong()
            c.copy(trimStartUs = start, keyframes = shiftKeys(c, shiftLocal))
        }
    }

    fun trimEnd(p: Project, clipId: String, newTrimEndUs: Long): Project = updateClip(p, clipId) { c ->
        if (c.isStill) {
            c.copy(trimStartUs = 0, trimEndUs = (newTrimEndUs - c.trimStartUs).coerceIn(MIN_CLIP_US, MAX_STILL_US))
        } else {
            val minEnd = c.trimStartUs + (MIN_CLIP_US * c.speed).toLong()
            c.copy(trimEndUs = newTrimEndUs.coerceIn(minEnd, c.sourceDurationUs))
        }
    }

    private fun shiftKeys(c: Clip, shiftLocalUs: Long) =
        c.keyframes.map { it.copy(timeUs = it.timeUs - shiftLocalUs) }.filter { it.timeUs >= 0 }

    fun setStillDuration(p: Project, clipId: String, durationUs: Long): Project = updateClip(p, clipId) { c ->
        if (!c.isStill) c else c.copy(trimStartUs = 0, trimEndUs = durationUs.coerceIn(MIN_CLIP_US, MAX_STILL_US))
    }

    /** Changes speed and rescales keyframe times so animations keep their relative position. */
    fun setSpeed(p: Project, clipId: String, speed: Float): Project = updateClip(p, clipId) { c ->
        val s = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        val factor = c.speed / s
        c.copy(speed = s, keyframes = c.keyframes.map { it.copy(timeUs = (it.timeUs * factor).roundToLong()) })
    }

    fun move(p: Project, from: Int, to: Int): Project {
        if (from !in p.clips.indices) return p
        val list = p.clips.toMutableList()
        val c = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), c)
        return p.copy(clips = list)
    }

    fun deleteClip(p: Project, clipId: String): Project = p.copy(clips = p.clips.filterNot { it.id == clipId })

    fun duplicateClip(p: Project, clipId: String, newId: String): Project {
        val i = indexOf(p, clipId)
        if (i < 0) return p
        val list = p.clips.toMutableList()
        list.add(i + 1, list[i].copy(id = newId, transitionIn = null))
        return p.copy(clips = list)
    }

    /** Splits at [timeUs] (when possible) and inserts [still] there: a freeze frame. */
    fun insertFreeze(p: Project, timeUs: Long, still: Clip, splitId: String): Project {
        val hit = clipAt(p, timeUs) ?: return p
        val split = split(p, timeUs, splitId)
        return if (split != null) {
            insertClips(split.first, hit.index + 1, listOf(still))
        } else {
            val atEnd = hit.localUs >= hit.clip.durationUs / 2
            insertClips(p, if (atEnd) hit.index + 1 else hit.index, listOf(still))
        }
    }

    /** Transition windows per clip: each join is shared half/half between the two clips. */
    fun transitions(p: Project): List<ClipTransitions> = p.clips.mapIndexed { i, c ->
        val incoming = c.transitionIn
        val inUs = when {
            incoming == null -> 0L
            i == 0 -> min(incoming.durationUs, c.durationUs / 2)
            else -> min(incoming.durationUs / 2, c.durationUs / 2)
        }
        val next = p.clips.getOrNull(i + 1)
        val outRef = if (next != null) next.transitionIn else p.outro
        val outUs = when {
            outRef == null -> 0L
            next == null -> min(outRef.durationUs, c.durationUs / 2)
            else -> min(outRef.durationUs / 2, c.durationUs / 2)
        }
        ClipTransitions(incoming?.type, inUs, outRef?.type, outUs)
    }

    // ---------------------------------------------------------------- Layers

    fun nextZ(p: Project): Int = ((p.texts.map { it.z } + p.overlays.map { it.z }).maxOrNull() ?: 0) + 1

    fun addText(p: Project, layer: TextLayer): Project = p.copy(texts = p.texts + layer.copy(z = nextZ(p)))

    fun updateText(p: Project, id: String, f: (TextLayer) -> TextLayer): Project =
        p.copy(texts = p.texts.map { if (it.id == id) f(it) else it })

    fun addOverlay(p: Project, layer: OverlayLayer): Project = p.copy(overlays = p.overlays + layer.copy(z = nextZ(p)))

    fun updateOverlay(p: Project, id: String, f: (OverlayLayer) -> OverlayLayer): Project =
        p.copy(overlays = p.overlays.map { if (it.id == id) f(it) else it })

    fun deleteLayer(p: Project, id: String): Project =
        p.copy(texts = p.texts.filterNot { it.id == id }, overlays = p.overlays.filterNot { it.id == id })

    fun duplicateLayer(p: Project, id: String, newId: String): Project {
        val z = nextZ(p)
        p.texts.firstOrNull { it.id == id }?.let { t ->
            return p.copy(texts = p.texts + t.copy(id = newId, z = z, startUs = t.endUs))
        }
        p.overlays.firstOrNull { it.id == id }?.let { o ->
            return p.copy(overlays = p.overlays + o.copy(id = newId, z = z, startUs = o.endUs))
        }
        return p
    }

    /** Moves a layer one step up (towards the viewer) or down in the stacking order. */
    fun moveLayerZ(p: Project, id: String, up: Boolean): Project {
        val order = (p.texts.map { it.id to it.z } + p.overlays.map { it.id to it.z })
            .sortedBy { it.second }.map { it.first }.toMutableList()
        val i = order.indexOf(id)
        if (i < 0) return p
        val j = if (up) i + 1 else i - 1
        if (j !in order.indices) return p
        order[i] = order[j].also { order[j] = order[i] }
        val zOf = order.withIndex().associate { it.value to it.index + 1 }
        return p.copy(
            texts = p.texts.map { it.copy(z = zOf.getValue(it.id)) },
            overlays = p.overlays.map { it.copy(z = zOf.getValue(it.id)) },
        )
    }

    /** Moves a layer in time, keeping it on the timeline. */
    fun moveLayer(p: Project, id: String, newStartUs: Long): Project {
        val s = max(0, newStartUs)
        return p.copy(
            texts = p.texts.map { if (it.id == id) it.copy(startUs = s) else it },
            overlays = p.overlays.map { if (it.id == id) it.copy(startUs = s) else it },
        )
    }

    /** Changes the start (keeping the end) or the end of a layer. */
    fun trimLayer(p: Project, id: String, newStartUs: Long?, newEndUs: Long?): Project {
        fun range(start: Long, end: Long): Pair<Long, Long> {
            var s = newStartUs ?: start
            var e = max(newEndUs ?: end, MIN_CLIP_US)
            s = s.coerceIn(0, e - MIN_CLIP_US)
            e = max(e, s + MIN_CLIP_US)
            return s to e
        }
        return p.copy(
            texts = p.texts.map {
                if (it.id != id) it else range(it.startUs, it.endUs).let { (s, e) -> it.copy(startUs = s, durationUs = e - s) }
            },
            overlays = p.overlays.map {
                if (it.id != id) it else range(it.startUs, it.endUs).let { (s, e) -> it.copy(startUs = s, durationUs = e - s) }
            },
        )
    }

    // ---------------------------------------------------------------- Audio

    /** First lane where [clip] fits without overlapping, or null if all lanes are busy. */
    fun freeLane(p: Project, startUs: Long, endUs: Long, ignoreId: String? = null, preferred: Int = 0): Int? {
        val lanes = listOf(preferred) + (0 until AudioClip.MAX_LANES).filter { it != preferred }
        return lanes.firstOrNull { lane ->
            p.audio.none { it.id != ignoreId && it.lane == lane && it.startUs < endUs && startUs < it.endUs }
        }
    }

    fun addAudio(p: Project, clip: AudioClip): Project? {
        val lane = freeLane(p, clip.startUs, clip.endUs) ?: return null
        return p.copy(audio = p.audio + clip.copy(lane = lane))
    }

    fun updateAudio(p: Project, id: String, f: (AudioClip) -> AudioClip): Project =
        p.copy(audio = p.audio.map { if (it.id == id) f(it) else it })

    fun deleteAudio(p: Project, id: String): Project = p.copy(audio = p.audio.filterNot { it.id == id })

    /** Moves an audio clip in time; it changes lane if needed, or stays put if no lane is free. */
    fun moveAudio(p: Project, id: String, newStartUs: Long): Project {
        val c = p.audio.firstOrNull { it.id == id } ?: return p
        val s = max(0, newStartUs)
        val lane = freeLane(p, s, s + c.durationUs, ignoreId = id, preferred = c.lane) ?: return p
        return updateAudio(p, id) { it.copy(startUs = s, lane = lane) }
    }

    fun trimAudio(p: Project, id: String, newStartUs: Long?, newEndUs: Long?): Project {
        val c = p.audio.firstOrNull { it.id == id } ?: return p
        var trimStart = c.trimStartUs
        var start = c.startUs
        var trimEnd = c.trimEndUs
        if (newStartUs != null) {
            val delta = (newStartUs - c.startUs).coerceIn(-c.trimStartUs, c.durationUs - MIN_CLIP_US)
            start = c.startUs + delta
            trimStart = c.trimStartUs + delta
        }
        if (newEndUs != null) {
            trimEnd = (trimStart + (newEndUs - start)).coerceIn(trimStart + MIN_CLIP_US, c.sourceDurationUs)
        }
        val candidate = c.copy(startUs = start, trimStartUs = trimStart, trimEndUs = trimEnd)
        val overlaps = p.audio.any {
            it.id != id && it.lane == c.lane && it.startUs < candidate.endUs && candidate.startUs < it.endUs
        }
        return if (overlaps) p else updateAudio(p, id) { candidate }
    }

    fun splitAudio(p: Project, id: String, timeUs: Long, newId: String): Project {
        val c = p.audio.firstOrNull { it.id == id } ?: return p
        val local = timeUs - c.startUs
        if (local < MIN_CLIP_US || c.durationUs - local < MIN_CLIP_US) return p
        val left = c.copy(trimEndUs = c.trimStartUs + local, fadeOutUs = 0)
        val right = c.copy(id = newId, startUs = timeUs, trimStartUs = c.trimStartUs + local, fadeInUs = 0)
        return p.copy(audio = p.audio.map { if (it.id == id) left else it } + right)
    }

    fun duplicateAudio(p: Project, id: String, newId: String): Project {
        val c = p.audio.firstOrNull { it.id == id } ?: return p
        val copy = c.copy(id = newId, startUs = c.endUs)
        val lane = freeLane(p, copy.startUs, copy.endUs, preferred = c.lane) ?: return p
        return p.copy(audio = p.audio + copy.copy(lane = lane))
    }
}

object Snapping {
    /** Rounds a time to the nearest frame boundary for [fps]. */
    fun toFrame(timeUs: Long, fps: Int): Long {
        if (fps <= 0) return timeUs
        val frameUs = 1_000_000.0 / fps
        return (Math.round(timeUs / frameUs) * frameUs).roundToLong()
    }

    /** Nearest candidate within [thresholdUs], or null. */
    fun snap(timeUs: Long, candidates: Collection<Long>, thresholdUs: Long): Long? {
        var best: Long? = null
        var bestDist = Long.MAX_VALUE
        for (c in candidates) {
            val d = abs(c - timeUs)
            if (d <= thresholdUs && d < bestDist) {
                best = c
                bestDist = d
            }
        }
        return best
    }

    /** Edges worth snapping to: playhead, clip joins and every layer/audio edge. */
    fun candidates(p: Project, playheadUs: Long, excludeId: String? = null): List<Long> {
        val out = ArrayList<Long>()
        out += 0L
        out += playheadUs
        var t = 0L
        for (c in p.clips) {
            t += c.durationUs
            out += t
        }
        p.texts.filter { it.id != excludeId }.forEach { out += it.startUs; out += it.endUs }
        p.overlays.filter { it.id != excludeId }.forEach { out += it.startUs; out += it.endUs }
        p.audio.filter { it.id != excludeId }.forEach { out += it.startUs; out += it.endUs }
        // Beats, manual markers, other effects' sync points and subtitle edges, so things line up with the music.
        out += p.markers
        p.fx.filter { it.id != excludeId }.forEach { out += it.startUs; out += it.endUs; out += it.syncUs }
        p.subtitles?.cues?.filter { it.id != excludeId }?.forEach { out += it.startUs; out += it.endUs }
        if (p.beat != null) {
            out += com.vanta.studio.domain.audio.BeatMapping.pulses(p).map { it.timeUs }
            out += com.vanta.studio.domain.audio.BeatMapping.onsets(p, 0.5f).map { it.timeUs }
        }
        return out
    }
}
