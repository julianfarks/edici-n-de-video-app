package com.vanta.studio.ui.editor

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SpeakerNotesOff
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Subtitles
import com.vanta.studio.domain.audio.BeatMapping
import com.vanta.studio.domain.fx.FxLibrary
import com.vanta.studio.domain.fx.FxCategory
import com.vanta.studio.data.Thumbnails
import com.vanta.studio.data.Waveforms
import com.vanta.studio.domain.model.AudioClip
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.domain.model.OverlayLayer
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.TextLayer
import com.vanta.studio.domain.timeline.Snapping
import com.vanta.studio.domain.timeline.Timeline
import com.vanta.studio.ui.components.formatTime
import com.vanta.studio.ui.theme.TimecodeStyle
import com.vanta.studio.ui.theme.Vanta
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val RULER_H = 22.dp
private val VIDEO_H = 58.dp
private val LANE_H = 30.dp
private val LANE_GAP = 4.dp
private val HANDLE_W = 22.dp
private val SNAP_PX = 10f

/** Everything the timeline needs to report back to the editor. */
class TimelineCallbacks(
    val onUserScroll: (Long) -> Unit,
    val onSelect: (Selection) -> Unit,
    val onZoom: (Float) -> Unit,
    val onReorder: (Int, Int) -> Unit,
    val onTrimClip: (clipId: String, startEdge: Boolean, newSourceUs: Long) -> Unit,
    val onStillDuration: (clipId: String, durationUs: Long) -> Unit,
    val onMoveLayer: (id: String, startUs: Long) -> Unit,
    val onTrimLayer: (id: String, startUs: Long?, endUs: Long?) -> Unit,
    val onMoveAudio: (id: String, startUs: Long) -> Unit,
    val onTrimAudio: (id: String, startUs: Long?, endUs: Long?) -> Unit,
    val onMoveFx: (id: String, startUs: Long) -> Unit,
    val onTrimFx: (id: String, startUs: Long?, endUs: Long?) -> Unit,
    val onMoveCue: (id: String, startUs: Long) -> Unit,
    val onTrimCue: (id: String, startUs: Long?, endUs: Long?) -> Unit,
    val onGestureEnd: () -> Unit,
    val onTransitionTap: (clipId: String) -> Unit,
    val onAddMedia: () -> Unit,
)

private class LaneItem(val id: String, val startUs: Long, val endUs: Long, val lane: Int)

/** Greedy lane assignment so overlapping layers never draw on top of each other. */
private fun assignLanes(items: List<Triple<String, Long, Long>>): List<LaneItem> {
    val laneEnds = ArrayList<Long>()
    return items.sortedBy { it.second }.map { (id, s, e) ->
        var lane = laneEnds.indexOfFirst { it <= s }
        if (lane < 0) {
            laneEnds += e
            lane = laneEnds.lastIndex
        } else {
            laneEnds[lane] = e
        }
        LaneItem(id, s, e, lane)
    }
}

