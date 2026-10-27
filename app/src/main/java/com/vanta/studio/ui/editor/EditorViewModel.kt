package com.vanta.studio.ui.editor

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vanta.studio.AppContainer
import com.vanta.studio.R
import com.vanta.studio.data.AppSettings
import com.vanta.studio.data.MediaImporter
import com.vanta.studio.data.MediaProbe
import com.vanta.studio.domain.anim.Keyframes
import com.vanta.studio.domain.anim.LayerAnimator
import com.vanta.studio.domain.anim.Pose
import com.vanta.studio.domain.anim.toLayerTransform
import com.vanta.studio.domain.anim.toPose
import com.vanta.studio.domain.anim.toTransform
import com.vanta.studio.domain.history.EditHistory
import com.vanta.studio.domain.model.AspectRatio
import com.vanta.studio.domain.model.AudioClip
import com.vanta.studio.domain.model.CanvasSpec
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.ColorAdjust
import com.vanta.studio.domain.model.CropRect
import com.vanta.studio.domain.model.Easing
import com.vanta.studio.domain.model.EffectRef
import com.vanta.studio.domain.model.EffectType
import com.vanta.studio.domain.model.FilterRef
import com.vanta.studio.domain.model.Keyframe
import com.vanta.studio.domain.model.LayerAnim
import com.vanta.studio.domain.model.LayerTransform
import com.vanta.studio.domain.model.OverlayKind
import com.vanta.studio.domain.model.OverlayLayer
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.TextLayer
import com.vanta.studio.domain.model.TextStyle
import com.vanta.studio.domain.model.TransitionRef
import com.vanta.studio.domain.model.TransitionType
import com.vanta.studio.domain.presets.TextPreset
import com.vanta.studio.domain.timeline.Snapping
import com.vanta.studio.domain.timeline.Timeline
import com.vanta.studio.engine.PreviewEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.vanta.studio.domain.fx.FxOps
import com.vanta.studio.domain.subtitles.SubtitleOps
import com.vanta.studio.domain.model.SubtitleTrack
import com.vanta.studio.domain.model.FxEvent
import com.vanta.studio.domain.model.FxType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

sealed interface Selection {
    data object None : Selection
    data class OfClip(val id: String) : Selection
    data class OfText(val id: String) : Selection
    data class OfOverlay(val id: String) : Selection
    data class OfAudio(val id: String) : Selection
    data class OfFx(val id: String) : Selection
    data class OfCue(val id: String) : Selection

    val itemId: String?
        get() = when (this) {
            None -> null
            is OfClip -> id
            is OfText -> id
            is OfOverlay -> id
            is OfAudio -> id
            is OfFx -> id
            is OfCue -> id
        }
}

enum class Panel {
    TEXT_PRESETS, TEXT_STYLE, ANIMATION, LAYERS, FILTERS, ADJUST, EFFECTS, TRANSITION,
    SPEED, VOLUME, TRANSFORM, CROP, TRIM, FORMAT, OVERLAY_COLOR, AUDIO_FADE,
    AI_STUDIO, BEAT_FX, FX_EDIT, SUBTITLES, SILENCE, AUTO_EDIT, ASSISTANT,
}

enum class SaveStatus { SAVED, SAVING, DIRTY }

data class UiMessage(@StringRes val res: Int, val args: List<Any> = emptyList(), val id: Long = System.nanoTime())

data class EditorState(
    val loaded: Boolean = false,
    val loadFailed: Boolean = false,
    val project: Project? = null,
    val selection: Selection = Selection.None,
    val panel: Panel? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.SAVED,
    val pxPerSec: Float = 90f,
    val message: UiMessage? = null,
    val missingMedia: Set<String> = emptySet(),
    val busy: Boolean = false,
    val showTip: Boolean = false,
    val editingText: String? = null,
)

class EditorViewModel(private val c: AppContainer, private val projectId: String) : ViewModel() {
    val engine = PreviewEngine(c.context, viewModelScope, c.compositionFactory)

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = c.settings.state

    private var history: EditHistory<Project>? = null
    private var gestureBase: Project? = null
    private var saveJob: Job? = null
    private var dirty = false

    val project: Project? get() = history?.current
    val positionUs: Long get() = engine.positionUs.value

