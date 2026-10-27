package com.vanta.studio.domain.fx

import com.vanta.studio.domain.model.FxEvent
import com.vanta.studio.domain.model.FxType
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.timeline.Timeline

/** Pure edits of a project's light/effect events and manual beat markers. */
object FxOps {
    const val MIN_DURATION_US = 60_000L
    const val MARKER_TOLERANCE_US = 120_000L

    private fun sorted(l: List<FxEvent>) = l.sortedBy { it.startUs }

    fun add(p: Project, e: FxEvent): Project = p.copy(fx = sorted(p.fx + e))

    fun addAt(p: Project, id: String, type: FxType, atUs: Long): Project {
        val d = FxLibrary.def(type)
        // Place so the peak of the effect lands on atUs.
        val start = (atUs - (d.defaultDurationUs * d.defaultPeak).toLong()).coerceAtLeast(0)
        return add(p, FxLibrary.create(id, type, start))
    }

    fun update(p: Project, id: String, f: (FxEvent) -> FxEvent): Project =
        p.copy(fx = sorted(p.fx.map { if (it.id == id) clamp(f(it)) else it }))

    private fun clamp(e: FxEvent) = e.copy(
        startUs = e.startUs.coerceAtLeast(0),
        durationUs = e.durationUs.coerceAtLeast(MIN_DURATION_US),
        intensity = e.intensity.coerceIn(0f, 2f),
        peak = e.peak.coerceIn(0.02f, 0.9f),
    )

    fun delete(p: Project, id: String): Project = p.copy(fx = p.fx.filterNot { it.id == id })

    fun duplicate(p: Project, id: String, newId: String): Project {
        val e = p.fx.firstOrNull { it.id == id } ?: return p
        return add(p, e.copy(id = newId, startUs = e.endUs))
    }

    /** Moves an event so its sync point (the beat-aligned moment) lands on [syncUs]. */
    fun moveSyncTo(p: Project, id: String, syncUs: Long): Project = update(p, id) {
        it.copy(startUs = syncUs - (it.durationUs * it.peak).toLong())
    }

    /** Sets where inside the event the beat-aligned moment sits, keeping that moment in place. */
    fun setPeak(p: Project, id: String, peak: Float): Project = update(p, id) {
        val sync = it.syncUs
        val np = peak.coerceIn(0.02f, 0.9f)
        it.copy(peak = np, startUs = sync - (it.durationUs * np).toLong())
    }

    fun removeAuto(p: Project): Project = p.copy(fx = p.fx.filterNot { it.id.startsWith(FxAutoSync.AUTO_PREFIX) })

    /** Replaces the auto-placed events by [events], keeping everything the user placed or edited by hand. */
    fun replaceAuto(p: Project, events: List<FxEvent>): Project = p.copy(fx = sorted(removeAuto(p).fx + events))

    /** Adds a marker at [timeUs], or removes the one already within tolerance. */
    fun toggleMarker(p: Project, timeUs: Long): Project {
        val total = Timeline.totalDurationUs(p)
        val near = p.markers.firstOrNull { kotlin.math.abs(it - timeUs) <= MARKER_TOLERANCE_US }
        return if (near != null) p.copy(markers = p.markers - near)
        else p.copy(markers = (p.markers + timeUs.coerceIn(0, maxOf(total, 0))).sorted())
    }

    fun moveMarker(p: Project, from: Long, to: Long): Project =
        p.copy(markers = (p.markers - from + to.coerceAtLeast(0)).distinct().sorted())
}
