package com.vanta.studio.ui.editor

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import com.vanta.studio.domain.effects.GradeParams
import kotlin.math.pow

/**
 * Approximates a [GradeParams] with a colour matrix. It only drives the small filter tiles
 * in the picker (an inexpensive representation of "what this filter does to colour");
 * the preview monitor and the export use the real shader.
 */
object FilterPreview {
    private fun identity() = FloatArray(25).also { for (i in 0 until 5) it[i * 5 + i] = 1f }

    /** Result applies [b] after [a]. Matrices are 5x5 row-major, last row (0,0,0,0,1). */
    private fun then(a: FloatArray, b: FloatArray): FloatArray {
        val r = FloatArray(25)
        for (i in 0 until 5) for (j in 0 until 5) {
            var s = 0f
            for (k in 0 until 5) s += b[i * 5 + k] * a[k * 5 + j]
            r[i * 5 + j] = s
        }
        return r
    }

    private fun scaleAdd(scale: Float, add: Float): FloatArray {
        val m = identity()
        m[0] = scale; m[6] = scale; m[12] = scale
        m[4] = add; m[9] = add; m[14] = add
        return m
    }

    private fun saturation(s: Float): FloatArray {
        val m = identity()
        val lr = 0.2126f; val lg = 0.7152f; val lb = 0.0722f
        m[0] = lr + (1 - lr) * s; m[1] = lg - lg * s; m[2] = lb - lb * s
        m[5] = lr - lr * s; m[6] = lg + (1 - lg) * s; m[7] = lb - lb * s
        m[10] = lr - lr * s; m[11] = lg - lg * s; m[12] = lb + (1 - lb) * s
        return m
    }

    private fun mix(a: FloatArray, b: FloatArray, t: Float) = FloatArray(25) { a[it] + (b[it] - a[it]) * t }

    private val sepia = identity().also {
        it[0] = 0.393f; it[1] = 0.769f; it[2] = 0.189f
        it[5] = 0.349f; it[6] = 0.686f; it[7] = 0.168f
        it[10] = 0.272f; it[11] = 0.534f; it[12] = 0.131f
    }

    fun matrix(p: GradeParams): ColorMatrix {
        var m = identity()
        m = then(m, scaleAdd(2f.pow(p.exposure * 1.5f), p.brightness * 0.25f * 255f))
        val c = 1f + p.contrast
        m = then(m, scaleAdd(c, (0.5f - 0.5f * c) * 255f))
        m = then(m, saturation(((1f + p.saturation) * (1f - p.bw)).coerceAtLeast(0f)))
        if (p.sepia > 0f) m = then(m, mix(identity(), sepia, p.sepia))
        val warm = identity()
        warm[4] = (p.temperature * 0.12f + p.tint * 0.04f + p.tealOrange * 0.06f) * 255f
        warm[9] = -p.tint * 0.10f * 255f
        warm[14] = (-p.temperature * 0.12f + p.tint * 0.04f - p.tealOrange * 0.06f) * 255f
        m = then(m, warm)
        if (p.fade > 0f) m = then(m, scaleAdd(1f - 0.25f * p.fade, 0.12f * p.fade * 255f))
        val out = FloatArray(20)
        for (i in 0 until 4) for (j in 0 until 5) out[i * 5 + j] = m[i * 5 + j]
        return ColorMatrix(out)
    }

    fun colorFilter(p: GradeParams): ColorFilter = ColorFilter.colorMatrix(matrix(p))
}
