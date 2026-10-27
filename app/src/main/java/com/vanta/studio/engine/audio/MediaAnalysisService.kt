package com.vanta.studio.engine.audio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.vanta.studio.domain.audio.SilenceDetector
import com.vanta.studio.domain.enhance.AudioStats
import com.vanta.studio.domain.enhance.ClipFeatures
import com.vanta.studio.domain.enhance.FrameAnalyzer
import com.vanta.studio.domain.enhance.FrameStats
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class AnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** What was measured on one clip for "Improve this video". Either part may be missing (no audio, unreadable picture). */
data class EnhanceReport(val clipId: String, val frame: FrameStats?, val audio: AudioStats?, val framesUsed: Int)

/** What was measured on one clip for "Best moments". Times are relative to the start of the trimmed clip. */
data class HighlightData(
    val clipId: String,
    val features: ClipFeatures,
    val totalUs: Long,
    val speechSeparable: Boolean,
    /** The part of the source this was measured on; if the clip is trimmed differently later, the result is stale. */
    val trimStartUs: Long,
    val trimEndUs: Long,
    val speed: Float,
)

/**
 * Measures real pictures and real audio of a clip: sampled frames (luma, colour, edges, frame-to-frame
 * change) and the decoded audio (level, loudness over time, silence). Everything runs off the main
 * thread, reports progress and stops when cancelled.
 */
class MediaAnalysisService(private val context: Context, private val decoder: AudioDecoder) {
    private fun clipOf(project: Project, id: String) =
        project.clips.firstOrNull { it.id == id } ?: throw AnalysisException("Clip not found")

    private fun toArgb(b: Bitmap): Triple<IntArray, Int, Int> {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Triple(px, b.width, b.height)
    }

    private fun scaledSize(c: Clip, longSide: Int): Pair<Int, Int> {
        val w = if (c.width > 0) c.width else 16
        val h = if (c.height > 0) c.height else 9
        return if (w >= h) longSide to max(8, longSide * h / w) else max(8, longSide * w / h) to longSide
    }

    suspend fun enhance(project: Project, clipId: String, onProgress: (Float) -> Unit): EnhanceReport = withContext(Dispatchers.Default) {
        val clip = clipOf(project, clipId)
        val stats = ArrayList<FrameStats>()
        if (clip.isStill) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bmp = context.contentResolver.openInputStream(Uri.parse(clip.uri))?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw AnalysisException("Cannot read the picture")
            val (px, w, h) = toArgb(bmp)
            stats += FrameAnalyzer.analyze(px, w, h)
        } else {
            val (w, h) = scaledSize(clip, 192)
            val r = MediaMetadataRetriever()
            try {
                try {
                    r.setDataSource(context, Uri.parse(clip.uri))
                } catch (e: Exception) {
                    throw AnalysisException("Cannot open the video", e)
                }
                val n = 12
                for (i in 0 until n) {
                    ensureActive()
                    val t = clip.trimStartUs + (clip.trimmedSourceUs * (i + 0.5) / n).toLong()
                    val f = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
                    if (f != null) {
                        val (px, fw, fh) = toArgb(f)
                        stats += FrameAnalyzer.analyze(px, fw, fh)
                    }
                    onProgress(0.6f * (i + 1) / n)
                }
            } finally {
                r.release()
            }
        }
        var audio: AudioStats? = null
        if (clip.hasAudio && !clip.isStill) {
            try {
                val pcm = decoder.decode(Uri.parse(clip.uri), 16_000, clip.trimStartUs, clip.trimEndUs, maxDurationUs = 10L * 60 * 1_000_000) { onProgress(0.6f + 0.4f * it) }
                audio = FrameAnalyzer.audio(pcm.pcm)
            } catch (e: AudioDecodeException) {
                audio = null
            }
        }
        onProgress(1f)
        if (stats.isEmpty() && audio == null) throw AnalysisException("Nothing could be measured")
        EnhanceReport(clipId, stats.takeIf { it.isNotEmpty() }?.let { FrameAnalyzer.combine(it) }, audio, stats.size)
    }

    suspend fun highlights(project: Project, clipId: String, onProgress: (Float) -> Unit): HighlightData = withContext(Dispatchers.Default) {
        val clip = clipOf(project, clipId)
        if (clip.isStill) throw AnalysisException("Best moments needs a video clip")
        val total = clip.trimmedSourceUs
        // One sample per second, at most ~240 samples for long videos.
        val step = max(1_000_000L, total / 240)
        val n = (total / step).toInt().coerceAtLeast(2)

        // picture: change between consecutive sampled frames (movement or a shot change)
        val motion = FloatArray(n)
        val (w, h) = scaledSize(clip, 96)
        val r = MediaMetadataRetriever()
        try {
            try {
                r.setDataSource(context, Uri.parse(clip.uri))
            } catch (e: Exception) {
                throw AnalysisException("Cannot open the video", e)
            }
            var prev: FloatArray? = null
            for (i in 0 until n) {
                ensureActive()
                val t = clip.trimStartUs + i * step + step / 2
                val f = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST, w, h)
                if (f != null) {
                    val (px, fw, fh) = toArgb(f)
                    val l = FloatArray(fw * fh) { k ->
                        val c = px[k]
                        (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 255f
                    }
                    prev?.let { p -> if (p.size == l.size) { var s = 0f; for (k in l.indices) s += abs(l[k] - p[k]); motion[i] = s / l.size } }
                    prev = l
                }
                onProgress(0.6f * (i + 1) / n)
            }
        } finally {
            r.release()
        }

        // sound: loudness per step, and where sound is present at all
        val audio = FloatArray(n)
        var speech: FloatArray? = null
        var separable = false
        if (clip.hasAudio) {
            try {
                val pcm = decoder.decode(Uri.parse(clip.uri), 16_000, clip.trimStartUs, clip.trimEndUs, maxDurationUs = 30L * 60 * 1_000_000) { onProgress(0.6f + 0.3f * it) }
                val perStep = (pcm.sampleRate.toLong() * step / 1_000_000).toInt().coerceAtLeast(1)
                for (i in 0 until n) {
                    val a = i * perStep
                    val b = minOf(pcm.pcm.size, a + perStep)
                    if (a >= b) break
                    var s = 0.0
                    for (k in a until b) s += pcm.pcm[k] * pcm.pcm[k]
                    val db = 20 * log10(max(sqrt(s / (b - a)), 1e-6))
                    audio[i] = ((db + 60.0) / 50.0).coerceIn(0.0, 1.0).toFloat() // -60..-10 dBFS
                }
                val res = SilenceDetector.detect(pcm.pcm, pcm.sampleRate, pcm.startUs, SilenceDetector.Options(minPauseUs = 300_000, marginUs = 0, sensitivity = 0.5f))
                separable = res.separable
                if (res.separable) {
                    speech = FloatArray(n) { i ->
                        val a = clip.trimStartUs + i * step
                        val b = a + step
                        val silent = res.cuts.sumOf { c -> max(0L, minOf(b, c.endUs) - max(a, c.startUs)) }
                        1f - (silent.toFloat() / step).coerceIn(0f, 1f)
                    }
                }
            } catch (e: AudioDecodeException) {
                // picture-only analysis
            }
        }
        onProgress(1f)
        HighlightData(clipId, ClipFeatures(step, audio, motion, speech), total, separable, clip.trimStartUs, clip.trimEndUs, clip.speed)
    }
}
