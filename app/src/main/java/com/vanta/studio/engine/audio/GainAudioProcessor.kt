package com.vanta.studio.engine.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import com.vanta.studio.engine.AudioClock
import java.nio.ByteBuffer

/** Gain envelope: volume, mute and linear fades, evaluated at a local time. */
object GainEnvelope {
    fun gainAt(localUs: Long, durationUs: Long, volume: Float, muted: Boolean, fadeInUs: Long, fadeOutUs: Long): Float {
        if (muted) return 0f
        var g = volume.coerceIn(0f, 4f)
        if (fadeInUs > 0 && localUs < fadeInUs) g *= (localUs.toFloat() / fadeInUs).coerceIn(0f, 1f)
        if (fadeOutUs > 0 && localUs > durationUs - fadeOutUs) {
            g *= ((durationUs - localUs).toFloat() / fadeOutUs).coerceIn(0f, 1f)
        }
        return g
    }
}

/** Supplies the current gain parameters (live in preview, frozen during export). */
fun interface GainSource {
    /** Returns the gain at [localUs]. */
    fun gainAt(localUs: Long): Float
}

/**
 * Applies a time-varying gain to 16-bit or float PCM. The local time restarts from the
 * [clock] on every flush (a seek or the start of the item).
 */
class GainAudioProcessor(
    private val source: GainSource,
    private val clock: AudioClock,
) : BaseAudioProcessor() {
    private var framesProcessed = 0L
    private var startLocalUs = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val format = inputAudioFormat
        val channels = format.channelCount.coerceAtLeast(1)
        val isFloat = format.encoding == C.ENCODING_PCM_FLOAT
        val bytesPerFrame = channels * if (isFloat) 4 else 2
        val frames = remaining / bytesPerFrame
        val out = replaceOutputBuffer(frames * bytesPerFrame)
        val rate = format.sampleRate.coerceAtLeast(1)
        var gain = 1f
        for (f in 0 until frames) {
            if (f % 128 == 0) {
                val localUs = startLocalUs + (framesProcessed + f) * 1_000_000L / rate
                gain = source.gainAt(localUs)
            }
            for (ch in 0 until channels) {
                if (isFloat) {
                    out.putFloat(inputBuffer.float * gain)
                } else {
                    val v = (inputBuffer.short * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(v.toShort())
                }
            }
        }
        framesProcessed += frames
        // Drop any trailing partial frame so the buffer is fully consumed.
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }

    override fun onFlush() {
        framesProcessed = 0
        startLocalUs = clock.restartLocalUs()
    }

    override fun onReset() {
        framesProcessed = 0
        startLocalUs = 0
    }
}
