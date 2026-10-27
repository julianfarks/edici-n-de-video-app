package com.vanta.studio.ui.editor

import android.net.Uri
import androidx.annotation.StringRes
import com.vanta.studio.AppContainer
import com.vanta.studio.R
import com.vanta.studio.domain.fx.AutoSyncOptions
import com.vanta.studio.domain.fx.FxAutoSync
import com.vanta.studio.domain.fx.FxOps
import com.vanta.studio.domain.assistant.AssistantExec
import com.vanta.studio.domain.assistant.ExecResult
import com.vanta.studio.domain.assistant.Interpretation
import com.vanta.studio.domain.assistant.SpanishInterpreter
import com.vanta.studio.domain.assistant.Template
import com.vanta.studio.engine.assistant.LlmInterpreter
import com.vanta.studio.engine.assistant.UsageMeter
import com.vanta.studio.domain.audio.SilenceDetector
import com.vanta.studio.domain.edit.AutoEdit
import com.vanta.studio.domain.edit.AutoEditOptions
import com.vanta.studio.domain.model.BeatSource
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.timeline.Ripple
import com.vanta.studio.engine.audio.SilenceProposal
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
enum class AiJobKind { BEAT, SUBTITLES, MODEL, SILENCE, ASSISTANT }

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
    /** Pauses proposed by the silence tool, waiting for review. */
    val silence: SilenceProposal? = null,
    val silenceChosen: Set<Int> = emptySet(),
    /** Open auto-edit session: the project as it was before, and the version being shown. */
    val autoEdit: AutoEditSession? = null,
    /** Instruction being reviewed or just applied in the text assistant. */
    val assistant: AssistantSession? = null,
    val llmConfigured: Boolean = false,
)

/**
 * A text instruction and what was understood from it. [base] is set once the proposal has been
 * applied (as a preview), so it can be cancelled back to exactly the previous project.
 */
data class AssistantSession(
    val instruction: String,
    val interpretation: Interpretation,
    val viaCloud: Boolean,
    val templateId: String? = null,
    val base: Project? = null,
    val result: ExecResult? = null,
)

