package com.vanta.studio.engine.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.vanta.studio.domain.audio.FloatList
import com.vanta.studio.domain.audio.StreamResampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

class AudioDecodeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Mono float PCM. [startUs] is the source time of the first sample. */
class DecodedAudio(val pcm: FloatArray, val sampleRate: Int, val startUs: Long) {
    val durationUs: Long get() = pcm.size * 1_000_000L / sampleRate
}

/**
 * Decodes the audio track of any audio or video file the platform can play into mono PCM at a
 * chosen sample rate (22.05 kHz for rhythm analysis, 16 kHz for speech recognition).
 */
class AudioDecoder(private val context: Context) {

    /** True if the file has at least one audio track. */
    suspend fun hasAudio(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            (0 until ex.trackCount).any { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } catch (e: Exception) {
            false
        } finally {
            ex.release()
        }
    }

    suspend fun decode(
        uri: Uri,
        targetRate: Int,
        startUs: Long = 0,
        endUs: Long = Long.MAX_VALUE,
        maxDurationUs: Long = 20L * 60 * 1_000_000,
        onProgress: (Float) -> Unit = {},
    ): DecodedAudio = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            try {
                ex.setDataSource(context, uri, null)
            } catch (e: Exception) {
                throw AudioDecodeException("Cannot open ${uri.lastPathSegment}", e)
            }
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw AudioDecodeException("No audio track")
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val totalUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val limitUs = minOf(endUs, if (startUs > Long.MAX_VALUE - maxDurationUs) Long.MAX_VALUE else startUs + maxDurationUs)
            if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val c = try {
                MediaCodec.createDecoderByType(mime).also { it.configure(format, null, null, 0); it.start() }
            } catch (e: Exception) {
                throw AudioDecodeException("This device cannot decode $mime", e)
            }
            codec = c

            val out = FloatList(1 shl 18)
            var rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else targetRate
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            var resampler = StreamResampler(rate, targetRate, out)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var scratch = ByteArray(0)
            var firstKept = false
            var firstKeptUs = startUs

            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val idx = c.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = c.getInputBuffer(idx)!!
                        val size = ex.readSampleData(buf, 0)
                        val t = ex.sampleTime
                        if (size < 0 || t >= limitUs) {
                            c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(idx, 0, size, t, 0)
                            ex.advance()
                            if (totalUs > 0) onProgress(((t - startUs).toFloat() / (minOf(limitUs, totalUs) - startUs).coerceAtLeast(1)).coerceIn(0f, 1f))
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                when {
                    oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        val newRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val newCh = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                        if (newRate != rate || newCh != channels) {
                            rate = newRate
                            channels = newCh
                            resampler = StreamResampler(rate, targetRate, out)
                        }
                    }
                    oi >= 0 -> {
                        if (info.size > 0 && info.presentationTimeUs >= startUs) {
                            if (!firstKept) {
                                firstKept = true
                                firstKeptUs = info.presentationTimeUs
                            }
                            val ob = c.getOutputBuffer(oi)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            if (floatPcm) {
                                val fb = ob.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                val n = fb.remaining() / channels
                                for (i in 0 until n) {
                                    var s = 0f
                                    for (ch in 0 until channels) s += fb.get()
                                    resampler.push(s / channels)
                                }
                            } else {
                                if (scratch.size < info.size) scratch = ByteArray(info.size)
                                ob.get(scratch, 0, info.size)
                                resampler.pushPcm16(scratch, info.size, channels)
                            }
                        }
                        c.releaseOutputBuffer(oi, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            onProgress(1f)
            DecodedAudio(out.toArray(), targetRate, firstKeptUs)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            ex.release()
        }
    }
}
