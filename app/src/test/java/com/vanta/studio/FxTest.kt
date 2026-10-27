package com.vanta.studio

import com.vanta.studio.TestData.SEC
import com.vanta.studio.domain.audio.BeatMapping
import com.vanta.studio.domain.fx.AutoSyncOptions
import com.vanta.studio.domain.fx.FxAutoSync
import com.vanta.studio.domain.fx.FxLibrary
import com.vanta.studio.domain.fx.LightMath
import com.vanta.studio.domain.fx.SyncTarget
import com.vanta.studio.domain.model.AudioClip
import com.vanta.studio.domain.model.Beat
import com.vanta.studio.domain.model.BeatMap
import com.vanta.studio.domain.model.BeatSource
import com.vanta.studio.domain.model.FxEvent
import com.vanta.studio.domain.model.FxType
import com.vanta.studio.domain.model.Project
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FxTest {
    private fun ev(type: FxType, start: Long, dur: Long = 400_000, intensity: Float = 1f, color: Long = 0xFFFFFFFF, peak: Float = 0.1f) =
        FxEvent("e${start}_$type", type, start, dur, intensity, color, peak)

    private fun map(onsets: List<Beat>, beats: List<Beat> = emptyList()) = BeatMap(
        "uri", 60 * SEC, 120f, 0.5f, onsets, beats, emptyList(), emptyList(), emptyList(), listOf(8 * SEC), emptyList(),
    )

    private fun projectWithMusic(onsets: List<Beat>, audioStart: Long = 2 * SEC, markers: List<Long> = emptyList()): Project {
        val audio = AudioClip("a1", "file:///m.mp3", "m", 60 * SEC, audioStart, 0, 60 * SEC)
        return TestData.project(TestData.video("v", 30)).copy(
            audio = listOf(audio),
            beat = BeatSource("a1", false, map(onsets)),
            markers = markers,
        )
    }

    @Test
    fun envelopeRisesToPeakThenDecays() {
        assertEquals(0f, LightMath.envelope(0f, 0.2f), 1e-6f)
        assertEquals(1f, LightMath.envelope(0.2f, 0.2f), 1e-4f)
        assertTrue(LightMath.envelope(0.1f, 0.2f) in 0.01f..0.99f)
        assertTrue(LightMath.envelope(0.5f, 0.2f) < LightMath.envelope(0.3f, 0.2f))
        assertEquals(0f, LightMath.envelope(1f, 0.2f), 1e-6f)
        assertEquals(0f, LightMath.envelope(-0.1f, 0.2f), 1e-6f)
    }

    @Test
    fun flashIsStrongestAtTheSyncPoint() {
        val e = ev(FxType.FLASH_WHITE, 1 * SEC, 400_000, 1f, peak = 0.25f)
        val atSync = LightMath.evaluate(listOf(e), e.syncUs)
        val later = LightMath.evaluate(listOf(e), e.syncUs + 200_000)
        assertEquals(1f, atSync.flashAmt, 1e-3f)
        assertTrue(later.flashAmt < atSync.flashAmt)
        assertTrue(LightMath.evaluate(listOf(e), e.startUs - 1).isNeutral)
        assertTrue(LightMath.evaluate(listOf(e), e.endUs).isNeutral)
    }

    @Test
    fun intensityScalesTheEffect() {
        val half = ev(FxType.FLASH_WHITE, 0, 400_000, 0.5f, peak = 0.25f)
        assertEquals(0.5f, LightMath.evaluate(listOf(half), half.syncUs).flashAmt, 1e-3f)
    }

    @Test
    fun disabledEventsAreIgnored() {
        val e = ev(FxType.FLASH_WHITE, 0).copy(enabled = false)
        assertTrue(LightMath.evaluate(listOf(e), e.syncUs).isNeutral)
    }

    @Test
    fun overlappingFlashesBlendTheirColours() {
        val red = ev(FxType.FLASH_COLOR, 0, 400_000, 0.5f, 0xFFFF0000, 0.25f)
        val blue = ev(FxType.FLASH_COLOR, 0, 400_000, 0.5f, 0xFF0000FF, 0.25f)
        val s = LightMath.evaluate(listOf(red, blue), red.syncUs)
        assertEquals(1f, s.flashAmt, 1e-3f)
        assertEquals(0.5f, s.flashR, 1e-3f)
        assertEquals(0.5f, s.flashB, 1e-3f)
    }

    @Test
    fun blackFlashIsBlack() {
        val e = ev(FxType.FLASH_BLACK, 0, 400_000, 1f, peak = 0.25f)
        val s = LightMath.evaluate(listOf(e), e.syncUs)
        assertEquals(0f, s.flashR + s.flashG + s.flashB, 1e-4f)
        assertEquals(1f, s.flashAmt, 1e-3f)
    }

    @Test
    fun differentTypesWriteDifferentChannels() {
        val z = LightMath.evaluate(listOf(ev(FxType.IMPACT_ZOOM, 0, 400_000, 1f, peak = 0.25f)), 100_000)
        assertTrue(z.zoom > 1.1f && z.flashAmt == 0f)
        val g = LightMath.evaluate(listOf(ev(FxType.GLITCH, 0, 400_000, 1f, peak = 0.25f)), 100_000)
        assertTrue(g.glitch > 0.9f && g.zoom == 1f)
        val sh = LightMath.evaluate(listOf(ev(FxType.SHAKE, 0, 400_000, 1f, peak = 0.25f)), 123_456)
        assertTrue(sh.shakeX != 0f || sh.shakeY != 0f)
    }

    @Test
    fun everyLibraryEffectProducesAVisibleChange() {
        for (d in FxLibrary.all) {
            val e = FxLibrary.create("x", d.type, 0, 1f)
            val s = LightMath.evaluate(listOf(e), e.syncUs)
            assertFalse("${d.type} did nothing at its sync point", s.isNeutral)
        }
    }

    @Test
    fun beatMappingFollowsAudioPlacement() {
        val p = projectWithMusic(listOf(Beat(1 * SEC, 0.9f), Beat(2 * SEC, 0.4f)), audioStart = 3 * SEC)
        assertEquals(listOf(4 * SEC, 5 * SEC), BeatMapping.onsets(p).map { it.timeUs })
        assertEquals(listOf(4 * SEC), BeatMapping.onsets(p, 0.5f).map { it.timeUs })
    }

    @Test
    fun beatMappingSkipsTrimmedOutPartsAndHonoursTrimStart() {
        val base = projectWithMusic(listOf(Beat(1 * SEC, 1f), Beat(5 * SEC, 1f)), audioStart = 0)
        val trimmed = base.copy(audio = base.audio.map { it.copy(trimStartUs = 3 * SEC) })
        assertEquals(listOf(2 * SEC), BeatMapping.onsets(trimmed).map { it.timeUs })
    }

    @Test
    fun beatMappingFollowsClipSpeed() {
        val clip = TestData.video("v", 20).copy(speed = 2f)
        val p = TestData.project(clip).copy(beat = BeatSource("v", true, map(listOf(Beat(4 * SEC, 1f)))))
        assertEquals(listOf(2 * SEC), BeatMapping.onsets(p).map { it.timeUs })
    }

    @Test
    fun autoSyncAlignsThePeakWithTheBeat() {
        val onsets = listOf(Beat(1 * SEC, 1f), Beat(3 * SEC, 0.9f), Beat(5 * SEC, 0.2f))
        val p = projectWithMusic(onsets, audioStart = 2 * SEC)
        var n = 0
        val events = FxAutoSync.place(p, listOf(FxType.FLASH_WHITE), AutoSyncOptions(hitThreshold = 0.5f)) { "id${n++}" }
        assertEquals(2, events.size)
        assertEquals(3 * SEC, events[0].syncUs, 2L)
        assertEquals(5 * SEC, events[1].syncUs, 2L)
        assertTrue(events.all { it.id.startsWith(FxAutoSync.AUTO_PREFIX) })
    }

    @Test
    fun safeModeLimitsFlashRateAndIntensity() {
        val onsets = (0 until 20).map { Beat(it * 200_000L + SEC, 0.5f + (it % 3) * 0.1f) }
        val p = projectWithMusic(onsets, audioStart = 0)
        var n = 0
        val safe = FxAutoSync.place(p, listOf(FxType.FLASH_WHITE), AutoSyncOptions(hitThreshold = 0f, safeMode = true)) { "s${n++}" }
        assertTrue(safe.size < onsets.size)
        assertTrue(safe.zipWithNext().all { (a, b) -> b.syncUs - a.syncUs >= FxAutoSync.SAFE_FLASH_GAP_US - 2 })
        assertTrue(safe.all { it.intensity <= FxAutoSync.SAFE_FLASH_MAX_INTENSITY })
        assertEquals(0, FxAutoSync.flickerWarnings(safe))
        val unsafe = FxAutoSync.place(p, listOf(FxType.FLASH_WHITE), AutoSyncOptions(hitThreshold = 0f, safeMode = false)) { "u${n++}" }
        assertTrue(unsafe.size > safe.size)
        assertTrue(FxAutoSync.flickerWarnings(unsafe) > 0)
    }

    @Test
    fun everyNthAndManualMarkers() {
        val onsets = (0 until 8).map { Beat(it * SEC, 1f) }
        val p = projectWithMusic(onsets, audioStart = 0, markers = listOf(4 * SEC, 9 * SEC))
        var n = 0
        val every2 = FxAutoSync.place(p, listOf(FxType.GLOW), AutoSyncOptions(everyNth = 2, hitThreshold = 0f)) { "g${n++}" }
        assertEquals(4, every2.size)
        val manual = FxAutoSync.place(p, listOf(FxType.IMPACT_ZOOM), AutoSyncOptions(target = SyncTarget.MANUAL_MARKERS)) { "m${n++}" }
        assertEquals(listOf(4 * SEC, 9 * SEC), manual.map { it.syncUs })
    }

    @Test
    fun recipesPlaceAlignedEventsOfEveryType() {
        val p = projectWithMusic(listOf(Beat(SEC, 1f)), audioStart = 0)
        var n = 0
        val ev = FxAutoSync.place(p, listOf(FxType.IMPACT_ZOOM, FxType.FLASH_WHITE, FxType.MOTION_BLUR), AutoSyncOptions()) { "r${n++}" }
        assertEquals(3, ev.size)
        assertTrue(ev.all { kotlin.math.abs(it.syncUs - SEC) < 3 })
        assertEquals(3, ev.map { it.type }.toSet().size)
    }

    @Test
    fun noBeatDataMeansNoEvents() {
        val p = TestData.project(TestData.video("v", 10))
        assertTrue(FxAutoSync.place(p, listOf(FxType.FLASH_WHITE), AutoSyncOptions()) { "x" }.isEmpty())
    }
}
