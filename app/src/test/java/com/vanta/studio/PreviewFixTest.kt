package com.vanta.studio

import androidx.compose.ui.graphics.Color
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.engine.CompositionFactory
import com.vanta.studio.ui.theme.Vanta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

class PreviewFixTest {
    private fun clip(src: Long, end: Long) =
        Clip(id = "c", uri = "file:///x.mp4", kind = MediaKind.VIDEO, sourceDurationUs = src, trimEndUs = end)

    @Test
    fun theSourceLengthGivenToThePlayerIsAlwaysAValidPositiveValue() {
        // normal
        assertEquals(146_300_000L, CompositionFactory.sourceDurationFor(clip(146_300_000, 146_300_000)))
        // trimmed: still the full file
        assertEquals(146_300_000L, CompositionFactory.sourceDurationFor(clip(146_300_000, 10_000_000)))
        // never smaller than the part that is played
        assertEquals(10_000_000L, CompositionFactory.sourceDurationFor(clip(5_000_000, 10_000_000)))
        // unset / unbounded / zero metadata can never reach Media3 as TIME_UNSET or Long.MAX
        val unset = -9223372036854775807L
        assertEquals(8_000_000L, CompositionFactory.sourceDurationFor(clip(unset, 8_000_000)))
        assertEquals(8_000_000L, CompositionFactory.sourceDurationFor(clip(0, 8_000_000)))
        assertEquals(8_000_000L, CompositionFactory.sourceDurationFor(clip(Clip.UNBOUNDED, 8_000_000)))
        assertTrue(CompositionFactory.sourceDurationFor(clip(0, 0)) > 0)
    }

    private fun lin(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun lum(c: Color) = 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val la = lum(a)
        val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun paletteMeetsReadableContrastOnEveryBackgroundItIsUsedOn() {
        val backgrounds = listOf(Vanta.Bg, Vanta.Surface, Vanta.Surface2, Vanta.Surface3)
        for (bg in backgrounds) {
            assertTrue("Text on $bg", contrast(Vanta.Text, bg) >= 7.0)
            assertTrue("TextDim on $bg", contrast(Vanta.TextDim, bg) >= 4.5)
            assertTrue("TextFaint on $bg", contrast(Vanta.TextFaint, bg) >= 4.5)
            assertTrue("AccentBright on $bg", contrast(Vanta.AccentBright, bg) >= 4.5)
            assertTrue("Danger on $bg", contrast(Vanta.Danger, bg) >= 4.5)
            assertTrue("Success on $bg", contrast(Vanta.Success, bg) >= 4.5)
        }
        // primary button: white text on the violet accent
        assertTrue(contrast(Color.White, Vanta.Accent) >= 3.0)
        // the project name used to be black on this background
        assertTrue(contrast(Color.Black, Vanta.Bg) < 1.5)
        assertTrue(contrast(Vanta.Text, Vanta.Bg) > 15)
    }
}