    init {
        viewModelScope.launch {
            try {
                val p = c.projects.load(projectId)
                history = EditHistory(p)
                _state.update { it.copy(loaded = true, project = p, showTip = c.settings.state.value.showTips && p.clips.isEmpty().not()) }
                pushToEngine()
                checkMissingMedia(p)
            } catch (e: Exception) {
                _state.update { it.copy(loaded = true, loadFailed = true) }
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun shortSide() = c.settings.state.value.previewQuality.shortSide

    private fun pushToEngine() {
        project?.let { engine.update(it, shortSide()) }
    }

    private fun publish(markDirty: Boolean = true) {
        val h = history ?: return
        val p = h.current
        val sel = validSelection(p, _state.value.selection)
        _state.update {
            it.copy(
                project = p,
                selection = sel,
                canUndo = h.canUndo,
                canRedo = h.canRedo,
                saveStatus = if (markDirty) SaveStatus.DIRTY else it.saveStatus,
                panel = if (sel == Selection.None && it.panel in selectionPanels) null else it.panel,
            )
        }
        pushToEngine()
        if (markDirty) scheduleSave()
    }

    private val selectionPanels = setOf(
        Panel.TEXT_STYLE, Panel.ANIMATION, Panel.OVERLAY_COLOR, Panel.AUDIO_FADE, Panel.TRIM,
        Panel.SPEED, Panel.VOLUME, Panel.CROP,
    )

    private fun validSelection(p: Project, s: Selection): Selection = when (s) {
        Selection.None -> s
        is Selection.OfClip -> if (p.clips.any { it.id == s.id }) s else Selection.None
        is Selection.OfText -> if (p.texts.any { it.id == s.id }) s else Selection.None
        is Selection.OfOverlay -> if (p.overlays.any { it.id == s.id }) s else Selection.None
        is Selection.OfAudio -> if (p.audio.any { it.id == s.id }) s else Selection.None
        is Selection.OfFx -> if (p.fx.any { it.id == s.id }) s else Selection.None
        is Selection.OfCue -> if (p.subtitles?.cues?.any { it.id == s.id } == true) s else Selection.None
    }

    /** Applies [f] as one undoable step. */
    private fun edit(f: (Project) -> Project) {
        val h = history ?: return
        endGesture()
        if (h.push(f(h.current).copy(modifiedAt = System.currentTimeMillis()))) publish()
    }

    /** Applies [f] without creating a history step; call [endGesture] when the gesture ends. */
    private fun live(f: (Project) -> Project) {
        val h = history ?: return
        if (gestureBase == null) gestureBase = h.current
        h.replace(f(h.current))
        publish()
    }

    /** Turns all live changes since the gesture started into a single undo step. */
    fun endGesture() {
        val h = history ?: return
        val base = gestureBase ?: return
        gestureBase = null
        val now = h.current
        h.replace(base)
        h.push(now.copy(modifiedAt = System.currentTimeMillis()))
        publish()
    }

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(900)
            saveNow()
        }
    }

    fun saveNow() {
        val p = history?.current ?: return
        if (!dirty && _state.value.saveStatus == SaveStatus.SAVED) return
        dirty = false
        _state.update { it.copy(saveStatus = SaveStatus.SAVING) }
        c.appScope.launch {
            try {
                c.projects.save(p)
                _state.update { s -> if (history?.current === p || !dirty) s.copy(saveStatus = SaveStatus.SAVED) else s }
            } catch (e: Exception) {
                dirty = true
                _state.update { it.copy(saveStatus = SaveStatus.DIRTY, message = UiMessage(R.string.msg_save_failed)) }
            }
        }
    }

    override fun onCleared() {
        saveJob?.cancel()
        history?.current?.let { p ->
            if (dirty || _state.value.saveStatus != SaveStatus.SAVED) {
                c.appScope.launch { runCatching { c.projects.save(p) } }
            }
        }
        engine.release()
    }

    private fun checkMissingMedia(p: Project) {
        viewModelScope.launch {
            val missing = withContext(Dispatchers.IO) {
                p.clips.filter { !MediaProbe.isReadable(c.context, Uri.parse(it.uri)) }.map { it.id }.toSet()
            }
            _state.update { it.copy(missingMedia = missing) }
            if (missing.isNotEmpty()) toast(R.string.msg_missing_media, missing.size)
        }
    }

    private fun toast(@StringRes res: Int, vararg args: Any) {
        _state.update { it.copy(message = UiMessage(res, args.toList())) }
    }

    fun messageShown(id: Long) = _state.update { if (it.message?.id == id) it.copy(message = null) else it }

    /** Closes the hint for good (it can be turned back on in Settings). */
    fun dismissTip() {
        _state.update { it.copy(showTip = false) }
        c.settings.update { it.copy(showTips = false) }
    }

    // ------------------------------------------------------------------ playback

    fun togglePlay() = engine.togglePlay()

    fun seekTo(us: Long, immediate: Boolean = false) {
        val p = project ?: return
        val fps = p.canvas.fps
        val t = if (c.settings.state.value.frameSnapping) Snapping.toFrame(us, fps) else us
        engine.seekTo(t, immediate)
    }

    fun stepFrame(delta: Int) {
        val p = project ?: return
        engine.pause()
        seekTo(positionUs + delta * (1_000_000L / p.canvas.fps), immediate = true)
    }

    fun jumpToStart() = seekTo(0, true)
    fun jumpToEnd() = project?.let { seekTo(Timeline.totalDurationUs(it), true) }

    // ------------------------------------------------------------------ selection / panels

    fun select(s: Selection) {
        _state.update { it.copy(selection = s, panel = if (s == Selection.None) null else it.panel.takeIf { p -> p in persistentPanels }) }
    }

    private val persistentPanels = setOf(Panel.FILTERS, Panel.EFFECTS, Panel.ADJUST, Panel.TRANSITION, Panel.TRANSFORM, Panel.LAYERS, Panel.TEXT_PRESETS, Panel.AI_STUDIO, Panel.BEAT_FX, Panel.FX_EDIT, Panel.SUBTITLES, Panel.SILENCE, Panel.AUTO_EDIT, Panel.ASSISTANT)

    fun openPanel(p: Panel?) = _state.update { it.copy(panel = p) }

    fun setZoom(pxPerSec: Float) = _state.update { it.copy(pxPerSec = pxPerSec.coerceIn(8f, 900f)) }

    fun zoomBy(factor: Float) = setZoom(_state.value.pxPerSec * factor)

    fun fitZoom(viewportPx: Float) {
        val p = project ?: return
        val dur = max(Timeline.totalDurationUs(p), 1_000_000L) / 1_000_000f
        setZoom(viewportPx * 0.8f / dur)
    }

    /** Clip the tools operate on: the selected one, otherwise the one under the playhead. */
    fun targetClip(): Clip? {
        val p = project ?: return null
        val sel = _state.value.selection
        if (sel is Selection.OfClip) return p.clips.firstOrNull { it.id == sel.id }
        return Timeline.clipAt(p, positionUs)?.clip
    }

    fun selectedClip(): Clip? = (_state.value.selection as? Selection.OfClip)?.let { s -> project?.clips?.firstOrNull { it.id == s.id } }

    // ------------------------------------------------------------------ importing

    fun addVisual(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = c.importer.importVisual(projectId, uris)
            _state.update { it.copy(busy = false) }
            if (result.clips.isNotEmpty()) {
                edit { p -> Timeline.insertClips(p, p.clips.size, result.clips) }
                if (_state.value.settings().showTips) _state.update { it.copy(showTip = true) }
            }
            if (result.failed > 0) toast(R.string.msg_import_failed, result.failed)
        }
    }

