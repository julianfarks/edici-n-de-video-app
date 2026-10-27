package com.vanta.studio.domain.assistant

/**
 * Local, editable starting points. A template is only a list of the same operations the text
 * assistant uses, so applying one gives ordinary clips, filters, effects and subtitle styles that
 * can be changed afterwards, and undone in one step. No template contains media.
 */
data class Template(
    val id: String,
    val category: TemplateCategory,
    val ops: List<AssistantOp>,
)

enum class TemplateCategory { SOCIAL, VIDEO, MUSIC, SPEECH }

object Templates {
    val all: List<Template> = listOf(
        Template(
            "tiktok_fast", TemplateCategory.SOCIAL,
            listOf(
                AssistantOp(Ops.ASPECT, name = "9:16"),
                AssistantOp(Ops.FILTER, name = "vivid", value = 0.8),
                AssistantOp(Ops.SUBTITLE_STYLE, name = "CREATOR"),
                AssistantOp(Ops.FX_ON_BEATS, name = "FLASH_WHITE,IMPACT_ZOOM", value = 0.6),
                AssistantOp(Ops.OPEN_TOOL, name = "subtitles"),
            ),
        ),
        Template(
            "reel_clean", TemplateCategory.SOCIAL,
            listOf(
                AssistantOp(Ops.ASPECT, name = "4:5"),
                AssistantOp(Ops.FILTER, name = "warm", value = 0.6),
                AssistantOp(Ops.SUBTITLE_STYLE, name = "MINIMAL"),
                AssistantOp(Ops.OPEN_TOOL, name = "subtitles"),
            ),
        ),
        Template(
            "youtube_clean", TemplateCategory.VIDEO,
            listOf(
                AssistantOp(Ops.ASPECT, name = "16:9"),
                AssistantOp(Ops.FILTER, name = "punch", value = 0.4),
                AssistantOp(Ops.SUBTITLE_STYLE, name = "HIGHLIGHT"),
                AssistantOp(Ops.OPEN_TOOL, name = "silence"),
            ),
        ),
        Template(
            "cinematic", TemplateCategory.VIDEO,
            listOf(
                AssistantOp(Ops.ASPECT, name = "16:9"),
                AssistantOp(Ops.FILTER, name = "blockbuster", value = 0.8),
                AssistantOp(Ops.SUBTITLE_STYLE, name = "CINEMATIC"),
            ),
        ),
        Template(
            "nostalgia", TemplateCategory.VIDEO,
            listOf(
                AssistantOp(Ops.ASPECT, name = "4:5"),
                AssistantOp(Ops.FILTER, name = "film70", value = 0.9),
            ),
        ),
        Template(
            "music_clip", TemplateCategory.MUSIC,
            listOf(
                AssistantOp(Ops.ASPECT, name = "9:16"),
                AssistantOp(Ops.FILTER, name = "punch", value = 0.7),
                AssistantOp(Ops.FX_ON_BEATS, name = "FLASH_WHITE,GLOW", value = 0.6),
                AssistantOp(Ops.OPEN_TOOL, name = "auto_edit"),
            ),
        ),
        Template(
            "interview", TemplateCategory.SPEECH,
            listOf(
                AssistantOp(Ops.ASPECT, name = "1:1"),
                AssistantOp(Ops.FILTER, name = "pastel", value = 0.4),
                AssistantOp(Ops.SUBTITLE_STYLE, name = "HIGHLIGHT"),
                AssistantOp(Ops.OPEN_TOOL, name = "silence"),
                AssistantOp(Ops.OPEN_TOOL, name = "subtitles"),
            ),
        ),
    )

    fun byId(id: String?): Template? = all.firstOrNull { it.id == id }
}