@Composable
fun TimelineView(
    project: Project,
    selection: Selection,
    pxPerSec: Float,
    positionUs: Long,
    missing: Set<String>,
    snapEnabled: Boolean,
    hitThreshold: Float,
    thumbs: Thumbnails,
    waveforms: Waveforms,
    callbacks: TimelineCallbacks,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val pps = pxPerSec
    val fps = project.canvas.fps

    BoxWithConstraints(modifier.background(Vanta.Bg)) {
        val viewportPx = with(density) { maxWidth.toPx() }
        val halfPx = viewportPx / 2f
        val vw = maxWidth
        val halfDp = maxWidth / 2

        fun usToPx(us: Long) = us / 1_000_000f * pps
        fun pxToUs(px: Float) = (px / pps * 1_000_000f).toLong()

        val totalUs = Timeline.totalDurationUs(project)
        val contentPx = usToPx(totalUs)
        val contentDp = with(density) { contentPx.toDp() }

        // ---- playhead <-> scroll position synchronisation
        var lastProgrammatic by remember { mutableIntStateOf(-1) }
        var programmatic by remember { mutableStateOf(false) }
        val cb by rememberUpdatedState(callbacks)

        LaunchedEffect(positionUs, pps, totalUs) {
            if (scroll.isScrollInProgress && !programmatic) return@LaunchedEffect
            val target = usToPx(positionUs).roundToInt()
            if (scroll.value != target) {
                lastProgrammatic = target.coerceIn(0, max(scroll.maxValue, target))
                programmatic = true
                try {
                    scroll.scrollTo(target)
                } finally {
                    programmatic = false
                }
                lastProgrammatic = scroll.value
            }
        }
        LaunchedEffect(Unit) {
            androidx.compose.runtime.snapshotFlow { scroll.value }.collect { v ->
                if (v == lastProgrammatic || programmatic) return@collect
                cb.onUserScroll(pxToUsLatest(v.toFloat(), pps))
            }
        }

        // ---- edge auto-scroll while dragging items
        var edgeVelocity by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(edgeVelocity != 0f) {
            while (edgeVelocity != 0f) {
                withFrameNanos { }
                scroll.scrollBy(edgeVelocity)
            }
        }

        val laneItems = remember(project.texts, project.overlays) {
            assignLanes(
                project.texts.map { Triple(it.id, it.startUs, it.endUs) } +
                    project.overlays.map { Triple(it.id, it.startUs, it.endUs) },
            )
        }
        val fxLanes = remember(project.fx) { assignLanes(project.fx.map { Triple(it.id, it.startUs, it.endUs) }) }
        val layerLaneCount = (laneItems.maxOfOrNull { it.lane } ?: -1) + 1
        val audioLanes = project.audio.map { it.lane }.distinct().sorted()

        // Pinch zoom: intercepts two-finger gestures before children / scroll see them.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.changes.count { it.pressed } >= 2) {
                                val zoom = event.calculateZoom()
                                if (zoom != 1f && zoom.isFinite()) {
                                    cb.onZoom(zoom)
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(scroll),
            ) {
                Column(
                    Modifier
                        .width(contentDp + vw)
                        .fillMaxHeight()
                        .verticalScroll(vScroll),
                ) {
                    Ruler(pps, scroll, halfPx, viewportPx, totalUs, Modifier.width(contentDp + vw).height(RULER_H))

                    // ---------------- main video track
                    Box(Modifier.width(contentDp + vw).height(VIDEO_H + 6.dp).padding(top = 3.dp)) {
                        val starts = Timeline.clipStarts(project)
                        project.clips.forEachIndexed { index, clip ->
                            ClipBlock(
                                clip = clip,
                                index = index,
                                project = project,
                                startPx = usToPx(starts[index]),
                                halfPx = halfPx,
                                pps = pps,
                                selected = selection == Selection.OfClip(clip.id),
                                missing = clip.id in missing,
                                scroll = scroll,
                                viewportPx = viewportPx,
                                thumbs = thumbs,
                                starts = starts,
                                positionUs = positionUs,
                                snapEnabled = snapEnabled,
                                setEdgeVelocity = { edgeVelocity = it },
                                callbacks = cb,
                                pxToUs = ::pxToUs,
                                usToPx = ::usToPx,
                            )
                        }
                        // transition markers on the joins
                        project.clips.forEachIndexed { index, clip ->
                            if (index > 0) {
                                val x = halfPx + usToPx(starts[index])
                                JoinMarker(
                                    active = clip.transitionIn != null,
                                    xDp = with(density) { x.toDp() },
                                    onClick = { cb.onTransitionTap(clip.id) },
                                )
                            }
                        }
                        if (project.clips.isNotEmpty()) {
                            val endX = with(density) { (halfPx + contentPx).toDp() }
                            Box(
                                Modifier
                                    .offset(x = endX + 8.dp)
                                    .size(VIDEO_H)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Vanta.Surface3)
                                    .clickable(onClick = { cb.onAddMedia() }),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Rounded.Add, null, tint = Vanta.AccentBright)
                            }
                        }
                    }

                    // ---------------- text / overlay lanes
                    if (layerLaneCount > 0) {
                        Box(
                            Modifier
                                .width(contentDp + vw)
                                .height((LANE_H + LANE_GAP) * layerLaneCount + 2.dp),
                        ) {
                            laneItems.forEach { li ->
                                val text = project.texts.firstOrNull { it.id == li.id }
                                val overlay = project.overlays.firstOrNull { it.id == li.id }
                                LayerBlock(
                                    id = li.id,
                                    label = text?.text ?: overlay?.let { overlayLabel(it) } ?: "",
                                    isText = text != null,
                                    startUs = li.startUs,
                                    endUs = li.endUs,
                                    lane = li.lane,
                                    halfPx = halfPx,
                                    pps = pps,
                                    fps = fps,
                                    selected = selection.itemId == li.id,
                                    color = if (text != null) Vanta.TrackText else Vanta.TrackOverlay,
                                    selectionOf = { if (text != null) Selection.OfText(li.id) else Selection.OfOverlay(li.id) },
                                    scroll = scroll,
                                    viewportPx = viewportPx,
                                    positionUs = positionUs,
                                    snapEnabled = snapEnabled,
                                    candidates = { Snapping.candidates(project, positionUs, li.id) },
                                    setEdgeVelocity = { edgeVelocity = it },
                                    callbacks = cb,
                                    pxToUs = ::pxToUs,
                                    usToPx = ::usToPx,
                                )
                            }
                        }
                    }

                    // ---------------- rhythm strip (pulses, hits, manual markers)
                    if (project.beat != null || project.markers.isNotEmpty()) {
                        BeatStrip(
                            project = project,
                            hitThreshold = hitThreshold,
                            scroll = scroll,
                            halfPx = halfPx,
                            viewportPx = viewportPx,
                            usToPx = ::usToPx,
                            modifier = Modifier.width(contentDp + vw).height(BEAT_H),
                        )
                    }

                    // ---------------- light / effect events
                    if (fxLanes.isNotEmpty()) {
                        Box(
                            Modifier
                                .width(contentDp + vw)
                                .height((LANE_H + LANE_GAP) * (fxLanes.maxOf { it.lane } + 1) + 2.dp),
                        ) {
                            fxLanes.forEach { li ->
                                val e = project.fx.first { it.id == li.id }
                                val def = FxLibrary.def(e.type)
                                SpanBlock(
                                    startUs = e.startUs,
                                    endUs = e.endUs,
                                    row = li.lane,
                                    label = androidx.compose.ui.res.stringResource(fxNameRes(e.type)),
                                    icon = Icons.Rounded.FlashOn,
                                    color = fxColor(def.category, e.enabled),
                                    syncFraction = e.peak,
                                    selected = selection == Selection.OfFx(e.id),
                                    selectionOf = { Selection.OfFx(e.id) },
                                    onMove = { cb.onMoveFx(e.id, it) },
                                    onTrim = { s2, e2 -> cb.onTrimFx(e.id, s2, e2) },
                                    halfPx = halfPx, fps = fps, scroll = scroll, viewportPx = viewportPx,
                                    snapEnabled = snapEnabled,
                                    candidates = { Snapping.candidates(project, positionUs, e.id) },
                                    setEdgeVelocity = { edgeVelocity = it },
                                    callbacks = cb, pxToUs = ::pxToUs, usToPx = ::usToPx,
                                )
                            }
                        }
                    }

                    // ---------------- subtitles
                    val cues = project.subtitles?.cues.orEmpty()
                    if (cues.isNotEmpty()) {
                        Box(Modifier.width(contentDp + vw).height(LANE_H + LANE_GAP + 2.dp)) {
                            cues.forEach { c ->
                                SpanBlock(
                                    startUs = c.startUs,
                                    endUs = c.endUs,
                                    row = 0,
                                    label = c.text,
                                    icon = Icons.Rounded.Subtitles,
                                    color = Vanta.TrackText,
                                    syncFraction = null,
                                    selected = selection == Selection.OfCue(c.id),
                                    selectionOf = { Selection.OfCue(c.id) },
                                    onMove = { cb.onMoveCue(c.id, it) },
                                    onTrim = { s2, e2 -> cb.onTrimCue(c.id, s2, e2) },
                                    halfPx = halfPx, fps = fps, scroll = scroll, viewportPx = viewportPx,
                                    snapEnabled = snapEnabled,
                                    candidates = { Snapping.candidates(project, positionUs, c.id) },
                                    setEdgeVelocity = { edgeVelocity = it },
                                    callbacks = cb, pxToUs = ::pxToUs, usToPx = ::usToPx,
                                )
                            }
                        }
                    }

                    // ---------------- audio lanes
                    if (audioLanes.isNotEmpty()) {
                        Box(
                            Modifier
                                .width(contentDp + vw)
                                .height((LANE_H + LANE_GAP) * audioLanes.size + 2.dp),
                        ) {
                            project.audio.forEach { a ->
                                AudioBlock(
                                    clip = a,
                                    row = audioLanes.indexOf(a.lane),
                                    halfPx = halfPx,
                                    pps = pps,
                                    fps = fps,
                                    selected = selection == Selection.OfAudio(a.id),
                                    scroll = scroll,
                                    viewportPx = viewportPx,
                                    waveforms = waveforms,
                                    positionUs = positionUs,
                                    snapEnabled = snapEnabled,
                                    candidates = { Snapping.candidates(project, positionUs, a.id) },
                                    setEdgeVelocity = { edgeVelocity = it },
                                    callbacks = cb,
                                    pxToUs = ::pxToUs,
                                    usToPx = ::usToPx,
                                )
                            }
                        }
                    }
                    Box(Modifier.height(24.dp))
                }
            }

            // Fixed playhead at the centre of the viewport.
            PlayheadLine(positionUs, Modifier.align(Alignment.TopCenter))

            if (project.clips.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        androidx.compose.ui.res.stringResource(com.vanta.studio.R.string.timeline_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = Vanta.TextFaint,
                    )
                }
            }
        }
    }
}

