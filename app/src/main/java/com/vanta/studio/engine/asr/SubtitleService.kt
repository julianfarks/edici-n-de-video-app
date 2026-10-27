package com.vanta.studio.engine.asr

import android.net.Uri
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.SubtitleTrack
import com.vanta.studio.domain.subtitles.SubtitleMapping
import com.vanta.studio.engine.audio.AudioDecodeException
import com.vanta.studio.engine.audio.AudioDecoder
import java.util.UUID

/** Runs speech recognition over a clip's used range and turns the result into timeline cues. */
class SubtitleService(private val decoder: AudioDecoder) {
    /**
     * Returns the project's subtitle track with the new cues merged in. Throws [SpeechException]
     * or [AudioDecodeException]; the caller shows the message and keeps the project unchanged.
     */
    suspend fun generate(
        project: Project,
        clipId: String,
        engine: SpeechEngine,
        language: String,
        onProgress: (Float) -> Unit,
    ): SubtitleTrack {
        val clip = project.clips.firstOrNull { it.id == clipId }
            ?: throw SpeechException(SpeechException.Kind.NO_AUDIO, "Clip not found")
        val start = SubtitleMapping.clipStartUs(project, clipId)!!
        val audio = try {
            decoder.decode(Uri.parse(clip.uri), SpeechEngine.RATE, clip.trimStartUs, clip.trimEndUs) { onProgress(it * 0.15f) }
        } catch (e: AudioDecodeException) {
            throw SpeechException(SpeechException.Kind.NO_AUDIO, e.message ?: "No audio", e)
        }
        val transcript = engine.transcribe(audio, language) { onProgress(0.15f + it * 0.85f) }
        val words = SubtitleMapping.wordsToTimeline(transcript.words, clip, start)
        if (words.isEmpty() && transcript.segments.isNotEmpty()) {
            // Engine gave no word times: split each segment's text evenly over its span, flagged as estimated.
            val est = transcript.segments.flatMap { s ->
                val ws = s.text.split(Regex("\\s+")).filter { it.isNotEmpty() }
                val step = (s.endUs - s.startUs) / ws.size.coerceAtLeast(1)
                ws.mapIndexed { i, w -> com.vanta.studio.domain.model.SubWord(w, s.startUs + i * step, s.startUs + (i + 1) * step) }
            }
            val mapped = SubtitleMapping.wordsToTimeline(est, clip, start)
            return SubtitleMapping.merge(project.subtitles, mapped, language, engine.id) { UUID.randomUUID().toString() }
        }
        return SubtitleMapping.merge(project.subtitles, words, language, engine.id) { UUID.randomUUID().toString() }
    }
}
