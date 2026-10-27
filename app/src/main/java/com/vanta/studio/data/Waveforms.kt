package com.vanta.studio.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Decodes an audio file once at low resolution and keeps one peak value per bucket
 * (normalised 0..1) covering the whole source. The timeline shows the trimmed slice.
 */
class Waveforms(private val context: Context) {
    private val cache = ConcurrentHashMap<String, FloatArray>()

    fun cached(uri: String): FloatArray? = cache[uri]

    suspend fun load(uri: String, buckets: Int = 600): FloatArray? {
        cache[uri]?.let { return it }
        return withContext(Dispatchers.IO) {
            val result = try {
                decode(Uri.parse(uri), buckets)
            } catch (e: Exception) {
                null
            }
            result?.also { cache[uri] = it }
        }
    }

    private suspend fun decode(uri: Uri, buckets: Int): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) return null
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()
            val peaks = FloatArray(buckets)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idleAfterEos = 0
            var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone && ++idleAfterEos > 300) break
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    outIdx >= 0 -> {
                        val out = codec.getOutputBuffer(outIdx)
                        if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val shorts = out.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val bucket = ((info.presentationTimeUs.toDouble() / durationUs) * buckets).toInt()
                                .coerceIn(0, buckets - 1)
                            var peak = 0
                            var i = 0
                            val step = max(1, channels) * 8
                            while (i < shorts.limit()) {
                                peak = max(peak, abs(shorts.get(i).toInt()))
                                i += step
                            }
                            peaks[bucket] = max(peaks[bucket], peak / 32768f)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            // Fill buckets that received no decoded buffer with their neighbour's value.
            for (b in 1 until buckets) if (peaks[b] == 0f) peaks[b] = peaks[b - 1] * 0.9f
            return peaks
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            extractor.release()
        }
    }
}
