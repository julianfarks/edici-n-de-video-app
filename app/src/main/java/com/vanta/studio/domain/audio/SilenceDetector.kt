package com.vanta.studio.domain.audio

import com.vanta.studio.domain.model.TimeRange
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Finds pauses in speech from the loudness envelope. The threshold is derived from the audio
 * itself (between its quiet floor and its normal speaking level), so it adapts to each recording.
 * Nothing is "denoised": the result is only a list of proposed cuts for the user to review.
 */
object SilenceDetector {
    data class Options(
        /** Shortest pause that is worth cutting. */
        val minPauseUs: Long = 600_000,
        /** Silence kept on each side of a cut, so words keep their natural attack/decay and breaths stay. */
        val marginUs: Long = 150_000,
        /** 0 = only very quiet stretches count as silence, 1 = quite loose. */
        val sensitivity: Float = 0.5f,
    )

    /** Result: the cuts, plus a note when the audio has no clear difference between speech and pauses. */
    data class Result(val cuts: List<TimeRange>, val separable: Boolean, val thresholdDb: Float)

    private const val HOP_MS = 20
    private const val WIN_MS = 30

    fun detect(pcm: FloatArray, sampleRate: Int, startUs: Long = 0, o: Options = Options()): Result {
        val hop = sampleRate * HOP_MS / 1000
        val win = sampleRate * WIN_MS / 1000
        if (pcm.size < win * 4) return Result(emptyList(), false, 0f)
        val n = (pcm.size - win) / hop + 1
        val db = FloatArray(n)
        for (f in 0 until n) {
            var sum = 0.0
            val s = f * hop
            for (i in s until s + win) sum += pcm[i] * pcm[i]
            db[f] = (20 * log10(max(sqrt(sum / win), 1e-5))).toFloat()
        }
        val sorted = db.sortedArray()
        val floor = sorted[(n * 0.10).toInt().coerceAtMost(n - 1)]
        val loud = sorted[(n * 0.90).toInt().coerceAtMost(n - 1)]
        // Speech and pauses must be at least 12 dB apart for a threshold to mean anything.
        if (loud - floor < 12f) return Result(emptyList(), false, floor)
        val k = 0.15f + 0.3f * o.sensitivity.coerceIn(0f, 1f)
        val thr = floor + k * (loud - floor)

        val quiet = BooleanArray(n) { db[it] < thr }
        // Bridge tiny sound blips (clicks, plosives) of less than 3 frames inside a pause.
        var i = 0
        while (i < n) {
            if (!quiet[i]) {
                var j = i
                while (j < n && !quiet[j]) j++
                if (j - i < 3 && i > 0 && j < n) for (x in i until j) quiet[x] = true
                i = j
            } else i++
        }
        val cuts = ArrayList<TimeRange>()
        i = 0
        while (i < n) {
            if (quiet[i]) {
                var j = i
                while (j < n && quiet[j]) j++
                val s = startUs + i * HOP_MS * 1000L
                val e = startUs + j * HOP_MS * 1000L
                if (e - s >= o.minPauseUs) {
                    val cs = s + o.marginUs
                    val ce = e - o.marginUs
                    if (ce - cs >= 80_000) cuts += TimeRange(cs, ce)
                }
                i = j
            } else i++
        }
        return Result(cuts, true, thr)
    }
}