/** The untouched project, the current version number and what it contains, while the user compares versions. */
data class AutoEditSession(
    val base: Project,
    val options: AutoEditOptions,
    val cuts: Int,
    val segments: Int,
    val fx: Int,
    val resamples: Boolean,
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
    private val _state = MutableStateFlow(AiState(cloudConfigured = c.secrets.has(CLOUD_KEY), llmConfigured = c.secrets.has(LLM_KEY)))
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

    // ------------------------------------------------------------------ silence

    private fun targetClipWithAudio(): String? {
        val p = vm.project ?: return null
        return (vm.state.value.selection as? Selection.OfClip)?.id?.takeIf { id -> p.clips.any { it.id == id && it.hasAudio } }
            ?: clipAtPlayhead()
    }

    fun analyzeSilence(o: SilenceDetector.Options) {
        val p = vm.project ?: return
        val id = targetClipWithAudio() ?: return fail(R.string.ai_err_no_audio, null)
        run(AiJobKind.SILENCE) { progress ->
            val res = c.silence.analyze(p, id, o, progress)
            _state.update { it.copy(silence = res, silenceChosen = res.cuts.indices.toSet()) }
        }
    }

    fun toggleSilenceCut(i: Int) = _state.update { s ->
        s.copy(silenceChosen = if (i in s.silenceChosen) s.silenceChosen - i else s.silenceChosen + i)
    }

    fun clearSilence() = _state.update { it.copy(silence = null, silenceChosen = emptySet()) }

    /** Removes the chosen pauses (ripple across all tracks) as one undoable step. */
    fun applySilence() {
        val s = _state.value
        val prop = s.silence ?: return
        val spans = prop.cuts.filterIndexed { i, _ -> i in s.silenceChosen }.map { it.startUs to it.endUs }
        if (spans.isEmpty()) return
        vm.mutate { Ripple.removeSpans(it, spans) { MediaImporter.newId() } }
        clearSilence()
    }

    // ------------------------------------------------------------------ auto edit to the music

    /** Shows a version of the music edit in the project; the original stays recoverable. */
    fun previewAutoEdit(o: AutoEditOptions) {
        val base = _state.value.autoEdit?.base ?: vm.project ?: return
        val plan = AutoEdit.plan(base, o) { MediaImporter.newId() } ?: return fail(R.string.ai_err_no_rhythm, null)
        val edited = AutoEdit.apply(base, plan, o) { MediaImporter.newId() }
        vm.mutate { edited.copy(modifiedAt = it.modifiedAt) }
        _state.update {
            it.copy(autoEdit = AutoEditSession(base, o, plan.cuts.size, plan.segments.size, plan.fx.size, plan.resamples))
        }
    }

    fun acceptAutoEdit() = _state.update { it.copy(autoEdit = null) }

    /** Puts the project back exactly as it was before the music edit. */
    fun cancelAutoEdit() {
        val s = _state.value.autoEdit ?: return
        vm.mutate { s.base.copy(modifiedAt = it.modifiedAt) }
        _state.update { it.copy(autoEdit = null) }
    }

    // ------------------------------------------------------------------ text assistant

    val meter: UsageMeter by lazy { UsageMeter(c.context) }

    private fun prefs() = c.context.getSharedPreferences("vanta_ai", android.content.Context.MODE_PRIVATE)

    fun llmSettings(): Pair<String, String> =
        (prefs().getString("llm_url", DEFAULT_URL) ?: DEFAULT_URL) to (prefs().getString("llm_model", DEFAULT_LLM_MODEL) ?: DEFAULT_LLM_MODEL)

    fun saveLlmSettings(url: String, model: String, key: String, monthlyLimit: Int) {
        prefs().edit().putString("llm_url", url.trim()).putString("llm_model", model.trim()).apply()
        meter.limit = monthlyLimit
        if (key.isNotBlank()) {
            c.secrets.put(LLM_KEY, key)
            _state.update { it.copy(llmConfigured = true) }
        }
    }

    fun forgetLlmKey() {
        c.secrets.put(LLM_KEY, "")
        _state.update { it.copy(llmConfigured = false) }
    }

    /** What would be sent to the service, shown in the consent dialog. */
    fun llmPreview(instruction: String): String =
        vm.project?.let { LlmInterpreter.summary(it, vm.positionUs) + "\n" + instruction } ?: instruction

    /** Understands the instruction offline, with fixed rules. Nothing leaves the device. */
    fun interpretLocal(instruction: String) {
        val text = instruction.trim()
        if (text.isEmpty()) return
        cancelAssistantPreview()
        _state.update { it.copy(assistant = AssistantSession(text, SpanishInterpreter.parse(text), viaCloud = false)) }
    }

    /** Proposes a local template: the same review, apply, preview, cancel and undo as any instruction. */
    fun proposeTemplate(t: Template) {
        cancelAssistantPreview()
        _state.update { it.copy(assistant = AssistantSession(t.id, Interpretation(t.ops), viaCloud = false, templateId = t.id)) }
    }

    /** Asks the user's own language-model service. Only call after the user has agreed to what is sent. */
    fun interpretCloud(instruction: String) {
        val text = instruction.trim()
        val project = vm.project ?: return
        if (text.isEmpty()) return
        val key = c.secrets.get(LLM_KEY)?.takeIf { it.isNotBlank() } ?: return fail(R.string.ai_err_auth, null)
        if (!meter.canCall) return fail(R.string.assist_limit_reached, null)
        cancelAssistantPreview()
        val (url, model) = llmSettings()
        run(AiJobKind.ASSISTANT) {
            val interpretation = LlmInterpreter(url, model, key).interpret(text, project, vm.positionUs)
            meter.record()
            _state.update { it.copy(assistant = AssistantSession(text, interpretation, viaCloud = true)) }
        }
    }

    /** Applies the proposal to the project as one undoable step, keeping the previous project to cancel back to. */
    fun applyAssistant() {
        val s = _state.value.assistant ?: return
        val base = s.base ?: vm.project ?: return
        val result = AssistantExec.apply(base, s.interpretation.ops, vm.positionUs) { MediaImporter.newId() }
        if (result.applied.isNotEmpty()) vm.mutate { result.project.copy(modifiedAt = it.modifiedAt, templateId = s.templateId ?: result.project.templateId) }
        _state.update { it.copy(assistant = s.copy(base = base, result = result)) }
    }

    fun acceptAssistant() = _state.update { it.copy(assistant = null) }

    /** Puts the project back as it was before the instruction was applied. */
    fun cancelAssistantPreview() {
        val s = _state.value.assistant ?: return
        val base = s.base
        if (base != null && s.result?.applied?.isNotEmpty() == true) vm.mutate { base.copy(modifiedAt = it.modifiedAt) }
        _state.update { it.copy(assistant = null) }
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
        const val LLM_KEY = "llm_assistant_key"
        const val DEFAULT_LLM_MODEL = "gpt-4o-mini"
        const val DEFAULT_URL = "https://api.openai.com/v1"
        const val DEFAULT_MODEL = "whisper-1"
    }
}
