package com.vanta.studio.domain.subtitles

import com.vanta.studio.domain.model.Cue

/** SubRip (.srt) reading and writing. */
object Srt {
    fun format(cues: List<Cue>): String {
        val sb = StringBuilder()
        cues.sortedBy { it.startUs }.forEachIndexed { i, c ->
            sb.append(i + 1).append('\n')
            sb.append(timestamp(c.startUs)).append(" --> ").append(timestamp(c.endUs)).append('\n')
            sb.append(c.text.trim()).append("\n\n")
        }
        return sb.toString()
    }

    fun timestamp(us: Long): String {
        val ms = (us.coerceAtLeast(0) + 500) / 1000
        val h = ms / 3_600_000
        val m = (ms / 60_000) % 60
        val s = (ms / 1000) % 60
        val milli = ms % 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d,%03d", h, m, s, milli)
    }

    private val timeLine = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    /** Parses SRT text; tolerant of BOMs, CRLF and missing index lines. Invalid blocks are skipped. */
    fun parse(text: String, newId: () -> String): List<Cue> {
        val out = ArrayList<Cue>()
        val blocks = text.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n').split(Regex("\n{2,}"))
        for (block in blocks) {
            val lines = block.trim().split('\n')
            val idx = lines.indexOfFirst { timeLine.containsMatchIn(it) }
            if (idx < 0) continue
            val m = timeLine.find(lines[idx]) ?: continue
            val g = m.groupValues
            fun us(h: String, mi: String, s: String, ms: String): Long {
                val milli = ms.padEnd(3, '0').take(3).toLong()
                return ((h.toLong() * 3600 + mi.toLong() * 60 + s.toLong()) * 1000 + milli) * 1000
            }
            val start = us(g[1], g[2], g[3], g[4])
            val end = us(g[5], g[6], g[7], g[8])
            val body = lines.drop(idx + 1).joinToString("\n").trim()
            if (body.isEmpty() || end <= start) continue
            out += Cue(newId(), start, end, body)
        }
        return out.sortedBy { it.startUs }
    }
}
