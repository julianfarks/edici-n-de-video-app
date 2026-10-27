package com.vanta.studio.engine

import android.content.Context
import android.view.Surface
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.transformer.CompositionPlayer
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.timeline.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/**
 * Real-time preview of a project, built on Media3's CompositionPlayer with the same
 * effect graph used for export.
 *
 * Structural edits (adding/removing/trimming clips, speed, transitions...) rebuild the
 * composition; parameter edits (colour, transform, keyframes, volume) are picked up live by
 * the effects through [LiveProject] and only need the current frame to be redrawn.
 *
 * Must be used from the main thread.
 */
class PreviewEngine(
    context: Context,
    private val scope: CoroutineScope,
    private val factory: CompositionFactory,
) {
    val sync = PlaybackSync()
    private var live: LiveProject? = null
    private val player: CompositionPlayer = CompositionPlayer.Builder(context).build()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionUs = MutableStateFlow(0L)
    val positionUs: StateFlow<Long> = _positionUs.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** True once the player has drawn a frame of the current composition on the surface. */
    private val _firstFrame = MutableStateFlow(false)
    val firstFrame: StateFlow<Boolean> = _firstFrame.asStateFlow()

    private val _building = MutableStateFlow(false)
    val building: StateFlow<Boolean> = _building.asStateFlow()

    private var durationUs = 0L
    private var structuralKey: Any? = null
    private var buildJob: Job? = null
    private var tickJob: Job? = null
    private var seekJob: Job? = null
    private var pendingSeekUs: Long? = null
    private var lastSeekAt = 0L
    private var hasComposition = false
    private var released = false
    private var lastProject: Project? = null
    private var lastShortSide = 0
    private var buildSerial = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) startTicker() else syncPosition()
            }

            override fun onRenderedFirstFrame() {
                _firstFrame.value = true
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _isPlaying.value = false
                    _positionUs.value = durationUs
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                _error.value = error.errorCodeName + (error.message?.let { ": $it" } ?: "")
                _isPlaying.value = false
                structuralKey = null
            }
        })
    }

    fun attachSurface(view: SurfaceView) {
        if (!released) player.setVideoSurfaceView(view)
    }

    fun detachSurface(view: SurfaceView) {
        if (!released) player.clearVideoSurfaceView(view)
    }

    /** Plain-surface variant, used by tests that render without a visible window. */
    fun attachSurface(surface: Surface) {
        if (!released) player.setVideoSurface(surface)
    }

    /** Rebuilds the preview after a failure, with the latest project. */
    fun retry() {
        val p = lastProject ?: return
        _error.value = null
        structuralKey = null
        hasComposition = false
        update(p, lastShortSide)
    }

    /** Feeds the latest project. Cheap to call after every edit. */
    fun update(project: Project, shortSide: Int) {
        if (released) return
        lastProject = project
        lastShortSide = shortSide
        val l = live ?: LiveProject(project).also { live = it }
        l.project = project
        durationUs = Timeline.totalDurationUs(project)
        if (_positionUs.value > durationUs) _positionUs.value = durationUs
        val key = structuralKey(project, shortSide)
        if (key == structuralKey && hasComposition) {
            if (!player.isPlaying) refreshFrame()
            return
        }
        structuralKey = key
        rebuild(project, shortSide, l)
    }

    private fun rebuild(project: Project, shortSide: Int, l: LiveProject) {
        buildJob?.cancel()
        if (project.clips.isEmpty()) {
            player.pause()
            hasComposition = false
            _firstFrame.value = false
            return
        }
        val serial = ++buildSerial
        buildJob = scope.launch {
            delay(100)
            _building.value = true
            try {
                val (w, h) = project.canvas.sizeForShortSide(shortSide)
                val composition = withContext(Dispatchers.Default) {
                    factory.build(project, CompositionFactory.Mode.Preview(w, h), l, sync)
                }
                if (serial != buildSerial) return@launch
                val wasPlaying = player.isPlaying
                val pos = min(_positionUs.value, durationUs).coerceAtLeast(0)
                _error.value = null
                _firstFrame.value = false
                player.setComposition(composition)
                player.prepare()
                hasComposition = true
                sync.mark(pos)
                player.seekTo(pos / 1000)
                if (wasPlaying) player.play()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
                hasComposition = false
                structuralKey = null
            } finally {
                _building.value = false
            }
        }
    }

    fun togglePlay() {
        if (released) return
        if (_error.value != null) {
            retry()
            return
        }
        if (!hasComposition) return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (_positionUs.value >= durationUs - 30_000) seekTo(0, immediate = true)
            player.play()
        }
    }

    fun pause() {
        if (!released && player.isPlaying) player.pause()
    }

    /**
     * Seeks the preview. Calls arriving faster than the player can follow (scrubbing) are
     * coalesced so the latest position always wins.
     */
    fun seekTo(positionUs: Long, immediate: Boolean = false) {
        val target = positionUs.coerceIn(0, maxOf(durationUs, 0))
        _positionUs.value = target
        if (released || !hasComposition) return
        val now = System.currentTimeMillis()
        if (immediate || now - lastSeekAt > SEEK_INTERVAL_MS) {
            // A newer position replaces any older one still waiting to be applied.
            pendingSeekUs = null
            doSeek(target)
        } else {
            pendingSeekUs = target
            if (seekJob?.isActive != true) {
                seekJob = scope.launch {
                    delay(SEEK_INTERVAL_MS)
                    val latest = pendingSeekUs
                    pendingSeekUs = null
                    if (latest != null) doSeek(latest)
                }
            }
        }
    }

    private fun doSeek(us: Long) {
        if (released) return
        lastSeekAt = System.currentTimeMillis()
        // Never ask the player for a position outside the composition.
        val safe = us.coerceIn(0, maxOf(durationUs, 0))
        sync.mark(safe)
        player.seekTo(safe / 1000)
    }

    /** Redraws the current frame so a parameter change becomes visible while paused. */
    fun refreshFrame() {
        if (!hasComposition) return
        seekTo(_positionUs.value)
    }

    private fun startTicker() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive && player.isPlaying) {
                syncPosition()
                delay(33)
            }
        }
    }

    private fun syncPosition() {
        if (released || !hasComposition) return
        val ms = player.currentPosition
        if (ms == C.TIME_UNSET || ms < 0) return
        _positionUs.value = (ms * 1000).coerceIn(0, durationUs)
    }

    fun release() {
        if (released) return
        released = true
        buildJob?.cancel()
        tickJob?.cancel()
        seekJob?.cancel()
        player.release()
    }

    companion object {
        private const val SEEK_INTERVAL_MS = 70L

        /** Everything that is baked into the Media3 graph; other properties are read live. */
        fun structuralKey(p: Project, shortSide: Int): Any = listOf(
            shortSide,
            p.canvas,
            p.outro,
            p.fx.isNotEmpty(),
            p.clips.map {
                listOf(
                    it.id, it.uri, it.kind, it.trimStartUs, it.trimEndUs, it.speed,
                    it.rotation, it.flipH, it.flipV, it.crop, it.transitionIn, it.hasAudio,
                )
            },
            p.audio.map { listOf(it.id, it.uri, it.startUs, it.trimStartUs, it.trimEndUs, it.lane) },
        )
    }
}
