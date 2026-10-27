package com.vanta.studio.engine.audio

import android.net.Uri
import com.vanta.studio.domain.audio.BeatAnalyzer
import com.vanta.studio.domain.model.BeatMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Decodes a media file's audio and runs the rhythm analysis on it. Cancellable, reports 0..1. */
class BeatService(private val decoder: AudioDecoder) {
    /**
     * [startUs]/[endUs] limit the analysed range of the source (the part the user actually uses).
     * Returned times are source times.
     */
    suspend fun analyze(uri: Uri, startUs: Long, endUs: Long, onProgress: (Float) -> Unit): BeatMap {
        val audio = decoder.decode(uri, BeatAnalyzer.TARGET_RATE, startUs, endUs) { onProgress(it * 0.4f) }
        return withContext(Dispatchers.Default) {
            val ctx = currentCoroutineContext()
            BeatAnalyzer.analyze(
                audio.pcm, audio.sampleRate, uri.toString(),
                isCancelled = { !ctx.isActive },
                progress = { onProgress(0.4f + it * 0.6f) },
            ).let { shiftTo(it, audio.startUs) }
        }
    }

    private fun shiftTo(m: BeatMap, offsetUs: Long): BeatMap = if (offsetUs == 0L) m else m.copy(
        onsets = m.onsets.map { it.copy(timeUs = it.timeUs + offsetUs) },
        beats = m.beats.map { it.copy(timeUs = it.timeUs + offsetUs) },
        sections = m.sections.map { it.copy(startUs = it.startUs + offsetUs, endUs = it.endUs + offsetUs) },
        pauses = m.pauses.map { it.copy(startUs = it.startUs + offsetUs, endUs = it.endUs + offsetUs) },
        peaks = m.peaks.map { it + offsetUs },
        tempoChanges = m.tempoChanges.map { it.copy(timeUs = it.timeUs + offsetUs) },
    )
}
