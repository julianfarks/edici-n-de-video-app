package com.vanta.studio.ui.editor

import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vanta.studio.domain.anim.LayerAnimator
import com.vanta.studio.domain.anim.Pose
import com.vanta.studio.domain.model.OverlayLayer
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.TextLayer
import com.vanta.studio.engine.PreviewEngine
import com.vanta.studio.engine.layers.LayerRenderer
import com.vanta.studio.ui.theme.Vanta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Monitor: the video surface plus a Compose layer that draws text/stickers on top of it
 * (exactly the bitmaps and animation maths used by the export) and handles touch editing.
 */
@Composable
fun PreviewMonitor(
    engine: PreviewEngine,
    project: Project,
    selection: Selection,
    positionUs: Long,
    building: Boolean,
    pose: Pose?,
    onSelect: (Selection) -> Unit,
    onPoseChange: ((Pose) -> Pose) -> Unit,
    onGestureEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ratio = project.canvas.ratio
    BoxWithConstraints(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val fitsByHeight = maxWidth / maxHeight > ratio
        val boxW = if (fitsByHeight) maxHeight * ratio else maxWidth
        val boxH = if (fitsByHeight) maxHeight else maxWidth / ratio
        Box(
            Modifier
                .size(boxW, boxH)
                .background(Color.Black)
                .clipToBounds(),
        ) {
            VideoSurface(engine)
            LayersOverlay(project, selection, positionUs, pose, onSelect, onPoseChange, onGestureEnd)
            if (building) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp),
                    strokeWidth = 2.dp,
                    color = Vanta.AccentBright,
                )
            }
        }
    }
}

