package com.vanta.studio.domain.fx

import com.vanta.studio.domain.model.FxEvent
import com.vanta.studio.domain.model.FxType
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The combined look of all light events at one instant. Every effect writes into a small set of
 * shared channels, so any number of events (and different types) can overlap and the shader
 * stays a single pass. This one structure drives both the preview and the exported file.
 */
data class LightState(
    val flashR: Float = 0f, val flashG: Float = 0f, val flashB: Float = 0f, val flashAmt: Float = 0f,
    val glow: Float = 0f,
    val bloom: Float = 0f,
    val radial: Float = 0f, val radialRadius: Float = 0f,
    val leak: Float = 0f,
    val flare: Float = 0f, val flareX: Float = 0.5f, val flareY: Float = 0.4f,
    val sweep: Float = 0f, val sweepPos: Float = 0f,
    val lightR: Float = 1f, val lightG: Float = 1f, val lightB: Float = 1f,
    val chroma: Float = 0f,
    val glitch: Float = 0f,
    val motionBlur: Float = 0f,
    val invert: Float = 0f,
    val exposure: Float = 0f,
    val satPulse: Float = 0f,
    val zoom: Float = 1f,
    val shakeX: Float = 0f, val shakeY: Float = 0f,
) {
    val isNeutral: Boolean
        get() = flashAmt <= 0f && glow <= 0f && bloom <= 0f && radial <= 0f && leak <= 0f && flare <= 0f &&
            sweep <= 0f && chroma <= 0f && glitch <= 0f && motionBlur <= 0f && invert <= 0f &&
            exposure == 0f && satPulse <= 0f && zoom == 1f && shakeX == 0f && shakeY == 0f

    companion object {
        val NEUTRAL = LightState()
    }
}

object LightMath {
    /** Strength 0..1 of an event at progress [x] (0..1): fast rise to [peak], then a smooth decay. */
    fun envelope(x: Float, peak: Float): Float {
        if (x < 0f || x >= 1f) return 0f
        val p = peak.coerceIn(0.001f, 0.95f)
        return if (x < p) {
            val r = x / p
            r * r * (3f - 2f * r)
        } else {
            val d = 1f - (x - p) / (1f - p)
            d * d
        }
    }

    fun progress(e: FxEvent, timeUs: Long): Float =
        if (e.durationUs <= 0) 2f else (timeUs - e.startUs).toFloat() / e.durationUs

    /** Mutable accumulator so evaluation allocates only the final [LightState]. */
    private class Acc {
        var flashR = 0f; var flashG = 0f; var flashB = 0f; var flashAmt = 0f
        var glow = 0f; var bloom = 0f
        var radial = 0f; var radialRadius = 0f
        var leak = 0f
        var flare = 0f; var flareX = 0.5f; var flareY = 0.4f
        var sweep = 0f; var sweepPos = 0f
        var lightR = 0f; var lightG = 0f; var lightB = 0f; var lightW = 0f
        var chroma = 0f; var glitch = 0f; var motionBlur = 0f; var invert = 0f
        var exposure = 0f; var satPulse = 0f
        var zoom = 0f
        var shakeX = 0f; var shakeY = 0f

        fun light(r: Float, g: Float, b: Float, w: Float) {
            lightR += r * w; lightG += g * w; lightB += b * w; lightW += w
        }
    }

