package com.vanta.studio.domain.effects

import com.vanta.studio.domain.model.ColorAdjust
import com.vanta.studio.domain.model.EffectRef
import com.vanta.studio.domain.model.EffectType
import com.vanta.studio.domain.model.FilterRef

/**
 * Every value consumed by the colour/effect shader. Neutral at all zeros, so a clip with
 * no adjustments renders unchanged.
 */
data class GradeParams(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val vignette: Float = 0f,
    val sharpen: Float = 0f,
    val grain: Float = 0f,
    val sepia: Float = 0f,
    val bw: Float = 0f,
    val tealOrange: Float = 0f,
    val fade: Float = 0f,
    val blur: Float = 0f,
    val pixelate: Float = 0f,
    val glitch: Float = 0f,
    val chroma: Float = 0f,
    val mirror: Float = 0f,
    val scanlines: Float = 0f,
    val lightLeak: Float = 0f,
) {
    operator fun plus(o: GradeParams) = GradeParams(
        brightness + o.brightness, contrast + o.contrast, saturation + o.saturation,
        exposure + o.exposure, temperature + o.temperature, tint + o.tint,
        shadows + o.shadows, highlights + o.highlights, vignette + o.vignette,
        sharpen + o.sharpen, grain + o.grain, sepia + o.sepia, bw + o.bw,
        tealOrange + o.tealOrange, fade + o.fade, blur + o.blur, pixelate + o.pixelate,
        glitch + o.glitch, chroma + o.chroma, mirror + o.mirror, scanlines + o.scanlines,
        lightLeak + o.lightLeak,
    )

    operator fun times(k: Float) = GradeParams(
        brightness * k, contrast * k, saturation * k, exposure * k, temperature * k, tint * k,
        shadows * k, highlights * k, vignette * k, sharpen * k, grain * k, sepia * k, bw * k,
        tealOrange * k, fade * k, blur * k, pixelate * k, glitch * k, chroma * k, mirror * k,
        scanlines * k, lightLeak * k,
    )

    /** Clamps every parameter to the range the shader expects. */
    fun clamped() = GradeParams(
        brightness.coerceIn(-1f, 1f), contrast.coerceIn(-1f, 1f), saturation.coerceIn(-1f, 1f),
        exposure.coerceIn(-1f, 1f), temperature.coerceIn(-1f, 1f), tint.coerceIn(-1f, 1f),
        shadows.coerceIn(-1f, 1f), highlights.coerceIn(-1f, 1f), vignette.coerceIn(0f, 1f),
        sharpen.coerceIn(0f, 1f), grain.coerceIn(0f, 1f), sepia.coerceIn(0f, 1f), bw.coerceIn(0f, 1f),
        tealOrange.coerceIn(0f, 1f), fade.coerceIn(0f, 1f), blur.coerceIn(0f, 1f),
        pixelate.coerceIn(0f, 1f), glitch.coerceIn(0f, 1f), chroma.coerceIn(0f, 1f),
        mirror.coerceIn(0f, 1f), scanlines.coerceIn(0f, 1f), lightLeak.coerceIn(0f, 1f),
    )

    val isNeutral: Boolean get() = this == GradeParams()

    /** True for effects that change over time, which need a redraw on every frame. */
    val isTimeVarying: Boolean get() = grain > 0f || glitch > 0f || lightLeak > 0f
}

enum class FilterCategory { COLOR, CINEMATIC, VINTAGE, RETRO, BW, MOOD }

data class FilterPreset(val id: String, val category: FilterCategory, val params: GradeParams)

enum class EffectCategory { GLITCH, DISTORTION, LIGHT, BLUR, SHARPNESS, RETRO, MOOD }

