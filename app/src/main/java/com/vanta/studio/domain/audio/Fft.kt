package com.vanta.studio.domain.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** In-place radix-2 FFT for a fixed power-of-two size. */
class Fft(val n: Int) {
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)
    private val rev = IntArray(n)

    init {
        require(n >= 2 && n and (n - 1) == 0) { "FFT size must be a power of two" }
        for (i in 0 until n / 2) {
            val a = -2.0 * PI * i / n
            cosT[i] = cos(a).toFloat()
            sinT[i] = sin(a).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun transform(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosT[k]
                    val wi = sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += size
            }
            size *= 2
        }
    }
}
