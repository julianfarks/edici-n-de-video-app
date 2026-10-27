package com.vanta.studio.domain.subtitles

import com.vanta.studio.domain.model.Cue
import com.vanta.studio.domain.model.SubWord
import com.vanta.studio.domain.model.SubtitleKind
import com.vanta.studio.domain.model.SubtitleStyle

/** Groups recognised words into readable on-screen cues. */
object SubtitleBuilder {
    private const val PAUSE_BREAK_US = 700_000L
    private const val MIN_CUE_US = 600_000L
    private const val HOLD_US = 180_000L
    private const val GAP_US = 30_000L

    fun fromWords(words: List<SubWord>, style: SubtitleStyle, newId: () -> String): List<Cue> {
        val clean = words.filter { it.text.isNotBlank() && it.endUs >= it.startUs }.sortedBy { it.startUs }
        if (clean.isEmpty()) return emptyList()
        val maxWords = if (style.kind == SubtitleKind.CREATOR) minOf(style.maxWordsPerCue, 3) else style.maxWordsPerCue
        val maxChars = style.maxCharsPerLine * 2
        val groups = ArrayList<MutableList<SubWord>>()
        var cur = ArrayList<SubWord>()
        var chars = 0
        for (w in clean) {
            val gap = cur.lastOrNull()?.let { w.startUs - it.endUs } ?: 0L
            val wouldBe = chars + w.text.length + 1
            val br = cur.isNotEmpty() && (gap > PAUSE_BREAK_US || cur.size >= maxWords || wouldBe > maxChars)
            if (br) {
                groups += cur
                cur = ArrayList()
                chars = 0
            }
            cur += w
            chars += w.text.length + 1
        }
        if (cur.isNotEmpty()) groups += cur

        val cues = ArrayList<Cue>()
        for ((i, g) in groups.withIndex()) {
            val start = g.first().startUs
            var end = maxOf(g.last().endUs + HOLD_US, start + MIN_CUE_US)
            val nextStart = groups.getOrNull(i + 1)?.first()?.startUs
            if (nextStart != null) end = minOf(end, nextStart - GAP_US).coerceAtLeast(g.last().endUs)
            if (end <= start) end = start + 1
            cues += Cue(newId(), start, end, g.joinToString(" ") { it.text }, g.toList())
        }
        return cues
    }
}
