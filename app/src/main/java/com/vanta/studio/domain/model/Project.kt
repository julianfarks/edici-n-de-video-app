package com.vanta.studio.domain.model

import kotlinx.serialization.Serializable

/**
 * Pure, immutable description of an editing project. Nothing in here touches Android or
 * media APIs, which keeps every editing operation testable on the JVM and makes undo/redo
 * a matter of keeping previous [Project] values (they never contain media data, only
 * references to it).
 *
 * All times are microseconds. "Local" times are relative to the start of the item they
 * belong to, measured on the output (post-speed) timeline.
 */
@Serializable
data class Project(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val canvas: CanvasSpec = CanvasSpec(),
    val clips: List<Clip> = emptyList(),
    val audio: List<AudioClip> = emptyList(),
    val texts: List<TextLayer> = emptyList(),
    val overlays: List<OverlayLayer> = emptyList(),
    /** Fade applied to the end of the last clip (fade to black / white...). */
    val outro: TransitionRef? = null,
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 2
    }
}

@Serializable
enum class AspectRatio(val w: Int, val h: Int) {
    R16_9(16, 9), R9_16(9, 16), R1_1(1, 1), R4_5(4, 5), CUSTOM(0, 0)
}

@Serializable
data class CanvasSpec(
    val aspect: AspectRatio = AspectRatio.R16_9,
    val customW: Int = 16,
    val customH: Int = 9,
    val fps: Int = 30,
) {
    val ratioW: Int get() = if (aspect == AspectRatio.CUSTOM) customW.coerceAtLeast(1) else aspect.w
    val ratioH: Int get() = if (aspect == AspectRatio.CUSTOM) customH.coerceAtLeast(1) else aspect.h
    val ratio: Float get() = ratioW.toFloat() / ratioH.toFloat()

    /**
     * Pixel size of the canvas when its short side is [shortSide] pixels. Both dimensions
     * are rounded to even numbers because H.264 encoders require it.
     */
    fun sizeForShortSide(shortSide: Int): Pair<Int, Int> {
        val r = ratio
        val (w, h) = if (r >= 1f) {
            (shortSide * r) to shortSide.toFloat()
        } else {
            shortSide.toFloat() to (shortSide / r)
        }
        return even(w) to even(h)
    }

    private fun even(v: Float): Int {
        val i = kotlin.math.round(v).toInt()
        return if (i % 2 == 0) i else i + 1
    }
}

@Serializable
enum class MediaKind { VIDEO, IMAGE, COLOR }

@Serializable
data class Clip(
    val id: String,
    val uri: String,
    val kind: MediaKind,
    /** Full length of the source media. Unbounded sources (images) use [UNBOUNDED]. */
    val sourceDurationUs: Long,
    val width: Int = 0,
    val height: Int = 0,
    val hasAudio: Boolean = false,
    val trimStartUs: Long = 0,
    val trimEndUs: Long,
    val speed: Float = 1f,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    /** Additional clockwise rotation in degrees, multiple of 90. */
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val crop: CropRect = CropRect(),
    val transform: Transform = Transform(),
    val keyframes: List<Keyframe> = emptyList(),
    val adjust: ColorAdjust = ColorAdjust(),
    val filter: FilterRef? = null,
    val effects: List<EffectRef> = emptyList(),
    /** Transition into this clip from the previous one (or from black for the first clip). */
    val transitionIn: TransitionRef? = null,
    val name: String = "",
    /** Solid colour (ARGB) for [MediaKind.COLOR] clips. */
    val color: Long = 0xFF000000,
) {
    val trimmedSourceUs: Long get() = trimEndUs - trimStartUs

    /** Duration on the output timeline after the speed change. */
    val durationUs: Long get() = (trimmedSourceUs / speed.toDouble()).toLong()

    val isStill: Boolean get() = kind != MediaKind.VIDEO

    /** Converts a local output time to the corresponding time in the source file. */
    fun localToSourceUs(localUs: Long): Long =
        trimStartUs + (localUs.coerceIn(0, durationUs) * speed.toDouble()).toLong()

    companion object {
        const val UNBOUNDED = Long.MAX_VALUE / 4
    }
}

/** Normalised crop, each edge is the fraction removed from that side (0..0.45). */
@Serializable
data class CropRect(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 0f,
    val bottom: Float = 0f,
) {
    val isIdentity: Boolean get() = left == 0f && top == 0f && right == 0f && bottom == 0f
}

/**
 * Placement of a clip inside the canvas. [x]/[y] are offsets as a fraction of the canvas
 * size (0 = centred, 0.5 = moved half a canvas to the right/down). [scale] 1 = fit.
 */
@Serializable
data class Transform(
    val x: Float = 0f,
    val y: Float = 0f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
)

@Serializable
enum class Easing { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, HOLD }

/** A full snapshot of the animatable properties at a local time. */
@Serializable
data class Keyframe(
    val timeUs: Long,
    val x: Float,
    val y: Float,
    val scale: Float,
    val rotation: Float,
    val opacity: Float,
    val easing: Easing = Easing.EASE_IN_OUT,
)

