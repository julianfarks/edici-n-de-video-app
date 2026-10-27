package com.vanta.studio.domain.model

import kotlinx.serialization.Serializable

// ---------------------------------------------------------------------------------------------
// Rhythm analysis
// ---------------------------------------------------------------------------------------------

/** A detected hit or pulse. [timeUs] is relative to the start of the analysed audio source. */
@Serializable
data class Beat(val timeUs: Long, val strength: Float)

@Serializable
data class TimeRange(val startUs: Long, val endUs: Long) {
    val durationUs: Long get() = endUs - startUs
}

/** A musical section with a coarse energy level (0..1). */
@Serializable
data class Section(val startUs: Long, val endUs: Long, val energy: Float)

@Serializable
data class TempoChange(val timeUs: Long, val bpm: Float)

/**
 * Result of analysing an audio source with real signal processing (spectral flux onsets,
 * dynamic-programming beat tracking, RMS energy, self-similarity novelty for sections).
 * All times are in the *source* audio's own timeline; [BeatMapping] maps them to the project.
 */
@Serializable
data class BeatMap(
    val sourceUri: String,
    val durationUs: Long,
    val bpm: Float,
    /** Confidence of the tempo estimate, 0..1. Low values mean "no clear steady pulse". */
    val tempoConfidence: Float,
    /** Every strong transient (drum hits, accents). */
    val onsets: List<Beat>,
    /** Regular pulse grid tracked from the onsets. */
    val beats: List<Beat>,
    /** Energy envelope sampled every [ENERGY_STEP_US], 0..1. */
    val energy: List<Float>,
    val sections: List<Section>,
    val pauses: List<TimeRange>,
    /** Highest-energy moments (local maxima of the smoothed envelope). */
    val peaks: List<Long>,
    val tempoChanges: List<TempoChange>,
) {
    companion object {
        const val ENERGY_STEP_US = 100_000L
    }
}

/** Which timeline item the [BeatMap] belongs to, so markers follow it when it moves. */
@Serializable
data class BeatSource(
    val itemId: String,
    /** True when [itemId] is a main-track [Clip], false for an [AudioClip]. */
    val isClip: Boolean,
    val map: BeatMap,
)

// ---------------------------------------------------------------------------------------------
// Light / flash events
// ---------------------------------------------------------------------------------------------

@Serializable
enum class FxType {
    FLASH_WHITE, FLASH_BLACK, FLASH_COLOR, RADIAL_BURST, GLOW, BLOOM, LIGHT_LEAK, LENS_FLARE,
    LIGHT_SWEEP, CHROMATIC, GLITCH, IMPACT_ZOOM, SHAKE, MOTION_BLUR, INVERT, EXPOSURE,
    SATURATION_PULSE, FLASH_ZOOM,
}

/**
 * A timed visual event drawn on top of the whole composition. It rises to full strength at
 * [peak] (a fraction of [durationUs], the "sync point") and then decays.
 */
@Serializable
data class FxEvent(
    val id: String,
    val type: FxType,
    val startUs: Long,
    val durationUs: Long,
    val intensity: Float = 1f,
    val color: Long = 0xFFFFFFFF,
    val peak: Float = 0.12f,
    val enabled: Boolean = true,
) {
    val endUs: Long get() = startUs + durationUs

    /** The moment the effect is at full strength: the one that should coincide with the beat. */
    val syncUs: Long get() = startUs + (durationUs * peak).toLong()
}

// ---------------------------------------------------------------------------------------------
// Subtitles
// ---------------------------------------------------------------------------------------------

@Serializable
data class SubWord(val text: String, val startUs: Long, val endUs: Long)

@Serializable
data class Cue(
    val id: String,
    val startUs: Long,
    val endUs: Long,
    val text: String,
    /** Word timings as reported by the recogniser; empty for hand-written cues. */
    val words: List<SubWord> = emptyList(),
    /** True when [words] timings were redistributed after an edit instead of measured. */
    val estimated: Boolean = false,
) {
    val durationUs: Long get() = endUs - startUs
}

@Serializable
enum class SubtitleKind { CINEMATIC, MINIMAL, HIGHLIGHT, CREATOR, KARAOKE, BOUNCE, PROGRESSIVE, SCALE_SYNC }

@Serializable
data class SubtitleStyle(
    val kind: SubtitleKind = SubtitleKind.HIGHLIGHT,
    val font: FontChoice = FontChoice.SANS_BLACK,
    /** Text height as a fraction of the canvas height. */
    val size: Float = 0.055f,
    val color: Long = 0xFFFFFFFF,
    /** Colour of the word being spoken (and of already-spoken words for karaoke). */
    val highlightColor: Long = 0xFFFFD84A,
    val strokeColor: Long? = 0xFF000000,
    val strokeWidth: Float = 0.12f,
    val shadow: Boolean = true,
    val background: Long? = null,
    /** Vertical centre of the block as a fraction of the canvas height (0 top, 1 bottom). */
    val positionY: Float = 0.82f,
    val uppercase: Boolean = false,
    val highlightWord: Boolean = true,
    val animIn: LayerAnim = LayerAnim.FADE,
    val animOut: LayerAnim = LayerAnim.FADE,
    val maxCharsPerLine: Int = 28,
    val maxWordsPerCue: Int = 8,
)

@Serializable
data class SubtitleTrack(
    val language: String = "es",
    val cues: List<Cue> = emptyList(),
    val style: SubtitleStyle = SubtitleStyle(),
    val enabled: Boolean = true,
    /** Where the text came from, for the UI ("vosk-es", "whisper-cloud", "manual"). */
    val engine: String = "manual",
)
