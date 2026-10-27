package com.vanta.studio.engine.effects

import android.content.Context
import android.opengl.GLES20
import android.opengl.Matrix
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.vanta.studio.domain.anim.ClipTransitions
import com.vanta.studio.domain.anim.Keyframes
import com.vanta.studio.domain.anim.MotionParams
import com.vanta.studio.domain.anim.TransitionMath
import com.vanta.studio.domain.anim.toPose
import com.vanta.studio.domain.effects.Grade
import com.vanta.studio.domain.fx.LightMath
import com.vanta.studio.engine.LiveProject
import com.vanta.studio.domain.effects.GradeParams
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.engine.ClipClock

/** Supplies the current state of a clip (live in preview, frozen during export). */
fun interface ClipSource {
    fun clip(): Clip?
}

/**
 * Colour grading + stylised effects for one clip. Parameters are read on every frame from
 * [source], so dragging a slider updates the preview without rebuilding the pipeline.
 */
class ColorGradeEffect(private val source: ClipSource, private val clock: ClipClock) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ColorGradeProgram(useHdr, source, clock)
}

private class ColorGradeProgram(
    useHdr: Boolean,
    private val source: ClipSource,
    private val clock: ClipClock,
) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var width = 1
    private var height = 1
    private var lastClip: Clip? = null
    private var params = GradeParams()

    init {
        program = try {
            GlProgram(Shaders.VERTEX_COPY, Shaders.FRAGMENT_GRADE)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val clip = source.clip()
        if (clip !== lastClip) {
            lastClip = clip
            params = if (clip == null) GradeParams() else Grade.resolve(clip.adjust, clip.filter, clip.effects)
        }
        val p = params
        val timeSec = clock.localUs(presentationTimeUs) / 1_000_000f
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTexel", floatArrayOf(1f / width, 1f / height))
            program.setFloatUniform("uTime", timeSec)
            program.setFloatsUniform("uBasic", floatArrayOf(p.brightness, p.contrast, p.saturation, p.exposure))
            program.setFloatsUniform("uTone", floatArrayOf(p.temperature, p.tint, p.shadows, p.highlights))
            program.setFloatsUniform("uLook", floatArrayOf(p.vignette, p.sharpen, p.grain, p.sepia))
            program.setFloatsUniform("uStyle", floatArrayOf(p.bw, p.tealOrange, p.fade, p.blur))
            program.setFloatsUniform("uFx", floatArrayOf(p.pixelate, p.glitch, p.chroma, p.mirror))
            program.setFloatsUniform("uFx2", floatArrayOf(p.scanlines, p.lightLeak))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/**
 * Places the clip inside the canvas (position, scale, rotation, opacity, keyframes) and
 * renders the clip's halves of its transitions. Runs after the frame was fitted to the
 * canvas size, so all values are relative to the canvas.
 */
class ClipMotionEffect(
    private val source: ClipSource,
    private val clock: ClipClock,
    private val transitions: ClipTransitions,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        ClipMotionProgram(useHdr, source, clock, transitions)

    companion object {
        /** Builds the vertex transform; [aspect] = canvas width / height. */
        fun matrix(m: MotionParams, aspect: Float): FloatArray {
            val out = FloatArray(16)
            Matrix.setIdentityM(out, 0)
            Matrix.translateM(out, 0, m.x * 2f, -m.y * 2f, 0f)
            Matrix.scaleM(out, 0, 1f / aspect, 1f, 1f)
            Matrix.rotateM(out, 0, -m.rotation, 0f, 0f, 1f)
            Matrix.scaleM(out, 0, aspect, 1f, 1f)
            Matrix.scaleM(out, 0, m.scale, m.scale, 1f)
            return out
        }
    }
}

private class ClipMotionProgram(
    useHdr: Boolean,
    private val source: ClipSource,
    private val clock: ClipClock,
    private val transitions: ClipTransitions,
) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var width = 1
    private var height = 1

    init {
        program = try {
            GlProgram(Shaders.VERTEX_TRANSFORM, Shaders.FRAGMENT_MOTION)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val local = clock.localUs(presentationTimeUs)
        val clip = source.clip()
        val pose = if (clip == null) {
            com.vanta.studio.domain.model.Transform().toPose()
        } else {
            Keyframes.evaluate(clip.keyframes, local, clip.transform.toPose())
        }
        val state = TransitionMath.state(transitions, clock.durationUs, local)
        val m = TransitionMath.motion(pose, state)
        val matrix = ClipMotionEffect.matrix(m, width.toFloat() / height.toFloat())
        try {
            // The clip may not cover the whole canvas (scaled down, sliding, rotated):
            // start from opaque black so nothing from a recycled texture shows through.
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTransform", matrix)
            program.setFloatsUniform("uTexel", floatArrayOf(1f / width, 1f / height))
            program.setFloatsUniform("uMix", floatArrayOf(m.opacity, m.darken, m.whiten, m.blur))
            program.setFloatsUniform("uWipe", floatArrayOf(m.wipe, if (m.wipeReveal) 1f else 0f))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}

/**
 * Draws the project's light events (flashes, glows, flares, zoom punches, glitch...) on top of
 * the finished frame. The events are read live, so moving or retuning one updates the preview
 * without rebuilding the pipeline, and the very same maths runs in the exporter.
 */
class LightEventsEffect(
    private val live: LiveProject,
    private val clock: ClipClock,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LightEventsProgram(useHdr, live, clock)
}

private class LightEventsProgram(
    useHdr: Boolean,
    private val live: LiveProject,
    private val clock: ClipClock,
) : BaseGlShaderProgram(useHdr, 1) {
    private val program: GlProgram
    private var width = 1
    private var height = 1

    init {
        program = try {
            GlProgram(Shaders.VERTEX_COPY, Shaders.FRAGMENT_LIGHT)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        program.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val timeUs = clock.compositionUs(presentationTimeUs)
        val s = LightMath.evaluate(live.project.fx, timeUs)
        try {
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            program.setFloatsUniform("uTexel", floatArrayOf(1f / width, 1f / height))
            program.setFloatUniform("uTime", timeUs / 1_000_000f)
            program.setFloatsUniform("uFlash", floatArrayOf(s.flashR, s.flashG, s.flashB, s.flashAmt))
            program.setFloatsUniform("uGlowA", floatArrayOf(s.glow, s.bloom, s.radial, s.radialRadius))
            program.setFloatsUniform("uGlowB", floatArrayOf(s.leak, s.flare, s.flareX, s.flareY))
            program.setFloatsUniform("uSweep", floatArrayOf(s.sweep, s.sweepPos, s.motionBlur, 0f))
            program.setFloatsUniform("uLightRgb", floatArrayOf(s.lightR, s.lightG, s.lightB))
            program.setFloatsUniform("uDist", floatArrayOf(s.chroma, s.glitch, s.invert, s.exposure))
            program.setFloatsUniform("uMisc", floatArrayOf(s.satPulse, s.zoom, s.shakeX, s.shakeY))
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            program.delete()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }
}
