package com.vanta.studio.engine.audio

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Generates silent WAV files used as spacers between music clips placed freely on an
 * audio lane (a Media3 sequence is contiguous). Files are cached by duration.
 */
object Silence {
    private const val SAMPLE_RATE = 22_050
    private const val CHANNELS = 1

    fun file(dir: File, durationUs: Long): File {
        val ms = (durationUs / 1000).coerceAtLeast(1)
        dir.mkdirs()
        val f = File(dir, "silence_$ms.wav")
        if (f.exists() && f.length() > 44) return f
        val frames = (SAMPLE_RATE.toLong() * ms / 1000).toInt().coerceAtLeast(1)
        val dataBytes = frames * CHANNELS * 2
        val tmp = File(dir, "silence_$ms.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(header(dataBytes))
            val zeros = ByteArray(64 * 1024)
            var left = dataBytes
            while (left > 0) {
                val n = minOf(left, zeros.size)
                out.write(zeros, 0, n)
                left -= n
            }
        }
        tmp.renameTo(f)
        return f
    }

    fun header(dataBytes: Int): ByteArray {
        val b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray(Charsets.US_ASCII))
        b.putInt(36 + dataBytes)
        b.put("WAVE".toByteArray(Charsets.US_ASCII))
        b.put("fmt ".toByteArray(Charsets.US_ASCII))
        b.putInt(16)
        b.putShort(1)
        b.putShort(CHANNELS.toShort())
        b.putInt(SAMPLE_RATE)
        b.putInt(SAMPLE_RATE * CHANNELS * 2)
        b.putShort((CHANNELS * 2).toShort())
        b.putShort(16)
        b.put("data".toByteArray(Charsets.US_ASCII))
        b.putInt(dataBytes)
        return b.array()
    }

    /** Removes cached spacers when they take more than [maxBytes]. */
    fun trim(dir: File, maxBytes: Long = 64L * 1024 * 1024) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }
}
