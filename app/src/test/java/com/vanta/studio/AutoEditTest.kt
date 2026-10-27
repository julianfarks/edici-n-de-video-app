package com.vanta.studio

import com.vanta.studio.TestData.SEC
import com.vanta.studio.domain.edit.AutoEdit
import com.vanta.studio.domain.edit.AutoEditOptions
import com.vanta.studio.domain.edit.EditMode
import com.vanta.studio.domain.fx.FxAutoSync
import com.vanta.studio.domain.model.AudioClip
import com.vanta.studio.domain.model.Beat
import com.vanta.studio.domain.model.BeatMap
import com.vanta.studio.domain.model.BeatSource
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.timeline.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AutoEditTest {
    private fun idGen(): () -> String { var n = 0; return { "x${n++}" } }

    /** 120 BPM for 20 s: a pulse every 0.5 s, a strong hit on every 4th, steady loudness. */
    private fun music(lengthSec: Int = 20): BeatMap {
        val beats = (0 until lengthSec * 2).map { Beat(it * 500_000L, if (it % 4 == 0) 1f else 0.5f) }
        return BeatMap(
            "uri", lengthSec * SEC, 120f, 0.9f,
            onsets = beats.filter { it.strength > 0.7f }, beats = beats,
            energy = List(lengthSec * 10) { 0.8f },
            sections = emptyList(), pauses = emptyList(), peaks = emptyList(), tempoChanges = emptyList(),
        )
    }

    private fun withMusic(): Project {
        val audio = AudioClip("a1", "file:///m.mp3", "m", 20 * SEC, 0, 0, 20 * SEC)
        return TestData.project(TestData.video("v1", 30), TestData.video("v2", 30)).copy(
            audio = listOf(audio),
            beat = BeatSource("a1", false, music()),
        )
    }

    private fun avgSegment(p: Project, mode: EditMode): Double {
        val plan = AutoEdit.plan(p, AutoEditOptions(mode = mode), idGen())!!
        return plan.segments.map { it.outEndUs - it.outStartUs }.average()
    }

    @Test
    fun cutsLandOnBeatsAndCoverTheMusic() {
        val p = withMusic()
        val plan = AutoEdit.plan(p, AutoEditOptions(mode = EditMode.DYNAMIC), idGen())!!
        assertTrue(plan.resamples)
        assertEquals(20 * SEC, plan.endUs)
        assertEquals(20 * SEC, plan.segments.sumOf { it.outEndUs - it.outStartUs })
        assertTrue(plan.cuts.isNotEmpty())
        plan.cuts.forEach { c -> assertEquals("cut $c not on a half-second pulse", 0L, c % 500_000L) }
    }

    @Test
    fun fasterModesCutMoreOften() {
        val p = withMusic()
        val soft = avgSegment(p, EditMode.SOFT)
        val cine = avgSegment(p, EditMode.CINEMATIC)
        val dyn = avgSegment(p, EditMode.DYNAMIC)
        val intense = avgSegment(p, EditMode.INTENSE)
        val fast = avgSegment(p, EditMode.VERY_FAST)
        assertTrue("soft=$soft cine=$cine dyn=$dyn intense=$intense fast=$fast", soft > cine && cine > dyn && dyn > intense && intense >= fast)
    }

    @Test
    fun applyingGivesEditableClipsOfTheRightLength() {
        val p = withMusic()
        val o = AutoEditOptions(mode = EditMode.DYNAMIC)
        val plan = AutoEdit.plan(p, o, idGen())!!
        val q = AutoEdit.apply(p, plan, o, idGen())
        assertEquals(20 * SEC, Timeline.totalDurationUs(q))
        assertTrue(q.clips.size > p.clips.size)
        q.clips.forEach { assertTrue("clip too short ${it.durationUs}", it.durationUs >= Timeline.MIN_CLIP_US) }
        // original footage is sampled across both sources, not just the first seconds
        assertTrue(q.clips.any { it.uri.endsWith("v2.mp4") })
        assertTrue(q.clips.any { it.uri.endsWith("v1.mp4") })
        // the original project object is untouched (so undo can restore it)
        assertEquals(2, p.clips.size)
        // original sound is muted when music is separate
        assertTrue(q.clips.all { it.muted })
        // light accents are auto events on the cuts
        assertTrue(q.fx.isNotEmpty())
        assertTrue(q.fx.all { it.id.startsWith(FxAutoSync.AUTO_PREFIX) })
    }

    @Test
    fun anotherVersionDiffersAndStartsFromTheSameOriginal() {
        val p = withMusic()
        val a = AutoEdit.plan(p, AutoEditOptions(variant = 0), idGen())!!
        val b = AutoEdit.plan(p, AutoEditOptions(variant = 1), idGen())!!
        val sa = a.segments.map { it.pieces.first().srcStartUs }
        val sb = b.segments.map { it.pieces.first().srcStartUs }
        assertTrue(a.cuts != b.cuts || sa != sb)
        // same project in, same project out: planning has no side effects
        assertEquals(a, AutoEdit.plan(p, AutoEditOptions(variant = 0), idGen()))
    }

    @Test
    fun safeModeKeepsFlashesBelowThreeAPerSecond() {
        val p = withMusic()
        val o = AutoEditOptions(mode = EditMode.VERY_FAST, safeMode = true)
        val plan = AutoEdit.plan(p, o, idGen())!!
        val q = AutoEdit.apply(p, plan, o, idGen())
        assertEquals(0, FxAutoSync.flickerWarnings(q.fx))
        assertTrue(q.fx.filter { it.type.name.startsWith("FLASH") }.all { it.intensity <= FxAutoSync.SAFE_FLASH_MAX_INTENSITY + 1e-4f })
    }

    @Test
    fun noAnalysisMeansNoPlan() {
        val p = TestData.project(TestData.video("v1", 10))
        assertEquals(null, AutoEdit.plan(p, AutoEditOptions(), idGen()))
    }

    @Test
    fun ownSoundtrackIsCutInPlaceWithoutReordering() {
        val clip = TestData.video("v1", 20)
        val p = TestData.project(clip).copy(beat = BeatSource("v1", true, music()))
        val o = AutoEditOptions(mode = EditMode.DYNAMIC)
        val plan = AutoEdit.plan(p, o, idGen())!!
        assertTrue(!plan.resamples)
        val q = AutoEdit.apply(p, plan, o, idGen())
        assertEquals(20 * SEC, Timeline.totalDurationUs(q))
        // consecutive pieces are contiguous in the source: the sound stays continuous
        q.clips.zipWithNext().forEach { (x, y) -> assertEquals(x.trimEndUs, y.trimStartUs) }
        assertTrue(q.clips.none { it.muted })
    }
}
