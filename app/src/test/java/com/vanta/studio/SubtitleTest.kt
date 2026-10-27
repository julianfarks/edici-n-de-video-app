package com.vanta.studio

import com.vanta.studio.TestData.SEC
import com.vanta.studio.domain.model.Cue
import com.vanta.studio.domain.model.SubWord
import com.vanta.studio.domain.model.SubtitleKind
import com.vanta.studio.domain.model.SubtitleStyle
import com.vanta.studio.domain.model.SubtitleTrack
import com.vanta.studio.domain.subtitles.Srt
import com.vanta.studio.domain.subtitles.SubtitleBuilder
import com.vanta.studio.domain.subtitles.SubtitleOps
import com.vanta.studio.domain.subtitles.SubtitlePresets
import com.vanta.studio.domain.subtitles.SubtitleRender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTest {
    private var seq = 0
    private fun id() = "c${seq++}"

    private fun words(vararg w: Triple<String, Double, Double>) = w.map { SubWord(it.first, (it.second * SEC).toLong(), (it.third * SEC).toLong()) }

    private val hello = words(
        Triple("hola", 0.5, 0.9), Triple("a", 1.0, 1.1), Triple("todos", 1.2, 1.7),
        Triple("bienvenidos", 4.0, 4.8), Triple("al", 4.9, 5.0), Triple("canal", 5.1, 5.5),
    )

    private fun track(vararg c: Cue, style: SubtitleStyle = SubtitleStyle()) = SubtitleTrack(cues = c.toList(), style = style)

    @Test
    fun builderBreaksOnLongPauses() {
        val cues = SubtitleBuilder.fromWords(hello, SubtitleStyle(), ::id)
        assertEquals(2, cues.size)
        assertEquals("hola a todos", cues[0].text)
        assertEquals("bienvenidos al canal", cues[1].text)
        assertEquals((0.5 * SEC).toLong(), cues[0].startUs)
        assertTrue(cues[0].endUs <= cues[1].startUs)
        assertEquals(3, cues[0].words.size)
    }

    @Test
    fun builderRespectsWordLimitAndCreatorStyle() {
        val many = (0 until 10).map { SubWord("w$it", it * 300_000L, it * 300_000L + 250_000L) }
        val normal = SubtitleBuilder.fromWords(many, SubtitleStyle(maxWordsPerCue = 4), ::id)
        assertTrue(normal.all { it.words.size <= 4 })
        val creator = SubtitleBuilder.fromWords(many, SubtitlePresets.forKind(SubtitleKind.CREATOR), ::id)
        assertTrue(creator.all { it.words.size <= 3 })
        assertEquals(10, creator.sumOf { it.words.size })
    }

    @Test
    fun builderOutputIsOrderedAndNonOverlapping() {
        val cues = SubtitleBuilder.fromWords(hello, SubtitleStyle(), ::id)
        cues.zipWithNext().forEach { (a, b) -> assertTrue(a.endUs <= b.startUs) }
        assertTrue(cues.all { it.endUs > it.startUs })
        assertTrue(SubtitleBuilder.fromWords(emptyList(), SubtitleStyle(), ::id).isEmpty())
    }

    @Test
    fun srtRoundTrip() {
        val cues = listOf(Cue("a", 1_500_000, 3_250_000, "Hola mundo"), Cue("b", 3_600_000, 5_000_000, "Segunda línea\ncon salto"))
        val text = Srt.format(cues)
        assertTrue(text.startsWith("1\n00:00:01,500 --> 00:00:03,250\nHola mundo"))
        val back = Srt.parse(text, ::id)
        assertEquals(2, back.size)
        assertEquals(cues[1].text, back[1].text)
        assertEquals(cues[0].startUs, back[0].startUs)
        assertEquals(cues[1].endUs, back[1].endUs)
    }

    @Test
    fun srtParserToleratesBomCrlfAndGarbage() {
        val text = "﻿1\r\n00:00:00,000 --> 00:00:01,000\r\nUno\r\n\r\nbasura\r\n\r\n3\r\n00:00:02,5 --> 00:00:03,0\r\nTres\r\n"
        val cues = Srt.parse(text, ::id)
        assertEquals(listOf("Uno", "Tres"), cues.map { it.text })
        assertEquals(2_500_000L, cues[1].startUs)
    }

    @Test
    fun srtTimestampFormat() {
        assertEquals("01:02:03,004", Srt.timestamp(3_723_004_000L / 1000 * 1000 + 0))
        assertEquals("00:00:00,000", Srt.timestamp(-5))
    }

    @Test
    fun spellingFixKeepsMeasuredTimings() {
        val c = SubtitleBuilder.fromWords(hello, SubtitleStyle(), ::id)[1]
        val fixed = SubtitleOps.setText(c, "bienvenidos a canal")
        assertEquals(c.words.map { it.startUs }, fixed.words.map { it.startUs })
        assertEquals("a", fixed.words[1].text)
        assertEquals(false, fixed.estimated)
    }

    @Test
    fun changingWordCountMarksTimingsAsEstimated() {
        val c = SubtitleBuilder.fromWords(hello, SubtitleStyle(), ::id)[1]
        val e = SubtitleOps.setText(c, "bienvenidos de nuevo al canal")
        assertEquals(5, e.words.size)
        assertTrue(e.estimated)
        assertEquals(c.startUs, e.words.first().startUs)
        assertEquals(c.endUs, e.words.last().endUs, 2.0)
        e.words.zipWithNext().forEach { (a, b) -> assertTrue(a.endUs <= b.startUs + 1) }
    }

    @Test
    fun splitUsesWordBoundaries() {
        val c = SubtitleBuilder.fromWords(hello, SubtitleStyle(), ::id)[0]
        val t = SubtitleOps.split(track(c), c.id, (1.15 * SEC).toLong(), ::id)!!
        assertEquals(2, t.cues.size)
        assertEquals("hola a", t.cues[0].text)
        assertEquals("todos", t.cues[1].text)
        assertEquals(c.words[2].startUs, t.cues[1].startUs)
    }

    @Test
    fun splitWithoutWordsCutsAtASpace() {
        val c = Cue("x", 0, 4 * SEC, "uno dos tres cuatro")
        val t = SubtitleOps.split(track(c), "x", 2 * SEC, ::id)!!
        assertEquals(2, t.cues.size)
        assertEquals("uno dos", t.cues[0].text)
        assertEquals("tres cuatro", t.cues[1].text)
        assertNull(SubtitleOps.split(track(c), "x", 10_000, ::id))
    }

    @Test
    fun mergeJoinsTextAndWords() {
        val cues = SubtitleBuilder.fromWords(hello, SubtitleStyle(), ::id)
        val m = SubtitleOps.mergeWithNext(track(*cues.toTypedArray()), cues[0].id)!!
        assertEquals(1, m.cues.size)
        assertEquals(6, m.cues[0].words.size)
        assertEquals(cues[1].endUs, m.cues[0].endUs)
        assertNull(SubtitleOps.mergeWithNext(m, m.cues[0].id))
    }

    @Test
    fun retimeRescalesWordTimings() {
        val c = SubtitleBuilder.fromWords(hello, SubtitleStyle(), ::id)[0]
        val r = SubtitleOps.retime(c, c.startUs + SEC, c.endUs + 3 * SEC)
        assertEquals(c.startUs + SEC, r.startUs)
        assertEquals(r.startUs, r.words.first().startUs)
        assertTrue(r.words.last().endUs <= r.endUs)
        assertTrue(r.words[1].startUs - r.words[0].startUs > c.words[1].startUs - c.words[0].startUs)
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        val t = track(Cue("a", 0, SEC, "Canción nueva"), Cue("b", SEC, 2 * SEC, "otra cosa"), Cue("c", 2 * SEC, 3 * SEC, "la CANCION final"))
        assertEquals(listOf(0, 2), SubtitleOps.search(t, "cancion"))
        assertTrue(SubtitleOps.search(t, "   ").isEmpty())
    }

    @Test
    fun replaceAllCountsMatches() {
        val t = track(Cue("a", 0, SEC, "hola Mundo"), Cue("b", SEC, 2 * SEC, "mundo mundo"))
        val (out, n) = SubtitleOps.replaceAll(t, "mundo", "planeta")
        assertEquals(3, n)
        assertEquals("hola planeta", out.cues[0].text)
    }

    @Test
    fun problemsFlagOverlapsAndTinyCues() {
        val t = track(Cue("a", 0, 2 * SEC, "uno"), Cue("b", SEC, 3 * SEC, "dos"), Cue("c", 4 * SEC, 4 * SEC + 50_000, "tres"))
        assertEquals(setOf("a", "c"), SubtitleOps.problems(t).toSet())
    }

    @Test
    fun activeWordFollowsMeasuredTimestamps() {
        val c = SubtitleBuilder.fromWords(hello, SubtitleStyle(), ::id)[0]
        val style = SubtitlePresets.forKind(SubtitleKind.HIGHLIGHT)
        assertEquals(0, SubtitleRender.stateFor(style, c, (0.7 * SEC).toLong())!!.activeWord)
        assertEquals(1, SubtitleRender.stateFor(style, c, (1.05 * SEC).toLong())!!.activeWord)
        assertEquals(2, SubtitleRender.stateFor(style, c, (1.4 * SEC).toLong())!!.activeWord)
    }

    @Test
    fun cuesWithoutWordTimingsAreNeverHighlighted() {
        val c = Cue("x", 0, 3 * SEC, "texto manual")
        val s = SubtitleRender.stateFor(SubtitlePresets.forKind(SubtitleKind.KARAOKE), c, SEC)!!
        assertEquals(-1, s.activeWord)
    }

    @Test
    fun progressiveRevealsWordsAsTheyAreSpoken() {
        val c = SubtitleBuilder.fromWords(hello, SubtitleStyle(), ::id)[0]
        val style = SubtitlePresets.forKind(SubtitleKind.PROGRESSIVE)
        assertEquals(1, SubtitleRender.stateFor(style, c, (0.6 * SEC).toLong())!!.shown)
        assertEquals(2, SubtitleRender.stateFor(style, c, (1.05 * SEC).toLong())!!.shown)
        assertEquals(3, SubtitleRender.stateFor(style, c, (1.3 * SEC).toLong())!!.shown)
    }

    @Test
    fun bounceAndScaleSyncPulseOnEachWord() {
        val c = SubtitleBuilder.fromWords(hello, SubtitleStyle(), ::id)[0]
        for (kind in listOf(SubtitleKind.BOUNCE, SubtitleKind.SCALE_SYNC)) {
            val style = SubtitlePresets.forKind(kind).copy(animIn = com.vanta.studio.domain.model.LayerAnim.NONE)
            val atStart = SubtitleRender.stateFor(style, c, c.words[1].startUs)!!.frame.scale
            val later = SubtitleRender.stateFor(style, c, c.words[1].startUs + 400_000)!!.frame.scale
            assertTrue("$kind start=$atStart later=$later", atStart > 1.05f && later < atStart)
        }
    }

    @Test
    fun stateAtOutsideCuesIsNull() {
        val c = Cue("x", SEC, 2 * SEC, "hola")
        assertNull(SubtitleRender.stateAt(track(c), 0))
        assertNotNull(SubtitleRender.stateAt(track(c), (1.5 * SEC).toLong()))
        assertNull(SubtitleRender.stateAt(track(c).copy(enabled = false), (1.5 * SEC).toLong()))
        assertNull(SubtitleRender.stateAt(null, SEC))
    }

    @Test
    fun karaokeColoursSungWords() {
        val style = SubtitlePresets.forKind(SubtitleKind.KARAOKE)
        assertEquals(style.highlightColor, SubtitleRender.colorForWord(style, 0, 1))
        assertEquals(style.highlightColor, SubtitleRender.colorForWord(style, 1, 1))
        assertEquals(style.color, SubtitleRender.colorForWord(style, 2, 1))
        val hl = SubtitlePresets.forKind(SubtitleKind.HIGHLIGHT)
        assertEquals(hl.color, SubtitleRender.colorForWord(hl, 0, 1))
        assertEquals(hl.highlightColor, SubtitleRender.colorForWord(hl, 1, 1))
    }
}
