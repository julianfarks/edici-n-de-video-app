package com.vanta.studio.domain.audio

import com.vanta.studio.domain.model.Beat
import com.vanta.studio.domain.model.BeatMap
import com.vanta.studio.domain.model.Section
import com.vanta.studio.domain.model.TempoChange
import com.vanta.studio.domain.model.TimeRange
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Music analysis with plain signal processing, no models and no fixed tempo assumption:
 *
 *  1. STFT (1024-point Hann, hop 512) -> log band energies -> half-wave rectified spectral flux
 *     (the "onset strength" envelope).
 *  2. Adaptive-threshold peak picking on that envelope, refined against the raw waveform to a few
 *     milliseconds -> *onsets* (every transient, each with a strength).
 *  3. Tempo from the autocorrelation of the envelope (with harmonics and a soft prior), then a
 *     dynamic-programming beat tracker (Ellis, 2007) that follows tempo drift -> *beats* (pulses).
 *  4. RMS energy envelope, silence/pause detection, high-energy peaks.
 *  5. Sections from a self-similarity novelty curve over [energy, brightness, bass] features.
 *  6. Tempo changes from windowed tempo estimates.
 *
 * Input is mono PCM in -1..1. The analysis is deterministic.
 */
object BeatAnalyzer {
    const val TARGET_RATE = 22_050
    private const val N = 1024
    private const val HOP = 512
    private const val BANDS = 24

