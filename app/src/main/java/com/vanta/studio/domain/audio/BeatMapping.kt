package com.vanta.studio.domain.audio

import com.vanta.studio.domain.model.Beat
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.TimeRange

/**
 * Maps the source-relative times of a [com.vanta.studio.domain.model.BeatMap] onto the project
 * timeline, following the clip it was analysed from (placement, trim and speed).
 */
object BeatMapping {
    /** Converts a source time to a timeline time, or null when it falls outside the used part. */
    fun toTimeline(project: Project, srcUs: Long): Long? {
        val b = project.beat ?: return null
        if (b.isClip) {
            var start = 0L
            for (c in project.clips) {
                if (c.id == b.itemId) {
                    if (srcUs < c.trimStartUs || srcUs > c.trimEndUs) return null
                    return start + ((srcUs - c.trimStartUs) / c.speed.toDouble()).toLong()
                }
                start += c.durationUs
            }
            return null
        }
        val a = project.audio.firstOrNull { it.id == b.itemId } ?: return null
        if (srcUs < a.trimStartUs || srcUs > a.trimEndUs) return null
        return a.startUs + (srcUs - a.trimStartUs)
    }

    private fun mapBeats(project: Project, list: List<Beat>, minStrength: Float): List<Beat> =
        list.mapNotNull { beat ->
            if (beat.strength < minStrength) return@mapNotNull null
            toTimeline(project, beat.timeUs)?.let { Beat(it, beat.strength) }
        }

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
        return m.peaks.mapNotNull { toTimeline(project, it) }
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
        return m.sections.drop(1).mapNotNull { toTimeline(project, it.startUs) }
    }
}
