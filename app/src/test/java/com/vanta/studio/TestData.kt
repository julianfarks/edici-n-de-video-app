package com.vanta.studio

import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.domain.model.Project

object TestData {
    const val SEC = 1_000_000L

    fun video(id: String, seconds: Long = 10, trimStart: Long = 0, trimEnd: Long = seconds * SEC, speed: Float = 1f) = Clip(
        id = id,
        uri = "file:///media/$id.mp4",
        kind = MediaKind.VIDEO,
        sourceDurationUs = seconds * SEC,
        width = 1920,
        height = 1080,
        hasAudio = true,
        trimStartUs = trimStart,
        trimEndUs = trimEnd,
        speed = speed,
    )

    fun image(id: String, seconds: Long = 3) = Clip(
        id = id,
        uri = "file:///media/$id.jpg",
        kind = MediaKind.IMAGE,
        sourceDurationUs = Clip.UNBOUNDED,
        width = 1000,
        height = 1000,
        trimEndUs = seconds * SEC,
    )

    fun project(vararg clips: Clip) = Project(id = "p1", name = "Test", createdAt = 0, modifiedAt = 0, clips = clips.toList())
}
