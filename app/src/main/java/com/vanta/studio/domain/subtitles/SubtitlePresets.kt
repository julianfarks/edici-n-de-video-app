package com.vanta.studio.domain.subtitles

import com.vanta.studio.domain.model.FontChoice
import com.vanta.studio.domain.model.LayerAnim
import com.vanta.studio.domain.model.SubtitleKind
import com.vanta.studio.domain.model.SubtitleStyle

/** Original subtitle looks. Each one is just a [SubtitleStyle], so all are fully editable. */
object SubtitlePresets {
    fun forKind(kind: SubtitleKind): SubtitleStyle = when (kind) {
        SubtitleKind.CINEMATIC -> SubtitleStyle(
            kind = kind, font = FontChoice.LIGHT, size = 0.042f, color = 0xFFF2EBDD, strokeColor = null,
            shadow = true, positionY = 0.9f, highlightWord = false, animIn = LayerAnim.FADE, animOut = LayerAnim.FADE,
            maxCharsPerLine = 38,
        )
        SubtitleKind.MINIMAL -> SubtitleStyle(
            kind = kind, font = FontChoice.SANS_MEDIUM, size = 0.04f, color = 0xFFFFFFFF, strokeColor = null,
            shadow = true, background = 0x88000000, positionY = 0.88f, highlightWord = false, animIn = LayerAnim.NONE,
            animOut = LayerAnim.NONE, maxCharsPerLine = 40,
        )
        SubtitleKind.HIGHLIGHT -> SubtitleStyle(
            kind = kind, font = FontChoice.SANS_BLACK, size = 0.055f, color = 0xFFFFFFFF, highlightColor = 0xFFFFD84A,
            positionY = 0.8f, animIn = LayerAnim.FADE, animOut = LayerAnim.FADE,
        )
        SubtitleKind.CREATOR -> SubtitleStyle(
            kind = kind, font = FontChoice.SANS_BLACK, size = 0.085f, color = 0xFFFFFFFF, highlightColor = 0xFF8B6CFF,
            strokeWidth = 0.16f, uppercase = true, positionY = 0.62f, animIn = LayerAnim.POP, animOut = LayerAnim.NONE,
            maxCharsPerLine = 16, maxWordsPerCue = 3,
        )
        SubtitleKind.KARAOKE -> SubtitleStyle(
            kind = kind, font = FontChoice.SANS_BLACK, size = 0.058f, color = 0xFFFFFFFF, highlightColor = 0xFF5CE0FF,
            positionY = 0.8f, animIn = LayerAnim.SLIDE_UP, animOut = LayerAnim.FADE, maxWordsPerCue = 7,
        )
        SubtitleKind.BOUNCE -> SubtitleStyle(
            kind = kind, font = FontChoice.CONDENSED, size = 0.07f, color = 0xFFFFFFFF, highlightColor = 0xFFFF5C7A,
            uppercase = true, positionY = 0.7f, animIn = LayerAnim.POP, animOut = LayerAnim.FADE, maxWordsPerCue = 4,
            maxCharsPerLine = 20,
        )
        SubtitleKind.PROGRESSIVE -> SubtitleStyle(
            kind = kind, font = FontChoice.SANS_MEDIUM, size = 0.052f, color = 0xFFFFFFFF, highlightColor = 0xFFFFFFFF,
            highlightWord = false, positionY = 0.8f, animIn = LayerAnim.NONE, animOut = LayerAnim.FADE,
        )
        SubtitleKind.SCALE_SYNC -> SubtitleStyle(
            kind = kind, font = FontChoice.SANS_BLACK, size = 0.06f, color = 0xFFFFFFFF, highlightColor = 0xFF4ADE80,
            positionY = 0.78f, animIn = LayerAnim.FADE, animOut = LayerAnim.FADE, maxWordsPerCue = 6,
        )
    }

    val all: List<SubtitleKind> = SubtitleKind.entries
}
