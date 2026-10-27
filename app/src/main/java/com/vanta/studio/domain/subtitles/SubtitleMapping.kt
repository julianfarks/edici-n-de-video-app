package com.vanta.studio.domain.subtitles

import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.Cue
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.SubWord
import com.vanta.studio.domain.model.SubtitleStyle
import com.vanta.studio.domain.model.SubtitleTrack

/** Moves recognised words from source-media time onto the project timeline. */
object SubtitleMapping {
    /** Timeline start of the main-track clip with [clipId], or null. */
    fun clipStartUs(project: Project, clipId: String): Long? {
        var start = 0L
        for (c in project.clips) {
            if (c.id == clipId) return start
            start += c.durationUs
        }
        return null
    }

    /**
     * Words of [clip] in source time → timeline time, honouring trim and speed. Words outside the
     * trimmed range are dropped; words straddling the edge are clamped.
     */
    fun wordsToTimeline(words: List<SubWord>, clip: Clip, clipStartUs: Long): List<SubWord> =
        words.mapNotNull { w ->
            if (w.endUs <= clip.trimStartUs || w.startUs >= clip.trimEndUs) return@mapNotNull null
            val s = maxOf(w.startUs, clip.trimStartUs)
            val e = minOf(w.endUs, clip.trimEndUs)
            fun map(t: Long) = clipStartUs + ((t - clip.trimStartUs) / clip.speed.toDouble()).toLong()
            val ms = map(s)
            val me = maxOf(map(e), ms + 1)
            SubWord(w.text, ms, me)
        }

    /**
     * Builds cues from [words] (timeline time) and merges them into [existing]: cues that overlap
     * the new range are replaced, the rest are kept. The existing style is preserved.
     */
    fun merge(
        existing: SubtitleTrack?,
        words: List<SubWord>,
        language: String,
        engine: String,
        newId: () -> String,
    ): SubtitleTrack {
        val style: SubtitleStyle = existing?.style ?: SubtitleStyle()
        val fresh: List<Cue> = SubtitleBuilder.fromWords(words, style, newId)
        if (fresh.isEmpty()) return existing ?: SubtitleTrack(language = language, style = style, engine = engine)
        val lo = fresh.first().startUs
        val hi = fresh.last().endUs
        val kept = existing?.cues.orEmpty().filter { it.endUs <= lo || it.startUs >= hi }
        return (existing ?: SubtitleTrack(language = language, style = style)).copy(
            language = language,
            cues = (kept + fresh).sortedBy { it.startUs },
            engine = engine,
            enabled = true,
        )
    }
}
