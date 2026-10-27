package com.vanta.studio

import com.vanta.studio.TestData.SEC
import com.vanta.studio.domain.assistant.AssistantExec
import com.vanta.studio.domain.assistant.AssistantOp
import com.vanta.studio.domain.assistant.Ops
import com.vanta.studio.domain.assistant.Reason
import com.vanta.studio.domain.assistant.SpanishInterpreter
import com.vanta.studio.domain.model.AspectRatio
import com.vanta.studio.domain.timeline.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTest {
    private fun idGen(): () -> String { var n = 0; return { "n${n++}" } }
    private fun parse(s: String) = SpanishInterpreter.parse(s)
    private fun names(s: String) = parse(s).ops.map { it.op to (it.name ?: "") }

    @Test
    fun silencesAndKaraokeSubtitles() {
        val r = parse("Quita los silencios y agrega subtítulos estilo karaoke")
        assertEquals(listOf("silence", "subtitles"), r.ops.filter { it.op == Ops.OPEN_TOOL }.map { it.name })
        assertEquals("KARAOKE", r.ops.first { it.op == Ops.SUBTITLE_STYLE }.name)
        assertTrue(r.unclear.isEmpty())
    }

    @Test
    fun flashAndZoomOnTheRhythm() {
        val op = parse("Pon un flash y un zoom en cada golpe del ritmo").ops.single()
        assertEquals(Ops.FX_ON_BEATS, op.op)
        assertEquals("FLASH_WHITE,IMPACT_ZOOM", op.name)
    }

    @Test
    fun cutAtASecond() {
        val op = parse("Corta en el segundo 5").ops.single()
        assertEquals(Ops.SPLIT, op.op)
        assertEquals(5.0, op.timeSec!!, 1e-9)
    }

    @Test
    fun timeAsMinutesAndSeconds() {
        val op = parse("Divide en 1:30").ops.single()
        assertEquals(90.0, op.timeSec!!, 1e-9)
    }

    @Test
    fun deleteARange() {
        val op = parse("Elimina del segundo 10 al 15").ops.single()
        assertEquals(Ops.DELETE_RANGE, op.op)
        assertEquals(10.0, op.timeSec!!, 1e-9)
        assertEquals(15.0, op.endSec!!, 1e-9)
    }

    @Test
    fun speedOfAClip() {
        val op = parse("Acelera el primer clip x2").ops.single()
        assertEquals(Ops.SPEED, op.op)
        assertEquals(2.0, op.value!!, 1e-9)
        assertEquals(1, op.clip)
        assertEquals(0.5, parse("ponlo en cámara lenta").ops.single().value!!, 1e-9)
    }

    @Test
    fun textWithQuotesAndTimes() {
        val op = parse("Agrega el texto \"Hola, mundo\" en el segundo 2 durante 3 segundos").ops.single()
        assertEquals(Ops.ADD_TEXT, op.op)
        assertEquals("Hola, mundo", op.text)
        assertEquals(2.0, op.timeSec!!, 1e-9)
        assertEquals(3.0, op.value!!, 1e-9)
    }

    @Test
    fun verticalAndWarmFilter() {
        val ops = parse("Hazlo vertical para TikTok y pon filtro cálido").ops
        assertEquals(listOf(Ops.ASPECT, Ops.FILTER), ops.map { it.op })
        assertEquals("9:16", ops[0].name)
        assertEquals("warm", ops[1].name)
    }

    @Test
    fun musicEditWithMode() {
        val op = parse("Edita al ritmo de la música, muy rápido").ops.single()
        assertEquals("auto_edit", op.name)
        assertEquals("VERY_FAST", op.text)
    }

    @Test
    fun whatItDoesNotUnderstandIsReported() {
        val r = parse("asdf qwer, corta en el segundo 3")
        assertEquals(1, r.ops.size)
        assertEquals(listOf("asdf qwer"), r.unclear)
        assertTrue(parse("hazme un café").ops.isEmpty())
    }

    // ------------------------------------------------------------------ execution

    private fun project() = TestData.project(TestData.video("a", 10), TestData.video("b", 10))

    @Test
    fun executesDirectEditsAndKeepsTheOriginal() {
        val p = project()
        val ops = listOf(
            AssistantOp(Ops.SPLIT, timeSec = 5.0),
            AssistantOp(Ops.SPEED, value = 2.0, clip = 2),
            AssistantOp(Ops.ASPECT, name = "9:16"),
            AssistantOp(Ops.FILTER, name = "warm", value = 0.7),
            AssistantOp(Ops.ADD_TEXT, text = "Hola", timeSec = 1.0, value = 2.0),
            AssistantOp(Ops.MUTE, flag = true, clip = 1),
        )
        val r = AssistantExec.apply(p, ops, 0, idGen())
        assertTrue(r.skipped.isEmpty())
        assertEquals(6, r.applied.size)
        assertEquals(AspectRatio.R9_16, r.project.canvas.aspect)
        assertEquals(1, r.project.texts.size)
        assertEquals(3, r.project.clips.size) // split added a clip
        assertTrue(r.project.clips.any { it.filter?.id == "warm" })
        // original untouched, so "cancel" just means not applying
        assertEquals(2, p.clips.size)
        assertEquals(AspectRatio.R16_9, p.canvas.aspect)
    }

    @Test
    fun deleteRangeClosesTheGap() {
        val r = AssistantExec.apply(project(), listOf(AssistantOp(Ops.DELETE_RANGE, timeSec = 4.0, endSec = 8.0)), 0, idGen())
        assertEquals(16 * SEC, Timeline.totalDurationUs(r.project))
    }

    @Test
    fun impossibleOrUnknownThingsAreSkippedWithAReason() {
        val ops = listOf(
            AssistantOp(Ops.SPLIT, timeSec = 500.0),
            AssistantOp(Ops.SPEED, value = 50.0),
            AssistantOp(Ops.FILTER, name = "no_such_filter"),
            AssistantOp(Ops.SPEED, value = 2.0, clip = 9),
            AssistantOp("format_disk"),
            AssistantOp(Ops.SPEED, value = Double.NaN),
        )
        val r = AssistantExec.apply(project(), ops, 0, idGen())
        assertTrue(r.applied.isEmpty())
        val reasons = r.skipped.map { it.second }
        assertTrue(Reason.OUT_OF_RANGE in reasons)
        assertTrue(Reason.UNKNOWN_NAME in reasons)
        assertTrue(Reason.NO_CLIP in reasons)
        assertTrue(Reason.INVALID in reasons)
        assertEquals(6, r.skipped.size)
    }

    @Test
    fun toolsAreReportedNotRun() {
        val ops = listOf(AssistantOp(Ops.OPEN_TOOL, name = "silence"), AssistantOp(Ops.FX_ON_BEATS, name = "FLASH_WHITE"))
        val r = AssistantExec.apply(project(), ops, 0, idGen())
        assertEquals(listOf("silence", "beat"), r.tools) // beat analysis is needed before effects can follow the rhythm
        assertEquals(project(), r.project)
        assertFalse(AssistantExec.validate(AssistantOp("rm_rf")))
    }

    @Test
    fun cloudReplyIsParsedAndAnythingOutsideTheWhitelistIsDropped() {
        val llm = com.vanta.studio.engine.assistant.LlmInterpreter("https://x.invalid/v1", "m", "k")
        val reply = """```json
            {"ops":[{"op":"split","timeSec":5},{"op":"speed","value":2,"clip":1},{"op":"delete_all_files"},
                    {"op":"speed","value":9999},{"op":"add_fx","name":"FLASH_WHITE","timeSec":-3}],
             "unclear":["quitar el fondo"]}
            ```"""
        val r = llm.parse(reply)
        assertEquals(listOf(Ops.SPLIT, Ops.SPEED), r.ops.map { it.op })
        assertEquals(2.0, r.ops[1].value!!, 0.0)
        assertTrue(r.unclear.contains("quitar el fondo"))
        assertEquals(3, r.unclear.size) // the three rejected operations are reported, not run
    }

    @Test(expected = org.json.JSONException::class)
    fun garbageReplyFailsLoudly() {
        com.vanta.studio.engine.assistant.LlmInterpreter("https://x.invalid/v1", "m", "k").parse("sure! here you go")
    }
}
