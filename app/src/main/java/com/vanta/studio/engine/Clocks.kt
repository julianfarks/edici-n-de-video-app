package com.vanta.studio.engine

import com.vanta.studio.domain.model.AudioClip
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.Project

/**
 * Shared between the editor and the effects running on the GL / audio threads. Every seek
 * bumps [epoch] and records where playback restarts, which lets each clip work out its own
 * local time without relying on how the media pipeline numbers frame timestamps.
 */
class PlaybackSync {
    @Volatile
    var epoch: Int = 0
        private set

    @Volatile
    var anchorUs: Long = 0
        private set

    @Synchronized
    fun mark(positionUs: Long) {
        anchorUs = positionUs
        epoch++
    }
}

/**
 * Converts the presentation timestamps a video effect receives into the clip's local time.
 *
 * The first frame after a (re)start is matched with the position playback restarted from:
 * the seek target when it falls inside this clip, or the clip start when playback flowed
 * into it. Later frames are measured from that base, so the result is independent of the
 * absolute timestamp offsets used by the player or the exporter.
 */
class ClipClock(
    val startUs: Long,
    val durationUs: Long,
    private val sync: PlaybackSync,
) {
    private var epoch = Int.MIN_VALUE
    private var basePts = 0L
    private var calibrated = false

    @Synchronized
    fun localUs(pts: Long): Long {
        val e = sync.epoch
        if (!calibrated || e != epoch) {
            calibrate(pts, e)
        } else if (pts - basePts < -50_000) {
            // Timestamps jumped backwards without a seek mark (e.g. a restart): start over.
            basePts = pts
        }
        return (pts - basePts).coerceIn(0, durationUs)
    }

    fun compositionUs(pts: Long): Long = startUs + localUs(pts)

    private fun calibrate(pts: Long, e: Int) {
        val startLocal = restartLocalUs(sync.anchorUs, e)
        basePts = pts - startLocal
        calibrated = true
    }

    private fun restartLocalUs(anchor: Long, e: Int): Long {
        val firstTimeForEpoch = e != epoch
        epoch = e
        return if (firstTimeForEpoch && anchor >= startUs && anchor < startUs + durationUs) anchor - startUs else 0
    }
}

/** Same idea for audio processors: called on every flush, returns where the stream restarts. */
class AudioClock(
    private val startUs: Long,
    private val durationUs: Long,
    private val sync: PlaybackSync,
) {
    private var epoch = Int.MIN_VALUE

    @Synchronized
    fun restartLocalUs(): Long {
        val e = sync.epoch
        val anchor = sync.anchorUs
        val changed = e != epoch
        epoch = e
        return if (changed && anchor >= startUs && anchor < startUs + durationUs) anchor - startUs else 0
    }
}

/**
 * The project as seen by the media threads. The editor swaps in a new value after each
 * edit; effects read it on every frame so parameter changes show up without rebuilding the
 * playback pipeline.
 */
class LiveProject(initial: Project) {
    private class State(val project: Project) {
        val clips: Map<String, Clip> = project.clips.associateBy { it.id }
        val audio: Map<String, AudioClip> = project.audio.associateBy { it.id }
    }

    @Volatile
    private var state = State(initial)

    var project: Project
        get() = state.project
        set(value) {
            state = State(value)
        }

    fun clip(id: String): Clip? = state.clips[id]
    fun audio(id: String): AudioClip? = state.audio[id]
}
