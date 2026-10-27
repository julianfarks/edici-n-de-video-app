package com.vanta.studio.engine.layers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.LruCache
import com.vanta.studio.domain.model.OverlayKind
import com.vanta.studio.domain.model.OverlayLayer
import com.vanta.studio.domain.model.TextAlign
import com.vanta.studio.domain.model.TextLayer
import com.vanta.studio.domain.model.TextStyle
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws text and overlay layers into bitmaps sized for a given frame. The same bitmaps are
 * shown in the preview (Compose) and burnt into the export (Media3 overlay), so a title
 * looks the same in both.
 */
object LayerRenderer {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun textKey(l: TextLayer, w: Int, h: Int) = "t|${l.text}|${l.style.hashCode()}|$w|$h"
    fun overlayKey(l: OverlayLayer, w: Int, h: Int) = "o|${l.kind}|${l.source}|${l.color}|${l.width}|$w|$h"

    fun text(layer: TextLayer, frameW: Int, frameH: Int): Bitmap {
        val key = textKey(layer, frameW, frameH)
        cache.get(key)?.let { return it }
        val bmp = renderText(layer.text, layer.style, frameW, frameH)
        cache.put(key, bmp)
        return bmp
    }

    fun overlay(context: Context, layer: OverlayLayer, frameW: Int, frameH: Int): Bitmap? {
        val key = overlayKey(layer, frameW, frameH)
        cache.get(key)?.let { return it }
        val bmp = renderOverlay(context, layer, frameW, frameH) ?: return null
        cache.put(key, bmp)
        return bmp
    }

