package com.vanta.studio.domain.fx

import com.vanta.studio.domain.audio.BeatMapping
import com.vanta.studio.domain.model.FxEvent
import com.vanta.studio.domain.model.FxType
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.timeline.Timeline

enum class SyncTarget { STRONG_HITS, ALL_PULSES, MANUAL_MARKERS, SECTION_CHANGES, ENERGY_PEAKS }

data class AutoSyncOptions(
    val target: SyncTarget = SyncTarget.STRONG_HITS,
    /** Minimum strength (0..1) of a transient to count as a strong hit. */
    val hitThreshold: Float = 0.5f,
    /** Use every Nth target (2 = every other one). */
    val everyNth: Int = 1,
    val intensity: Float = 1f,
    val durationUs: Long? = null,
    val color: Long? = null,
    /** Limits flashing to at most 3 per second and softens flashes. */
    val safeMode: Boolean = true,
    /** Extra minimum spacing between placed events. */
    val minGapUs: Long = 0L,
)

/** A moment on the timeline an effect can be tied to, with how important it is (0..1). */
data class SyncPoint(val timeUs: Long, val strength: Float)

object FxAutoSync {
    /** Flashes may not exceed 3 per second (the usual photosensitivity guideline). */
    const val SAFE_FLASH_GAP_US = 334_000L
    const val SAFE_FLASH_MAX_INTENSITY = 0.6f
    private const val MIN_ANY_GAP_US = 120_000L
    const val AUTO_PREFIX = "auto-"

    fun points(project: Project, o: AutoSyncOptions): List<SyncPoint> {
        val total = Timeline.totalDurationUs(project)
        val raw: List<SyncPoint> = when (o.target) {
            SyncTarget.STRONG_HITS -> BeatMapping.onsets(project, o.hitThreshold).map { SyncPoint(it.timeUs, it.strength) }
            SyncTarget.ALL_PULSES -> BeatMapping.pulses(project).map { SyncPoint(it.timeUs, it.strength) }
            SyncTarget.MANUAL_MARKERS -> project.markers.map { SyncPoint(it, 1f) }
            SyncTarget.SECTION_CHANGES -> BeatMapping.sectionStarts(project).map { SyncPoint(it, 1f) }
            SyncTarget.ENERGY_PEAKS -> BeatMapping.peaks(project).map { SyncPoint(it, 1f) }
        }
        val inRange = raw.filter { it.timeUs in 0 until total }.sortedBy { it.timeUs }
        return if (o.everyNth > 1) inRange.filterIndexed { i, _ -> i % o.everyNth == 0 } else inRange
    }

    /** Keeps the stronger of any two points closer than [gapUs]. */
    fun thin(points: List<SyncPoint>, gapUs: Long): List<SyncPoint> {
        val out = ArrayList<SyncPoint>()
        for (p in points.sortedBy { it.timeUs }) {
            val last = out.lastOrNull()
            if (last != null && p.timeUs - last.timeUs < gapUs) {
                if (p.strength > last.strength) out[out.lastIndex] = p
            } else {
                out += p
            }
        }
        return out
    }

    /**
     * Creates one event per sync point for each of [types] (so a recipe like zoom + flash + blur
     * produces three aligned events per hit). Event ids start with [AUTO_PREFIX].
     */
    fun place(project: Project, types: List<FxType>, o: AutoSyncOptions, newId: () -> String): List<FxEvent> {
        if (types.isEmpty()) return emptyList()
        val flashing = types.any { FxLibrary.def(it).isFlashing }
        val gap = maxOf(
            MIN_ANY_GAP_US,
            o.minGapUs,
            if (o.safeMode && flashing) SAFE_FLASH_GAP_US else 0L,
        )
        val pts = thin(points(project, o), gap)
        val out = ArrayList<FxEvent>()
        for (p in pts) {
            for (t in types) {
                val def = FxLibrary.def(t)
                val dur = (o.durationUs ?: def.defaultDurationUs).coerceAtLeast(60_000L)
                var intensity = o.intensity.coerceIn(0f, 2f)
                if (o.safeMode && def.isFlashing) intensity = minOf(intensity, SAFE_FLASH_MAX_INTENSITY)
                // The event is positioned so that its peak lands exactly on the sync point.
                val start = (p.timeUs - (dur * def.defaultPeak).toLong()).coerceAtLeast(0)
                out += FxLibrary.create(AUTO_PREFIX + newId(), t, start, intensity, dur, o.color ?: def.defaultColor)
            }
        }
        return out
    }

    /**
     * Events that flash more often than the safe rate, for the warning in the UI. Returns the
     * number of one-second windows containing more than three flashing events.
     */
    fun flickerWarnings(events: List<FxEvent>): Int {
        val flashes = events.filter { it.enabled && FxLibrary.def(it.type).isFlashing }.map { it.syncUs }.sorted()
        var count = 0
        var j = 0
        for (i in flashes.indices) {
            while (flashes[i] - flashes[j] >= 1_000_000L) j++
            if (i - j + 1 > 3) count++
        }
        return count
    }
}
