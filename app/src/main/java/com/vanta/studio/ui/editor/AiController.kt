package com.vanta.studio.ui.editor

import android.net.Uri
import androidx.annotation.StringRes
import com.vanta.studio.AppContainer
import com.vanta.studio.R
import com.vanta.studio.domain.fx.AutoSyncOptions
import com.vanta.studio.domain.fx.FxAutoSync
import com.vanta.studio.domain.fx.FxOps
import com.vanta.studio.domain.model.BeatSource
import com.vanta.studio.domain.model.FxType
import com.vanta.studio.domain.subtitles.SubtitleOps
import com.vanta.studio.data.MediaImporter
import com.vanta.studio.engine.asr.CloudEngine
import com.vanta.studio.engine.asr.SpeechEngine
import com.vanta.studio.engine.asr.SpeechException
import com.vanta.studio.engine.asr.SpeechModel
import com.vanta.studio.engine.asr.VoskEngine
import com.vanta.studio.engine.audio.AudioDecodeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Something the AI hub can run. */
enum class AiJobKind { BEAT, SUBTITLES, MODEL }

data class AiJobState(val kind: AiJobKind, val progress: Float)

data class AiError(@StringRes val res: Int, val detail: String? = null)

data class AiState(
    val job: AiJobState? = null,
    val error: AiError? = null,
    /** Number of flashes the last auto-sync added, shown as feedback. */
    val lastAutoCount: Int? = null,
    val cloudConfigured: Boolean = false,
    /** Minimum strength for a transient to count as a strong hit (the sensitivity control). */
    val hitThreshold: Float = 0.5f,
    /** Set when a subtitle run has just finished, so the UI can move to the editor. */
    val lastSubtitleCount: Int? = null,
)

/**
 * Runs the long AI operations (decoding, rhythm analysis, speech recognition, model download) off
 * the main thread, one at a time, with progress and cancellation. Results are applied to the
 * project through [EditorViewModel.mutate], so each one is a single undo step. A failed or
 * cancelled job changes nothing.
 */
