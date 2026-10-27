package com.vanta.studio.domain.presets

import com.vanta.studio.domain.model.FontChoice
import com.vanta.studio.domain.model.LayerAnim
import com.vanta.studio.domain.model.TextAlign
import com.vanta.studio.domain.model.TextStyle

/** A starting point for a new title; [y] is the suggested vertical position. */
data class TextPreset(
    val id: String,
    val style: TextStyle,
    val y: Float = 0.5f,
    val animIn: LayerAnim = LayerAnim.FADE,
    val animOut: LayerAnim = LayerAnim.FADE,
)

object TextPresets {
    val all = listOf(
        TextPreset("modern", TextStyle(font = FontChoice.SANS_BLACK, size = 0.09f, letterSpacing = 0.02f), animIn = LayerAnim.POP),
        TextPreset(
            "subtitle",
            TextStyle(font = FontChoice.SANS_MEDIUM, size = 0.05f, background = 0x99000000, lineSpacing = 1.1f),
            y = 0.85f, animIn = LayerAnim.FADE,
        ),
        TextPreset(
            "cinematic",
            TextStyle(font = FontChoice.SERIF, size = 0.075f, letterSpacing = 0.35f, uppercase = true, shadow = true),
            animIn = LayerAnim.FADE, animOut = LayerAnim.FADE,
        ),
        TextPreset("minimal", TextStyle(font = FontChoice.LIGHT, size = 0.055f, letterSpacing = 0.12f), animIn = LayerAnim.SLIDE_UP),
        TextPreset(
            "outline",
            TextStyle(font = FontChoice.SANS_BLACK, size = 0.09f, color = 0xFFFFFFFF, strokeColor = 0xFF000000, strokeWidth = 0.14f),
            animIn = LayerAnim.ZOOM,
        ),
        TextPreset(
            "label",
            TextStyle(font = FontChoice.CONDENSED, size = 0.045f, color = 0xFF0B0B0F, background = 0xFFB9A6FF, uppercase = true, letterSpacing = 0.08f),
            y = 0.2f, animIn = LayerAnim.SLIDE_LEFT, animOut = LayerAnim.SLIDE_LEFT,
        ),
        TextPreset(
            "shorts",
            TextStyle(font = FontChoice.SANS_BLACK, size = 0.085f, color = 0xFFFFE14D, strokeColor = 0xFF000000, strokeWidth = 0.18f, uppercase = true),
            y = 0.62f, animIn = LayerAnim.POP, animOut = LayerAnim.NONE,
        ),
    )

    val colors: List<Long> = listOf(
        0xFFFFFFFF, 0xFF0B0B0F, 0xFF8B6CFF, 0xFFB9A6FF, 0xFFFF5C7A, 0xFFFFB547,
        0xFFFFE14D, 0xFF4ADE80, 0xFF38BDF8, 0xFF2563EB, 0xFFF472B6, 0xFF94A3B8,
    )
}

/** Procedurally drawn stickers, shapes and frames (no third-party artwork). */
object StickerCatalog {
    val emoji = listOf("😀", "😂", "😍", "🔥", "✨", "🎉", "👍", "❤️", "💯", "⭐", "🎬", "🎵", "😎", "🤯", "👀", "🚀")
    val shapes = listOf("circle", "square", "rounded", "triangle", "star", "heart", "arrow", "ring", "bubble", "burst")
    val frames = listOf("border", "rounded_border", "cinema_bars", "film_strip", "corners", "glow")

    fun emojiId(e: String) = "emoji:$e"
}
