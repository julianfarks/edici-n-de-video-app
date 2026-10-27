package com.vanta.studio.domain.subtitles

import com.vanta.studio.domain.model.Cue
import com.vanta.studio.domain.model.SubWord
import com.vanta.studio.domain.model.SubtitleTrack
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Editing operations on a [SubtitleTrack]. All are pure; the editor wraps them in undo steps. */
object SubtitleOps {
    const val MIN_CUE_US = 200_000L

    private fun withCues(t: SubtitleTrack, cues: List<Cue>) = t.copy(cues = cues.sortedBy { it.startUs })

    fun cueAt(t: SubtitleTrack, timeUs: Long): Cue? = t.cues.firstOrNull { timeUs >= it.startUs && timeUs < it.endUs }

    fun update(t: SubtitleTrack, id: String, f: (Cue) -> Cue): SubtitleTrack =
        withCues(t, t.cues.map { if (it.id == id) f(it) else it })

    fun delete(t: SubtitleTrack, id: String): SubtitleTrack = t.copy(cues = t.cues.filterNot { it.id == id })

    /**
     * Replaces the text of a cue. If the number of words is unchanged the measured word timings
     * are kept (a spelling fix). Otherwise the new words are spread over the cue in proportion to
     * their length and the cue is marked as estimated.
     */
    fun setText(c: Cue, text: String): Cue {
        val trimmed = text.trim()
        val newWords = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (c.words.isEmpty()) return c.copy(text = trimmed)
        if (newWords.size == c.words.size) {
            return c.copy(text = trimmed, words = c.words.mapIndexed { i, w -> w.copy(text = newWords[i]) })
        }
        return c.copy(text = trimmed, words = spread(newWords, c.startUs, c.endUs), estimated = true)
    }

    private fun spread(words: List<String>, startUs: Long, endUs: Long): List<SubWord> {
        if (words.isEmpty()) return emptyList()
        val total = words.sumOf { it.length + 1 }.toDouble()
        var cursor = startUs.toDouble()
        val span = (endUs - startUs).toDouble()
        return words.map { w ->
            val len = span * (w.length + 1) / total
            val sw = SubWord(w, cursor.roundToLong(), (cursor + len).roundToLong())
            cursor += len
            sw
        }
    }

    /** Moves/resizes a cue; measured word timings are rescaled to the new span. */
    fun retime(c: Cue, startUs: Long, endUs: Long): Cue {
        val s = max(0L, startUs)
        val e = max(s + MIN_CUE_US, endUs)
        if (c.words.isEmpty() || c.durationUs <= 0) return c.copy(startUs = s, endUs = e)
        val k = (e - s).toDouble() / c.durationUs
        return c.copy(
            startUs = s,
            endUs = e,
            words = c.words.map {
                SubWord(it.text, s + ((it.startUs - c.startUs) * k).roundToLong(), s + ((it.endUs - c.startUs) * k).roundToLong())
            },
        )
    }

    fun shift(t: SubtitleTrack, deltaUs: Long): SubtitleTrack = t.copy(
        cues = t.cues.map { c ->
            val d = max(deltaUs, -c.startUs)
            c.copy(startUs = c.startUs + d, endUs = c.endUs + d, words = c.words.map { it.copy(startUs = it.startUs + d, endUs = it.endUs + d) })
        },
    )

    /** Splits a cue at [atUs]. Uses word boundaries when available, otherwise the nearest space. */
    fun split(t: SubtitleTrack, id: String, atUs: Long, newId: () -> String): SubtitleTrack? {
        val c = t.cues.firstOrNull { it.id == id } ?: return null
        if (atUs <= c.startUs + MIN_CUE_US / 2 || atUs >= c.endUs - MIN_CUE_US / 2) return null
        val left: Cue
        val right: Cue
        if (c.words.size >= 2) {
            val idx = c.words.indexOfFirst { it.startUs >= atUs }.let { if (it <= 0) c.words.size / 2 else it }
            val lw = c.words.subList(0, idx)
            val rw = c.words.subList(idx, c.words.size)
            if (lw.isEmpty() || rw.isEmpty()) return null
            left = c.copy(endUs = min(atUs, lw.last().endUs + 150_000L).coerceAtLeast(lw.last().endUs), text = lw.joinToString(" ") { it.text }, words = lw)
            right = Cue(newId(), rw.first().startUs, c.endUs, rw.joinToString(" ") { it.text }, rw, c.estimated)
        } else {
            val ratio = (atUs - c.startUs).toDouble() / c.durationUs
            val target = (c.text.length * ratio).toInt().coerceIn(1, max(1, c.text.length - 1))
            val spaceL = c.text.lastIndexOf(' ', target)
            val spaceR = c.text.indexOf(' ', target)
            val cut = when {
                spaceL <= 0 && spaceR < 0 -> return null
                spaceL <= 0 -> spaceR
                spaceR < 0 -> spaceL
                target - spaceL <= spaceR - target -> spaceL
                else -> spaceR
            }
            left = c.copy(endUs = atUs, text = c.text.substring(0, cut).trim(), words = emptyList())
            right = Cue(newId(), atUs, c.endUs, c.text.substring(cut).trim())
        }
        return withCues(t, t.cues.filterNot { it.id == id } + left + right)
    }

    /** Joins a cue with the one that follows it in time. */
    fun mergeWithNext(t: SubtitleTrack, id: String): SubtitleTrack? {
        val sorted = t.cues.sortedBy { it.startUs }
        val i = sorted.indexOfFirst { it.id == id }
        if (i < 0 || i >= sorted.lastIndex) return null
        val a = sorted[i]
        val b = sorted[i + 1]
        val merged = Cue(
            a.id, a.startUs, b.endUs, (a.text.trim() + " " + b.text.trim()).trim(),
            if (a.words.isNotEmpty() && b.words.isNotEmpty()) a.words + b.words else emptyList(),
            a.estimated || b.estimated,
        )
        return withCues(t, sorted.filterIndexed { j, _ -> j != i && j != i + 1 } + merged)
    }

    /** Indices (in time order) of the cues containing [query], ignoring case and accents. */
    fun search(t: SubtitleTrack, query: String): List<Int> {
        val q = fold(query)
        if (q.isBlank()) return emptyList()
        return t.cues.sortedBy { it.startUs }.mapIndexedNotNull { i, c -> if (fold(c.text).contains(q)) i else null }
    }

    fun fold(s: String): String = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Replaces [find] with [replace] in every cue (case-insensitive). Returns the new track and the count. */
    fun replaceAll(t: SubtitleTrack, find: String, replace: String): Pair<SubtitleTrack, Int> {
        if (find.isBlank()) return t to 0
        var count = 0
        val re = Regex(Regex.escape(find), RegexOption.IGNORE_CASE)
        val cues = t.cues.map { c ->
            if (!re.containsMatchIn(c.text)) c else {
                count += re.findAll(c.text).count()
                setText(c, re.replace(c.text) { replace })
            }
        }
        return t.copy(cues = cues) to count
    }

    /** Cues that overlap their neighbour or are too short to read: for the "check" badge. */
    fun problems(t: SubtitleTrack): List<String> {
        val out = ArrayList<String>()
        val sorted = t.cues.sortedBy { it.startUs }
        for (i in sorted.indices) {
            val c = sorted[i]
            if (c.durationUs < MIN_CUE_US) out += c.id
            else if (i + 1 < sorted.size && sorted[i + 1].startUs < c.endUs - 1_000) out += c.id
        }
        return out.distinct()
    }
}