object Grade {
    val filters: List<FilterPreset> = listOf(
        FilterPreset("vivid", FilterCategory.COLOR, GradeParams(saturation = 0.35f, contrast = 0.15f)),
        FilterPreset("warm", FilterCategory.COLOR, GradeParams(temperature = 0.4f, saturation = 0.05f)),
        FilterPreset("cool", FilterCategory.COLOR, GradeParams(temperature = -0.4f, tint = 0.05f)),
        FilterPreset("pastel", FilterCategory.COLOR, GradeParams(saturation = -0.25f, fade = 0.25f, brightness = 0.05f)),
        FilterPreset("punch", FilterCategory.COLOR, GradeParams(contrast = 0.3f, saturation = 0.25f, shadows = -0.1f)),
        FilterPreset("teal_orange", FilterCategory.CINEMATIC, GradeParams(tealOrange = 0.7f, contrast = 0.15f, vignette = 0.25f)),
        FilterPreset("blockbuster", FilterCategory.CINEMATIC, GradeParams(contrast = 0.35f, tealOrange = 0.5f, saturation = 0.1f)),
        FilterPreset("night", FilterCategory.CINEMATIC, GradeParams(temperature = -0.3f, exposure = -0.2f, contrast = 0.2f, saturation = -0.2f)),
        FilterPreset("golden", FilterCategory.CINEMATIC, GradeParams(temperature = 0.3f, tealOrange = 0.3f, highlights = -0.15f, vignette = 0.3f)),
        FilterPreset("sepia", FilterCategory.VINTAGE, GradeParams(sepia = 1f)),
        FilterPreset("film70", FilterCategory.VINTAGE, GradeParams(sepia = 0.35f, fade = 0.25f, grain = 0.25f, temperature = 0.2f)),
        FilterPreset("instant", FilterCategory.VINTAGE, GradeParams(fade = 0.3f, temperature = 0.15f, saturation = -0.1f, highlights = 0.1f)),
        FilterPreset("vhs", FilterCategory.RETRO, GradeParams(chroma = 0.35f, scanlines = 0.4f, saturation = -0.15f, grain = 0.2f)),
        FilterPreset("faded", FilterCategory.RETRO, GradeParams(fade = 0.45f, contrast = -0.2f, saturation = -0.2f)),
        FilterPreset("retro80", FilterCategory.RETRO, GradeParams(saturation = 0.25f, tint = 0.25f, chroma = 0.2f)),
        FilterPreset("mono", FilterCategory.BW, GradeParams(bw = 1f)),
        FilterPreset("noir", FilterCategory.BW, GradeParams(bw = 1f, contrast = 0.45f, vignette = 0.4f)),
        FilterPreset("silver", FilterCategory.BW, GradeParams(bw = 1f, fade = 0.2f, brightness = 0.05f)),
        FilterPreset("dreamy", FilterCategory.MOOD, GradeParams(fade = 0.2f, brightness = 0.1f, saturation = -0.1f, highlights = 0.2f)),
        FilterPreset("moody", FilterCategory.MOOD, GradeParams(exposure = -0.15f, saturation = -0.3f, contrast = 0.2f, vignette = 0.35f)),
        FilterPreset("sunset", FilterCategory.MOOD, GradeParams(temperature = 0.5f, tint = 0.2f, saturation = 0.2f)),
    )

    fun filter(id: String): FilterPreset? = filters.firstOrNull { it.id == id }

    val effectCategories: Map<EffectType, EffectCategory> = mapOf(
        EffectType.GLITCH to EffectCategory.GLITCH,
        EffectType.CHROMATIC to EffectCategory.GLITCH,
        EffectType.MIRROR to EffectCategory.DISTORTION,
        EffectType.PIXELATE to EffectCategory.DISTORTION,
        EffectType.LIGHT_LEAK to EffectCategory.LIGHT,
        EffectType.BLUR to EffectCategory.BLUR,
        EffectType.SHARPEN to EffectCategory.SHARPNESS,
        EffectType.SCANLINES to EffectCategory.RETRO,
        EffectType.FILM_GRAIN to EffectCategory.MOOD,
        EffectType.VIGNETTE to EffectCategory.MOOD,
    )

    fun effectParams(e: EffectRef): GradeParams {
        val k = e.intensity.coerceIn(0f, 1f)
        return when (e.type) {
            EffectType.BLUR -> GradeParams(blur = k)
            EffectType.PIXELATE -> GradeParams(pixelate = k)
            EffectType.GLITCH -> GradeParams(glitch = k)
            EffectType.CHROMATIC -> GradeParams(chroma = k)
            EffectType.MIRROR -> GradeParams(mirror = if (k > 0f) 1f else 0f)
            EffectType.FILM_GRAIN -> GradeParams(grain = k)
            EffectType.SCANLINES -> GradeParams(scanlines = k)
            EffectType.LIGHT_LEAK -> GradeParams(lightLeak = k)
            EffectType.SHARPEN -> GradeParams(sharpen = k)
            EffectType.VIGNETTE -> GradeParams(vignette = k)
        }
    }

    fun fromAdjust(a: ColorAdjust) = GradeParams(
        brightness = a.brightness, contrast = a.contrast, saturation = a.saturation,
        exposure = a.exposure, temperature = a.temperature, tint = a.tint, shadows = a.shadows,
        highlights = a.highlights, vignette = a.vignette, sharpen = a.sharpen, grain = a.grain,
    )

    /** Combines manual adjustments, the selected filter (scaled by its intensity) and effects. */
    fun resolve(adjust: ColorAdjust, filter: FilterRef?, effects: List<EffectRef>): GradeParams {
        var p = fromAdjust(adjust)
        if (filter != null) {
            val preset = filter(filter.id)
            if (preset != null) p += preset.params * filter.intensity.coerceIn(0f, 1f)
        }
        for (e in effects) p += effectParams(e)
        return p.clamped()
    }
}
