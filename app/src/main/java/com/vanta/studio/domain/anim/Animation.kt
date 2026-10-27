package com.vanta.studio.domain.anim

import com.vanta.studio.domain.model.Easing
import com.vanta.studio.domain.model.Keyframe
import com.vanta.studio.domain.model.LayerAnim
import com.vanta.studio.domain.model.LayerTransform
import com.vanta.studio.domain.model.Transform
import com.vanta.studio.domain.model.TransitionType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The animatable state of a clip or layer at one instant. */
data class Pose(
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotation: Float,
    val opacity: Float,
) {
    fun toKeyframe(timeUs: Long, easing: Easing = Easing.EASE_IN_OUT) =
        Keyframe(timeUs, x, y, scale, rotation, opacity, easing)
}

fun Transform.toPose() = Pose(x, y, scale, rotation, opacity)
fun LayerTransform.toPose() = Pose(x, y, scale, rotation, opacity)
fun Keyframe.toPose() = Pose(x, y, scale, rotation, opacity)
fun Pose.toTransform() = Transform(x, y, scale, rotation, opacity)
fun Pose.toLayerTransform() = LayerTransform(x, y, scale, rotation, opacity)

object Easings {
    fun apply(easing: Easing, t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return when (easing) {
            Easing.LINEAR -> x
            Easing.EASE_IN -> x * x * x
            Easing.EASE_OUT -> 1f - (1f - x).pow(3)
            Easing.EASE_IN_OUT -> if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f
            Easing.HOLD -> 0f
        }
    }

    fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}

object Keyframes {
    /**
     * Evaluates keyframes at [timeUs]. Returns [base] when there are no keyframes; holds the
     * first/last values outside the keyframed range. The easing of the keyframe that starts a
     * segment shapes that segment.
     */
    fun evaluate(keyframes: List<Keyframe>, timeUs: Long, base: Pose): Pose {
        if (keyframes.isEmpty()) return base
        val sorted = if (isSorted(keyframes)) keyframes else keyframes.sortedBy { it.timeUs }
        val first = sorted.first()
        val last = sorted.last()
        if (timeUs <= first.timeUs) return first.toPose()
        if (timeUs >= last.timeUs) return last.toPose()
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]
            val b = sorted[i + 1]
            if (timeUs >= a.timeUs && timeUs < b.timeUs) {
                val span = (b.timeUs - a.timeUs).coerceAtLeast(1)
                val f = Easings.apply(a.easing, (timeUs - a.timeUs).toFloat() / span)
                return Pose(
                    lerp(a.x, b.x, f),
                    lerp(a.y, b.y, f),
                    lerp(a.scale, b.scale, f),
                    lerp(a.rotation, b.rotation, f),
                    lerp(a.opacity, b.opacity, f),
                )
            }
        }
        return last.toPose()
    }

    /** Inserts or replaces (within [toleranceUs]) a keyframe, keeping the list sorted. */
    fun upsert(keyframes: List<Keyframe>, keyframe: Keyframe, toleranceUs: Long): List<Keyframe> {
        val idx = keyframes.indexOfFirst { abs(it.timeUs - keyframe.timeUs) <= toleranceUs }
        val list = keyframes.toMutableList()
        if (idx >= 0) {
            list[idx] = keyframe.copy(timeUs = keyframes[idx].timeUs)
        } else {
            list.add(keyframe)
        }
        return list.sortedBy { it.timeUs }
    }

    fun indexNear(keyframes: List<Keyframe>, timeUs: Long, toleranceUs: Long): Int =
        keyframes.indexOfFirst { abs(it.timeUs - timeUs) <= toleranceUs }

    fun remove(keyframes: List<Keyframe>, timeUs: Long, toleranceUs: Long): List<Keyframe> {
        val idx = indexNear(keyframes, timeUs, toleranceUs)
        return if (idx < 0) keyframes else keyframes.filterIndexed { i, _ -> i != idx }
    }

    /** Splits keyframes at [splitUs], adding an interpolated boundary key on both sides. */
    fun split(keyframes: List<Keyframe>, splitUs: Long, base: Pose): Pair<List<Keyframe>, List<Keyframe>> {
        if (keyframes.isEmpty()) return keyframes to keyframes
        val boundary = evaluate(keyframes, splitUs, base)
        val left = keyframes.filter { it.timeUs < splitUs } + boundary.toKeyframe(splitUs)
        val right = listOf(boundary.toKeyframe(0)) +
            keyframes.filter { it.timeUs > splitUs }.map { it.copy(timeUs = it.timeUs - splitUs) }
        return left.sortedBy { it.timeUs } to right.sortedBy { it.timeUs }
    }

    private fun isSorted(k: List<Keyframe>): Boolean {
        for (i in 1 until k.size) if (k[i].timeUs < k[i - 1].timeUs) return false
        return true
    }
}

fun lerp(a: Float, b: Float, f: Float) = a + (b - a) * f

/** The visual state of a floating layer (text, sticker...) at one instant. */
data class LayerFrame(
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotation: Float,
    val alpha: Float,
)

