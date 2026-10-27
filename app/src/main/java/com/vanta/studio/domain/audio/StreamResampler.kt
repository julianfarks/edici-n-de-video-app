package com.vanta.studio.domain.audio

/** Growable float buffer without boxing. */
class FloatList(initial: Int = 1 shl 16) {
    private var data = FloatArray(initial)
    var size = 0
        private set

    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun toArray(): FloatArray = data.copyOf(size)
}

/**
 * Streaming mono down-mixer and box-filter resampler: feed interleaved PCM in any chunking and
 * get mono samples at [targetRate]. A box filter is enough for onset / speech analysis, where
 * only content well below the new Nyquist matters.
 */
class StreamResampler(private val sourceRate: Int, private val targetRate: Int, private val out: FloatList) {
    private val step = sourceRate.toDouble() / targetRate
    private var pos = 0.0
    private var acc = 0.0
    private var n = 0

    /** [frameMono] is one already down-mixed sample. */
    fun push(frameMono: Float) {
        acc += frameMono
        n++
        pos += 1.0
        if (pos >= step) {
            out.add((acc / n).toFloat())
            pos -= step
            acc = 0.0
            n = 0
        }
    }

    /** Down-mixes interleaved 16-bit little-endian PCM and pushes it. */
    fun pushPcm16(bytes: ByteArray, length: Int, channels: Int) {
        val frames = length / (2 * channels)
        var i = 0
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) {
                val lo = bytes[i].toInt() and 0xFF
                val hi = bytes[i + 1].toInt()
                sum += (hi shl 8) or lo
                i += 2
            }
            push(sum / (32768f * channels))
        }
    }

    companion object {
        /** Convenience for tests and for already-decoded mono data. */
        fun resample(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray {
            val list = FloatList(maxOf(16, input.size * targetRate / sourceRate + 16))
            val r = StreamResampler(sourceRate, targetRate, list)
            for (v in input) r.push(v)
            return list.toArray()
        }
    }
}
