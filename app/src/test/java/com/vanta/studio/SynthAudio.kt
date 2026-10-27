package com.vanta.studio

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Deterministic synthetic music for DSP tests (no recordings are bundled). */
class SynthAudio(val sampleRate: Int, seconds: Float) {
    val pcm = FloatArray((sampleRate * seconds).toInt())
    private val rnd = Random(7)

    fun kick(timeSec: Float, amp: Float = 0.8f) {
        val start = (timeSec * sampleRate).toInt()
        val len = (0.18f * sampleRate).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx !in pcm.indices) break
            val t = i.toFloat() / sampleRate
            val freq = 50f + 90f * exp(-t * 35f)
            pcm[idx] += amp * exp(-t * 18f) * sin(2.0 * PI * freq * t).toFloat()
        }
    }

    fun snare(timeSec: Float, amp: Float = 0.5f) {
        val start = (timeSec * sampleRate).toInt()
        val len = (0.12f * sampleRate).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx !in pcm.indices) break
            val t = i.toFloat() / sampleRate
            pcm[idx] += amp * exp(-t * 30f) * (rnd.nextFloat() * 2f - 1f)
        }
    }

    fun hat(timeSec: Float, amp: Float = 0.12f) {
        val start = (timeSec * sampleRate).toInt()
        val len = (0.03f * sampleRate).toInt()
        for (i in 0 until len) {
            val idx = start + i
            if (idx !in pcm.indices) break
            val t = i.toFloat() / sampleRate
            pcm[idx] += amp * exp(-t * 120f) * (rnd.nextFloat() * 2f - 1f)
        }
    }

    /** Kick on every beat, snare on 2 and 4, hats on the off-beats. */
    fun drumLoop(bpm: Float, fromSec: Float, toSec: Float, kickAmp: Float = 0.8f): List<Float> {
        val beat = 60f / bpm
        val kicks = ArrayList<Float>()
        var t = fromSec
        var n = 0
        while (t < toSec - 0.2f) {
            kick(t, kickAmp)
            kicks += t
            if (n % 2 == 1) snare(t, kickAmp * 0.6f)
            hat(t + beat / 2f)
            t += beat
            n++
        }
        return kicks
    }
}
