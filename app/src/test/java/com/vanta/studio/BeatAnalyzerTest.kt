package com.vanta.studio

import com.vanta.studio.domain.audio.BeatAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BeatAnalyzerTest {
    private val sr = BeatAnalyzer.TARGET_RATE

    private fun analyze(s: SynthAudio) = BeatAnalyzer.analyze(s.pcm, sr, "test://synth")

    private fun hitRate(found: List<Long>, expectedSec: List<Float>, tolUs: Long): Float {
        val hits = expectedSec.count { e -> found.any { abs(it - (e * 1_000_000f).toLong()) <= tolUs } }
        return hits.toFloat() / expectedSec.size
    }

    @Test
    fun detectsTempoOfSteadyLoop() {
        val s = SynthAudio(sr, 16f)
        s.drumLoop(120f, 0.25f, 16f)
        val m = analyze(s)
        assertEquals("bpm ${m.bpm}", 120f, m.bpm, 4f)
        assertTrue("confidence ${m.tempoConfidence}", m.tempoConfidence > 0.1f)
    }

    @Test
    fun detectsOtherTempo() {
        val s = SynthAudio(sr, 16f)
        s.drumLoop(96f, 0.3f, 16f)
        val m = analyze(s)
        assertEquals("bpm ${m.bpm}", 96f, m.bpm, 4f)
    }

    @Test
    fun onsetsLandOnTheKicks() {
        val s = SynthAudio(sr, 12f)
        val kicks = s.drumLoop(120f, 0.5f, 12f)
        val m = analyze(s)
        val strong = m.onsets.filter { it.strength > 0.3f }.map { it.timeUs }
        val rate = hitRate(strong, kicks, 35_000)
        assertTrue("only ${(rate * 100).toInt()}% of kicks found, onsets=${m.onsets.size}", rate >= 0.9f)
    }

    @Test
    fun kicksAreStrongerThanHats() {
        val s = SynthAudio(sr, 10f)
        val kicks = s.drumLoop(120f, 0.5f, 10f)
        val m = analyze(s)
        fun strengthNear(t: Float) = m.onsets.filter { abs(it.timeUs - (t * 1e6f).toLong()) < 40_000 }.maxOfOrNull { it.strength } ?: 0f
        val kickAvg = kicks.map { strengthNear(it) }.average()
        val hatAvg = kicks.map { strengthNear(it + 0.25f) }.average()
        assertTrue("kick $kickAvg vs hat $hatAvg", kickAvg > hatAvg * 1.5)
    }

    @Test
    fun beatGridFollowsThePulse() {
        val s = SynthAudio(sr, 16f)
        val kicks = s.drumLoop(120f, 0.5f, 16f)
        val m = analyze(s)
        assertTrue("beats=${m.beats.size}", m.beats.size >= 20)
        val rate = hitRate(m.beats.map { it.timeUs }, kicks.drop(2), 60_000)
        assertTrue("grid matches ${(rate * 100).toInt()}% of kicks", rate >= 0.85f)
    }

    @Test
    fun noPulseInNoise() {
        val s = SynthAudio(sr, 12f)
        val r = java.util.Random(3)
        for (i in s.pcm.indices) s.pcm[i] = (r.nextFloat() * 2f - 1f) * 0.3f
        val m = analyze(s)
        assertTrue("noise should not give a confident tempo, got ${m.bpm} @ ${m.tempoConfidence}", m.tempoConfidence < 0.3f)
        assertTrue("noise onsets with high strength: ${m.onsets.count { it.strength > 0.8f }}", m.onsets.count { it.strength > 0.8f } < 30)
    }

    @Test
    fun findsPauses() {
        val s = SynthAudio(sr, 20f)
        s.drumLoop(120f, 0.5f, 7f)
        s.drumLoop(120f, 10f, 20f)
        val m = analyze(s)
        val p = m.pauses.firstOrNull { it.startUs in 6_000_000..9_000_000 }
        assertTrue("pauses=${m.pauses}", p != null)
        assertTrue("pause too short: ${p!!.durationUs}", p.durationUs > 2_000_000)
    }

    @Test
    fun energyReflectsLoudness() {
        val s = SynthAudio(sr, 20f)
        s.drumLoop(120f, 0.5f, 10f, kickAmp = 0.15f)
        s.drumLoop(120f, 10f, 20f, kickAmp = 0.9f)
        val m = analyze(s)
        val quiet = m.energy.subList(10, 90).average()
        val loud = m.energy.subList(110, 190).average()
        assertTrue("quiet $quiet loud $loud", loud > quiet * 1.5)
        assertTrue(m.peaks.any { it > 10_000_000 })
    }

    @Test
    fun findsSectionBoundary() {
        val s = SynthAudio(sr, 36f)
        s.drumLoop(120f, 0.5f, 12f, kickAmp = 0.12f)
        s.drumLoop(120f, 12f, 24f, kickAmp = 0.9f)
        s.drumLoop(120f, 24f, 36f, kickAmp = 0.12f)
        val m = analyze(s)
        val inner = m.sections.drop(1).map { it.startUs }
        assertTrue("sections=${m.sections}", inner.any { abs(it - 12_000_000L) < 2_500_000 })
        assertTrue("sections=${m.sections}", m.sections.size >= 2)
    }

    @Test
    fun findsTempoChange() {
        val s = SynthAudio(sr, 32f)
        s.drumLoop(100f, 0.3f, 16f)
        s.drumLoop(140f, 16f, 32f)
        val m = analyze(s)
        assertTrue("tempoChanges=${m.tempoChanges} bpm=${m.bpm}", m.tempoChanges.any { it.timeUs in 10_000_000..22_000_000 })
    }

    @Test
    fun shortOrSilentInputIsHandled() {
        val m1 = BeatAnalyzer.analyze(FloatArray(100), sr, "x")
        assertTrue(m1.onsets.isEmpty() && m1.beats.isEmpty())
        val m2 = BeatAnalyzer.analyze(FloatArray(sr * 5), sr, "x")
        assertTrue(m2.onsets.isEmpty())
    }

    @Test
    fun cancellationReturnsEmptyResult() {
        val s = SynthAudio(sr, 8f)
        s.drumLoop(120f, 0.5f, 8f)
        val m = BeatAnalyzer.analyze(s.pcm, sr, "x", isCancelled = { true })
        assertTrue(m.onsets.isEmpty())
    }
}
