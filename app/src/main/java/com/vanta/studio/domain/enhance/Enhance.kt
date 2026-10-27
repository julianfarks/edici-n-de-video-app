package com.vanta.studio.domain.enhance

import com.vanta.studio.domain.model.AspectRatio
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.ColorAdjust
import com.vanta.studio.domain.model.Project
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/** Measurements of one picture, all on a 0..1 scale. */
data class FrameStats(
    val meanLuma: Float,
    /** 5th and 95th percentile of luma: the real tonal range of the picture. */
    val p5: Float,
    val p95: Float,
    val saturation: Float,
    /** Red minus blue, positive = warm cast, negative = cool cast. */
    val warmth: Float,
    /** Edge energy relative to brightness; low values mean a soft or blurred picture. */
    val sharpness: Float,
    /** Share of pixels that are crushed to black / blown out to white. */
    val crushed: Float,
    val blown: Float,
)

/** What the audio of a clip measures like. */
data class AudioStats(val rmsDb: Float, val peak: Float)

object FrameAnalyzer {
    fun analyze(argb: IntArray, w: Int, h: Int): FrameStats {
        require(argb.size >= w * h && w >= 3 && h >= 3)
        val luma = FloatArray(w * h)
        val hist = IntArray(256)
        var sumL = 0.0
        var sumS = 0.0
        var sumR = 0.0
        var sumB = 0.0
        for (i in 0 until w * h) {
            val c = argb[i]
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            val l = 0.2126f * r + 0.7152f * g + 0.0722f * b
            luma[i] = l
            hist[(l * 255f).toInt().coerceIn(0, 255)]++
            sumL += l
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            sumS += if (mx > 0.02f) (mx - mn) / mx else 0f
            sumR += r
            sumB += b
        }
        val n = w * h
        fun pct(p: Float): Float {
            var acc = 0
            val target = (n * p).toInt()
            for (i in 0..255) {
                acc += hist[i]
                if (acc >= target) return i / 255f
            }
            return 1f
        }
        // Laplacian energy on the luma plane.
        var lap = 0.0
        var cnt = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = 4 * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - w] - luma[i + w]
            lap += v * v
            cnt++
        }
        val mean = (sumL / n).toFloat()
        val sharp = (sqrt(lap / cnt) / max(mean.toDouble(), 0.05)).toFloat()
        var crushed = 0
        var blown = 0
        for (i in 0..255) {
            if (i / 255f < 0.04f) crushed += hist[i]
            if (i / 255f > 0.97f) blown += hist[i]
        }
        return FrameStats(
            meanLuma = mean, p5 = pct(0.05f), p95 = pct(0.95f),
            saturation = (sumS / n).toFloat(), warmth = ((sumR - sumB) / n).toFloat(),
            sharpness = sharp, crushed = crushed.toFloat() / n, blown = blown.toFloat() / n,
        )
    }

    /** Average of several pictures of the same clip. */
    fun combine(list: List<FrameStats>): FrameStats {
        require(list.isNotEmpty())
        fun avg(f: (FrameStats) -> Float) = list.map(f).average().toFloat()
        return FrameStats(
            avg { it.meanLuma }, avg { it.p5 }, avg { it.p95 }, avg { it.saturation },
            avg { it.warmth }, avg { it.sharpness }, avg { it.crushed }, avg { it.blown },
        )
    }

    /** Level of a mono signal in dBFS (RMS) and its absolute peak. */
    fun audio(pcm: FloatArray): AudioStats {
        if (pcm.isEmpty()) return AudioStats(-120f, 0f)
        var s = 0.0
        var peak = 0f
        for (v in pcm) {
            s += v * v
            peak = max(peak, abs(v))
        }
        val rms = sqrt(s / pcm.size)
        return AudioStats((20 * log10(max(rms, 1e-6))).toFloat(), peak)
    }
}

enum class RecKind { BRIGHTEN, DARKEN, CONTRAST, SATURATION, COOL_CAST, WARM_CAST, SHARPEN, LIFT_SHADOWS, TAME_HIGHLIGHTS, VOLUME_UP, VOLUME_DOWN, AUDIO_CLIPPING, ORIENTATION }

/** A change the user can accept or ignore. [measured] is the number that triggered it, for the explanation. */
data class Recommendation(
    val kind: RecKind,
    val measured: Float,
    val adjust: ColorAdjust? = null,
    val volume: Float? = null,
    val aspect: AspectRatio? = null,
) {
    /** True when accepting this changes the project (some are advice only). */
    val actionable: Boolean get() = adjust != null || volume != null || aspect != null
}

