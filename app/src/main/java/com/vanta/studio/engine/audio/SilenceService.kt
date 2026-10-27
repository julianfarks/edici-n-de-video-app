package com.vanta.studio.engine.audio

import android.net.Uri
import com.vanta.studio.domain.audio.SilenceDetector
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.TimeRange
import com.vanta.studio.domain.subtitles.SubtitleMapping
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pauses found in a clip, as cuts on the project timeline, waiting for the user's review. */
data class SilenceProposal(
    val clipId: String,
    val cuts: List<TimeRange>,
    /** False when speech and pauses are too similar in level to tell apart. */
    val separable: Boolean,
)

class SilenceService(private val decoder: AudioDecoder) {
    suspend fun analyze(project: Project, clipId: String, o: SilenceDetector.Options, onProgress: (Float) -> Unit): SilenceProposal {
        val clip = project.clips.firstOrNull { it.id == clipId } ?: throw AudioDecodeException("Clip not found")
        val start = SubtitleMapping.clipStartUs(project, clipId) ?: throw AudioDecodeException("Clip not found")
        val audio = decoder.decode(Uri.parse(clip.uri), 16_000, clip.trimStartUs, clip.trimEndUs) { onProgress(it * 0.7f) }
        val res = withContext(Dispatchers.Default) { SilenceDetector.detect(audio.pcm, audio.sampleRate, audio.startUs, o) }
        onProgress(1f)
        fun toTimeline(src: Long) = start + ((src - clip.trimStartUs) / clip.speed.toDouble()).toLong()
        val cuts = res.cuts.map { TimeRange(toTimeline(it.startUs), toTimeline(it.endUs)) }
            .filter { it.durationUs >= 80_000 }
        return SilenceProposal(clipId, cuts, res.separable)
    }
}
