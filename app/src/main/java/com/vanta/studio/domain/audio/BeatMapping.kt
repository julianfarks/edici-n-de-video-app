package com.vanta.studio.domain.audio

import com.vanta.studio.domain.model.Beat
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.TimeRange

/**
 * Maps the source-relative times of a [com.vanta.studio.domain.model.BeatMap] onto the project
 * timeline, following the clip it was analysed from (placement, trim and speed).
 */
object BeatMapping {
    /** True if [c] plays the media the rhythm was analysed from. */
    private fun plays(c: com.vanta.studio.domain.model.Clip, b: com.vanta.studio.domain.model.BeatSource): Boolean =
        c.id == b.itemId || c.uri == b.map.sourceUri

    /**
     * Every timeline time at which source time [srcUs] is heard. A clip that was split or cut into
     * several pieces (all playing the same media) keeps its beats, and repeated pieces repeat them.
     */
    fun toTimelineAll(project: Project, srcUs: Long): List<Long> {
        val b = project.beat ?: return emptyList()
        if (b.isClip) {
            val out = ArrayList<Long>()
            var start = 0L
            for (c in project.clips) {
                if (plays(c, b) && srcUs >= c.trimStartUs && srcUs <= c.trimEndUs) {
                    out += start + ((srcUs - c.trimStartUs) / c.speed.toDouble()).toLong()
                }
                start += c.durationUs
            }
            return out
        }
        val a = project.audio.firstOrNull { it.id == b.itemId } ?: return emptyList()
        if (srcUs < a.trimStartUs || srcUs > a.trimEndUs) return emptyList()
        return listOf(a.startUs + (srcUs - a.trimStartUs))
    }

    /** First timeline time of a source time, or null when it falls outside the used part. */
    fun toTimeline(project: Project, srcUs: Long): Long? = toTimelineAll(project, srcUs).firstOrNull()

    private fun mapBeats(project: Project, list: List<Beat>, minStrength: Float): List<Beat> =
        list.flatMap { beat ->
            if (beat.strength < minStrength) emptyList()
            else toTimelineAll(project, beat.timeUs).map { Beat(it, beat.strength) }
        }.sortedBy { it.timeUs }

    /** Transients (drum hits, accents) with at least [minStrength], in timeline time. */
    fun onsets(project: Project, minStrength: Float = 0f): List<Beat> {
        val m = project.beat?.map ?: return emptyList()
        return mapBeats(project, m.onsets, minStrength)
    }

    /** The tracked pulse grid in timeline time. */
    fun pulses(project: Project): List<Beat> {
        val m = project.beat?.map ?: return emptyList()
        return mapBeats(project, m.beats, 0f)
    }

    fun peaks(project: Project): List<Long> {
        val m = project.beat?.map ?: return emptyList()
        return m.peaks.flatMap { toTimelineAll(project, it) }.sorted()
    }

    fun pauses(project: Project): List<TimeRange> {
        val m = project.beat?.map ?: return emptyList()
        return m.pauses.mapNotNull { r ->
            val a = toTimeline(project, r.startUs) ?: return@mapNotNull null
            val b = toTimeline(project, r.endUs) ?: return@mapNotNull null
            TimeRange(a, b)
        }
    }

    /** Boundaries between musical sections in timeline time (excluding the very start/end). */
    fun sectionStarts(project: Project): List<Long> {
        val m = project.beat?.map ?: return emptyList()
        return m.sections.drop(1).flatMap { toTimelineAll(project, it.startUs) }.sorted()
    }

    /** Source time of the analysed media for a timeline time, or null when outside the used part. */
    fun toSource(project: Project, timelineUs: Long): Long? {
        val b = project.beat ?: return null
        if (b.isClip) {
            var start = 0L
            for (c in project.clips) {
                val local = timelineUs - start
                if (plays(c, b) && local >= 0 && local <= c.durationUs) return c.trimStartUs + (local * c.speed).toLong()
                start += c.durationUs
            }
            return null
        }
        val a = project.audio.firstOrNull { it.id == b.itemId } ?: return null
        val local = timelineUs - a.startUs
        if (local < 0 || local > a.durationUs) return null
        return a.trimStartUs + local
    }

    /** Loudness (0..1) of the analysed audio around a timeline time. */
    fun energyAt(project: Project, timelineUs: Long): Float {
        val m = project.beat?.map ?: return 0.5f
        val src = toSource(project, timelineUs) ?: return 0.5f
        if (m.energy.isEmpty()) return 0.5f
        val i = (src / com.vanta.studio.domain.model.BeatMap.ENERGY_STEP_US).toInt().coerceIn(0, m.energy.lastIndex)
        return m.energy[i].coerceIn(0f, 1f)
    }

    /** Timeline time at which the analysed audio ends, if it is a music clip. */
    fun musicEndUs(project: Project): Long? {
        val b = project.beat ?: return null
        if (b.isClip) return null
        return project.audio.firstOrNull { it.id == b.itemId }?.endUs
    }
}