object Enhance {
    fun recommend(stats: FrameStats?, audio: AudioStats?, clip: Clip, canvasAspect: AspectRatio): List<Recommendation> {
        val out = ArrayList<Recommendation>()
        if (stats != null) {
            val mono = stats.saturation < 0.03f
            if (stats.meanLuma < 0.30f) {
                val k = ((0.42f - stats.meanLuma) / 0.42f).coerceIn(0f, 1f)
                out += Recommendation(RecKind.BRIGHTEN, stats.meanLuma, ColorAdjust(exposure = 0.15f + 0.35f * k, shadows = 0.15f + 0.2f * k))
            } else if (stats.meanLuma > 0.72f) {
                val k = ((stats.meanLuma - 0.6f) / 0.4f).coerceIn(0f, 1f)
                out += Recommendation(RecKind.DARKEN, stats.meanLuma, ColorAdjust(exposure = -(0.1f + 0.3f * k)))
            }
            if (stats.crushed > 0.25f && stats.meanLuma >= 0.30f) {
                out += Recommendation(RecKind.LIFT_SHADOWS, stats.crushed, ColorAdjust(shadows = 0.3f))
            }
            if (stats.blown > 0.10f && stats.meanLuma <= 0.72f) {
                out += Recommendation(RecKind.TAME_HIGHLIGHTS, stats.blown, ColorAdjust(highlights = -0.3f))
            }
            val range = stats.p95 - stats.p5
            if (range < 0.45f) {
                out += Recommendation(RecKind.CONTRAST, range, ColorAdjust(contrast = (0.15f + (0.45f - range)).coerceAtMost(0.4f)))
            }
            if (!mono && stats.saturation < 0.18f) {
                out += Recommendation(RecKind.SATURATION, stats.saturation, ColorAdjust(saturation = 0.2f))
            }
            if (!mono && abs(stats.warmth) > 0.12f) {
                val corr = (abs(stats.warmth) * 1.2f).coerceAtMost(0.3f)
                out += if (stats.warmth > 0) Recommendation(RecKind.WARM_CAST, stats.warmth, ColorAdjust(temperature = -corr))
                else Recommendation(RecKind.COOL_CAST, stats.warmth, ColorAdjust(temperature = corr))
            }
            if (stats.sharpness < 0.10f) {
                out += Recommendation(RecKind.SHARPEN, stats.sharpness, ColorAdjust(sharpen = 0.25f))
            }
        }
        if (audio != null && clip.hasAudio && audio.rmsDb > -119f) {
            if (audio.peak >= 0.99f) {
                out += Recommendation(RecKind.AUDIO_CLIPPING, audio.peak) // cannot be repaired: advice only
            } else if (audio.rmsDb < -30f) {
                val gain = 10.0.pow((-20.0 - audio.rmsDb) / 20.0).toFloat().coerceAtMost(2f)
                out += Recommendation(RecKind.VOLUME_UP, audio.rmsDb, volume = (clip.volume * gain).coerceAtMost(2f))
            } else if (audio.rmsDb > -10f) {
                out += Recommendation(RecKind.VOLUME_DOWN, audio.rmsDb, volume = (clip.volume * 0.7f))
            }
        }
        if (clip.width > 0 && clip.height > 0) {
            val vertical = clip.height > clip.width * 1.2f
            val horizontal = clip.width > clip.height * 1.2f
            if (vertical && canvasAspect == AspectRatio.R16_9) out += Recommendation(RecKind.ORIENTATION, clip.height.toFloat() / clip.width, aspect = AspectRatio.R9_16)
            if (horizontal && canvasAspect == AspectRatio.R9_16) out += Recommendation(RecKind.ORIENTATION, clip.width.toFloat() / clip.height, aspect = AspectRatio.R16_9)
        }
        return out
    }

    private fun ColorAdjust.plus(d: ColorAdjust) = ColorAdjust(
        brightness = (brightness + d.brightness).coerceIn(-1f, 1f), contrast = (contrast + d.contrast).coerceIn(-1f, 1f),
        saturation = (saturation + d.saturation).coerceIn(-1f, 1f), exposure = (exposure + d.exposure).coerceIn(-1f, 1f),
        temperature = (temperature + d.temperature).coerceIn(-1f, 1f), tint = (tint + d.tint).coerceIn(-1f, 1f),
        shadows = (shadows + d.shadows).coerceIn(-1f, 1f), highlights = (highlights + d.highlights).coerceIn(-1f, 1f),
        vignette = (vignette + d.vignette).coerceIn(-1f, 1f), sharpen = (sharpen + d.sharpen).coerceIn(0f, 1f),
        grain = (grain + d.grain).coerceIn(0f, 1f),
    )

    /** Applies the chosen recommendations to [clipId] only; every other clip and layer is left as it was. */
    fun apply(project: Project, clipId: String, recs: List<Recommendation>): Project {
        var p = project
        for (r in recs) {
            r.adjust?.let { d -> p = p.copy(clips = p.clips.map { if (it.id == clipId) it.copy(adjust = it.adjust + d) else it }) }
            r.volume?.let { v -> p = p.copy(clips = p.clips.map { if (it.id == clipId) it.copy(volume = v) else it }) }
            r.aspect?.let { a -> p = p.copy(canvas = p.canvas.copy(aspect = a)) }
        }
        return p
    }
}
