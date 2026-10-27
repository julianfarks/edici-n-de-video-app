package com.vanta.studio

import com.vanta.studio.TestData.SEC
import com.vanta.studio.domain.model.Easing
import com.vanta.studio.domain.model.Keyframe
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.ProjectCodec
import com.vanta.studio.domain.model.ProjectCorruptException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CodecTest {
    @Test
    fun roundTripKeepsEverything() {
        val clip = TestData.video("a", 8).copy(
            speed = 1.5f,
            keyframes = listOf(Keyframe(0, 0f, 0f, 1f, 0f, 1f), Keyframe(SEC, 0.2f, 0.1f, 1.4f, 10f, 0.5f, Easing.EASE_OUT)),
        )
        val p = TestData.project(clip, TestData.image("i", 2))
        val decoded = ProjectCodec.decode(ProjectCodec.encode(p))
        assertEquals(p.copy(formatVersion = Project.CURRENT_FORMAT_VERSION), decoded)
    }

    @Test
    fun garbageIsReportedAsCorrupt() {
        for (bad in listOf("", "not json", "[]", "{\"id\": 3}", "{")) {
            try {
                ProjectCodec.decode(bad)
                fail("should have failed for: $bad")
            } catch (e: ProjectCorruptException) {
                assertNotNull(e.message)
            }
        }
    }

    @Test
    fun newerFormatIsRejectedInsteadOfLosingData() {
        val text = ProjectCodec.encode(TestData.project(TestData.video("a"))).replace(
            "\"formatVersion\":${Project.CURRENT_FORMAT_VERSION}", "\"formatVersion\":999",
        )
        try {
            ProjectCodec.decode(text)
            fail("expected a corrupt/newer-version error")
        } catch (e: ProjectCorruptException) {
            assertTrue(e.message!!.contains("newer"))
        }
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val text = ProjectCodec.encode(TestData.project(TestData.video("a")))
            .replaceFirst("{", "{\"someFutureField\":123,")
        assertEquals("p1", ProjectCodec.decode(text).id)
    }

    @Test
    fun sanitizeKeepsClipsPlayable() {
        // trim end beyond the source and a silly speed must come back in range
        val bad = TestData.video("a", 5).copy(trimEndUs = 99 * SEC, speed = 1000f)
        val p = ProjectCodec.sanitize(TestData.project(bad))
        val c = p.clips[0]
        assertTrue(c.trimEndUs <= c.sourceDurationUs)
        assertTrue(c.speed <= 8f)
    }

    @Test
    fun peekNameReadsNameOnly() {
        val text = ProjectCodec.encode(TestData.project().copy(name = "Mi vídeo"))
        assertEquals("Mi vídeo", ProjectCodec.peekName(text))
        assertEquals(null, ProjectCodec.peekName("garbage"))
    }
}

class CodecNewFieldsTest {
    @org.junit.Test
    fun newProjectFieldsSurviveRoundTripAndOldFilesStillLoad() {
        val p = TestData.project(TestData.video("a")).copy(
            fx = listOf(com.vanta.studio.domain.model.FxEvent("f", com.vanta.studio.domain.model.FxType.GLITCH, 1, 500_000)),
            markers = listOf(3L, 1L),
            subtitles = com.vanta.studio.domain.model.SubtitleTrack(
                cues = listOf(com.vanta.studio.domain.model.Cue("c", 0, 1_000_000, "hola", listOf(com.vanta.studio.domain.model.SubWord("hola", 0, 900_000)))),
            ),
        )
        val back = ProjectCodec.decode(ProjectCodec.encode(p))
        assertEquals(p.fx, back.fx)
        assertEquals(listOf(1L, 3L), back.markers) // sanitised: sorted
        assertEquals(p.subtitles, back.subtitles)
        // a version-2 file without any of the new keys
        val v2 = ProjectCodec.encode(TestData.project(TestData.video("a"))).replace(""formatVersion":3", ""formatVersion":2")
        assertEquals("p1", ProjectCodec.decode(v2).id)
    }
}
