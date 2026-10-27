package com.vanta.studio.engine.layers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.util.LruCache
import androidx.media3.common.OverlaySettings
import androidx.media3.effect.BitmapOverlay
import com.vanta.studio.domain.model.Cue
import com.vanta.studio.domain.model.SubtitleStyle
import com.vanta.studio.domain.model.SubtitleTrack
import com.vanta.studio.domain.model.TextStyle
import com.vanta.studio.domain.subtitles.SubState
import com.vanta.studio.domain.subtitles.SubtitleRender
import com.vanta.studio.engine.ClipClock
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a subtitle cue (with the spoken word highlighted, or only the words said so far) into a
 * bitmap. The preview shows these bitmaps with Compose and the exporter burns the very same ones
 * in, so what you see is what is exported.
 */
object SubtitleRenderer {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun key(state: SubState, style: SubtitleStyle, w: Int, h: Int) =
        "s|${state.cue.id}|${state.cue.text}|${state.activeWord}|${state.shown}|${style.hashCode()}|$w|$h"

    fun bitmap(state: SubState, style: SubtitleStyle, frameW: Int, frameH: Int): Bitmap {
        val k = key(state, style, frameW, frameH)
        cache.get(k)?.let { return it }
        val bmp = render(state.cue, style, state.activeWord, state.shown, frameW, frameH)
        cache.put(k, bmp)
        return bmp
    }

    fun render(cue: Cue, style: SubtitleStyle, activeWord: Int, shown: Int, frameW: Int, frameH: Int): Bitmap {
        val ts = TextStyle(font = style.font, size = style.size)
        val textPx = max(6f, style.size * frameH)
        val base = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textPx
            typeface = LayerRenderer.typeface(ts)
            color = style.color.toInt()
            letterSpacing = if (style.kind == com.vanta.studio.domain.model.SubtitleKind.CINEMATIC) 0.12f else 0f
        }
        val raw = if (cue.words.isNotEmpty()) cue.words.joinToString(" ") { it.text } else cue.text
        val text = if (style.uppercase) raw.uppercase() else raw
        val maxW = max(1, (frameW * 0.9f).roundToInt())
        val desired = text.split('\n').maxOf { Layout.getDesiredWidth(it, base) }
        val layoutW = min(ceil(desired).toInt() + 2, maxW).coerceAtLeast(1)

        // Two spannables with identical text: one carries the colours, the other only hides
        // words that are not visible yet, so outline and shadow follow the same layout.
        val colored = SpannableStringBuilder(text)
        val plain = SpannableStringBuilder(text)
        if (cue.words.isNotEmpty()) {
            var pos = 0
            for ((i, w) in cue.words.withIndex()) {
                val len = w.text.length
                val end = min(pos + len, text.length)
                if (pos < end) {
                    val visible = i < shown
                    val color = if (visible) SubtitleRender.colorForWord(style, i, activeWord) else 0x00000000L
                    colored.setSpan(ForegroundColorSpan(color.toInt()), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (!visible) plain.setSpan(ForegroundColorSpan(Color.TRANSPARENT), pos, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                pos = end + 1
            }
        }
        fun layout(sp: CharSequence, paint: TextPaint) = StaticLayout.Builder.obtain(sp, 0, sp.length, paint, layoutW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, 1.05f)
            .setIncludePad(true)
            .build()

        val fillPaint = TextPaint(base)
        val strokePaint = TextPaint(base)
        val strokePx = if (style.strokeColor != null) textPx * style.strokeWidth.coerceIn(0f, 0.4f) else 0f
        val shadowPx = if (style.shadow) textPx * 0.18f else 0f
        val bgPad = if (style.background != null) textPx * 0.4f else 0f
        val pad = bgPad + strokePx + shadowPx + 2f
        val fillLayout = layout(colored, fillPaint)
        val strokeLayout = layout(plain, strokePaint)
        val bw = (layoutW + pad * 2).roundToInt().coerceAtLeast(1)
        val bh = (fillLayout.height + pad * 2).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        style.background?.let { bg ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.toInt() }
            val inset = strokePx + shadowPx
            canvas.drawRoundRect(RectF(inset, inset, bw - inset, bh - inset), textPx * 0.28f, textPx * 0.28f, p)
        }
        canvas.translate(pad, pad)
        if (style.strokeColor != null && strokePx > 0f) {
            strokePaint.style = Paint.Style.STROKE
            strokePaint.strokeWidth = strokePx * 2f
            strokePaint.strokeJoin = Paint.Join.ROUND
            strokePaint.color = style.strokeColor.toInt()
            strokeLayout.draw(canvas)
        }
        fillPaint.style = Paint.Style.FILL
        if (style.shadow) fillPaint.setShadowLayer(textPx * 0.15f, 0f, textPx * 0.06f, 0xAA000000.toInt())
        fillLayout.draw(canvas)
        return bmp
    }

    private val EMPTY: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    /** Burns the subtitle track into the exported video for one clip's time span. */
    class Overlay(
        private val track: SubtitleTrack,
        private val frameW: Int,
        private val frameH: Int,
        private val clock: ClipClock,
    ) : BitmapOverlay() {
        @Volatile
        private var last: SubState? = null

        private fun state(pts: Long): SubState? = SubtitleRender.stateAt(track, clock.compositionUs(pts))

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val s = state(presentationTimeUs)
            last = s
            return if (s == null) EMPTY else bitmap(s, track.style, frameW, frameH)
        }

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val s = last ?: state(presentationTimeUs) ?: return LayerOverlay.HIDDEN_SETTINGS
            return LayerOverlay.settingsFor(s.frame)
        }
    }
}
