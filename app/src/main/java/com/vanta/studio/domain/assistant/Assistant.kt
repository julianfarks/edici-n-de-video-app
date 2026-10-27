package com.vanta.studio.domain.assistant

import com.vanta.studio.domain.fx.AutoSyncOptions
import com.vanta.studio.domain.fx.FxAutoSync
import com.vanta.studio.domain.fx.FxLibrary
import com.vanta.studio.domain.fx.FxOps
import com.vanta.studio.domain.fx.SyncTarget
import com.vanta.studio.domain.model.AspectRatio
import com.vanta.studio.domain.model.FilterRef
import com.vanta.studio.domain.model.FxType
import com.vanta.studio.domain.model.LayerAnim
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.SubtitleKind
import com.vanta.studio.domain.model.TextLayer
import com.vanta.studio.domain.model.TextStyle
import com.vanta.studio.domain.model.FontChoice
import com.vanta.studio.domain.presets.TextPresets
import com.vanta.studio.domain.subtitles.SubtitlePresets
import com.vanta.studio.domain.timeline.Ripple
import com.vanta.studio.domain.timeline.Timeline
import kotlinx.serialization.Serializable

/**
 * One structured edit the assistant proposes. Both interpreters (the offline Spanish rules and the
 * optional language model) produce only these, so everything shown to the user can be previewed,
 * applied and undone exactly like a manual edit. The model never gets to run anything itself.
 */
@Serializable
data class AssistantOp(
    val op: String,
    val timeSec: Double? = null,
    val endSec: Double? = null,
    val value: Double? = null,
    val text: String? = null,
    val name: String? = null,
    /** 1-based clip number on the main track; null = every clip. */
    val clip: Int? = null,
    val flag: Boolean? = null,
)

object Ops {
    const val SPLIT = "split"
    const val DELETE_RANGE = "delete_range"
    const val SPEED = "speed"
    const val VOLUME = "volume"
    const val MUTE = "mute"
    const val ADD_TEXT = "add_text"
    const val ADD_FX = "add_fx"
    const val FX_ON_BEATS = "fx_on_beats"
    const val ASPECT = "aspect"
    const val FILTER = "filter"
    const val SUBTITLE_STYLE = "subtitle_style"
    /** Not executed here: points the user to a tool that needs analysis (subtitles, silence, ...). */
    const val OPEN_TOOL = "open_tool"
    val ALL = setOf(SPLIT, DELETE_RANGE, SPEED, VOLUME, MUTE, ADD_TEXT, ADD_FX, FX_ON_BEATS, ASPECT, FILTER, SUBTITLE_STYLE, OPEN_TOOL)
    val TOOLS = setOf("subtitles", "silence", "beat", "auto_edit")
}

/** What an interpreter understood, plus what it could not. */
data class Interpretation(
    val ops: List<AssistantOp>,
    /** Pieces of the instruction that were not understood. */
    val unclear: List<String> = emptyList(),
)

data class ExecResult(
    val project: Project,
    /** Operations that changed the project. */
    val applied: List<AssistantOp>,
    /** Operations that could not run, with the reason (a [Reason]). */
    val skipped: List<Pair<AssistantOp, Reason>>,
    /** Tools the user should open to finish the request. */
    val tools: List<String>,
)

enum class Reason { OUT_OF_RANGE, NO_CLIP, NO_RHYTHM, UNKNOWN_NAME, INVALID, NOTHING_TO_DO }

object AssistantExec {
    private const val SEC = 1_000_000L

    /** Rejects ops that are unknown or have impossible numbers (the model may return anything). */
    fun validate(op: AssistantOp): Boolean {
        if (op.op !in Ops.ALL) return false
        fun ok(v: Double?, lo: Double, hi: Double) = v == null || (v.isFinite() && v in lo..hi)
        return ok(op.timeSec, 0.0, 36_000.0) && ok(op.endSec, 0.0, 36_000.0) && ok(op.value, 0.0, 36_000.0) &&
            (op.text == null || op.text.length <= 300) && (op.name == null || op.name.length <= 80) &&
            (op.clip == null || op.clip in 1..500)
    }

