package com.vanta.studio.domain.fx

import com.vanta.studio.domain.model.FxEvent
import com.vanta.studio.domain.model.FxType

/** Defaults and presentation hints for each effect, so the UI and the auto-sync agree. */
data class FxDef(
    val type: FxType,
    /** Stable id, also the suffix of the string resource `fx_<id>` and `fx_<id>_desc`. */
    val id: String,
    val defaultDurationUs: Long,
    val defaultPeak: Float,
    val defaultColor: Long,
    val usesColor: Boolean,
    /** True for effects that flash the whole frame; they are limited by the safety setting. */
    val isFlashing: Boolean,
    val category: FxCategory,
)

enum class FxCategory { FLASH, LIGHT, LENS, MOTION, COLOR, GLITCH }

object FxLibrary {
    private const val WHITE = 0xFFFFFFFFL
    private const val WARM = 0xFFFF9A4DL
    private const val VIOLET = 0xFF8B6CFFL
    private const val GOLD = 0xFFFFE3A3L

    val all: List<FxDef> = listOf(
        FxDef(FxType.FLASH_WHITE, "flash_white", 300_000, 0.08f, WHITE, false, true, FxCategory.FLASH),
        FxDef(FxType.FLASH_BLACK, "flash_black", 300_000, 0.08f, 0xFF000000, false, true, FxCategory.FLASH),
        FxDef(FxType.FLASH_COLOR, "flash_color", 350_000, 0.08f, VIOLET, true, true, FxCategory.FLASH),
        FxDef(FxType.FLASH_ZOOM, "flash_zoom", 400_000, 0.10f, WHITE, true, true, FxCategory.FLASH),
        FxDef(FxType.RADIAL_BURST, "radial", 500_000, 0.10f, WHITE, true, false, FxCategory.LIGHT),
        FxDef(FxType.GLOW, "glow", 600_000, 0.15f, WHITE, true, false, FxCategory.LIGHT),
        FxDef(FxType.BLOOM, "bloom", 600_000, 0.15f, WHITE, true, false, FxCategory.LIGHT),
        FxDef(FxType.LIGHT_LEAK, "leak", 900_000, 0.30f, WARM, true, false, FxCategory.LIGHT),
        FxDef(FxType.LENS_FLARE, "flare", 700_000, 0.30f, GOLD, true, false, FxCategory.LENS),
        FxDef(FxType.LIGHT_SWEEP, "sweep", 700_000, 0.50f, WHITE, true, false, FxCategory.LENS),
        FxDef(FxType.CHROMATIC, "chromatic", 350_000, 0.12f, WHITE, false, false, FxCategory.GLITCH),
        FxDef(FxType.GLITCH, "glitch", 300_000, 0.10f, WHITE, false, false, FxCategory.GLITCH),
        FxDef(FxType.IMPACT_ZOOM, "impact", 350_000, 0.10f, WHITE, false, false, FxCategory.MOTION),
        FxDef(FxType.SHAKE, "shake", 400_000, 0.10f, WHITE, false, false, FxCategory.MOTION),
        FxDef(FxType.MOTION_BLUR, "motionblur", 350_000, 0.15f, WHITE, false, false, FxCategory.MOTION),
        FxDef(FxType.INVERT, "invert", 180_000, 0.10f, WHITE, false, true, FxCategory.COLOR),
        FxDef(FxType.EXPOSURE, "exposure", 400_000, 0.10f, WHITE, false, true, FxCategory.COLOR),
        FxDef(FxType.SATURATION_PULSE, "saturation", 500_000, 0.15f, WHITE, false, false, FxCategory.COLOR),
    )

    private val byType = all.associateBy { it.type }
    fun def(type: FxType): FxDef = byType.getValue(type)

    fun create(
        id: String,
        type: FxType,
        startUs: Long,
        intensity: Float = 1f,
        durationUs: Long? = null,
        color: Long? = null,
    ): FxEvent {
        val d = def(type)
        return FxEvent(
            id = id,
            type = type,
            startUs = startUs.coerceAtLeast(0),
            durationUs = durationUs ?: d.defaultDurationUs,
            intensity = intensity,
            color = color ?: d.defaultColor,
            peak = d.defaultPeak,
        )
    }
}
