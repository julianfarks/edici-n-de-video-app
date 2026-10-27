package com.vanta.studio

import com.vanta.studio.domain.enhance.AudioStats
import com.vanta.studio.domain.enhance.ClipFeatures
import com.vanta.studio.domain.enhance.Enhance
import com.vanta.studio.domain.enhance.FrameAnalyzer
import com.vanta.studio.domain.enhance.Highlights
import com.vanta.studio.domain.enhance.RecKind
import com.vanta.studio.domain.enhance.Why
import com.vanta.studio.domain.model.AspectRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class EnhanceTest {
    private val clip = TestData.video("a", 10)

    /** A picture whose luma follows [f] over x (0..1) with a little noise, grey unless [tint] says otherwise. */
    private fun picture(w: Int = 96, h: Int = 54, tint: Triple<Float, Float, Float> = Triple(1f, 1f, 1f), f: (Float) -> Float): IntArray {
        val rnd = Random(3)
        return IntArray(w * h) { i ->
            val x = (i % w) / (w - 1f)
            val l = (f(x) + (rnd.nextFloat() - 0.5f) * 0.04f).coerceIn(0f, 1f)
            fun ch(k: Float) = (l * k).coerceIn(0f, 1f).times(255).toInt()
            0xFF000000.toInt() or (ch(tint.first) shl 16) or (ch(tint.second) shl 8) or ch(tint.third)
        }
    }

    private fun kinds(argb: IntArray, w: Int = 96, h: Int = 54) =
        Enhance.recommend(FrameAnalyzer.analyze(argb, w, h), null, clip, AspectRatio.R16_9).map { it.kind }

    @Test
    fun aDarkPictureIsAskedToBeLifted() {
        val k = kinds(picture { 0.05f + 0.2f * it })
        assertTrue(k.toString(), RecKind.BRIGHTEN in k)
        assertFalse(RecKind.DARKEN in k)
    }

    @Test
    fun aBrightWashedPictureIsAskedToBeDarkenedAndGainContrast() {
        val k = kinds(picture { 0.72f + 0.2f * it })
        assertTrue(k.toString(), RecKind.DARKEN in k)
        assertTrue(k.toString(), RecKind.CONTRAST in k)
    }

    @Test
    fun aWellExposedPictureIsLeftAlone() {
        val k = kinds(picture(tint = Triple(1f, 0.9f, 0.8f).let { Triple(1f, 0.95f, 0.9f) }) { 0.08f + 0.84f * it })
        assertTrue("nothing should be suggested for a healthy picture: $k", k.none { it == RecKind.BRIGHTEN || it == RecKind.DARKEN || it == RecKind.CONTRAST })
    }

    @Test
    fun aColourCastIsDetectedInTheRightDirection() {
        val warm = kinds(picture(tint = Triple(1f, 0.75f, 0.35f)) { 0.1f + 0.8f * it })
        assertTrue(warm.toString(), RecKind.WARM_CAST in warm)
        val cool = kinds(picture(tint = Triple(0.35f, 0.75f, 1f)) { 0.1f + 0.8f * it })
        assertTrue(cool.toString(), RecKind.COOL_CAST in cool)
    }

    @Test
    fun audioAdviceFollowsTheMeasuredLevel() {
        val quiet = Enhance.recommend(null, AudioStats(-42f, 0.1f), clip, AspectRatio.R16_9).single()
        assertEquals(RecKind.VOLUME_UP, quiet.kind)
        assertTrue(quiet.volume!! > 1f && quiet.volume!! <= 2f)
        val clipped = Enhance.recommend(null, AudioStats(-8f, 1.0f), clip, AspectRatio.R16_9).single()
        assertEquals(RecKind.AUDIO_CLIPPING, clipped.kind)
        assertFalse("clipping cannot be repaired, so it is advice only", clipped.actionable)
        assertTrue(Enhance.recommend(null, AudioStats(-20f, 0.5f), clip, AspectRatio.R16_9).isEmpty())
        val s = FloatArray(16000) { if (it % 2 == 0) 0.01f else -0.01f }
        assertEquals(-40f, FrameAnalyzer.audio(s).rmsDb, 0.5f)
    }

    @Test
    fun applyingChangesOnlyTheTargetClipAndNothingElse() {
        val p = TestData.project(TestData.video("a", 10), TestData.video("b", 10))
        val recs = Enhance.recommend(FrameAnalyzer.analyze(picture { 0.05f + 0.2f * it }, 96, 54), null, p.clips[0], AspectRatio.R16_9)
        val q = Enhance.apply(p, "a", recs.filter { it.actionable })
        assertTrue(q.clips[0].adjust.exposure > 0f)
        assertEquals(p.clips[1], q.clips[1])
        assertEquals(p.canvas, q.canvas)
        assertEquals(p.clips.map { it.durationUs }, q.clips.map { it.durationUs })
        // applying twice stays inside the slider range
        val r = Enhance.apply(Enhance.apply(Enhance.apply(q, "a", recs), "a", recs), "a", recs)
        assertTrue(r.clips[0].adjust.exposure <= 1f)
    }

    private fun features(n: Int, loud: IntRange, moving: IntRange): ClipFeatures {
        val rnd = Random(9)
        return ClipFeatures(
            1_000_000,
            FloatArray(n) { if (it in loud) 0.8f + rnd.nextFloat() * 0.1f else 0.05f + rnd.nextFloat() * 0.03f },
            FloatArray(n) { if (it in moving) 0.5f + rnd.nextFloat() * 0.1f else rnd.nextFloat() * 0.02f },
            FloatArray(n) { if (it in loud) 1f else 0f },
        )
    }

    @Test
    fun highlightsLandOnTheActiveStretchAndExplainWhy() {
        val f = features(120, loud = 40..49, moving = 42..51)
        val picks = Highlights.pick(f, targetUs = 8_000_000)
        assertTrue(picks.isNotEmpty())
        val best = picks.maxByOrNull { it.score }!!
        assertTrue("best pick ${best.startUs} should sit on 40-51 s", best.startUs >= 38_000_000 && best.endUs <= 54_000_000)
        assertTrue(Why.LOUD in best.why)
        picks.zipWithNext().forEach { (a, b) -> assertTrue("overlap", a.endUs <= b.startUs) }
    }

    @Test
    fun aFlatClipHasNoHighlightsInsteadOfInventedOnes() {
        val flat = ClipFeatures(1_000_000, FloatArray(90) { 0.2f }, FloatArray(90) { 0.1f }, FloatArray(90) { 1f })
        assertTrue(Highlights.pick(flat, 20_000_000).isEmpty())
    }

    @Test
    fun theTargetLengthIsRespectedAndTheComplementCoversTheRest() {
        val f = ClipFeatures(
            1_000_000,
            FloatArray(200) { if (it % 40 in 10..19) 0.9f else 0.05f },
            FloatArray(200) { if (it % 40 in 12..21) 0.6f else 0.0f },
            null,
        )
        val picks = Highlights.pick(f, 15_000_000)
        val total = picks.sumOf { it.durationUs }
        assertTrue("picked $total", total in 3_000_000..24_000_000)
        val rest = Highlights.complement(picks, 200_000_000)
        assertEquals(200_000_000L, total + rest.sumOf { it.second - it.first })
    }
}