    fun apply(project: Project, ops: List<AssistantOp>, playheadUs: Long, newId: () -> String): ExecResult {
        var p = project
        val applied = ArrayList<AssistantOp>()
        val skipped = ArrayList<Pair<AssistantOp, Reason>>()
        val tools = ArrayList<String>()
        // Time-removing operations first (latest first) would shift others; keep the user's order
        // but run range deletions last so earlier ops refer to the original timeline.
        val ordered = ops.filter { it.op != Ops.DELETE_RANGE } + ops.filter { it.op == Ops.DELETE_RANGE }.sortedByDescending { it.timeSec ?: 0.0 }
        for (op in ordered) {
            if (!validate(op)) { skipped += op to Reason.INVALID; continue }
            val r = runOne(p, op, playheadUs, newId)
            when (r) {
                is Step.Done -> { p = r.project; applied += op }
                is Step.Skip -> skipped += op to r.reason
                is Step.Tool -> if (r.tool !in tools) tools += r.tool
            }
        }
        return ExecResult(p, applied, skipped, tools)
    }

    private sealed interface Step {
        data class Done(val project: Project) : Step
        data class Skip(val reason: Reason) : Step
        data class Tool(val tool: String) : Step
    }

    private fun targets(p: Project, clip: Int?): List<String>? =
        if (clip == null) p.clips.map { it.id } else p.clips.getOrNull(clip - 1)?.let { listOf(it.id) }