/**
 * Basic colour corrections. Every value is neutral at 0 and roughly spans -1..1
 * (vignette, sharpen and grain span 0..1).
 */
@Serializable
data class ColorAdjust(
    val brightness: Float = 0f,
    val contrast: Float = 0f,
    val saturation: Float = 0f,
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val shadows: Float = 0f,
    val highlights: Float = 0f,
    val vignette: Float = 0f,
    val sharpen: Float = 0f,
    val grain: Float = 0f,
) {
    val isNeutral: Boolean get() = this == ColorAdjust()
}

@Serializable
data class FilterRef(val id: String, val intensity: Float = 1f)

@Serializable
enum class EffectType {
    BLUR, PIXELATE, GLITCH, CHROMATIC, MIRROR, FILM_GRAIN, SCANLINES, LIGHT_LEAK, SHARPEN, VIGNETTE
}

@Serializable
data class EffectRef(val type: EffectType, val intensity: Float = 0.6f)

@Serializable
enum class TransitionType { FADE, FLASH, ZOOM, SLIDE, WIPE, BLUR }

@Serializable
data class TransitionRef(val type: TransitionType, val durationUs: Long = 600_000)

@Serializable
data class AudioClip(
    val id: String,
    val uri: String,
    val name: String,
    val sourceDurationUs: Long,
    /** Position of the clip on the project timeline. */
    val startUs: Long,
    val trimStartUs: Long = 0,
    val trimEndUs: Long,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val fadeInUs: Long = 0,
    val fadeOutUs: Long = 0,
    /** Lane (0..[MAX_LANES]-1). Clips in the same lane never overlap. */
    val lane: Int = 0,
) {
    val durationUs: Long get() = trimEndUs - trimStartUs
    val endUs: Long get() = startUs + durationUs

    companion object {
        const val MAX_LANES = 4
    }
}

/** Placement of a floating layer (text, sticker, image). Position is the centre in 0..1. */
@Serializable
data class LayerTransform(
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val opacity: Float = 1f,
)

@Serializable
enum class LayerAnim { NONE, FADE, SLIDE_UP, SLIDE_LEFT, POP, ZOOM }

@Serializable
enum class FontChoice(val family: String, val bold: Boolean = false) {
    SANS("sans-serif"),
    SANS_MEDIUM("sans-serif-medium"),
    SANS_BLACK("sans-serif-black"),
    CONDENSED("sans-serif-condensed", true),
    LIGHT("sans-serif-light"),
    SERIF("serif"),
    MONO("monospace"),
    CASUAL("casual"),
    CURSIVE("cursive"),
    SMALLCAPS("sans-serif-smallcaps"),
}

@Serializable
enum class TextAlign { LEFT, CENTER, RIGHT }

@Serializable
data class TextStyle(
    val font: FontChoice = FontChoice.SANS_MEDIUM,
    /** Text height as a fraction of the canvas height. */
    val size: Float = 0.07f,
    val color: Long = 0xFFFFFFFF,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val align: TextAlign = TextAlign.CENTER,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1f,
    val background: Long? = null,
    val strokeColor: Long? = null,
    /** Stroke width as a fraction of the text size. */
    val strokeWidth: Float = 0.12f,
    val shadow: Boolean = false,
    val shadowColor: Long = 0xAA000000,
    val uppercase: Boolean = false,
)

@Serializable
data class TextLayer(
    val id: String,
    val text: String,
    val startUs: Long,
    val durationUs: Long,
    val style: TextStyle = TextStyle(),
    val transform: LayerTransform = LayerTransform(),
    val animIn: LayerAnim = LayerAnim.NONE,
    val animOut: LayerAnim = LayerAnim.NONE,
    val animDurationUs: Long = 400_000,
    val keyframes: List<Keyframe> = emptyList(),
    val z: Int = 0,
) {
    val endUs: Long get() = startUs + durationUs
}

@Serializable
enum class OverlayKind { IMAGE, STICKER, SHAPE, FRAME }

@Serializable
data class OverlayLayer(
    val id: String,
    val kind: OverlayKind,
    /** Image uri for [OverlayKind.IMAGE]; sticker, shape or frame id otherwise. */
    val source: String,
    val startUs: Long,
    val durationUs: Long,
    /** Tint colour for shapes and frames. */
    val color: Long = 0xFFFFFFFF,
    /** Width as a fraction of the canvas width (ignored for frames, which fill the canvas). */
    val width: Float = 0.3f,
    val transform: LayerTransform = LayerTransform(),
    val animIn: LayerAnim = LayerAnim.NONE,
    val animOut: LayerAnim = LayerAnim.NONE,
    val animDurationUs: Long = 400_000,
    val keyframes: List<Keyframe> = emptyList(),
    val z: Int = 0,
) {
    val endUs: Long get() = startUs + durationUs
}