private fun pxToUsLatest(px: Float, pps: Float) = (px / pps * 1_000_000f).toLong()

@Composable
private fun PlayheadLine(positionUs: Long, modifier: Modifier) {
    Box(modifier.fillMaxHeight().width(48.dp), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(Vanta.Playhead),
        )
        Text(
            formatTime(positionUs),
            style = TimecodeStyle.copy(fontSize = 10.sp, color = Color.Black),
            modifier = Modifier
                .padding(top = 1.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Vanta.Playhead)
                .padding(horizontal = 4.dp, vertical = 1.dp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

private fun overlayLabel(o: OverlayLayer) = when (o.kind) {
    com.vanta.studio.domain.model.OverlayKind.STICKER -> o.source.removePrefix("emoji:")
    com.vanta.studio.domain.model.OverlayKind.IMAGE -> "IMG"
    else -> o.source
}

@Composable
private fun Ruler(pps: Float, scroll: ScrollState, halfPx: Float, viewportPx: Float, totalUs: Long, modifier: Modifier) {
    val measurer = rememberTextMeasurer()
    Canvas(modifier) {
        val steps = doubleArrayOf(0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 1800.0, 3600.0)
        val stepSec = steps.firstOrNull { it * pps >= 70 } ?: steps.last()
        val minor = stepSec / 5.0
        val left = scroll.value - halfPx
        val right = left + viewportPx + 200
        val firstIdx = max(0, floor(left / pps / minor).toInt())
        val lastIdx = ceil(right / pps / minor).toInt()
        for (i in firstIdx..lastIdx) {
            val sec = i * minor
            val x = halfPx + (sec * pps).toFloat()
            val major = i % 5 == 0
            drawLine(
                if (major) Vanta.TextFaint else Vanta.Outline,
                Offset(x, if (major) size.height * 0.45f else size.height * 0.72f),
                Offset(x, size.height),
                strokeWidth = 1f,
            )
            if (major) {
                val label = formatTime((sec * 1_000_000).toLong(), fraction = stepSec < 1.0).let { if (stepSec < 1.0) it else it.substringBefore('.') }
                drawText(
                    measurer, label, Offset(x + 4f, 1f),
                    style = TimecodeStyle.copy(fontSize = 9.sp, color = Vanta.TextFaint),
                )
            }
        }
        val endX = halfPx + totalUs / 1_000_000f * pps
        drawLine(Vanta.AccentBright.copy(alpha = 0.6f), Offset(endX, 0f), Offset(endX, size.height), strokeWidth = 2f)
    }
}

@Composable
private fun JoinMarker(active: Boolean, xDp: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(
        Modifier
            .offset(x = xDp - 11.dp, y = (VIDEO_H - 22.dp) / 2 + 3.dp)
            .size(22.dp)
            .zIndex(5f)
            .shadow(3.dp, CircleShape)
            .clip(CircleShape)
            .background(if (active) Vanta.Accent else Vanta.Surface3)
            .border(1.dp, if (active) Vanta.AccentBright else Vanta.OutlineStrong, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = if (active) Color.White else Vanta.TextDim, modifier = Modifier.size(12.dp))
    }
}

// ============================================================================== video clips

@Composable
private fun ClipBlock(
    clip: Clip,
    index: Int,
    project: Project,
    startPx: Float,
    halfPx: Float,
    pps: Float,
    selected: Boolean,
    missing: Boolean,
    scroll: ScrollState,
    viewportPx: Float,
    thumbs: Thumbnails,
    starts: LongArray,
    positionUs: Long,
    snapEnabled: Boolean,
    setEdgeVelocity: (Float) -> Unit,
    callbacks: TimelineCallbacks,
    pxToUs: (Float) -> Long,
    usToPx: (Long) -> Float,
) {
    val density = LocalDensity.current
    val widthPx = max(usToPx(clip.durationUs), 6f)
    val widthDp = with(density) { widthPx.toDp() }
    val xDp = with(density) { (halfPx + startPx).toDp() }
    val heightPx = with(density) { VIDEO_H.toPx() }

    var dragging by remember { mutableStateOf(false) }
    var dragDx by remember { mutableFloatStateOf(0f) }
    val currentClip by rememberUpdatedState(clip)
    val currentIndex by rememberUpdatedState(index)
    val currentProject by rememberUpdatedState(project)
    val startPxState by rememberUpdatedState(startPx)
    val widthState by rememberUpdatedState(widthPx)
    val border by animateColorAsState(if (selected) Vanta.AccentBright else Color.Transparent, label = "clipBorder")

    Box(
        Modifier
            .offset(x = xDp)
            .zIndex(if (dragging) 10f else if (selected) 2f else 1f)
            .width(widthDp)
            .height(VIDEO_H)
            .graphicsLayer {
                translationX = if (dragging) dragDx else 0f
                scaleY = if (dragging) 1.06f else 1f
                alpha = if (dragging) 0.92f else 1f
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Vanta.TrackVideo)
            .border(2.dp, border, RoundedCornerShape(8.dp))
            .clickable { callbacks.onSelect(Selection.OfClip(clip.id)) }
            .pointerInput(clip.id) {
                var scrollAtStart = 0
                var downX = 0f
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        dragging = true
                        dragDx = 0f
                        scrollAtStart = scroll.value
                        downX = offset.x
                        callbacks.onSelect(Selection.OfClip(currentClip.id))
                    },
                    onDragEnd = {
                        setEdgeVelocity(0f)
                        val p = currentProject
                        val centre = startPxState + widthState / 2f + dragDx
                        var target = 0
                        var acc = 0f
                        for (j in p.clips.indices) {
                            val w = max(usToPx(p.clips[j].durationUs), 1f)
                            if (centre >= acc + w) target = j + 1
                            acc += w
                        }
                        target = target.coerceIn(0, p.clips.lastIndex)
                        dragging = false
                        dragDx = 0f
                        if (target != currentIndex) callbacks.onReorder(currentIndex, target)
                    },
                    onDragCancel = {
                        setEdgeVelocity(0f)
                        dragging = false
                        dragDx = 0f
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        dragDx += amount.x
                        val viewportX = halfPx + startPxState + downX + dragDx - scroll.value
                        val edge = 56f * density.density
                        setEdgeVelocity(
                            when {
                                viewportX < edge -> -14f
                                viewportX > viewportPx - edge -> 14f
                                else -> 0f
                            },
                        )
                    },
                )
            },
    ) {
        Filmstrip(
            clip = clip, widthPx = widthPx, heightPx = heightPx, pps = pps,
            contentLeftPx = halfPx + startPx, scroll = scroll, viewportPx = viewportPx, thumbs = thumbs,
        )
        Box(Modifier.fillMaxSize().background(scrimBrush))

        Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
            if (widthPx > 70f) {
                Text(
                    formatTime(clip.durationUs, fraction = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
        Row2(Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 3.dp)) {
            if (clip.speed != 1f && widthPx > 60f) {
                Icon(Icons.Rounded.Speed, null, tint = Color.White, modifier = Modifier.size(11.dp))
                Text(" ${trimFloat(clip.speed)}x", style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
            }
            if ((clip.muted || (!clip.hasAudio && clip.kind == MediaKind.VIDEO)) && widthPx > 90f) {
                Icon(Icons.Rounded.VolumeOff, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.padding(start = 6.dp).size(11.dp))
            }
        }
        if (missing) {
            Box(Modifier.fillMaxSize().background(Color(0xAA2A0A12)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.WarningAmber, null, tint = Vanta.Warning)
            }
        }
        if (selected) {
            TrimHandle(
                left = true,
                modifier = Modifier.align(Alignment.CenterStart),
                clip = clip, project = project, index = index, starts = starts, positionUs = positionUs,
                snapEnabled = snapEnabled, callbacks = callbacks, pxToUs = pxToUs, usToPx = usToPx,
            )
            TrimHandle(
                left = false,
                modifier = Modifier.align(Alignment.CenterEnd),
                clip = clip, project = project, index = index, starts = starts, positionUs = positionUs,
                snapEnabled = snapEnabled, callbacks = callbacks, pxToUs = pxToUs, usToPx = usToPx,
            )
        }
    }
}

private val scrimBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
    listOf(Color(0x66000000), Color.Transparent, Color(0x55000000)),
)

@Composable
private fun Row2(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically, content = content)
}

private fun trimFloat(v: Float): String = if (v == v.toInt().toFloat()) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v)

/** Candidate cut positions that do not move while the clip is being trimmed. */
private fun fixedCandidates(project: Project, index: Int, starts: LongArray, positionUs: Long): List<Long> {
    val out = ArrayList<Long>()
    out += 0L
    out += positionUs
    for (j in 0 until index) out += starts[j] + project.clips[j].durationUs
    project.texts.forEach { out += it.startUs; out += it.endUs }
    project.overlays.forEach { out += it.startUs; out += it.endUs }
    project.audio.forEach { out += it.startUs; out += it.endUs }
    return out
}

@Composable
private fun TrimHandle(
    left: Boolean,
    modifier: Modifier,
    clip: Clip,
    project: Project,
    index: Int,
    starts: LongArray,
    positionUs: Long,
    snapEnabled: Boolean,
    callbacks: TimelineCallbacks,
    pxToUs: (Float) -> Long,
    usToPx: (Long) -> Float,
) {
    val clipState by rememberUpdatedState(clip)
    val projectState by rememberUpdatedState(project)
    val startsState by rememberUpdatedState(starts)
    val posState by rememberUpdatedState(positionUs)
    val fps = project.canvas.fps
    Box(
        modifier
            .width(HANDLE_W)
            .fillMaxHeight()
            .clip(if (left) RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp) else RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp))
            .background(Vanta.AccentBright)
            .pointerInput(clip.id, left) {
                var base: Clip? = null
                var totalDx = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        base = clipState
                        totalDx = 0f
                    },
                    onDragEnd = { callbacks.onGestureEnd() },
                    onDragCancel = { callbacks.onGestureEnd() },
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        val b = base ?: return@detectHorizontalDragGestures
                        totalDx += dx
                        var deltaLocal = pxToUs(totalDx)
                        val clipStart = startsState[index.coerceAtMost(startsState.lastIndex)]
                        if (snapEnabled) {
                            val cands = fixedCandidates(projectState, index, startsState, posState)
                            val edge = if (left) clipStart + deltaLocal else clipStart + b.durationUs + deltaLocal
                            val thresholdUs = pxToUs(SNAP_PX)
                            Snapping.snap(edge, cands, thresholdUs)?.let { snapped ->
                                deltaLocal += snapped - edge
                            }
                        }
                        val frameUs = 1_000_000L / fps
                        deltaLocal = Math.round(deltaLocal.toDouble() / frameUs) * frameUs
                        if (b.isStill) {
                            val newDur = if (left) b.trimEndUs - deltaLocal else b.trimEndUs + deltaLocal
                            callbacks.onStillDuration(b.id, newDur)
                        } else if (left) {
                            callbacks.onTrimClip(b.id, true, b.trimStartUs + (deltaLocal * b.speed).toLong())
                        } else {
                            callbacks.onTrimClip(b.id, false, b.trimEndUs + (deltaLocal * b.speed).toLong())
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(Vanta.AccentDim))
    }
}