    private fun runOne(p: Project, op: AssistantOp, playheadUs: Long, newId: () -> String): Step {
        val total = Timeline.totalDurationUs(p)
        fun us(sec: Double?) = ((sec ?: 0.0) * SEC).toLong()
        return when (op.op) {
            Ops.SPLIT -> {
                val t = if (op.timeSec != null) us(op.timeSec) else playheadUs
                if (t <= 0 || t >= total) Step.Skip(Reason.OUT_OF_RANGE)
                else Timeline.split(p, t, newId())?.let { Step.Done(it.first) } ?: Step.Skip(Reason.OUT_OF_RANGE)
            }
            Ops.DELETE_RANGE -> {
                val a = us(op.timeSec)
                val b = us(op.endSec)
                if (op.timeSec == null || op.endSec == null || b <= a || a >= total) Step.Skip(Reason.OUT_OF_RANGE)
                else Step.Done(Ripple.removeSpan(p, a, minOf(b, total), newId))
            }
            Ops.SPEED -> {
                val v = op.value?.toFloat() ?: return Step.Skip(Reason.INVALID)
                if (v < 0.1f || v > 8f) return Step.Skip(Reason.OUT_OF_RANGE)
                val ids = targets(p, op.clip) ?: return Step.Skip(Reason.NO_CLIP)
                var q = p
                var changed = false
                for (id in ids) {
                    val c = q.clips.first { it.id == id }
                    if (c.isStill) continue
                    q = Timeline.setSpeed(q, id, v)
                    changed = true
                }
                if (changed) Step.Done(q) else Step.Skip(Reason.NOTHING_TO_DO)
            }
            Ops.VOLUME -> {
                val v = op.value?.toFloat() ?: return Step.Skip(Reason.INVALID)
                val ids = targets(p, op.clip) ?: return Step.Skip(Reason.NO_CLIP)
                Step.Done(ids.fold(p) { q, id -> Timeline.updateClip(q, id) { it.copy(volume = v.coerceIn(0f, 2f)) } })
            }
            Ops.MUTE -> {
                val ids = targets(p, op.clip) ?: return Step.Skip(Reason.NO_CLIP)
                val m = op.flag ?: true
                Step.Done(ids.fold(p) { q, id -> Timeline.updateClip(q, id) { it.copy(muted = m) } })
            }
            Ops.ADD_TEXT -> {
                val text = op.text?.trim().orEmpty()
                if (text.isEmpty()) return Step.Skip(Reason.INVALID)
                if (total <= 0) return Step.Skip(Reason.NO_CLIP)
                val start = if (op.timeSec != null) us(op.timeSec) else playheadUs
                if (start >= total) return Step.Skip(Reason.OUT_OF_RANGE)
                val dur = ((op.value ?: ((op.endSec ?: 0.0) - (op.timeSec ?: 0.0)).takeIf { it > 0 }) ?: 3.0)
                val end = minOf(total, start + us(dur))
                val preset = TextPresets.all.first()
                val layer = TextLayer(
                    id = newId(), text = text, startUs = start, durationUs = (end - start).coerceAtLeast(500_000),
                    style = preset.style, transform = com.vanta.studio.domain.model.LayerTransform(0.5f, preset.y),
                    animIn = preset.animIn, animOut = preset.animOut,
                )
                Step.Done(Timeline.addText(p, layer))
            }
            Ops.ADD_FX -> {
                val type = FxType.entries.firstOrNull { it.name.equals(op.name?.trim(), true) } ?: return Step.Skip(Reason.UNKNOWN_NAME)
                val t = if (op.timeSec != null) us(op.timeSec) else playheadUs
                if (t > total) Step.Skip(Reason.OUT_OF_RANGE) else Step.Done(FxOps.addAt(p, newId(), type, t))
            }
            Ops.FX_ON_BEATS -> {
                if (p.beat == null) return Step.Tool("beat")
                val types = (op.name ?: "FLASH_WHITE").split(',').mapNotNull { n -> FxType.entries.firstOrNull { it.name.equals(n.trim(), true) } }
                if (types.isEmpty()) return Step.Skip(Reason.UNKNOWN_NAME)
                val events = FxAutoSync.place(p, types, AutoSyncOptions(target = SyncTarget.STRONG_HITS, intensity = (op.value ?: 0.6).toFloat()), newId)
                if (events.isEmpty()) Step.Skip(Reason.NO_RHYTHM) else Step.Done(FxOps.replaceAuto(p, events))
            }
            Ops.ASPECT -> {
                val a = when (op.name?.trim()) {
                    "9:16" -> AspectRatio.R9_16
                    "16:9" -> AspectRatio.R16_9
                    "1:1" -> AspectRatio.R1_1
                    "4:5" -> AspectRatio.R4_5
                    else -> return Step.Skip(Reason.UNKNOWN_NAME)
                }
                Step.Done(p.copy(canvas = p.canvas.copy(aspect = a)))
            }
            Ops.FILTER -> {
                val id = op.name?.trim()?.lowercase() ?: return Step.Skip(Reason.UNKNOWN_NAME)
                if (com.vanta.studio.domain.effects.Grade.filter(id) == null) return Step.Skip(Reason.UNKNOWN_NAME)
                val ids = targets(p, op.clip) ?: return Step.Skip(Reason.NO_CLIP)
                val inten = (op.value ?: 1.0).toFloat().coerceIn(0f, 1f)
                Step.Done(ids.fold(p) { q, cid -> Timeline.updateClip(q, cid) { it.copy(filter = FilterRef(id, inten)) } })
            }
            Ops.SUBTITLE_STYLE -> {
                val kind = SubtitleKind.entries.firstOrNull { it.name.equals(op.name?.trim(), true) } ?: return Step.Skip(Reason.UNKNOWN_NAME)
                val t = p.subtitles ?: return Step.Tool("subtitles")
                Step.Done(p.copy(subtitles = t.copy(style = SubtitlePresets.forKind(kind))))
            }
            Ops.OPEN_TOOL -> Step.Tool(op.name?.trim()?.lowercase()?.takeIf { it in Ops.TOOLS } ?: return Step.Skip(Reason.UNKNOWN_NAME))
            else -> Step.Skip(Reason.INVALID)
        }
    }
}