    fun typeface(style: TextStyle): Typeface {
        val bold = style.bold || style.font.bold
        val s = when {
            bold && style.italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            style.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(style.font.family, s)
    }

    fun renderText(raw: String, s: TextStyle, frameW: Int, frameH: Int): Bitmap {
        val textPx = max(4f, s.size * frameH)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textPx
            typeface = typeface(s)
            letterSpacing = s.letterSpacing
            color = s.color.toInt()
        }
        val text = (if (s.uppercase) raw.uppercase() else raw).ifEmpty { " " }
        val maxW = max(1, (frameW * 0.92f).roundToInt())
        val desired = text.split('\n').maxOf { Layout.getDesiredWidth(it, paint) }
        val layoutW = min(ceil(desired).toInt() + 2, maxW).coerceAtLeast(1)
        val align = when (s.align) {
            TextAlign.LEFT -> Layout.Alignment.ALIGN_NORMAL
            TextAlign.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlign.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, layoutW)
            .setAlignment(align)
            .setLineSpacing(0f, s.lineSpacing.coerceIn(0.6f, 3f))
            .setIncludePad(true)
            .build()
        val strokePx = if (s.strokeColor != null) textPx * s.strokeWidth.coerceIn(0f, 0.5f) else 0f
        val shadowPx = if (s.shadow) textPx * 0.18f else 0f
        val bgPad = if (s.background != null) textPx * 0.35f else 0f
        val pad = bgPad + strokePx + shadowPx + 2f
        val bw = (layoutW + pad * 2).roundToInt().coerceAtLeast(1)
        val bh = (layout.height + pad * 2).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        s.background?.let { bg ->
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg.toInt() }
            val inset = strokePx + shadowPx
            canvas.drawRoundRect(RectF(inset, inset, bw - inset, bh - inset), textPx * 0.25f, textPx * 0.25f, p)
        }
        canvas.translate(pad, pad)
        if (s.strokeColor != null && strokePx > 0f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = strokePx * 2f
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = s.strokeColor.toInt()
            layout.draw(canvas)
        }
        paint.style = Paint.Style.FILL
        paint.color = s.color.toInt()
        if (s.shadow) paint.setShadowLayer(textPx * 0.15f, 0f, textPx * 0.06f, s.shadowColor.toInt())
        layout.draw(canvas)
        return bmp
    }

    fun renderOverlay(context: Context, layer: OverlayLayer, frameW: Int, frameH: Int): Bitmap? {
        val targetW = max(8, (layer.width * frameW).roundToInt())
        return when (layer.kind) {
            OverlayKind.IMAGE -> decodeImage(context, Uri.parse(layer.source), targetW)
            OverlayKind.STICKER -> renderEmoji(layer.source.removePrefix("emoji:"), targetW)
            OverlayKind.SHAPE -> renderShape(layer.source, layer.color.toInt(), targetW)
            OverlayKind.FRAME -> renderFrame(layer.source, layer.color.toInt(), frameW, frameH)
        }
    }

    private fun decodeImage(context: Context, uri: Uri, targetW: Int): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= targetW) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val decoded = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            decoded?.let {
                val h = max(1, (targetW.toFloat() * it.height / it.width).roundToInt())
                val scaled = Bitmap.createScaledBitmap(it, targetW, h, true)
                if (scaled !== it) it.recycle()
                scaled
            }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun renderEmoji(emoji: String, targetW: Int): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 100f }
        val measured = max(1f, paint.measureText(emoji))
        paint.textSize = 100f * targetW * 0.9f / measured
        val fm = paint.fontMetrics
        val h = max(1, ceil(fm.descent - fm.ascent).toInt())
        val bmp = Bitmap.createBitmap(targetW, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = paint.measureText(emoji)
        c.drawText(emoji, (targetW - w) / 2f, -fm.ascent, paint)
        return bmp
    }

    private fun renderShape(id: String, color: Int, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val s = size.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        when (id) {
            "circle" -> c.drawCircle(s / 2, s / 2, s / 2, paint)
            "square" -> c.drawRect(0f, 0f, s, s, paint)
            "rounded" -> c.drawRoundRect(0f, 0f, s, s, s * 0.22f, s * 0.22f, paint)
            "triangle" -> c.drawPath(Path().apply {
                moveTo(s / 2, s * 0.06f); lineTo(s, s * 0.94f); lineTo(0f, s * 0.94f); close()
            }, paint)
            "star" -> c.drawPath(starPath(s, 5, 0.42f), paint)
            "burst" -> c.drawPath(starPath(s, 12, 0.7f), paint)
            "heart" -> c.drawPath(Path().apply {
                moveTo(s / 2, s * 0.92f)
                cubicTo(s * -0.15f, s * 0.48f, s * 0.12f, s * -0.08f, s / 2, s * 0.26f)
                cubicTo(s * 0.88f, s * -0.08f, s * 1.15f, s * 0.48f, s / 2, s * 0.92f)
                close()
            }, paint)
            "arrow" -> c.drawPath(Path().apply {
                moveTo(0f, s * 0.38f); lineTo(s * 0.58f, s * 0.38f); lineTo(s * 0.58f, s * 0.16f)
                lineTo(s, s / 2); lineTo(s * 0.58f, s * 0.84f); lineTo(s * 0.58f, s * 0.62f)
                lineTo(0f, s * 0.62f); close()
            }, paint)
            "ring" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.1f
                c.drawCircle(s / 2, s / 2, s / 2 - s * 0.05f, paint)
            }
            "bubble" -> {
                c.drawRoundRect(0f, 0f, s, s * 0.72f, s * 0.16f, s * 0.16f, paint)
                c.drawPath(Path().apply {
                    moveTo(s * 0.22f, s * 0.68f); lineTo(s * 0.18f, s * 0.98f); lineTo(s * 0.48f, s * 0.68f); close()
                }, paint)
            }
            else -> c.drawCircle(s / 2, s / 2, s / 2, paint)
        }
        return bmp
    }

    private fun starPath(s: Float, points: Int, innerRatio: Float): Path {
        val path = Path()
        val cx = s / 2
        val cy = s / 2
        val outer = s / 2
        val inner = outer * innerRatio
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI / 2 + i * PI / points
            val x = cx + (r * cos(a)).toFloat()
            val y = cy + (r * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun renderFrame(id: String, color: Int, w: Int, h: Int): Bitmap {
        val bmp = Bitmap.createBitmap(max(1, w), max(1, h), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fw = w.toFloat()
        val fh = h.toFloat()
        val m = min(fw, fh)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        when (id) {
            "border" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = m * 0.05f
                c.drawRect(0f, 0f, fw, fh, paint)
            }
            "rounded_border" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = m * 0.025f
                val inset = m * 0.05f
                c.drawRoundRect(inset, inset, fw - inset, fh - inset, m * 0.06f, m * 0.06f, paint)
            }
            "cinema_bars" -> {
                paint.color = Color.BLACK
                val bar = fh * 0.12f
                c.drawRect(0f, 0f, fw, bar, paint)
                c.drawRect(0f, fh - bar, fw, fh, paint)
            }
            "film_strip" -> {
                val band = fw * 0.07f
                paint.color = Color.BLACK
                c.drawRect(0f, 0f, band, fh, paint)
                c.drawRect(fw - band, 0f, fw, fh, paint)
                paint.color = color
                val hole = band * 0.45f
                var y = hole * 0.6f
                while (y < fh) {
                    c.drawRoundRect(band * 0.27f, y, band * 0.27f + hole, y + hole * 0.7f, hole * 0.15f, hole * 0.15f, paint)
                    c.drawRoundRect(fw - band * 0.27f - hole, y, fw - band * 0.27f, y + hole * 0.7f, hole * 0.15f, hole * 0.15f, paint)
                    y += hole * 1.6f
                }
            }
            "corners" -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = m * 0.012f
                paint.strokeCap = Paint.Cap.ROUND
                val i = m * 0.06f
                val l = m * 0.12f
                c.drawLine(i, i, i + l, i, paint); c.drawLine(i, i, i, i + l, paint)
                c.drawLine(fw - i, i, fw - i - l, i, paint); c.drawLine(fw - i, i, fw - i, i + l, paint)
                c.drawLine(i, fh - i, i + l, fh - i, paint); c.drawLine(i, fh - i, i, fh - i - l, paint)
                c.drawLine(fw - i, fh - i, fw - i - l, fh - i, paint); c.drawLine(fw - i, fh - i, fw - i, fh - i - l, paint)
            }
            "glow" -> {
                val edge = m * 0.18f
                val transparent = color and 0x00FFFFFF
                val solid = (color and 0x00FFFFFF) or (0xAA shl 24)
                paint.shader = LinearGradient(0f, 0f, 0f, edge, solid, transparent, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, fw, edge, paint)
                paint.shader = LinearGradient(0f, fh, 0f, fh - edge, solid, transparent, Shader.TileMode.CLAMP)
                c.drawRect(0f, fh - edge, fw, fh, paint)
                paint.shader = LinearGradient(0f, 0f, edge, 0f, solid, transparent, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, edge, fh, paint)
                paint.shader = LinearGradient(fw, 0f, fw - edge, 0f, solid, transparent, Shader.TileMode.CLAMP)
                c.drawRect(fw - edge, 0f, fw, fh, paint)
            }
        }
        return bmp
    }
}