    fun evaluate(events: List<FxEvent>, timeUs: Long): LightState {
        var acc: Acc? = null
        for (e in events) {
            if (!e.enabled || timeUs < e.startUs || timeUs >= e.endUs) continue
            val x = progress(e, timeUs)
            val env = envelope(x, e.peak) * e.intensity.coerceIn(0f, 2f)
            if (env <= 0f) continue
            val a = acc ?: Acc().also { acc = it }
            val cr = ((e.color shr 16) and 0xFF) / 255f
            val cg = ((e.color shr 8) and 0xFF) / 255f
            val cb = (e.color and 0xFF) / 255f
            when (e.type) {
                FxType.FLASH_WHITE -> flash(a, 1f, 1f, 1f, env)
                FxType.FLASH_BLACK -> flash(a, 0f, 0f, 0f, env)
                FxType.FLASH_COLOR -> flash(a, cr, cg, cb, env)
                FxType.FLASH_ZOOM -> {
                    flash(a, cr, cg, cb, env * 0.85f)
                    a.zoom += 0.10f * env
                }
                FxType.RADIAL_BURST -> {
                    a.radial += env
                    a.radialRadius = max(a.radialRadius, 0.12f + 0.75f * x.coerceIn(0f, 1f))
                    a.light(cr, cg, cb, env)
                }
                FxType.GLOW -> { a.glow += env; a.light(cr, cg, cb, env) }
                FxType.BLOOM -> { a.bloom += env; a.light(cr, cg, cb, env) }
                FxType.LIGHT_LEAK -> { a.leak += env; a.light(cr, cg, cb, env) }
                FxType.LENS_FLARE -> {
                    a.flare += env
                    a.flareX = 0.25f + 0.5f * x.coerceIn(0f, 1f)
                    a.flareY = 0.33f
                    a.light(cr, cg, cb, env)
                }
                FxType.LIGHT_SWEEP -> {
                    val bell = sin(PI.toFloat() * x.coerceIn(0f, 1f)) * e.intensity.coerceIn(0f, 2f)
                    a.sweep += bell
                    a.sweepPos = x
                    a.light(cr, cg, cb, bell)
                }
                FxType.CHROMATIC -> a.chroma += env
                FxType.GLITCH -> a.glitch += env
                FxType.IMPACT_ZOOM -> a.zoom += 0.14f * env
                FxType.SHAKE -> {
                    val t = timeUs / 1_000_000f
                    a.shakeX += env * 0.018f * (sin(t * 71f) + 0.5f * sin(t * 133f + 1f))
                    a.shakeY += env * 0.018f * (sin(t * 59f + 2f) + 0.5f * sin(t * 97f))
                }
                FxType.MOTION_BLUR -> a.motionBlur += env
                FxType.INVERT -> a.invert += env
                FxType.EXPOSURE -> a.exposure += env * 1.6f
                FxType.SATURATION_PULSE -> a.satPulse += env
            }
        }
        val a = acc ?: return LightState.NEUTRAL
        val w = a.flashAmt
        val fr = if (w > 0f) a.flashR / w else 0f
        val fg = if (w > 0f) a.flashG / w else 0f
        val fb = if (w > 0f) a.flashB / w else 0f
        val lw = a.lightW
        return LightState(
            flashR = fr, flashG = fg, flashB = fb, flashAmt = min(1f, w),
            glow = min(1.5f, a.glow), bloom = min(1.5f, a.bloom),
            radial = min(1.5f, a.radial), radialRadius = a.radialRadius,
            leak = min(1.5f, a.leak),
            flare = min(1.5f, a.flare), flareX = a.flareX, flareY = a.flareY,
            sweep = min(1.5f, a.sweep), sweepPos = a.sweepPos,
            lightR = if (lw > 0f) a.lightR / lw else 1f,
            lightG = if (lw > 0f) a.lightG / lw else 1f,
            lightB = if (lw > 0f) a.lightB / lw else 1f,
            chroma = min(1.5f, a.chroma), glitch = min(1.5f, a.glitch),
            motionBlur = min(1.5f, a.motionBlur), invert = min(1f, a.invert),
            exposure = a.exposure.coerceIn(-3f, 3f), satPulse = min(1.5f, a.satPulse),
            zoom = 1f + a.zoom.coerceIn(0f, 0.4f),
            shakeX = a.shakeX, shakeY = a.shakeY,
        )
    }

    /**
     * Flashes accumulate as a weighted colour: two overlapping flashes of different colours
     * blend instead of one replacing the other. [LightState.flashAmt] is capped at 1.
     */
    private fun flash(a: Acc, r: Float, g: Float, b: Float, amt: Float) {
        a.flashR += r * amt; a.flashG += g * amt; a.flashB += b * amt; a.flashAmt += amt
    }

    /** Highest overall brightness change a state can cause, used by the flicker-safety checks. */
    fun flashStrength(s: LightState): Float = max(s.flashAmt, max(s.invert, s.exposure / 1.6f))
}
