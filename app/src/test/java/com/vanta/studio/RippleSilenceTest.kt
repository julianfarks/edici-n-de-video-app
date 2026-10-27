package com.vanta.studio

import com.vanta.studio.TestData.SEC
import com.vanta.studio.domain.audio.SilenceDetector
import com.vanta.studio.domain.model.AudioClip
import com.vanta.studio.domain.model.Cue
import com.vanta.studio.domain.model.FxEvent
import com.vanta.studio.domain.model.FxType
import com.vanta.studio.domain.model.SubWord
import com.vanta.studio.domain.model.SubtitleTrack
import com.vanta.studio.domain.model.TimeRange
import com.vanta.studio.domain.timeline.Ripple
import com.vanta.studio.domain.timeline.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class RippleSilenceTest {
    private val sr = 16_000

    /** Voiced-like bursts (harmonics + amplitude wobble) over a faint noise floor. */
    private fun speech(totalSec: Float, voiced: List<ClosedFloatingPointRange<Float>>): FloatArray {
        val n = (totalSec * sr).toInt()
        val r = java.util.Random(5)
        val x = FloatArray(n) { (r.nextFloat() - 0.5f) * 0.004f }
        for (v in voiced) {
            for (i in (v.start * sr).toInt() until (v.endInclusive * sr).toInt().coerceAtMost(n)) {
                val t = i / sr.toFloat()
                val env = 0.6f + 0.4f * sin(2 * Math.PI * 4 * t).toFloat()
                x[i] += 0.25f * env * (sin(2 * Math.PI * 180 * t) + 0.5 * sin(2 * Math.PI * 360 * t) + 0.3 * sin(2 * Math.PI * 900 * t)).toFloat()
            }
        }
        return x
    }

    @Test
    fun findsLongPauseButNotShortOne() {
        val pcm = speech(8f, listOf(0f..2f, 3.5f..5f, 5.4f..8f))
        val r = SilenceDetector.detect(pcm, sr)
        assertTrue(r.separable)
        assertEquals("cuts=${r.cuts}", 1, r.cuts.size)
        val c = r.cuts[0]
        assertTrue("start ${c.startUs}", abs(c.startUs - 2_150_000) < 120_000)
        assertTrue("end ${c.endUs}", abs(c.endUs - 3_350_000) < 120_000)
    }

    @Test
    fun marginKeepsSilenceAroundTheCut() {
        val pcm = speech(6f, listOf(0f..2f, 4f..6f))
        val tight = SilenceDetector.detect(pcm, sr, o = SilenceDetector.Options(marginUs = 50_000)).cuts.single()
        val loose = SilenceDetector.detect(pcm, sr, o = SilenceDetector.Options(marginUs = 300_000)).cuts.single()
        assertTrue(tight.durationUs > loose.durationUs + 300_000)
    }

    @Test
    fun uniformAudioHasNothingToCut() {
        val r = java.util.Random(1)
        val pcm = FloatArray(sr * 5) { (r.nextFloat() - 0.5f) * 0.3f }
        val res = SilenceDetector.detect(pcm, sr)
        assertTrue(res.cuts.isEmpty())
        assertFalse(res.separable)
    }

    @Test
    fun startOffsetShiftsCuts() {
        val pcm = speech(6f, listOf(0f..2f, 4f..6f))
        val a = SilenceDetector.detect(pcm, sr).cuts.single()
        val b = SilenceDetector.detect(pcm, sr, startUs = 10 * SEC).cuts.single()
        assertEquals(a.startUs + 10 * SEC, b.startUs)
    }

    private fun idGen(): () -> String { var n = 0; return { "n${n++}" } }

    private fun rich() = TestData.project(TestData.video("v", 20)).copy(
        audio = listOf(AudioClip("m", "file:///m.mp3", "m", 40 * SEC, 0, 0, 20 * SEC)),
        fx = listOf(
            FxEvent("f1", FxType.FLASH_WHITE, 2 * SEC, 400_000), // before the cut
            FxEvent("f2", FxType.FLASH_WHITE, 12 * SEC, 400_000), // after the cut
            FxEvent("f3", FxType.FLASH_WHITE, 7_900_000, 400_000), // inside the cut
        ),
        markers = listOf(3 * SEC, 8 * SEC, 15 * SEC),
        subtitles = SubtitleTrack(
            cues = listOf(
                Cue("c1", 1 * SEC, 3 * SEC, "hola", listOf(SubWord("hola", 1 * SEC, 3 * SEC))),
                Cue("c2", 11 * SEC, 13 * SEC, "mundo", listOf(SubWord("mundo", 11 * SEC, 13 * SEC))),
            ),
        ),
    )

    @Test
    fun removeSpanClosesTheGapOnEveryTrack() {
        val p = rich()
        val q = Ripple.removeSpan(p, 6 * SEC, 10 * SEC, idGen())
        assertEquals(16 * SEC, Timeline.totalDurationUs(q))
        // main track stays contiguous and sources are consistent
        assertEquals(20 * SEC - 4 * SEC, q.clips.sumOf { it.durationUs })
        // music: split at both ends, middle removed, tail pulled back
        assertEquals(16 * SEC, q.audio.sumOf { it.durationUs })
        val tail = q.audio.maxByOrNull { it.startUs }!!
        assertEquals(6 * SEC, tail.startUs)
        assertEquals(10 * SEC, tail.trimStartUs) // the music after the cut still plays what was after the cut
        // effects: before untouched, after shifted, inside removed
        assertEquals(2 * SEC, q.fx.first { it.id == "f1" }.startUs)
        assertEquals(8 * SEC, q.fx.first { it.id == "f2" }.startUs)
        assertEquals(3, p.fx.size)
        assertTrue(q.fx.none { it.id == "f3" } || q.fx.first { it.id == "f3" }.durationUs < 400_000)
        // markers
        assertEquals(listOf(3 * SEC, 11 * SEC), q.markers.filter { it != 6 * SEC })
        // captions keep their words consistent
        val c2 = q.subtitles!!.cues.first { it.id == "c2" }
        assertEquals(7 * SEC, c2.startUs)
        assertEquals(7 * SEC, c2.words.first().startUs)
    }

    @Test
    fun removeSpansHandlesSeveralFromTheEnd() {
        val p = TestData.project(TestData.video("v", 30))
        val q = Ripple.removeSpans(p, listOf(5 * SEC to 7 * SEC, 15 * SEC to 18 * SEC), idGen())
        assertEquals(25 * SEC, Timeline.totalDurationUs(q))
        // The first kept source second after the first cut is 7 s.
        val second = q.clips[1]
        assertEquals(7 * SEC, second.trimStartUs)
    }

    @Test
    fun spanOutsideDoesNothing() {
        val p = TestData.project(TestData.video("v", 10))
        val q = Ripple.removeSpan(p, 12 * SEC, 14 * SEC, idGen())
        assertEquals(10 * SEC, Timeline.totalDurationUs(q))
    }
}