@Composable
private fun Filmstrip(
    clip: Clip,
    widthPx: Float,
    heightPx: Float,
    pps: Float,
    contentLeftPx: Float,
    scroll: ScrollState,
    viewportPx: Float,
    thumbs: Thumbnails,
) {
    val thumbH = 112
    val tileW = heightPx * 1.5f
    val tileCount = max(1, ceil(widthPx / tileW).toInt())
    val visible by remember(contentLeftPx, tileW, tileCount, viewportPx) {
        derivedStateOf {
            val localLeft = scroll.value - contentLeftPx
            val first = floor(localLeft / tileW).toInt().coerceIn(0, tileCount - 1)
            val last = floor((localLeft + viewportPx) / tileW).toInt().coerceIn(0, tileCount - 1)
            first to max(first, last)
        }
    }
    var tick by remember { mutableIntStateOf(0) }
    val images = remember { HashMap<String, ImageBitmap>() }

    fun keyFor(i: Int): String {
        return if (clip.isStill) thumbs.stillKey(clip.uri, thumbH)
        else thumbs.videoKey(clip.uri, sourceTime(clip, i, tileW, pps), thumbH)
    }

    LaunchedEffect(visible, clip.uri, clip.trimStartUs, clip.trimEndUs, clip.speed, tileW) {
        for (i in visible.first..visible.second) {
            val key = keyFor(i)
            if (images.containsKey(key)) continue
            val bmp = if (clip.isStill) thumbs.still(clip.uri, thumbH)
            else thumbs.videoFrame(clip.uri, sourceTime(clip, i, tileW, pps), thumbH)
            if (bmp != null) {
                if (images.size > 120) images.clear()
                images[key] = bmp.asImageBitmap()
                tick++
            }
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        tick // observe
        for (i in visible.first..visible.second) {
            val img = images[keyFor(i)] ?: continue
            val x = i * tileW
            val dstW = min(tileW, widthPx - x)
            if (dstW <= 0f) continue
            // centre-crop the thumbnail into the tile
            val tileAspect = tileW / heightPx
            val imgAspect = img.width.toFloat() / img.height
            val srcW: Int
            val srcH: Int
            if (imgAspect > tileAspect) {
                srcH = img.height
                srcW = (img.height * tileAspect).toInt()
            } else {
                srcW = img.width
                srcH = (img.width / tileAspect).toInt()
            }
            val srcX = (img.width - srcW) / 2
            val srcY = (img.height - srcH) / 2
            val visibleSrcW = (srcW * (dstW / tileW)).toInt().coerceAtLeast(1)
            drawImage(
                img,
                srcOffset = androidx.compose.ui.unit.IntOffset(srcX, srcY),
                srcSize = IntSize(visibleSrcW, srcH),
                dstOffset = androidx.compose.ui.unit.IntOffset(x.roundToInt(), 0),
                dstSize = IntSize(dstW.roundToInt().coerceAtLeast(1), size.height.roundToInt()),
            )
        }
    }
}

private fun sourceTime(clip: Clip, tile: Int, tileW: Float, pps: Float): Long {
    val localUs = ((tile + 0.5f) * tileW / pps * 1_000_000f).toLong()
    val src = clip.localToSourceUs(localUs)
    return (src / 100_000) * 100_000
}

// ============================================================================== rhythm & effects

private val BEAT_H = 22.dp

private fun fxColor(c: FxCategory, enabled: Boolean): Color {
    val base = when (c) {
        FxCategory.FLASH -> Color(0xFFFFE27A)
        FxCategory.LIGHT -> Color(0xFFFFB86B)
        FxCategory.LENS -> Color(0xFF9AD7FF)
        FxCategory.MOTION -> Color(0xFFB9A2FF)
        FxCategory.COLOR -> Color(0xFFFF8FC2)
        FxCategory.GLITCH -> Color(0xFF7DF0C1)
    }
    return if (enabled) base else base.copy(alpha = 0.35f)
}

/**
 * Ticks for the analysed rhythm: tall = strong hit, small = weaker transient, accent dots = the
 * pulse grid, diamonds = markers placed by hand. Only the visible window is drawn.
 */
@Composable
private fun BeatStrip(
    project: Project,
    hitThreshold: Float,
    scroll: ScrollState,
    halfPx: Float,
    viewportPx: Float,
    usToPx: (Long) -> Float,
    modifier: Modifier,
) {
    val onsets = remember(project.beat, project.clips, project.audio) { BeatMapping.onsets(project, 0f) }
    val pulses = remember(project.beat, project.clips, project.audio) { BeatMapping.pulses(project) }
    val markers = project.markers
    Canvas(modifier) {
        val x0 = scroll.value - halfPx - 8f
        val x1 = scroll.value + viewportPx
        val h = size.height
        fun px(us: Long) = halfPx + usToPx(us)
        // transients: height follows strength; below the threshold they are drawn faint
        for (b in onsets) {
            val x = px(b.timeUs)
            if (x < x0 || x > x1) continue
            val strong = b.strength >= hitThreshold
            val len = h * (0.25f + 0.6f * b.strength)
            drawLine(
                if (strong) Vanta.AccentBright else Vanta.TextFaint.copy(alpha = 0.55f),
                Offset(x, h - len), Offset(x, h), strokeWidth = if (strong) 3f else 1.5f,
            )
        }
        for (b in pulses) {
            val x = px(b.timeUs)
            if (x < x0 || x > x1) continue
            drawCircle(Color(0xFFFFE27A), radius = 3f, center = Offset(x, 4f))
        }
        for (m in markers) {
            val x = px(m)
            if (x < x0 || x > x1) continue
            val d = 6f
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(x, h / 2 - d); lineTo(x + d, h / 2); lineTo(x, h / 2 + d); lineTo(x - d, h / 2); close()
            }
            drawPath(path, Vanta.Playhead)
        }
    }
}