object LayerAnimator {
    /**
     * Returns where/how a layer is drawn at the absolute project time [timeUs], or null when
     * the layer is not visible at that time. This single function drives both the preview
     * (Compose) and the export (GL overlay), which keeps them in sync.
     */
    fun evaluate(
        startUs: Long,
        durationUs: Long,
        transform: LayerTransform,
        keyframes: List<Keyframe>,
        animIn: LayerAnim,
        animOut: LayerAnim,
        animDurationUs: Long,
        timeUs: Long,
    ): LayerFrame? {
        if (durationUs <= 0 || timeUs < startUs || timeUs >= startUs + durationUs) return null
        val local = timeUs - startUs
        val pose = Keyframes.evaluate(keyframes, local, transform.toPose())
        var frame = LayerFrame(pose.x, pose.y, pose.scale, pose.rotation, pose.opacity)
        val animLen = min(animDurationUs, durationUs / 2).coerceAtLeast(1)
        if (animIn != LayerAnim.NONE && local < animLen) {
            val a = 1f - Easings.apply(Easing.EASE_OUT, local.toFloat() / animLen)
            frame = applyAnim(frame, animIn, a, incoming = true)
        }
        if (animOut != LayerAnim.NONE && local > durationUs - animLen) {
            val a = Easings.apply(Easing.EASE_IN, (local - (durationUs - animLen)).toFloat() / animLen)
            frame = applyAnim(frame, animOut, a, incoming = false)
        }
        return frame.copy(alpha = frame.alpha.coerceIn(0f, 1f), scale = max(0.001f, frame.scale))
    }

    /** [a] is the strength of the animation: 1 = fully hidden, 0 = at rest. */
    private fun applyAnim(f: LayerFrame, anim: LayerAnim, a: Float, incoming: Boolean): LayerFrame =
        when (anim) {
            LayerAnim.NONE -> f
            LayerAnim.FADE -> f.copy(alpha = f.alpha * (1f - a))
            LayerAnim.SLIDE_UP -> f.copy(
                y = f.y + (if (incoming) 0.12f else -0.12f) * a,
                alpha = f.alpha * (1f - a),
            )
            LayerAnim.SLIDE_LEFT -> f.copy(
                x = f.x + (if (incoming) 0.18f else -0.18f) * a,
                alpha = f.alpha * (1f - a),
            )
            LayerAnim.POP -> f.copy(scale = f.scale * (1f - 0.7f * a), alpha = f.alpha * (1f - a))
            LayerAnim.ZOOM -> f.copy(scale = f.scale * (1f + 0.8f * a), alpha = f.alpha * (1f - a))
        }
}

/** Transition windows for one clip, computed from the project's joins. */
data class ClipTransitions(
    val inType: TransitionType? = null,
    val inUs: Long = 0,
    val outType: TransitionType? = null,
    val outUs: Long = 0,
)

/** Parameters consumed by the clip motion shader. All neutral by default. */
data class MotionParams(
    val scale: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
    /** 0..1, mix towards black. */
    val darken: Float = 0f,
    /** 0..1, mix towards white. */
    val whiten: Float = 0f,
    /** Horizontal wipe: pixels with u < [wipeHide] hidden (>= 0) when [wipeFromLeft]. */
    val wipe: Float = -1f,
    val wipeReveal: Boolean = false,
    /** 0..1 blur strength. */
    val blur: Float = 0f,
)

object TransitionMath {
    /**
     * Strength of the active transition half at [localUs] for a clip lasting [durationUs]:
     * returns (type, amount, incoming) or null when no transition is running.
     */
    fun state(t: ClipTransitions, durationUs: Long, localUs: Long): Triple<TransitionType, Float, Boolean>? {
        var best: Triple<TransitionType, Float, Boolean>? = null
        if (t.inType != null && t.inUs > 0 && localUs < t.inUs) {
            val a = Easings.smoothstep(1f - localUs.toFloat() / t.inUs)
            best = Triple(t.inType, a, true)
        }
        if (t.outType != null && t.outUs > 0 && localUs > durationUs - t.outUs) {
            val a = Easings.smoothstep((localUs - (durationUs - t.outUs)).toFloat() / t.outUs)
            if (best == null || a > best.second) best = Triple(t.outType, a, false)
        }
        return best
    }

    /** Combines the keyframed pose with the transition into shader parameters. */
    fun motion(pose: Pose, transition: Triple<TransitionType, Float, Boolean>?): MotionParams {
        val base = MotionParams(
            scale = pose.scale,
            x = pose.x,
            y = pose.y,
            rotation = pose.rotation,
            opacity = pose.opacity.coerceIn(0f, 1f),
        )
        if (transition == null) return base
        val (type, a, incoming) = transition
        return when (type) {
            TransitionType.FADE -> base.copy(darken = a)
            TransitionType.FLASH -> base.copy(whiten = a)
            TransitionType.ZOOM -> base.copy(scale = base.scale * (1f + 0.7f * a), darken = a * a * a)
            TransitionType.SLIDE -> base.copy(x = base.x + (if (incoming) 1f else -1f) * a)
            TransitionType.WIPE -> base.copy(wipe = if (incoming) 1f - a else a, wipeReveal = incoming)
            TransitionType.BLUR -> base.copy(blur = a, darken = a * 0.5f)
        }
    }
}
