package com.vanta.studio.domain.enhance

import kotlin.math.max
import kotlin.math.min

/**
 * Per-step measurements of a clip's source time (step = [stepUs]). Only what can really be measured:
 * how loud it is, how much the picture changes (movement or shot changes) and whether someone is
 * speaking or making sound at all. It does not understand what happens in the scene.
 */
class ClipFeatures(
    val stepUs: Long,
    val audio: FloatArray,
    val motion: FloatArray,
    /** 1 = sound present, 0 = silence; null when the audio could not be separated from noise. */
    val speech: FloatArray?,
) {
    val steps: Int get() = minOf(audio.size, motion.size)
}

enum class Why { LOUD, MOTION, SPEECH }

data class Highlight(val startUs: Long, val endUs: Long, val score: Float, val why: Set<Why>) {
    val durationUs: Long get() = endUs - startUs
}

object Highlights {
    private fun robustNorm(v: FloatArray): FloatArray {
        if (v.isEmpty()) return v
        val s = v.sortedArray()
        val lo = s[(s.size * 0.10f).toInt().coerceIn(0, s.size - 1)]
        val hi = s[(s.size * 0.95f).toInt().coerceIn(0, s.size - 1)]
        val span = max(hi - lo, 1e-6f)
        return FloatArray(v.size) { ((v[it] - lo) / span).coerceIn(0f, 1f) }
    }

    private fun smooth(v: FloatArray): FloatArray =
        FloatArray(v.size) { i -> (v[max(0, i - 1)] + v[i] + v[min(v.size - 1, i + 1)]) / 3f }

    /**
     * Picks the most active stretches of the clip, best first, never overlapping, until about
     * [targetUs] of material is chosen. Returned in source order.
     */
    fun pick(f: ClipFeatures, targetUs: Long, minSegUs: Long = 3_000_000, maxSegUs: Long = 8_000_000): List<Highlight> {
        val n = f.steps
        if (n < 2 || f.stepUs <= 0) return emptyList()
        val a = robustNorm(f.audio.copyOf(n))
        val m = robustNorm(f.motion.copyOf(n))
        val sp = f.speech?.copyOf(n)
        val wa = if (sp != null) 0.40f else 0.50f
        val wm = if (sp != null) 0.35f else 0.50f
        val ws = if (sp != null) 0.25f else 0f
        val score = smooth(FloatArray(n) { wa * a[it] + wm * m[it] + ws * (sp?.get(it) ?: 0f) })

        val segSteps = (((targetUs / 5).coerceIn(minSegUs, maxSegUs)) / f.stepUs).toInt().coerceIn(1, n)
        val windowMean = FloatArray(n - segSteps + 1) { s -> var t = 0f; for (i in s until s + segSteps) t += score[i]; t / segSteps }
        val mean = score.average().toFloat()
        val taken = BooleanArray(n)
        val picks = ArrayList<Highlight>()
        var total = 0L
        val order = windowMean.indices.sortedByDescending { windowMean[it] }
        for (s in order) {
            if (total >= targetUs) break
            // a stretch is only worth keeping if it is clearly more active than the clip as a whole
            if (windowMean[s] < max(0.2f, mean * 1.15f)) break
            if ((s until s + segSteps).any { taken[it] }) continue
            for (i in max(0, s - 1)..min(n - 1, s + segSteps)) taken[i] = true
            val seg = (s until s + segSteps)
            val why = HashSet<Why>()
            if (seg.map { a[it] }.average() > 0.6) why += Why.LOUD
            if (seg.map { m[it] }.average() > 0.6) why += Why.MOTION
            if (sp != null && seg.map { sp[it] }.average() > 0.6) why += Why.SPEECH
            picks += Highlight(s * f.stepUs, (s + segSteps) * f.stepUs, windowMean[s], why)
            total += segSteps * f.stepUs
        }
        return picks.sortedBy { it.startUs }
    }

    /** Source-time spans NOT covered by [keep], within [0, totalUs]. */
    fun complement(keep: List<Highlight>, totalUs: Long): List<Pair<Long, Long>> {
        val out = ArrayList<Pair<Long, Long>>()
        var cursor = 0L
        for (h in keep.sortedBy { it.startUs }) {
            if (h.startUs > cursor) out += cursor to h.startUs
            cursor = max(cursor, h.endUs)
        }
        if (cursor < totalUs) out += cursor to totalUs
        return out
    }
}
