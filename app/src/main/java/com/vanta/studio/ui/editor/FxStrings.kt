package com.vanta.studio.ui.editor

import androidx.annotation.StringRes
import com.vanta.studio.R
import com.vanta.studio.domain.model.FxType

@StringRes
fun fxNameRes(t: FxType): Int = when (t) {
        FxType.FLASH_WHITE -> R.string.fx_flash_white
        FxType.FLASH_BLACK -> R.string.fx_flash_black
        FxType.FLASH_COLOR -> R.string.fx_flash_color
        FxType.RADIAL_BURST -> R.string.fx_radial_burst
        FxType.GLOW -> R.string.fx_glow
        FxType.BLOOM -> R.string.fx_bloom
        FxType.LIGHT_LEAK -> R.string.fx_light_leak
        FxType.LENS_FLARE -> R.string.fx_lens_flare
        FxType.LIGHT_SWEEP -> R.string.fx_light_sweep
        FxType.CHROMATIC -> R.string.fx_chromatic
        FxType.GLITCH -> R.string.fx_glitch
        FxType.IMPACT_ZOOM -> R.string.fx_impact_zoom
        FxType.SHAKE -> R.string.fx_shake
        FxType.MOTION_BLUR -> R.string.fx_motion_blur
        FxType.INVERT -> R.string.fx_invert
        FxType.EXPOSURE -> R.string.fx_exposure
        FxType.SATURATION_PULSE -> R.string.fx_saturation_pulse
        FxType.FLASH_ZOOM -> R.string.fx_flash_zoom
}