class AiController(
    private val c: AppContainer,
    private val vm: EditorViewModel,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(AiState(cloudConfigured = c.secrets.has(CLOUD_KEY)))
    val state: StateFlow<AiState> = _state.asStateFlow()
    private var job: Job? = null

    val busy: Boolean get() = _state.value.job != null

    fun setHitThreshold(v: Float) = _state.update { it.copy(hitThreshold = v.coerceIn(0.05f, 0.95f)) }

    fun deleteCue(id: String) {
        vm.mutate { p -> p.copy(subtitles = p.subtitles?.let { SubtitleOps.delete(it, id) }) }
        vm.select(Selection.None)
    }

    fun consumeSubtitleResult() = _state.update { it.copy(lastSubtitleCount = null) }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun cancel() {
        job?.cancel()
    }

    private fun run(kind: AiJobKind, block: suspend (progress: (Float) -> Unit) -> Unit) {
        if (busy) return
        _state.update { it.copy(job = AiJobState(kind, 0f), error = null) }
        job = scope.launch {
            try {
                block { p -> _state.update { s -> s.copy(job = s.job?.copy(progress = p.coerceIn(0f, 1f))) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechException) {
                fail(
                    when (e.kind) {
                        SpeechException.Kind.NO_MODEL -> R.string.ai_err_no_model
                        SpeechException.Kind.NO_AUDIO -> R.string.ai_err_no_audio
                        SpeechException.Kind.NETWORK -> R.string.ai_err_network
                        SpeechException.Kind.AUTH -> R.string.ai_err_auth
                        SpeechException.Kind.QUOTA -> R.string.ai_err_quota
                        else -> R.string.ai_err_engine
                    },
                    e.message,
                )
            } catch (e: AudioDecodeException) {
                fail(R.string.ai_err_no_audio, e.message)
            } catch (e: OutOfMemoryError) {
                fail(R.string.ai_err_memory, null)
            } catch (e: Exception) {
                fail(R.string.ai_err_generic, e.message)
            } finally {
                _state.update { it.copy(job = null) }
            }
        }
    }

    private fun fail(@StringRes res: Int, detail: String?) = _state.update { it.copy(error = AiError(res, detail)) }

    // ------------------------------------------------------------------ rhythm

    /** What can be analysed: the clip/audio the playhead or selection points at, with audio. */
    fun beatCandidate(): Triple<String, Boolean, Pair<Uri, LongRange>>? {
        val p = vm.project ?: return null
        val sel = vm.state.value.selection
        (sel as? Selection.OfAudio)?.let { s -> p.audio.firstOrNull { it.id == s.id } }?.let {
            return Triple(it.id, false, Uri.parse(it.uri) to (it.trimStartUs..it.trimEndUs))
        }
        val clip = (sel as? Selection.OfClip)?.let { s -> p.clips.firstOrNull { it.id == s.id && it.hasAudio } }
        clip?.let { return Triple(it.id, true, Uri.parse(it.uri) to (it.trimStartUs..it.trimEndUs)) }
        p.audio.firstOrNull()?.let { return Triple(it.id, false, Uri.parse(it.uri) to (it.trimStartUs..it.trimEndUs)) }
        p.clips.firstOrNull { it.hasAudio }?.let { return Triple(it.id, true, Uri.parse(it.uri) to (it.trimStartUs..it.trimEndUs)) }
        return null
    }

    fun analyzeBeat() {
        val (itemId, isClip, src) = beatCandidate() ?: return fail(R.string.ai_err_no_audio, null)
        run(AiJobKind.BEAT) { progress ->
            val map = c.beats.analyze(src.first, src.second.first, src.second.last, progress)
            if (map.beats.isEmpty() && map.onsets.isEmpty()) {
                fail(R.string.ai_err_no_rhythm, null)
            } else {
                vm.mutate { it.copy(beat = BeatSource(itemId, isClip, map)) }
            }
        }
    }

    fun clearBeat() = vm.mutate { it.copy(beat = null) }

    /** Replaces earlier auto-placed events with new ones synced to the analysed rhythm. */
    fun autoSync(types: List<FxType>, options: AutoSyncOptions) {
        val p = vm.project ?: return
        val events = FxAutoSync.place(p, types, options) { MediaImporter.newId() }
        vm.mutate { FxOps.replaceAuto(it, events) }
        _state.update { it.copy(lastAutoCount = events.size) }
    }

    fun removeAutoFx() {
        vm.mutate { FxOps.removeAuto(it) }
        _state.update { it.copy(lastAutoCount = null) }
    }

    // ------------------------------------------------------------------ speech

    fun isModelInstalled(model: SpeechModel) = c.models.installed(model) != null

    fun installModel(model: SpeechModel) = run(AiJobKind.MODEL) { progress -> c.models.install(model, progress) }

    fun deleteModel(model: SpeechModel) {
        c.models.delete(model)
        _state.update { it.copy() }
    }

    fun saveCloudKey(key: String) {
        c.secrets.put(CLOUD_KEY, key)
        _state.update { it.copy(cloudConfigured = key.isNotBlank()) }
    }

    fun cloudSettings(): Pair<String, String> {
        val prefs = c.context.getSharedPreferences("vanta_ai", android.content.Context.MODE_PRIVATE)
        return (prefs.getString("cloud_url", DEFAULT_URL) ?: DEFAULT_URL) to (prefs.getString("cloud_model", DEFAULT_MODEL) ?: DEFAULT_MODEL)
    }

    fun saveCloudSettings(url: String, model: String) {
        c.context.getSharedPreferences("vanta_ai", android.content.Context.MODE_PRIVATE)
            .edit().putString("cloud_url", url.trim()).putString("cloud_model", model.trim()).apply()
    }

    /**
     * Transcribes the clip under the playhead (or the selected clip). [cloud] must only be true
     * when the user has just agreed to send this audio to the configured server.
     */
    fun generateSubtitles(language: String, cloud: Boolean) {
        val p = vm.project ?: return
        val clipId = (vm.state.value.selection as? Selection.OfClip)?.id?.takeIf { id -> p.clips.any { it.id == id && it.hasAudio } }
            ?: clipAtPlayhead()
            ?: return fail(R.string.ai_err_no_audio, null)
        val engine: SpeechEngine = if (cloud) {
            val key = c.secrets.get(CLOUD_KEY) ?: return fail(R.string.ai_err_auth, null)
            val (url, model) = cloudSettings()
            CloudEngine(url, model, key)
        } else {
            val m = c.models.forLanguage(language) ?: return fail(R.string.ai_err_no_model, null)
            val dir = c.models.installed(m) ?: return fail(R.string.ai_err_no_model, null)
            VoskEngine(dir)
        }
        run(AiJobKind.SUBTITLES) { progress ->
            val base = vm.project ?: return@run
            val track = c.subtitles.generate(base, clipId, engine, language, progress)
            if (track.cues.isEmpty()) {
                fail(R.string.ai_err_no_speech, null)
            } else {
                vm.mutate { it.copy(subtitles = track) }
                _state.update { it.copy(lastSubtitleCount = track.cues.size) }
            }
        }
    }

    private fun clipAtPlayhead(): String? {
        val p = vm.project ?: return null
        var start = 0L
        val t = vm.positionUs
        for (cl in p.clips) {
            if (t < start + cl.durationUs && cl.hasAudio) return cl.id
            start += cl.durationUs
        }
        return p.clips.firstOrNull { it.hasAudio }?.id
    }

    companion object {
        const val CLOUD_KEY = "cloud_asr_key"
        const val DEFAULT_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "whisper-1"
    }
}