@Composable
private fun VideoSurface(engine: PreviewEngine) {
    var view: SurfaceView? = null
    AndroidView(
        factory = { ctx ->
            SurfaceView(ctx).also {
                view = it
                engine.attachSurface(it)
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
    DisposableEffect(engine) {
        onDispose { view?.let { engine.detachSurface(it) } }
    }
}

private class DrawnLayer(
    val id: String,
    val z: Int,
    val bitmap: ImageBitmap,
    val frameAt: (Long) -> com.vanta.studio.domain.anim.LayerFrame?,
    val selection: Selection,
)

@Composable
private fun rememberTextBitmap(layer: TextLayer, w: Int, h: Int): ImageBitmap? {
    if (w <= 0 || h <= 0) return null
    return key(layer.id) {
        remember(LayerRenderer.textKey(layer, w, h)) { LayerRenderer.text(layer, w, h).asImageBitmap() }
    }
}

@Composable
private fun rememberOverlayBitmap(layer: OverlayLayer, w: Int, h: Int): ImageBitmap? {
    val context = LocalContext.current
    if (w <= 0 || h <= 0) return null
    return key(layer.id) {
        val state = produceState<ImageBitmap?>(null, LayerRenderer.overlayKey(layer, w, h)) {
            value = withContext(Dispatchers.Default) {
                LayerRenderer.overlay(context, layer, w, h)?.asImageBitmap()
            }
        }
        state.value
    }
}

@Composable
private fun LayersOverlay(
    project: Project,
    selection: Selection,
    positionUs: Long,
    pose: Pose?,
    onSelect: (Selection) -> Unit,
    onPoseChange: ((Pose) -> Pose) -> Unit,
    onGestureEnd: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }.toInt()
        val hPx = with(density) { maxHeight.toPx() }.toInt()

        val drawn = ArrayList<DrawnLayer>()
        project.texts.forEach { t ->
            rememberTextBitmap(t, wPx, hPx)?.let { bmp ->
                drawn += DrawnLayer(t.id, t.z, bmp, { time ->
                    LayerAnimator.evaluate(t.startUs, t.durationUs, t.transform, t.keyframes, t.animIn, t.animOut, t.animDurationUs, time)
                }, Selection.OfText(t.id))
            }
        }
        project.overlays.forEach { o ->
            rememberOverlayBitmap(o, wPx, hPx)?.let { bmp ->
                drawn += DrawnLayer(o.id, o.z, bmp, { time ->
                    LayerAnimator.evaluate(o.startUs, o.durationUs, o.transform, o.keyframes, o.animIn, o.animOut, o.animDurationUs, time)
                }, Selection.OfOverlay(o.id))
            }
        }
        val sorted = drawn.sortedBy { it.z }
        // Subtitles use the same renderer and state maths as the exporter.
        val subState = com.vanta.studio.domain.subtitles.SubtitleRender.stateAt(project.subtitles, positionUs)
        val subStyle = project.subtitles?.style
        val subBitmap: ImageBitmap? = if (subState != null && subStyle != null && wPx > 0 && hPx > 0) {
            val key = com.vanta.studio.engine.layers.SubtitleRenderer.key(subState, subStyle, wPx, hPx)
            remember(key) { com.vanta.studio.engine.layers.SubtitleRenderer.bitmap(subState, subStyle, wPx, hPx).asImageBitmap() }
        } else {
            null
        }
        val currentSorted by rememberUpdatedState(sorted)
        val currentPos by rememberUpdatedState(positionUs)
        val currentSelection by rememberUpdatedState(selection)
        val poseState by rememberUpdatedState(pose)

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        val hit = currentSorted.asReversed().firstOrNull { l ->
                            val f = l.frameAt(currentPos) ?: return@firstOrNull false
                            hitTest(tap, f, l.bitmap, size.width.toFloat(), size.height.toFloat())
                        }
                        if (hit != null) {
                            onSelect(hit.selection)
                        } else {
                            val cur = currentSelection
                            if (cur is Selection.OfClip) onSelect(Selection.None) else onSelect(clipSelectionAt(project, currentPos))
                        }
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var started = false
                        var accX = 0f
                        var accY = 0f
                        val slop = viewConfiguration.touchSlop
                        do {
                            val event = awaitPointerEvent()
                            val zoom = event.calculateZoom()
                            val rot = event.calculateRotation()
                            val pan = event.calculatePan()
                            if (!started) {
                                accX += pan.x
                                accY += pan.y
                                if (abs(accX) + abs(accY) > slop || abs(zoom - 1f) > 0.02f || abs(rot) > 2f) started = true
                            }
                            if (started && currentSelection != Selection.None && poseState != null) {
                                val w = size.width.toFloat()
                                val h = size.height.toFloat()
                                onPoseChange { p ->
                                    p.copy(
                                        x = p.x + pan.x / w,
                                        y = p.y + pan.y / h,
                                        scale = (p.scale * zoom).coerceIn(0.05f, 8f),
                                        rotation = p.rotation + rot,
                                    )
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (started) onGestureEnd()
                    }
                },
        ) {
            val w = size.width
            val h = size.height
            for (l in sorted) {
                val f = l.frameAt(positionUs) ?: continue
                val bmp = l.bitmap
                val cx = f.x * w
                val cy = f.y * h
                withTransform({
                    translate(cx, cy)
                    rotate(f.rotation, Offset.Zero)
                    scale(f.scale, f.scale, Offset.Zero)
                }) {
                    drawImage(bmp, Offset(-bmp.width / 2f, -bmp.height / 2f), alpha = f.alpha)
                }
            }
            if (subState != null && subBitmap != null) {
                val f = subState.frame
                withTransform({
                    translate(f.x * w, f.y * h)
                    rotate(f.rotation, Offset.Zero)
                    scale(f.scale, f.scale, Offset.Zero)
                }) {
                    drawImage(subBitmap, Offset(-subBitmap.width / 2f, -subBitmap.height / 2f), alpha = f.alpha)
                }
            }
            drawSelection(project, selection, positionUs, pose, sorted, w, h)
        }
    }
}

private fun clipSelectionAt(project: Project, positionUs: Long): Selection =
    com.vanta.studio.domain.timeline.Timeline.clipAt(project, positionUs)?.let { Selection.OfClip(it.clip.id) } ?: Selection.None

private fun hitTest(p: Offset, f: com.vanta.studio.domain.anim.LayerFrame, bmp: ImageBitmap, w: Float, h: Float): Boolean {
    val cx = f.x * w
    val cy = f.y * h
    val rad = Math.toRadians(-f.rotation.toDouble())
    val dx = p.x - cx
    val dy = p.y - cy
    val lx = (dx * cos(rad) - dy * sin(rad)).toFloat() / f.scale
    val ly = (dx * sin(rad) + dy * cos(rad)).toFloat() / f.scale
    val pad = 12f
    return abs(lx) <= bmp.width / 2f + pad && abs(ly) <= bmp.height / 2f + pad
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelection(
    project: Project,
    selection: Selection,
    positionUs: Long,
    pose: Pose?,
    layers: List<DrawnLayer>,
    w: Float,
    h: Float,
) {
    val accent = Vanta.AccentBright
    when (selection) {
        is Selection.OfText, is Selection.OfOverlay -> {
            val l = layers.firstOrNull { it.selection == selection } ?: return
            val f = l.frameAt(positionUs) ?: return
            withTransform({
                translate(f.x * w, f.y * h)
                rotate(f.rotation, Offset.Zero)
                scale(f.scale, f.scale, Offset.Zero)
            }) {
                drawRect(
                    accent,
                    topLeft = Offset(-l.bitmap.width / 2f, -l.bitmap.height / 2f),
                    size = Size(l.bitmap.width.toFloat(), l.bitmap.height.toFloat()),
                    style = Stroke(width = 2f / max(f.scale, 0.2f)),
                )
            }
        }
        is Selection.OfClip -> {
            val p = pose ?: return
            withTransform({
                translate(w / 2f + p.x * w, h / 2f + p.y * h)
                rotate(p.rotation, Offset.Zero)
                scale(p.scale, p.scale, Offset.Zero)
            }) {
                drawRect(
                    accent.copy(alpha = 0.8f),
                    topLeft = Offset(-w / 2f, -h / 2f),
                    size = Size(w, h),
                    style = Stroke(width = 2f / max(p.scale, 0.2f)),
                )
            }
        }
        else -> Unit
    }
}
