package com.vanta.studio.engine.layers

import android.graphics.Bitmap
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlaySettings
import androidx.media3.effect.StaticOverlaySettings
import com.vanta.studio.domain.anim.LayerAnimator
import com.vanta.studio.domain.anim.LayerFrame
import com.vanta.studio.domain.model.OverlayLayer
import com.vanta.studio.domain.model.TextLayer
import com.vanta.studio.engine.ClipClock

/** A text/sticker/image layer burnt into the exported video, animated like in the preview. */
class LayerOverlay(
    private val bitmap: Bitmap,
    private val clock: ClipClock,
    private val frameAt: (Long) -> LayerFrame?,
) : BitmapOverlay() {

    override fun getBitmap(presentationTimeUs: Long): Bitmap = bitmap

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
        val f = frameAt(clock.compositionUs(presentationTimeUs)) ?: return HIDDEN
        return settingsFor(f)
    }

    companion object {
        private val HIDDEN: OverlaySettings = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

        /** Maps a layer frame (centre in 0..1, y down, clockwise degrees) to Media3 settings. */
        fun settingsFor(f: LayerFrame): OverlaySettings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(f.x * 2f - 1f, 1f - f.y * 2f)
            .setScale(f.scale, f.scale)
            .setRotationDegrees(-f.rotation)
            .setAlphaScale(f.alpha.coerceIn(0f, 1f))
            .build()

        fun forText(layer: TextLayer, bitmap: Bitmap, clock: ClipClock) = LayerOverlay(bitmap, clock) { t ->
            LayerAnimator.evaluate(
                layer.startUs, layer.durationUs, layer.transform, layer.keyframes,
                layer.animIn, layer.animOut, layer.animDurationUs, t,
            )
        }

        fun forOverlay(layer: OverlayLayer, bitmap: Bitmap, clock: ClipClock) = LayerOverlay(bitmap, clock) { t ->
            LayerAnimator.evaluate(
                layer.startUs, layer.durationUs, layer.transform, layer.keyframes,
                layer.animIn, layer.animOut, layer.animDurationUs, t,
            )
        }
    }
}
