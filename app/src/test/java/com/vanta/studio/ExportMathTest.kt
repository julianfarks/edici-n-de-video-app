package com.vanta.studio

import com.vanta.studio.domain.export.ExportMath
import com.vanta.studio.domain.export.ExportQuality
import com.vanta.studio.domain.export.ExportResolution
import com.vanta.studio.domain.model.AspectRatio
import com.vanta.studio.domain.model.CanvasSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportMathTest {
    @Test
    fun outputSizesAreEvenAndMatchTheAspect() {
        for (ratio in listOf(AspectRatio.R16_9, AspectRatio.R9_16, AspectRatio.R1_1, AspectRatio.R4_5)) {
            for (res in ExportResolution.entries) {
                val (w, h) = ExportMath.outputSize(CanvasSpec(aspect = ratio), res)
                assertEquals("$ratio $res width", 0, w % 2)
                assertEquals("$ratio $res height", 0, h % 2)
                assertEquals(res.shortSide, minOf(w, h))
            }
        }
    }

    @Test
    fun sixteenNineAt1080Is1920x1080() {
        assertEquals(1920 to 1080, ExportMath.outputSize(CanvasSpec(aspect = AspectRatio.R16_9), ExportResolution.P1080))
        assertEquals(1080 to 1920, ExportMath.outputSize(CanvasSpec(aspect = AspectRatio.R9_16), ExportResolution.P1080))
    }

    @Test
    fun bitrateGrowsWithQualityAndSize() {
        val low = ExportMath.videoBitrate(1280, 720, 30, ExportQuality.LOW)
        val high = ExportMath.videoBitrate(1280, 720, 30, ExportQuality.HIGH)
        val bigger = ExportMath.videoBitrate(1920, 1080, 30, ExportQuality.HIGH)
        assertTrue(high > low)
        assertTrue(bigger > high)
    }

    @Test
    fun bitrateIsClamped() {
        assertTrue(ExportMath.videoBitrate(16, 16, 1, ExportQuality.LOW) >= ExportMath.MIN_VIDEO_BITRATE)
        assertTrue(ExportMath.videoBitrate(7680, 4320, 60, ExportQuality.HIGH) <= ExportMath.MAX_VIDEO_BITRATE)
    }

    @Test
    fun sixtyFpsCostsMoreButLessThanDouble() {
        val a = ExportMath.videoBitrate(1920, 1080, 30, ExportQuality.STANDARD)
        val b = ExportMath.videoBitrate(1920, 1080, 60, ExportQuality.STANDARD)
        assertTrue(b > a && b < a * 2)
    }

    @Test
    fun estimateScalesWithDurationAndAudio() {
        val v = 8_000_000
        val oneMin = ExportMath.estimateBytes(60_000_000, v, true)
        val twoMin = ExportMath.estimateBytes(120_000_000, v, true)
        assertEquals(twoMin.toDouble(), oneMin * 2.0, oneMin * 0.001)
        assertTrue(ExportMath.estimateBytes(60_000_000, v, true) > ExportMath.estimateBytes(60_000_000, v, false))
    }

    @Test
    fun fileNamesAreSafe() {
        assertEquals("Mi vídeo.mp4", ExportMath.sanitizeFileName("Mi vídeo", "x"))
        assertEquals("a_b_c.mp4", ExportMath.sanitizeFileName("a/b:c", "x"))
        assertEquals("fallback.mp4", ExportMath.sanitizeFileName("   ", "fallback"))
        assertEquals("fallback.mp4", ExportMath.sanitizeFileName("...", "fallback"))
        assertEquals("clip.mp4", ExportMath.sanitizeFileName("clip.mp4", "x"))
        assertEquals(84, ExportMath.sanitizeFileName("a".repeat(300), "x").length)
    }
}
