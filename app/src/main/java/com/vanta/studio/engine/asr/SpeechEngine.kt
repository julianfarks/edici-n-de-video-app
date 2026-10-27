package com.vanta.studio.engine.asr

import com.vanta.studio.domain.model.SubWord
import com.vanta.studio.engine.audio.DecodedAudio

class SpeechException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind { NO_MODEL, NO_AUDIO, NETWORK, AUTH, QUOTA, ENGINE, CANCELLED }
}

/** A stretch of text without per-word timing (cloud engines that only return segments). */
data class Segment(val text: String, val startUs: Long, val endUs: Long)

/**
 * What an engine heard. [words] carry measured timestamps; [segments] are used only when an
 * engine cannot give word times, in which case the spoken word cannot be highlighted.
 * All times are in source-audio time.
 */
data class Transcript(val words: List<SubWord>, val segments: List<Segment> = emptyList())

/** Speech recognition backend. Input is always 16 kHz mono PCM. */
interface SpeechEngine {
    val id: String

    /** True when audio leaves the device. */
    val isCloud: Boolean

    suspend fun transcribe(audio: DecodedAudio, language: String, onProgress: (Float) -> Unit): Transcript

    companion object {
        const val RATE = 16_000
    }
}
