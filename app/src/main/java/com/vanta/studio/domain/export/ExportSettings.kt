package com.vanta.studio.domain.export

import com.vanta.studio.domain.model.CanvasSpec
import kotlin.math.roundToInt

enum class ExportResolution(val shortSide: Int) { P480(480), P720(720), P1080(1080), P2160(2160) }

enum class ExportQuality(val bitsPerPixel: Float) { LOW(0.06f), STANDARD(0.1f), HIGH(0.16f) }

data class ExportSettings(
    val resolution: ExportResolution = ExportResolution.P1080,
    val fps: Int = 30,
    val quality: ExportQuality = ExportQuality.STANDARD,
    val includeAudio: Boolean = true,
    val fileName: String = "",
)

object ExportMath {
    const val AUDIO_BITRATE = 128_000
    const val MIN_VIDEO_BITRATE = 1_000_000
    const val MAX_VIDEO_BITRATE = 80_000_000

    fun outputSize(canvas: CanvasSpec, resolution: ExportResolution): Pair<Int, Int> =
        canvas.sizeForShortSide(resolution.shortSide)

    /** Video bitrate from a bits-per-pixel budget; 60 fps gets 1.5x rather than 2x. */
    fun videoBitrate(width: Int, height: Int, fps: Int, quality: ExportQuality): Int {
        val fpsFactor = if (fps > 30) 30f * 1.5f else fps.coerceAtLeast(1).toFloat()
        val raw = width.toFloat() * height * fpsFactor * quality.bitsPerPixel
        return raw.roundToInt().coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)
    }

    /** Rough size of the output file, with a 10 % container/variance margin. */
    fun estimateBytes(durationUs: Long, videoBitrate: Int, includeAudio: Boolean): Long {
        val bps = videoBitrate.toLong() + if (includeAudio) AUDIO_BITRATE else 0
        return (bps * (durationUs / 1_000_000.0) / 8.0 * 1.1).toLong()
    }

    private val illegal = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /** Safe file name ending in .mp4; falls back to [fallback] when nothing usable remains. */
    fun sanitizeFileName(name: String, fallback: String): String {
        var base = name.trim().removeSuffix(".mp4").removeSuffix(".MP4")
        base = base.replace(illegal, "_").trim('.', ' ')
        if (base.isEmpty()) base = fallback
        if (base.length > 80) base = base.take(80)
        return "$base.mp4"
    }
}