/** A draggable, trimmable block on a lane: effects and subtitle cues. */
@Composable
private fun SpanBlock(
    startUs: Long,
    endUs: Long,
    row: Int,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    syncFraction: Float?,
    selected: Boolean,
    selectionOf: () -> Selection,
    onMove: (Long) -> Unit,
    onTrim: (Long?, Long?) -> Unit,
    halfPx: Float,
    fps: Int,
    scroll: ScrollState,
    viewportPx: Float,
    snapEnabled: Boolean,
    candidates: () -> List<Long>,
    setEdgeVelocity: (Float) -> Unit,
    callbacks: TimelineCallbacks,
    pxToUs: (Float) -> Long,
    usToPx: (Long) -> Float,
) {
    val density = LocalDensity.current
    val widthPx = max(usToPx(endUs - startUs), 10f)
    val xDp = with(density) { (halfPx + usToPx(startUs)).toDp() }
    val widthDp = with(density) { widthPx.toDp() }
    val startState by rememberUpdatedState(startUs)
    val endState by rememberUpdatedState(endUs)
    val candidatesState by rememberUpdatedState(candidates)
    val base = remember { LongArray(2) }
    TimedBlock(
        modifier = Modifier.offset(x = xDp, y = (LANE_H + LANE_GAP) * row + 2.dp).width(widthDp).height(LANE_H),
        selected = selected,
        color = color,
        onTap = { callbacks.onSelect(selectionOf()) },
        label = {
            Icon(icon, null, tint = Color.Black.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Black.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(start = 3.dp))
        },
        onMove = { dxPx -> moveSnapped(base[0], base[1] - base[0], dxPx, pxToUs, snapEnabled, candidatesState(), fps) { onMove(it) } },
        onTrimStart = { dxPx -> onTrim(snapEdge(base[0] + pxToUs(dxPx), pxToUs, snapEnabled, candidatesState(), fps), null) },
        onTrimEnd = { dxPx -> onTrim(null, snapEdge(base[1] + pxToUs(dxPx), pxToUs, snapEnabled, candidatesState(), fps)) },
        onStartDrag = {
            base[0] = startState
            base[1] = endState
            callbacks.onSelect(selectionOf())
        },
        onEnd = { setEdgeVelocity(0f); callbacks.onGestureEnd() },
        scroll = scroll,
        viewportPx = viewportPx,
        setEdgeVelocity = setEdgeVelocity,
        blockLeftContentPx = { halfPx + usToPx(startState) },
        background = {
            // The beat-aligned moment of an effect, drawn as a thin white line.
            if (syncFraction != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val x = size.width * syncFraction
                    drawLine(Color.White.copy(alpha = 0.9f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                }
            }
        },
    )
}

// ============================================================================== layers

@Composable
private fun LayerBlock(
    id: String,
    label: String,
    isText: Boolean,
    startUs: Long,
    endUs: Long,
    lane: Int,
    halfPx: Float,
    pps: Float,
    fps: Int,
    selected: Boolean,
    color: Color,
    selectionOf: () -> Selection,
    scroll: ScrollState,
    viewportPx: Float,
    positionUs: Long,
    snapEnabled: Boolean,
    candidates: () -> List<Long>,
    setEdgeVelocity: (Float) -> Unit,
    callbacks: TimelineCallbacks,
    pxToUs: (Float) -> Long,
    usToPx: (Long) -> Float,
) {
    val density = LocalDensity.current
    val widthPx = max(usToPx(endUs - startUs), 10f)
    val xDp = with(density) { (halfPx + usToPx(startUs)).toDp() }
    val widthDp = with(density) { widthPx.toDp() }
    val startState by rememberUpdatedState(startUs)
    val endState by rememberUpdatedState(endUs)
    val candidatesState by rememberUpdatedState(candidates)
    // Values at the moment a drag began: live edits change startUs/endUs while dragging.
    val base = remember { LongArray(2) }

    TimedBlock(
        modifier = Modifier.offset(x = xDp, y = (LANE_H + LANE_GAP) * lane + 2.dp).width(widthDp).height(LANE_H),
        selected = selected,
        color = color,
        onTap = { callbacks.onSelect(selectionOf()) },
        label = {
            Icon(if (isText) Icons.Rounded.TextFields else Icons.Rounded.Image, null, tint = Color.Black.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = Color.Black.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(start = 3.dp))
        },
        onMove = { dxPx -> moveSnapped(base[0], base[1] - base[0], dxPx, pxToUs, snapEnabled, candidatesState(), fps) { callbacks.onMoveLayer(id, it) } },
        onTrimStart = { dxPx -> callbacks.onTrimLayer(id, snapEdge(base[0] + pxToUs(dxPx), pxToUs, snapEnabled, candidatesState(), fps), null) },
        onTrimEnd = { dxPx -> callbacks.onTrimLayer(id, null, snapEdge(base[1] + pxToUs(dxPx), pxToUs, snapEnabled, candidatesState(), fps)) },
        onStartDrag = {
            base[0] = startState
            base[1] = endState
            callbacks.onSelect(selectionOf())
        },
        onEnd = { setEdgeVelocity(0f); callbacks.onGestureEnd() },
        scroll = scroll,
        viewportPx = viewportPx,
        setEdgeVelocity = setEdgeVelocity,
        blockLeftContentPx = { halfPx + usToPx(startState) },
    )
}

@Composable
private fun AudioBlock(
    clip: AudioClip,
    row: Int,
    halfPx: Float,
    pps: Float,
    fps: Int,
    selected: Boolean,
    scroll: ScrollState,
    viewportPx: Float,
    waveforms: Waveforms,
    positionUs: Long,
    snapEnabled: Boolean,
    candidates: () -> List<Long>,
    setEdgeVelocity: (Float) -> Unit,
    callbacks: TimelineCallbacks,
    pxToUs: (Float) -> Long,
    usToPx: (Long) -> Float,
) {
    val density = LocalDensity.current
    val widthPx = max(usToPx(clip.durationUs), 10f)
    val xDp = with(density) { (halfPx + usToPx(clip.startUs)).toDp() }
    val widthDp = with(density) { widthPx.toDp() }
    val clipState by rememberUpdatedState(clip)
    val candidatesState by rememberUpdatedState(candidates)
    val base = remember { LongArray(2) }
    var peaks by remember(clip.uri) { mutableStateOf(waveforms.cached(clip.uri)) }
    LaunchedEffect(clip.uri) {
        if (peaks == null) peaks = waveforms.load(clip.uri)
    }

    TimedBlock(
        modifier = Modifier.offset(x = xDp, y = (LANE_H + LANE_GAP) * row + 2.dp).width(widthDp).height(LANE_H),
        selected = selected,
        color = Vanta.TrackAudio,
        onTap = { callbacks.onSelect(Selection.OfAudio(clip.id)) },
        label = {
            Icon(Icons.Rounded.MusicNote, null, tint = Color.Black.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
            if (clip.muted) Icon(Icons.Rounded.SpeakerNotesOff, null, tint = Color.Black.copy(alpha = 0.6f), modifier = Modifier.size(11.dp))
            Text(clip.name, style = MaterialTheme.typography.labelSmall, color = Color.Black.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Clip, modifier = Modifier.padding(start = 3.dp))
        },
        background = {
            val p = peaks
            if (p != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = clipState
                    val srcDur = max(c.sourceDurationUs, 1L).toFloat()
                    val step = 3f
                    var x = 0f
                    while (x < size.width) {
                        val frac = (c.trimStartUs + (x / size.width) * c.durationUs) / srcDur
                        val idx = (frac * (p.size - 1)).toInt().coerceIn(0, p.size - 1)
                        val h = max(2f, p[idx] * size.height * 0.9f)
                        drawLine(
                            Color.Black.copy(alpha = 0.35f),
                            Offset(x, (size.height - h) / 2), Offset(x, (size.height + h) / 2),
                            strokeWidth = 2f,
                        )
                        x += step
                    }
                }
            }
        },
        onMove = { dxPx -> moveSnapped(base[0], base[1] - base[0], dxPx, pxToUs, snapEnabled, candidatesState(), fps) { callbacks.onMoveAudio(clip.id, it) } },
        onTrimStart = { dxPx -> callbacks.onTrimAudio(clip.id, snapEdge(base[0] + pxToUs(dxPx), pxToUs, snapEnabled, candidatesState(), fps), null) },
        onTrimEnd = { dxPx -> callbacks.onTrimAudio(clip.id, null, snapEdge(base[1] + pxToUs(dxPx), pxToUs, snapEnabled, candidatesState(), fps)) },
        onStartDrag = {
            base[0] = clipState.startUs
            base[1] = clipState.endUs
            callbacks.onSelect(Selection.OfAudio(clip.id))
        },
        onEnd = { setEdgeVelocity(0f); callbacks.onGestureEnd() },
        scroll = scroll,
        viewportPx = viewportPx,
        setEdgeVelocity = setEdgeVelocity,
        blockLeftContentPx = { halfPx + usToPx(clipState.startUs) },
    )
}

private fun snapEdge(edge: Long, pxToUs: (Float) -> Long, enabled: Boolean, cands: List<Long>, fps: Int): Long {
    var e = edge
    if (enabled) Snapping.snap(e, cands, pxToUs(SNAP_PX))?.let { return it }
    e = Snapping.toFrame(e, fps)
    return e.coerceAtLeast(0)
}

/** Moves a block by [dxPx]; both its start and end edges snap to [cands]. */
private fun moveSnapped(
    startUs: Long, durationUs: Long, dxPx: Float, pxToUs: (Float) -> Long,
    enabled: Boolean, cands: List<Long>, fps: Int, apply: (Long) -> Unit,
) {
    var s = startUs + pxToUs(dxPx)
    if (enabled) {
        val th = pxToUs(SNAP_PX)
        val a = Snapping.snap(s, cands, th)
        val b = Snapping.snap(s + durationUs, cands, th)
        val da = a?.let { abs(it - s) } ?: Long.MAX_VALUE
        val db = b?.let { abs(it - (s + durationUs)) } ?: Long.MAX_VALUE
        when {
            a != null && da <= db -> s = a
            b != null -> s = b - durationUs
            else -> s = Snapping.toFrame(s, fps)
        }
    } else {
        s = Snapping.toFrame(s, fps)
    }
    apply(s.coerceAtLeast(0))
}

/** Shared chrome of layer and audio blocks: tap to select, drag body to move, drag edges to trim. */
@Composable
private fun TimedBlock(
    modifier: Modifier,
    selected: Boolean,
    color: Color,
    onTap: () -> Unit,
    label: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
    onMove: (Float) -> Unit,
    onTrimStart: (Float) -> Unit,
    onTrimEnd: (Float) -> Unit,
    onStartDrag: () -> Unit,
    onEnd: () -> Unit,
    scroll: ScrollState,
    viewportPx: Float,
    setEdgeVelocity: (Float) -> Unit,
    blockLeftContentPx: () -> Float,
    background: @Composable () -> Unit = {},
) {
    val density = LocalDensity.current
    val border by animateColorAsState(if (selected) Vanta.Playhead else Color.Transparent, label = "blockBorder")
    val moveCb by rememberUpdatedState(onMove)
    val trimStartCb by rememberUpdatedState(onTrimStart)
    val trimEndCb by rememberUpdatedState(onTrimEnd)
    val leftCb by rememberUpdatedState(blockLeftContentPx)
    Box(
        modifier
            .clip(RoundedCornerShape(7.dp))
            .background(color.copy(alpha = if (selected) 1f else 0.85f))
            .border(2.dp, border, RoundedCornerShape(7.dp))
            .clickable(onClick = onTap)
            .then(
                if (selected) {
                    Modifier.pointerInput(Unit) {
                        var total = 0f
                        var scrollAtStart = 0
                        var downX = 0f
                        detectDragGestures(
                            onDragStart = { offset ->
                                total = 0f
                                scrollAtStart = scroll.value
                                downX = offset.x
                                onStartDrag()
                            },
                            onDragEnd = onEnd,
                            onDragCancel = onEnd,
                            onDrag = { change, amount ->
                                change.consume()
                                total += amount.x
                                val comp = (scroll.value - scrollAtStart).toFloat()
                                moveCb(total + comp)
                                val viewportX = leftCb() + downX + total + comp - scroll.value
                                val edge = 56f * density.density
                                setEdgeVelocity(
                                    when {
                                        viewportX < edge -> -14f
                                        viewportX > viewportPx - edge -> 14f
                                        else -> 0f
                                    },
                                )
                            },
                        )
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        background()
        androidx.compose.foundation.layout.Row(
            Modifier.padding(start = if (selected) 14.dp else 5.dp).fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            content = label,
        )
        if (selected) {
            EdgeGrip(Modifier.align(Alignment.CenterStart), onStart = onStartDrag, onDrag = { trimStartCb(it) }, onEnd = onEnd)
            EdgeGrip(Modifier.align(Alignment.CenterEnd), onStart = onStartDrag, onDrag = { trimEndCb(it) }, onEnd = onEnd)
        }
    }
}

@Composable
private fun EdgeGrip(modifier: Modifier, onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit) {
    val dragCb by rememberUpdatedState(onDrag)
    val startCb by rememberUpdatedState(onStart)
    Box(
        modifier
            .width(16.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.9f))
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        total = 0f
                        startCb()
                    },
                    onDragEnd = onEnd,
                    onDragCancel = onEnd,
                    onHorizontalDrag = { change, dx ->
                        change.consume()
                        total += dx
                        dragCb(total)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(12.dp).background(Color.Black.copy(alpha = 0.5f)))
    }
}