    private fun EditorState.settings() = c.settings.state.value

    fun addAudio(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val clip = c.importer.importAudio(projectId, uri, positionUs)
            _state.update { it.copy(busy = false) }
            if (clip == null) {
                toast(R.string.msg_audio_failed)
                return@launch
            }
            val h = history ?: return@launch
            val added = Timeline.addAudio(h.current, clip)
            if (added == null) {
                toast(R.string.msg_no_audio_lane)
            } else {
                edit { added }
                select(Selection.OfAudio(clip.id))
            }
        }
    }

    fun addColorClip(color: Long) {
        val p = project ?: return
        viewModelScope.launch {
            val clip = c.importer.createColorClip(projectId, color, p.canvas)
            edit { Timeline.insertClips(it, it.clips.size, listOf(clip)) }
        }
    }

    fun addImageLayer(uri: Uri) {
        viewModelScope.launch {
            val kept = c.importer.importImageLayer(projectId, uri)
            if (kept == null) {
                toast(R.string.msg_import_failed, 1)
                return@launch
            }
            addOverlay(OverlayKind.IMAGE, kept.toString(), width = 0.4f)
        }
    }

    // ------------------------------------------------------------------ clip operations

    fun splitAtPlayhead() {
        val p = project ?: return
        val t = positionUs
        when (val sel = _state.value.selection) {
            is Selection.OfAudio -> {
                val before = p.audio.size
                edit { Timeline.splitAudio(it, sel.id, t, MediaImporter.newId()) }
                if (project?.audio?.size == before) toast(R.string.msg_cannot_split)
            }
            is Selection.OfText, is Selection.OfOverlay -> splitLayer(sel.itemId!!, t)
            else -> {
                val result = Timeline.split(p, t, MediaImporter.newId())
                if (result == null) {
                    toast(R.string.msg_cannot_split)
                } else {
                    edit { Timeline.split(it, t, result.second)?.first ?: it }
                    select(Selection.OfClip(result.second))
                }
            }
        }
    }

    private fun splitLayer(id: String, t: Long) {
        val newId = MediaImporter.newId()
        val p = project ?: return
        val text = p.texts.firstOrNull { it.id == id }
        val ov = p.overlays.firstOrNull { it.id == id }
        val start = text?.startUs ?: ov?.startUs ?: return
        val end = text?.endUs ?: ov?.endUs ?: return
        if (t - start < Timeline.MIN_CLIP_US || end - t < Timeline.MIN_CLIP_US) {
            toast(R.string.msg_cannot_split)
            return
        }
        edit {
            if (text != null) {
                it.copy(
                    texts = it.texts.map { x -> if (x.id == id) x.copy(durationUs = t - start) else x } +
                        text.copy(id = newId, startUs = t, durationUs = end - t, keyframes = emptyList()),
                )
            } else {
                it.copy(
                    overlays = it.overlays.map { x -> if (x.id == id) x.copy(durationUs = t - start) else x } +
                        ov!!.copy(id = newId, startUs = t, durationUs = end - t, keyframes = emptyList()),
                )
            }
        }
    }

    fun deleteSelected() {
        when (val sel = _state.value.selection) {
            Selection.None -> Unit
            is Selection.OfClip -> edit { Timeline.deleteClip(it, sel.id) }
            is Selection.OfAudio -> edit { Timeline.deleteAudio(it, sel.id) }
            is Selection.OfText -> edit { Timeline.deleteLayer(it, sel.id) }
            is Selection.OfOverlay -> edit { Timeline.deleteLayer(it, sel.id) }
            is Selection.OfFx -> edit { FxOps.delete(it, sel.id) }
            is Selection.OfCue -> edit { p -> p.copy(subtitles = p.subtitles?.let { SubtitleOps.delete(it, sel.id) }) }
        }
        select(Selection.None)
    }

    fun duplicateSelected() {
        val newId = MediaImporter.newId()
        when (val sel = _state.value.selection) {
            Selection.None -> Unit
            is Selection.OfClip -> edit { Timeline.duplicateClip(it, sel.id, newId) }
            is Selection.OfAudio -> {
                val before = project?.audio?.size
                edit { Timeline.duplicateAudio(it, sel.id, newId) }
                if (project?.audio?.size == before) toast(R.string.msg_no_audio_lane) else select(Selection.OfAudio(newId))
                return
            }
            is Selection.OfText -> edit { Timeline.duplicateLayer(it, sel.id, newId) }
            is Selection.OfOverlay -> edit { Timeline.duplicateLayer(it, sel.id, newId) }
            is Selection.OfFx -> edit { FxOps.duplicate(it, sel.id, newId) }
            is Selection.OfCue -> return
        }
        when (_state.value.selection) {
            is Selection.OfFx -> select(Selection.OfFx(newId))
            is Selection.OfClip -> select(Selection.OfClip(newId))
            is Selection.OfText -> select(Selection.OfText(newId))
            is Selection.OfOverlay -> select(Selection.OfOverlay(newId))
            else -> Unit
        }
    }

    fun moveClip(from: Int, to: Int) {
        if (from == to) return
        edit { Timeline.move(it, from, to) }
    }

    /** Live trim from a handle drag; [startEdge] selects which end. Values are source times. */
    fun trimLive(clipId: String, startEdge: Boolean, newSourceUs: Long) {
        live { p -> if (startEdge) Timeline.trimStart(p, clipId, newSourceUs) else Timeline.trimEnd(p, clipId, newSourceUs) }
    }

    fun setStillDuration(clipId: String, us: Long) = live { Timeline.setStillDuration(it, clipId, us) }

    fun nudgeTrim(startEdge: Boolean, frames: Int) {
        val clip = selectedClip() ?: return
        val p = project ?: return
        val step = (1_000_000L / p.canvas.fps) * frames
        val deltaSource = (step * clip.speed).toLong()
        edit {
            if (startEdge) Timeline.trimStart(it, clip.id, clip.trimStartUs + deltaSource)
            else Timeline.trimEnd(it, clip.id, clip.trimEndUs + deltaSource)
        }
    }

    fun setSpeed(speed: Float) {
        val id = selectedClip()?.id ?: return
        live { Timeline.setSpeed(it, id, speed) }
    }

    fun updateSelectedClipLive(f: (Clip) -> Clip) {
        val id = selectedClip()?.id ?: return
        live { Timeline.updateClip(it, id, f) }
    }

    fun updateSelectedClip(f: (Clip) -> Clip) {
        val id = selectedClip()?.id ?: return
        edit { Timeline.updateClip(it, id, f) }
    }

    fun updateTargetClip(f: (Clip) -> Clip) {
        val id = targetClip()?.id ?: return
        edit { Timeline.updateClip(it, id, f) }
    }

    fun updateTargetClipLive(f: (Clip) -> Clip) {
        val id = targetClip()?.id ?: return
        live { Timeline.updateClip(it, id, f) }
    }

    fun rotate90() = updateSelectedClip { it.copy(rotation = (it.rotation + 90) % 360) }
    fun flipH() = updateSelectedClip { it.copy(flipH = !it.flipH) }
    fun flipV() = updateSelectedClip { it.copy(flipV = !it.flipV) }
    fun toggleMute() = updateSelectedClip { it.copy(muted = !it.muted) }

    fun freezeFrame() {
        val p = project ?: return
        val hit = Timeline.clipAt(p, positionUs) ?: return
        val clip = hit.clip
        if (clip.kind != com.vanta.studio.domain.model.MediaKind.VIDEO) {
            toast(R.string.msg_freeze_video_only)
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val srcTime = clip.localToSourceUs(hit.localUs)
            val still = c.importer.createFreezeFrame(projectId, clip, srcTime)
            _state.update { it.copy(busy = false) }
            if (still == null) {
                toast(R.string.msg_freeze_failed)
            } else {
                edit { Timeline.insertFreeze(it, positionUs, still, MediaImporter.newId()) }
                select(Selection.OfClip(still.id))
            }
        }
    }

    // ------------------------------------------------------------------ colour, filters, effects

    fun setFilter(id: String?) = updateTargetClip { it.copy(filter = id?.let { f -> FilterRef(f, it.filter?.takeIf { cur -> cur.id == f }?.intensity ?: 1f) }) }

    fun setFilterIntensity(v: Float) = updateTargetClipLive { c -> c.copy(filter = c.filter?.copy(intensity = v)) }

    fun setAdjustLive(f: (ColorAdjust) -> ColorAdjust) = updateTargetClipLive { it.copy(adjust = f(it.adjust)) }

    fun resetAdjust() = updateTargetClip { it.copy(adjust = ColorAdjust()) }

    fun applyColorToAll() {
        val src = targetClip() ?: return
        edit { p -> p.copy(clips = p.clips.map { it.copy(adjust = src.adjust, filter = src.filter) }) }
        toast(R.string.msg_applied_all)
    }

    fun addEffect(type: EffectType) = updateTargetClip { c ->
        if (c.effects.any { it.type == type }) c else c.copy(effects = c.effects + EffectRef(type))
    }

    fun removeEffect(type: EffectType) = updateTargetClip { c -> c.copy(effects = c.effects.filterNot { it.type == type }) }

    fun setEffectIntensityLive(type: EffectType, v: Float) =
        updateTargetClipLive { c -> c.copy(effects = c.effects.map { if (it.type == type) it.copy(intensity = v) else it }) }

    // ------------------------------------------------------------------ transitions

    fun setTransition(clipId: String, type: TransitionType?, durationUs: Long? = null) {
        edit { p ->
            Timeline.updateClip(p, clipId) { c ->
                if (type == null) c.copy(transitionIn = null)
                else c.copy(transitionIn = TransitionRef(type, durationUs ?: c.transitionIn?.durationUs ?: 600_000))
            }
        }
    }

    fun setTransitionDurationLive(clipId: String, durationUs: Long) {
        live { p -> Timeline.updateClip(p, clipId) { c -> c.copy(transitionIn = c.transitionIn?.copy(durationUs = durationUs)) } }
    }

    fun setOutro(type: TransitionType?) {
        edit { p -> p.copy(outro = type?.let { TransitionRef(it, p.outro?.durationUs ?: 800_000) }) }
    }

    fun setOutroDurationLive(us: Long) = live { p -> p.copy(outro = p.outro?.copy(durationUs = us)) }

    // ------------------------------------------------------------------ transforms & keyframes

    /** Pose of the selected visual item at the playhead (keyframes applied). */
    fun currentPose(): Pose? {
        val p = project ?: return null
        val t = positionUs
        return when (val sel = _state.value.selection) {
            is Selection.OfClip -> {
                val hit = Timeline.clipAt(p, t) ?: return null
                val clip = p.clips.firstOrNull { it.id == sel.id } ?: return null
                val local = if (hit.clip.id == clip.id) hit.localUs else 0L
                Keyframes.evaluate(clip.keyframes, local, clip.transform.toPose())
            }
            is Selection.OfText -> p.texts.firstOrNull { it.id == sel.id }?.let {
                Keyframes.evaluate(it.keyframes, (t - it.startUs).coerceIn(0, it.durationUs), it.transform.toPose())
            }
            is Selection.OfOverlay -> p.overlays.firstOrNull { it.id == sel.id }?.let {
                Keyframes.evaluate(it.keyframes, (t - it.startUs).coerceIn(0, it.durationUs), it.transform.toPose())
            }
            else -> null
        }
    }

    /** Local time of the playhead inside the selected item, or null when it is outside. */
    fun localTimeInSelection(): Long? {
        val p = project ?: return null
        val t = positionUs
        return when (val sel = _state.value.selection) {
            is Selection.OfClip -> {
                val i = Timeline.indexOf(p, sel.id)
                if (i < 0) null else (t - Timeline.clipStartUs(p, i)).takeIf { it in 0..p.clips[i].durationUs }
            }
            is Selection.OfText -> p.texts.firstOrNull { it.id == sel.id }?.let { (t - it.startUs).takeIf { l -> l in 0..it.durationUs } }
            is Selection.OfOverlay -> p.overlays.firstOrNull { it.id == sel.id }?.let { (t - it.startUs).takeIf { l -> l in 0..it.durationUs } }
            else -> null
        }
    }

    fun selectionKeyframes(): List<Keyframe> {
        val p = project ?: return emptyList()
        return when (val sel = _state.value.selection) {
            is Selection.OfClip -> p.clips.firstOrNull { it.id == sel.id }?.keyframes
            is Selection.OfText -> p.texts.firstOrNull { it.id == sel.id }?.keyframes
            is Selection.OfOverlay -> p.overlays.firstOrNull { it.id == sel.id }?.keyframes
            else -> null
        } ?: emptyList()
    }

    private fun frameTolerance(): Long = 1_000_000L / (project?.canvas?.fps ?: 30)

    /**
     * Changes the selected item's pose. When the item is animated the change is written to
     * a keyframe at the playhead (creating it if needed); otherwise to its base transform.
     */
    fun applyPose(f: (Pose) -> Pose) {
        val p = project ?: return
        val sel = _state.value.selection
        val pose = currentPose() ?: return
        val next = f(pose)
        val local = localTimeInSelection()
        when (sel) {
            is Selection.OfClip -> live { proj ->
                Timeline.updateClip(proj, sel.id) { clip ->
                    if (clip.keyframes.isNotEmpty() && local != null) {
                        clip.copy(keyframes = Keyframes.upsert(clip.keyframes, next.toKeyframe(local), frameTolerance()))
                    } else {
                        clip.copy(transform = next.toTransform())
                    }
                }
            }
            is Selection.OfText -> live { proj ->
                Timeline.updateText(proj, sel.id) { l ->
                    if (l.keyframes.isNotEmpty() && local != null) {
                        l.copy(keyframes = Keyframes.upsert(l.keyframes, next.toKeyframe(local), frameTolerance()))
                    } else {
                        l.copy(transform = next.toLayerTransform())
                    }
                }
            }
            is Selection.OfOverlay -> live { proj ->
                Timeline.updateOverlay(proj, sel.id) { l ->
                    if (l.keyframes.isNotEmpty() && local != null) {
                        l.copy(keyframes = Keyframes.upsert(l.keyframes, next.toKeyframe(local), frameTolerance()))
                    } else {
                        l.copy(transform = next.toLayerTransform())
                    }
                }
            }
            else -> Unit
        }
        if (p.clips.isEmpty()) return
    }

    fun toggleKeyframe() {
        val local = localTimeInSelection()
        val pose = currentPose()
        if (local == null || pose == null) {
            toast(R.string.msg_keyframe_outside)
            return
        }
        val tol = frameTolerance()
        val existing = Keyframes.indexNear(selectionKeyframes(), local, tol) >= 0
        val update: (List<Keyframe>) -> List<Keyframe> = { list ->
            if (existing) Keyframes.remove(list, local, tol)
            else Keyframes.upsert(list, pose.toKeyframe(local), tol)
        }
        updateSelectedKeyframes(update)
    }

    fun setKeyframeEasing(easing: Easing) {
        val local = localTimeInSelection() ?: return
        val tol = frameTolerance()
        updateSelectedKeyframes { list ->
            val i = Keyframes.indexNear(list, local, tol)
            if (i < 0) list else list.mapIndexed { idx, k -> if (idx == i) k.copy(easing = easing) else k }
        }
    }

    fun clearKeyframes() = updateSelectedKeyframes { emptyList() }

    fun jumpToKeyframe(next: Boolean) {
        val p = project ?: return
        val sel = _state.value.selection
        val origin = when (sel) {
            is Selection.OfClip -> Timeline.clipStartUs(p, Timeline.indexOf(p, sel.id))
            is Selection.OfText -> p.texts.firstOrNull { it.id == sel.id }?.startUs
            is Selection.OfOverlay -> p.overlays.firstOrNull { it.id == sel.id }?.startUs
            else -> null
        } ?: return
        val times = selectionKeyframes().map { origin + it.timeUs }.sorted()
        val now = positionUs
        val target = if (next) times.firstOrNull { it > now + 1000 } else times.lastOrNull { it < now - 1000 }
        target?.let { seekTo(it, true) }
    }

    private fun updateSelectedKeyframes(f: (List<Keyframe>) -> List<Keyframe>) {
        when (val sel = _state.value.selection) {
            is Selection.OfClip -> edit { Timeline.updateClip(it, sel.id) { c -> c.copy(keyframes = f(c.keyframes)) } }
            is Selection.OfText -> edit { Timeline.updateText(it, sel.id) { l -> l.copy(keyframes = f(l.keyframes)) } }
            is Selection.OfOverlay -> edit { Timeline.updateOverlay(it, sel.id) { l -> l.copy(keyframes = f(l.keyframes)) } }
            else -> Unit
        }
    }

    fun setCropLive(f: (CropRect) -> CropRect) = updateSelectedClipLive { it.copy(crop = f(it.crop)) }

    fun resetTransform() {
        when (val sel = _state.value.selection) {
            is Selection.OfClip -> edit { Timeline.updateClip(it, sel.id) { c -> c.copy(transform = com.vanta.studio.domain.model.Transform(), keyframes = emptyList()) } }
            is Selection.OfText -> edit { Timeline.updateText(it, sel.id) { l -> l.copy(transform = LayerTransform(), keyframes = emptyList()) } }
            is Selection.OfOverlay -> edit { Timeline.updateOverlay(it, sel.id) { l -> l.copy(transform = LayerTransform(), keyframes = emptyList()) } }
            else -> Unit
        }
    }

    // ------------------------------------------------------------------ text & layers

    fun addText(preset: TextPreset, text: String) {
        val p = project ?: return
        val start = positionUs.coerceAtMost(max(0, Timeline.totalDurationUs(p) - Timeline.MIN_CLIP_US))
        val total = Timeline.totalDurationUs(p)
        val duration = min(3_000_000L, max(Timeline.MIN_CLIP_US, total - start)).coerceAtLeast(1_000_000L)
        val layer = TextLayer(
            id = MediaImporter.newId(), text = text, startUs = start, durationUs = duration,
            style = preset.style, transform = LayerTransform(y = preset.y),
            animIn = preset.animIn, animOut = preset.animOut,
        )
        edit { Timeline.addText(it, layer) }
        select(Selection.OfText(layer.id))
        _state.update { it.copy(panel = Panel.TEXT_STYLE) }
    }

    fun startEditingText() {
        val sel = _state.value.selection as? Selection.OfText ?: return
        val t = project?.texts?.firstOrNull { it.id == sel.id } ?: return
        _state.update { it.copy(editingText = t.text) }
    }

    fun stopEditingText() = _state.update { it.copy(editingText = null) }

    fun setText(text: String) {
        val sel = _state.value.selection as? Selection.OfText ?: return
        edit { Timeline.updateText(it, sel.id) { l -> l.copy(text = text) } }
        stopEditingText()
    }

    fun updateTextStyleLive(f: (TextStyle) -> TextStyle) {
        val sel = _state.value.selection as? Selection.OfText ?: return
        live { Timeline.updateText(it, sel.id) { l -> l.copy(style = f(l.style)) } }
    }

    fun updateTextStyle(f: (TextStyle) -> TextStyle) {
        val sel = _state.value.selection as? Selection.OfText ?: return
        edit { Timeline.updateText(it, sel.id) { l -> l.copy(style = f(l.style)) } }
    }

    fun addOverlay(kind: OverlayKind, source: String, width: Float = 0.3f, color: Long = 0xFFFFFFFF) {
        val p = project ?: return
        val total = Timeline.totalDurationUs(p)
        val start = positionUs.coerceAtMost(max(0, total - Timeline.MIN_CLIP_US))
        val duration = if (kind == OverlayKind.FRAME) max(Timeline.MIN_CLIP_US, total - start)
        else min(3_000_000L, max(Timeline.MIN_CLIP_US, total - start)).coerceAtLeast(1_000_000L)
        val layer = OverlayLayer(
            id = MediaImporter.newId(), kind = kind, source = source, startUs = start,
            durationUs = duration, color = color, width = width,
            animIn = if (kind == OverlayKind.FRAME) LayerAnim.NONE else LayerAnim.POP,
        )
        edit { Timeline.addOverlay(it, layer) }
        select(Selection.OfOverlay(layer.id))
    }

    fun setOverlayColor(color: Long) {
        val sel = _state.value.selection as? Selection.OfOverlay ?: return
        edit { Timeline.updateOverlay(it, sel.id) { l -> l.copy(color = color) } }
    }

    fun setOverlayWidthLive(width: Float) {
        val sel = _state.value.selection as? Selection.OfOverlay ?: return
        live { Timeline.updateOverlay(it, sel.id) { l -> l.copy(width = width) } }
    }

    fun setLayerAnim(animIn: LayerAnim? = null, animOut: LayerAnim? = null) {
        when (val sel = _state.value.selection) {
            is Selection.OfText -> edit { Timeline.updateText(it, sel.id) { l -> l.copy(animIn = animIn ?: l.animIn, animOut = animOut ?: l.animOut) } }
            is Selection.OfOverlay -> edit { Timeline.updateOverlay(it, sel.id) { l -> l.copy(animIn = animIn ?: l.animIn, animOut = animOut ?: l.animOut) } }
            else -> Unit
        }
    }

    fun setLayerAnimDurationLive(us: Long) {
        when (val sel = _state.value.selection) {
            is Selection.OfText -> live { Timeline.updateText(it, sel.id) { l -> l.copy(animDurationUs = us) } }
            is Selection.OfOverlay -> live { Timeline.updateOverlay(it, sel.id) { l -> l.copy(animDurationUs = us) } }
            else -> Unit
        }
    }

    fun layerOrderUp(up: Boolean) {
        val id = _state.value.selection.itemId ?: return
        edit { Timeline.moveLayerZ(it, id, up) }
    }

    fun moveLayerLive(id: String, startUs: Long) = live { Timeline.moveLayer(it, id, startUs) }

    fun trimLayerLive(id: String, startUs: Long?, endUs: Long?) = live { Timeline.trimLayer(it, id, startUs, endUs) }

    // ------------------------------------------------------------------ audio

    fun moveAudioLive(id: String, startUs: Long) = live { Timeline.moveAudio(it, id, startUs) }

    fun trimAudioLive(id: String, startUs: Long?, endUs: Long?) = live { Timeline.trimAudio(it, id, startUs, endUs) }

    fun updateSelectedAudio(f: (AudioClip) -> AudioClip) {
        val sel = _state.value.selection as? Selection.OfAudio ?: return
        edit { Timeline.updateAudio(it, sel.id, f) }
    }

    fun updateSelectedAudioLive(f: (AudioClip) -> AudioClip) {
        val sel = _state.value.selection as? Selection.OfAudio ?: return
        live { Timeline.updateAudio(it, sel.id, f) }
    }

    // ------------------------------------------------------------------ project

    fun setCanvas(canvas: CanvasSpec) = edit { it.copy(canvas = canvas) }

    fun rename(name: String) {
        if (name.isBlank()) return
        edit { it.copy(name = name.trim()) }
    }

    fun undo() {
        val h = history ?: return
        endGesture()
        if (h.undo() != null) publish()
    }

    fun redo() {
        val h = history ?: return
        if (h.redo() != null) publish()
    }

    // ------------------------------------------------------------------ AI / effects plumbing

    /** One undoable edit made by a feature outside this class (AI tools, effects). */
    fun mutate(f: (Project) -> Project) = edit(f)

    /** A continuous change (slider drag); finish with [endGesture]. */
    fun mutateLive(f: (Project) -> Project) = live(f)

    val ai: AiController by lazy { AiController(c, this, viewModelScope) }

    fun addFx(type: FxType, atUs: Long = positionUs) {
        val id = MediaImporter.newId()
        edit { FxOps.addAt(it, id, type, atUs) }
        select(Selection.OfFx(id))
    }

    fun updateFx(id: String, f: (FxEvent) -> FxEvent) = mutateLive { FxOps.update(it, id, f) }

    fun deleteFx(id: String) {
        edit { FxOps.delete(it, id) }
        select(Selection.None)
    }

    fun duplicateFx(id: String) {
        val newId = MediaImporter.newId()
        edit { FxOps.duplicate(it, id, newId) }
        select(Selection.OfFx(newId))
    }

    fun moveFxLive(id: String, startUs: Long) = mutateLive { FxOps.update(it, id) { e -> e.copy(startUs = startUs) } }

    /** Trims an effect from either edge while dragging (times are timeline times). */
    fun trimFxLive(id: String, startUs: Long?, endUs: Long?) = mutateLive { p ->
        FxOps.update(p, id) { e ->
            val s = startUs ?: e.startUs
            val en = endUs ?: e.endUs
            // Keep the beat-aligned moment where it was while the edges move.
            val sync = e.syncUs
            val dur = (en - s).coerceAtLeast(FxOps.MIN_DURATION_US)
            e.copy(startUs = s, durationUs = dur, peak = ((sync - s).toFloat() / dur).coerceIn(0.02f, 0.9f))
        }
    }

    fun moveCueLive(id: String, startUs: Long) = mutateLive { p ->
        val t = p.subtitles ?: return@mutateLive p
        p.copy(subtitles = SubtitleOps.update(t, id) { c -> SubtitleOps.retime(c, startUs, startUs + (c.endUs - c.startUs)) })
    }

    fun trimCueLive(id: String, startUs: Long?, endUs: Long?) = mutateLive { p ->
        val t = p.subtitles ?: return@mutateLive p
        p.copy(subtitles = SubtitleOps.update(t, id) { c -> SubtitleOps.retime(c, startUs ?: c.startUs, endUs ?: c.endUs) })
    }

    /** Plays the preview starting at [us] (clamped to the project). */
    fun previewFrom(us: Long) {
        seekTo(us.coerceAtLeast(0), true)
        if (!engine.isPlaying.value) engine.togglePlay()
    }

    /** One undoable change to the subtitle track (no-op when there is none). */
    fun editSubtitles(f: (SubtitleTrack) -> SubtitleTrack) = edit { p -> p.subtitles?.let { p.copy(subtitles = f(it)) } ?: p }

    /** Continuous change to the subtitle track; finish with [endGesture]. */
    fun liveSubtitles(f: (SubtitleTrack) -> SubtitleTrack) = mutateLive { p -> p.subtitles?.let { p.copy(subtitles = f(it)) } ?: p }

    /** Replaces/creates the subtitle track from imported cues, keeping the current style. */
    fun importCues(cues: List<com.vanta.studio.domain.model.Cue>) = edit { p ->
        val base = p.subtitles ?: SubtitleTrack(engine = "srt")
        p.copy(subtitles = base.copy(cues = cues.sortedBy { it.startUs }, enabled = true))
    }

    fun toggleMarkerAtPlayhead() = edit { FxOps.toggleMarker(it, positionUs) }

    fun clearMarkers() = edit { it.copy(markers = emptyList()) }

    /** Playhead-relative helpers for the timeline UI. */
    fun snapCandidates(excludeId: String?): List<Long> {
        val p = project ?: return emptyList()
        return Snapping.candidates(p, positionUs, excludeId)
    }

    fun layerFrameAt(selectionId: String, timeUs: Long) = project?.let { p ->
        p.texts.firstOrNull { it.id == selectionId }?.let {
            LayerAnimator.evaluate(it.startUs, it.durationUs, it.transform, it.keyframes, it.animIn, it.animOut, it.animDurationUs, timeUs)
        } ?: p.overlays.firstOrNull { it.id == selectionId }?.let {
            LayerAnimator.evaluate(it.startUs, it.durationUs, it.transform, it.keyframes, it.animIn, it.animOut, it.animDurationUs, timeUs)
        }
    }

    companion object {
        fun distanceUs(a: Long, b: Long) = abs(a - b)
        fun aspectLabel(a: AspectRatio) = a.name
    }
}