    fun analyze(
        pcmIn: FloatArray,
        sampleRate: Int,
        sourceUri: String,
        isCancelled: () -> Boolean = { false },
        progress: (Float) -> Unit = {},
    ): BeatMap {
        val durationUs = (pcmIn.size * 1_000_000L) / sampleRate
        if (pcmIn.size < N * 4) {
            return BeatMap(sourceUri, durationUs, 0f, 0f, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        }
        val pcm = normalise(pcmIn)
        val fps = sampleRate.toFloat() / HOP

        val feats = spectralFeatures(pcm, sampleRate, isCancelled, progress) ?: return emptyMap(sourceUri, durationUs)
        val flux = feats.flux
        val frames = flux.size

        // ---- onsets
        val mean = movingMean(flux, (0.6f * fps).roundToInt())
        val over = FloatArray(frames) { max(0f, flux[it] - mean[it]) }
        val scale = max(percentile(over, 0.97f), 1e-6f)
        val std = stdDev(flux)
        val onsetFrames = ArrayList<Int>()
        for (t in 2 until frames - 2) {
            val v = flux[t]
            if (v <= mean[t] + 0.45f * std || v < 1e-3f) continue
            var isMax = true
            for (d in -3..3) {
                val u = t + d
                if (u in 0 until frames && flux[u] > v) { isMax = false; break }
            }
            if (isMax) onsetFrames += t
        }
        val rawOnsets = onsetFrames.map { t ->
            val strength = ((flux[t] - mean[t]) / scale).coerceIn(0f, 1f)
            Beat(refineOnset(pcm, t, sampleRate), strength)
        }.sortedBy { it.timeUs }
        val onsets = enforceGap(rawOnsets, 80_000L)
        if (isCancelled()) return emptyMap(sourceUri, durationUs)
        progress(0.7f)

        // ---- tempo & beat tracking
        val env = normaliseStd(flux)
        val tempo = estimateTempo(env, fps, 0, frames)
        val bpm = tempo.first
        val confidence = tempo.second
        val beats = if (bpm > 0f) trackBeats(env, fps, bpm, onsets, sampleRate) else emptyList()
        progress(0.8f)

        // ---- energy, pauses, peaks
        val energy = energyCurve(pcm, sampleRate)
        val pauses = findPauses(pcm, sampleRate)
        val peaks = findPeaks(energy)

        // ---- sections
        val sections = findSections(feats, fps, energy, durationUs)
        progress(0.9f)

        // ---- tempo changes
        val changes = if (bpm > 0f) findTempoChanges(env, fps, bpm) else emptyList()
        progress(1f)

        return BeatMap(
            sourceUri = sourceUri,
            durationUs = durationUs,
            bpm = bpm,
            tempoConfidence = confidence,
            onsets = onsets,
            beats = beats,
            energy = energy,
            sections = sections,
            pauses = pauses,
            peaks = peaks,
            tempoChanges = changes,
        )
    }

    private fun emptyMap(uri: String, durationUs: Long) =
        BeatMap(uri, durationUs, 0f, 0f, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    // ------------------------------------------------------------------ features

    private class Features(
        val flux: FloatArray,
        val rms: FloatArray,
        val centroid: FloatArray,
        val low: FloatArray,
    )

    private fun normalise(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        if (peak < 1e-6f) return x
        val g = 1f / peak
        return FloatArray(x.size) { x[it] * g }
    }

    private fun bandEdges(): IntArray {
        val edges = IntArray(BANDS + 1)
        edges[0] = 2
        for (i in 1..BANDS) {
            val e = (2.0 * (N / 2 / 2.0).pow(i.toDouble() / BANDS)).roundToInt()
            edges[i] = max(edges[i - 1] + 1, min(e, N / 2))
        }
        edges[BANDS] = N / 2
        return edges
    }

    private fun spectralFeatures(pcm: FloatArray, sampleRate: Int, isCancelled: () -> Boolean, progress: (Float) -> Unit): Features? {
        val frames = (pcm.size - N) / HOP + 1
        val fft = Fft(N)
        val window = FloatArray(N) { (0.5 - 0.5 * cos(2.0 * PI * it / (N - 1))).toFloat() }
        val edges = bandEdges()
        val re = FloatArray(N)
        val im = FloatArray(N)
        var prev = FloatArray(BANDS)
        var cur = FloatArray(BANDS)
        val flux = FloatArray(frames)
        val rms = FloatArray(frames)
        val centroid = FloatArray(frames)
        val low = FloatArray(frames)
        val lowBin = (250f * N / sampleRate).roundToInt().coerceIn(2, N / 2)
        val mag = FloatArray(N / 2 + 1)
        for (t in 0 until frames) {
            if (t % 256 == 0) {
                if (isCancelled()) return null
                progress(0.65f * t / frames)
            }
            val o = t * HOP
            var sq = 0f
            for (i in 0 until N) {
                val s = pcm[o + i]
                sq += s * s
                re[i] = s * window[i]
                im[i] = 0f
            }
            rms[t] = sqrt(sq / N)
            fft.transform(re, im)
            var total = 0f
            var weighted = 0f
            var lowE = 0f
            for (k in 1..N / 2) {
                val m = sqrt(re[k] * re[k] + im[k] * im[k])
                mag[k] = m
                total += m
                weighted += m * k
                if (k < lowBin) lowE += m
            }
            centroid[t] = if (total > 1e-9f) weighted / total / (N / 2) else 0f
            low[t] = if (total > 1e-9f) lowE / total else 0f
            var f = 0f
            for (b in 0 until BANDS) {
                var e = 0f
                for (k in edges[b] until edges[b + 1]) e += mag[k]
                cur[b] = ln(1f + e)
                val d = cur[b] - prev[b]
                if (d > 0f && t > 0) f += d
            }
            flux[t] = f / BANDS
            val tmp = prev; prev = cur; cur = tmp
        }
        return Features(flux, rms, centroid, low)
    }

    /** Finds the sample where the energy rises fastest around frame [t], to a few ms. */
    private fun refineOnset(pcm: FloatArray, t: Int, sampleRate: Int): Long {
        val block = 64
        val from = max(0, t * HOP - HOP)
        val to = min(pcm.size - block, t * HOP + N)
        var bestPos = t * HOP + N / 4
        var bestRise = 0f
        var pos = from + block * 4
        while (pos <= to) {
            var e = 0f
            for (i in 0 until block) e += pcm[pos + i] * pcm[pos + i]
            var before = 0f
            for (j in 1..4) {
                val p = pos - j * block
                var s = 0f
                for (i in 0 until block) s += pcm[p + i] * pcm[p + i]
                before += s
            }
            before /= 4f
            val rise = e - before
            if (rise > bestRise) {
                bestRise = rise
                bestPos = pos
            }
            pos += block / 2
        }
        return bestPos * 1_000_000L / sampleRate
    }

    private fun enforceGap(sorted: List<Beat>, gapUs: Long): List<Beat> {
        val out = ArrayList<Beat>()
        for (b in sorted) {
            val last = out.lastOrNull()
            if (last != null && b.timeUs - last.timeUs < gapUs) {
                if (b.strength > last.strength) out[out.lastIndex] = b
            } else {
                out += b
            }
        }
        return out
    }

    // ------------------------------------------------------------------ tempo

    private fun normaliseStd(x: FloatArray): FloatArray {
        val s = max(stdDev(x), 1e-6f)
        val m = x.average().toFloat()
        return FloatArray(x.size) { (x[it] - m) / s }
    }

    /** Returns (bpm, confidence 0..1) for env[from, to), or (0, 0) when there is no pulse. */
    private fun estimateTempo(env: FloatArray, fps: Float, from: Int, to: Int): Pair<Float, Float> {
        val len = to - from
        if (len < fps * 3) return 0f to 0f
        val maxLag = min((fps * 60f / 50f * 4f).toInt() + 2, len - 1)
        val ac = FloatArray(maxLag + 1)
        for (lag in 0..maxLag) {
            var s = 0f
            for (i in from + lag until to) s += env[i] * env[i - lag]
            ac[lag] = s / (len - lag)
        }
        if (ac[0] <= 1e-6f) return 0f to 0f
        fun at(l: Float): Float {
            if (l < 0f || l >= maxLag) return 0f
            val i = l.toInt()
            val f = l - i
            return ac[i] * (1f - f) + ac[min(i + 1, maxLag)] * f
        }
        var bestBpm = 0f
        var bestScore = -1f
        var bpm = 55f
        while (bpm <= 200f) {
            val lag = 60f * fps / bpm
            var score = at(lag) + 0.5f * at(lag * 2f) + 0.25f * at(lag * 4f)
            // soft log-normal prior centred on 115 BPM, one octave wide
            val octaves = ln(bpm / 115f) / ln(2f)
            score *= exp(-0.5f * (octaves / 0.9f).pow(2))
            if (score > bestScore) {
                bestScore = score
                bestBpm = bpm
            }
            bpm += 0.25f
        }
        val conf = (at(60f * fps / bestBpm) / ac[0]).coerceIn(0f, 1f)
        return if (conf < 0.04f) 0f to conf else bestBpm to conf
    }

    private fun trackBeats(env: FloatArray, fps: Float, bpm: Float, onsets: List<Beat>, sampleRate: Int): List<Beat> {
        val frames = env.size
        val period = 60f * fps / bpm
        val alpha = 100f
        val score = FloatArray(frames)
        val back = IntArray(frames) { -1 }
        val lo = max(1, (period / 2f).roundToInt())
        val hi = max(lo + 1, (period * 2f).roundToInt())
        for (t in 0 until frames) {
            var best = 0f
            var arg = -1
            for (d in lo..hi) {
                val p = t - d
                if (p < 0) break
                val penalty = alpha * ln(d / period).let { it * it }
                val s = score[p] - penalty
                if (arg == -1 || s > best) { best = s; arg = p }
            }
            if (arg == -1) {
                score[t] = env[t]
            } else {
                score[t] = env[t] + (if (best > 0f) best else 0f)
                back[t] = if (best > 0f) arg else -1
            }
        }
        var end = frames - 1
        var bestEnd = Float.NEGATIVE_INFINITY
        for (t in max(0, frames - hi - 1) until frames) {
            if (score[t] > bestEnd) { bestEnd = score[t]; end = t }
        }
        val chain = ArrayList<Int>()
        var t = end
        while (t >= 0) {
            chain += t
            t = back[t]
        }
        chain.reverse()
        // Convert frames to times, snapping to refined onsets when one is close.
        val out = ArrayList<Beat>()
        val onsetTimes = onsets.map { it.timeUs }
        for (f in chain) {
            val approx = (f * HOP + N / 4) * 1_000_000L / sampleRate
            val idx = nearestIndex(onsetTimes, approx)
            val near = if (idx >= 0 && abs(onsetTimes[idx] - approx) <= 70_000) onsets[idx] else null
            val tUs = near?.timeUs ?: approx
            if (out.isNotEmpty() && tUs <= out.last().timeUs) continue
            out += Beat(tUs, near?.strength ?: 0.2f)
        }
        return out
    }

    private fun nearestIndex(sorted: List<Long>, t: Long): Int {
        if (sorted.isEmpty()) return -1
        var lo = 0
        var hi = sorted.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sorted[mid] < t) lo = mid + 1 else hi = mid
        }
        val a = lo
        val b = max(0, lo - 1)
        return if (abs(sorted[a] - t) <= abs(sorted[b] - t)) a else b
    }

