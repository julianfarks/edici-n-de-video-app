package com.vanta.studio

import com.vanta.studio.TestData.SEC
import com.vanta.studio.domain.timeline.Snapping
import com.vanta.studio.domain.timeline.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    @Test
    fun totalDurationSumsClips() {
        val p = TestData.project(TestData.video("a", 4), TestData.video("b", 6))
        assertEquals(10 * SEC, Timeline.totalDurationUs(p))
    }

    @Test
    fun speedChangesDuration() {
        val p = TestData.project(TestData.video("a", 10))
        val fast = Timeline.setSpeed(p, "a", 2f)
        assertEquals(5 * SEC, Timeline.totalDurationUs(fast))
        val slow = Timeline.setSpeed(p, "a", 0.5f)
        assertEquals(20 * SEC, Timeline.totalDurationUs(slow))
    }

    @Test
    fun speedIsClamped() {
        val p = TestData.project(TestData.video("a", 10))
        assertEquals(Timeline.MAX_SPEED, Timeline.setSpeed(p, "a", 100f).clips[0].speed, 0f)
        assertEquals(Timeline.MIN_SPEED, Timeline.setSpeed(p, "a", 0f).clips[0].speed, 0f)
    }

    @Test
    fun splitCreatesTwoClipsThatKeepTotalDuration() {
        val p = TestData.project(TestData.video("a", 10))
        val (out, rightId) = Timeline.split(p, 4 * SEC, "b")!!
        assertEquals("b", rightId)
        assertEquals(2, out.clips.size)
        assertEquals(4 * SEC, out.clips[0].durationUs)
        assertEquals(6 * SEC, out.clips[1].durationUs)
        assertEquals(Timeline.totalDurationUs(p), Timeline.totalDurationUs(out))
        assertEquals(4 * SEC, out.clips[1].trimStartUs)
    }

    @Test
    fun splitRespectsSpeed() {
        val p = Timeline.setSpeed(TestData.project(TestData.video("a", 10)), "a", 2f) // 5 s on timeline
        val (out, _) = Timeline.split(p, 2 * SEC, "b")!!
        // 2 s of timeline at 2x == 4 s of source
        assertEquals(4 * SEC, out.clips[0].trimEndUs)
        assertEquals(2 * SEC, out.clips[0].durationUs)
        assertEquals(3 * SEC, out.clips[1].durationUs)
    }

    @Test
    fun splitNearEdgeIsRejected() {
        val p = TestData.project(TestData.video("a", 10))
        assertNull(Timeline.split(p, 10_000, "b"))
        assertNull(Timeline.split(p, 10 * SEC - 10_000, "b"))
    }

    @Test
    fun splitOnEmptyProjectIsNull() {
        assertNull(Timeline.split(TestData.project(), SEC, "x"))
    }

    @Test
    fun trimStartAndEndNeverCrossOrLeaveSource() {
        val p = TestData.project(TestData.video("a", 10))
        val s = Timeline.trimStart(p, "a", 50 * SEC).clips[0]
        assertTrue(s.trimStartUs < s.trimEndUs)
        assertTrue(s.durationUs >= Timeline.MIN_CLIP_US)
        val e = Timeline.trimEnd(p, "a", 99 * SEC).clips[0]
        assertEquals(10 * SEC, e.trimEndUs)
        val e2 = Timeline.trimEnd(p, "a", -5).clips[0]
        assertTrue(e2.durationUs >= Timeline.MIN_CLIP_US)
        val s2 = Timeline.trimStart(p, "a", -5 * SEC).clips[0]
        assertEquals(0L, s2.trimStartUs)
    }

    @Test
    fun stillDurationIsAdjustable() {
        val p = TestData.project(TestData.image("i", 3))
        assertEquals(3 * SEC, p.clips[0].durationUs)
        assertEquals(5 * SEC, Timeline.setStillDuration(p, "i", 5 * SEC).clips[0].durationUs)
        assertEquals(Timeline.MIN_CLIP_US, Timeline.setStillDuration(p, "i", 0).clips[0].durationUs)
    }

    @Test
    fun clipAtFindsClipAndLocalTime() {
        val p = TestData.project(TestData.video("a", 4), TestData.video("b", 6))
        val hit = Timeline.clipAt(p, 5 * SEC)!!
        assertEquals(1, hit.index)
        assertEquals(4 * SEC, hit.startUs)
        assertEquals(SEC, hit.localUs)
        // the very end maps to the last clip
        assertEquals(1, Timeline.clipAt(p, 10 * SEC)!!.index)
        assertNull(Timeline.clipAt(TestData.project(), 0))
    }

    @Test
    fun moveReordersClips() {
        val p = TestData.project(TestData.video("a", 1), TestData.video("b", 2), TestData.video("c", 3))
        val out = Timeline.move(p, 0, 2)
        assertEquals(listOf("b", "c", "a"), out.clips.map { it.id })
        assertEquals(Timeline.totalDurationUs(p), Timeline.totalDurationUs(out))
    }

    @Test
    fun deleteAndDuplicate() {
        val p = TestData.project(TestData.video("a", 2), TestData.video("b", 3))
        val dup = Timeline.duplicateClip(p, "a", "a2")
        assertEquals(listOf("a", "a2", "b"), dup.clips.map { it.id })
        val del = Timeline.deleteClip(dup, "a")
        assertEquals(listOf("a2", "b"), del.clips.map { it.id })
    }

    @Test
    fun clipStartsAreCumulative() {
        val p = TestData.project(TestData.video("a", 2), TestData.video("b", 3), TestData.video("c", 1))
        assertEquals(listOf(0L, 2 * SEC, 5 * SEC), Timeline.clipStarts(p).toList())
    }

    @Test
    fun snappingPicksNearestWithinThreshold() {
        val c = listOf(0L, 1000L, 1100L, 5000L)
        assertEquals(1100L, Snapping.snap(1080, c, 50))
        assertEquals(1000L, Snapping.snap(1040, c, 50))
        assertNull(Snapping.snap(3000, c, 50))
    }

    @Test
    fun frameSnappingRoundsToFrameGrid() {
        val frame = 1_000_000.0 / 30
        val snapped = Snapping.toFrame((frame * 3 + 5).toLong(), 30)
        assertEquals((frame * 3).toLong(), snapped)
    }

    @Test
    fun snapCandidatesIncludeJoinsAndPlayhead() {
        val p = TestData.project(TestData.video("a", 2), TestData.video("b", 3))
        val c = Snapping.candidates(p, 1234)
        assertTrue(c.containsAll(listOf(0L, 1234L, 2 * SEC, 5 * SEC)))
    }

    @Test
    fun splitThenTrimIsConsistent() {
        val p = TestData.project(TestData.video("a", 10))
        val (s, _) = Timeline.split(p, 5 * SEC, "b")!!
        val t = Timeline.trimEnd(s, "b", 8 * SEC)
        assertEquals(8 * SEC, Timeline.totalDurationUs(t))
        assertNotNull(Timeline.indexOf(t, "b").takeIf { it >= 0 })
    }
}