    private fun findTempoChanges(env: FloatArray, fps: Float, base: Float): List<TempoChange> {
        val win = (10 * fps).toInt()
        val hop = (5 * fps).toInt()
        if (env.size < win + hop) return emptyList()
        val out = ArrayList<TempoChange>()
        var current = base
        var pending: Float? = null
        var start = 0
        while (start + win <= env.size) {
            val (raw, conf) = estimateTempo(env, fps, start, start + win)
            if (raw > 0f && conf > 0.1f) {
                val local = listOf(raw / 2f, raw, raw * 2f).minByOrNull { abs(ln(it / current)) } ?: raw
                if (abs(local / current - 1f) > 0.07f) {
                    val p = pending
                    if (p != null && abs(local / p - 1f) < 0.05f) {
                        val timeUs = (start / fps * 1_000_000f).toLong()
                        out += TempoChange(timeUs, local)
                        current = local
                        pending = null
                    } else {
                        pending = local
                    }
                } else {
                    pending = null
                }
            }
            start += hop
        }
        return out
    }

    // ------------------------------------------------------------------ energy

    private fun energyCurve(pcm: FloatArray, sampleRate: Int): List<Float> {
        val step = (sampleRate * BeatMap.ENERGY_STEP_US / 1_000_000L).toInt()
        val n = pcm.size / step
        val e = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            for (j in i * step until (i + 1) * step) s += pcm[j] * pcm[j]
            e[i] = sqrt(s / step)
        }
        val norm = max(percentile(e, 0.98f), 1e-6f)
        val smoothed = movingMean(FloatArray(n) { (e[it] / norm).coerceIn(0f, 1f) }, 1)
        return smoothed.toList()
    }

    private fun findPauses(pcm: FloatArray, sampleRate: Int): List<TimeRange> {
        val step = sampleRate / 20 // 50 ms
        val n = pcm.size / step
        if (n < 4) return emptyList()
        val r = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            for (j in i * step until (i + 1) * step) s += pcm[j] * pcm[j]
            r[i] = sqrt(s / step)
        }
        val thr = 0.03f * max(percentile(r, 0.95f), 1e-6f)
        val out = ArrayList<TimeRange>()
        var runStart = -1
        for (i in 0..n) {
            val quiet = i < n && r[i] < thr
            if (quiet && runStart < 0) runStart = i
            if (!quiet && runStart >= 0) {
                if (i - runStart >= 5) { // >= 250 ms
                    out += TimeRange(runStart * 50_000L, i * 50_000L)
                }
                runStart = -1
            }
        }
        return out
    }

    private fun findPeaks(energy: List<Float>): List<Long> {
        if (energy.size < 5) return emptyList()
        val sm = movingMean(energy.toFloatArray(), 5)
        val top = sm.max()
        val out = ArrayList<Long>()
        var i = 1
        while (i < sm.size - 1) {
            if (sm[i] >= 0.75f * top && sm[i] >= sm[i - 1] && sm[i] > sm[i + 1]) {
                val t = i * BeatMap.ENERGY_STEP_US
                if (out.isEmpty() || t - out.last() >= 2_000_000L) out += t
            }
            i++
        }
        return out
    }

    // ------------------------------------------------------------------ sections

    private fun findSections(f: Features, fps: Float, energy: List<Float>, durationUs: Long): List<Section> {
        val group = max(1, (0.5f * fps).roundToInt())
        val n = f.rms.size / group
        if (n < 12) return listOf(Section(0, durationUs, meanOf(energy, 0, energy.size)))
        val feat = Array(n) { FloatArray(3) }
        for (i in 0 until n) {
            var r = 0f; var c = 0f; var l = 0f
            for (j in i * group until (i + 1) * group) { r += f.rms[j]; c += f.centroid[j]; l += f.low[j] }
            feat[i][0] = r / group; feat[i][1] = c / group; feat[i][2] = l / group
        }
        for (d in 0 until 3) {
            var m = 0f
            for (i in 0 until n) m += feat[i][d]
            m /= n
            var v = 0f
            for (i in 0 until n) v += (feat[i][d] - m) * (feat[i][d] - m)
            val s = max(sqrt(v / n), 1e-6f)
            for (i in 0 until n) feat[i][d] = (feat[i][d] - m) / s
        }
        fun sim(a: Int, b: Int): Float {
            var dot = 0f; var na = 0f; var nb = 0f
            for (d in 0 until 3) { dot += feat[a][d] * feat[b][d]; na += feat[a][d] * feat[a][d]; nb += feat[b][d] * feat[b][d] }
            return if (na < 1e-6f || nb < 1e-6f) 0f else dot / sqrt(na * nb)
        }
        val half = min(8, n / 4)
        val novelty = FloatArray(n)
        for (i in half until n - half) {
            var s = 0f
            for (a in -half until half) {
                for (b in -half until half) {
                    val sign = (if (a >= 0) 1 else -1) * (if (b >= 0) 1 else -1)
                    s += sign * sim(i + a, i + b)
                }
            }
            novelty[i] = s
        }
        val m = novelty.average().toFloat()
        val sd = stdDev(novelty)
        val minDist = (6f / 0.5f).toInt()
        val bounds = ArrayList<Int>()
        for (i in half until n - half) {
            if (novelty[i] < m + 0.8f * sd) continue
            var isMax = true
            for (d in -4..4) {
                val u = i + d
                if (u in 0 until n && novelty[u] > novelty[i]) { isMax = false; break }
            }
            if (!isMax) continue
            if (bounds.isEmpty() || i - bounds.last() >= minDist) bounds += i
        }
        val edges = listOf(0L) + bounds.map { (it * group / fps * 1_000_000f).toLong() } + listOf(durationUs)
        val out = ArrayList<Section>()
        for (i in 0 until edges.size - 1) {
            val a = edges[i]
            val b = edges[i + 1]
            if (b - a < 1_000_000L && out.isNotEmpty()) {
                out[out.lastIndex] = out.last().copy(endUs = b)
                continue
            }
            val ia = (a / BeatMap.ENERGY_STEP_US).toInt()
            val ib = (b / BeatMap.ENERGY_STEP_US).toInt()
            out += Section(a, b, meanOf(energy, ia, ib))
        }
        return out
    }

    private fun meanOf(e: List<Float>, from: Int, to: Int): Float {
        val a = from.coerceIn(0, e.size)
        val b = to.coerceIn(a, e.size)
        if (b <= a) return 0f
        var s = 0f
        for (i in a until b) s += e[i]
        return s / (b - a)
    }

    // ------------------------------------------------------------------ small helpers

    private fun movingMean(x: FloatArray, radius: Int): FloatArray {
        val n = x.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + x[i]
        return FloatArray(n) {
            val a = max(0, it - radius)
            val b = min(n, it + radius + 1)
            ((prefix[b] - prefix[a]) / (b - a)).toFloat()
        }
    }

    private fun stdDev(x: FloatArray): Float {
        if (x.isEmpty()) return 0f
        val m = x.average()
        var s = 0.0
        for (v in x) s += (v - m) * (v - m)
        return sqrt(s / x.size).toFloat()
    }

    private fun percentile(x: FloatArray, p: Float): Float {
        if (x.isEmpty()) return 0f
        val s = x.sortedArray()
        return s[((s.size - 1) * p).roundToInt().coerceIn(0, s.size - 1)]
    }
}
