package com.vanta.studio.data

import android.content.Context
import com.vanta.studio.domain.export.ExportQuality
import com.vanta.studio.domain.export.ExportResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class PreviewQuality(val shortSide: Int) { LOW(480), MEDIUM(720), HIGH(1080) }

data class AppSettings(
    val previewQuality: PreviewQuality = PreviewQuality.MEDIUM,
    val snapping: Boolean = true,
    val frameSnapping: Boolean = true,
    val exportResolution: ExportResolution = ExportResolution.P1080,
    val exportQuality: ExportQuality = ExportQuality.STANDARD,
    val showTips: Boolean = true,
)

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("vanta_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read() = AppSettings(
        previewQuality = enumOr(prefs.getString("preview", null), PreviewQuality.MEDIUM),
        snapping = prefs.getBoolean("snap", true),
        frameSnapping = prefs.getBoolean("frameSnap", true),
        exportResolution = enumOr(prefs.getString("exportRes", null), ExportResolution.P1080),
        exportQuality = enumOr(prefs.getString("exportQuality", null), ExportQuality.STANDARD),
        showTips = prefs.getBoolean("tips", true),
    )

    fun update(f: (AppSettings) -> AppSettings) {
        val s = f(_state.value)
        prefs.edit()
            .putString("preview", s.previewQuality.name)
            .putBoolean("snap", s.snapping)
            .putBoolean("frameSnap", s.frameSnapping)
            .putString("exportRes", s.exportResolution.name)
            .putString("exportQuality", s.exportQuality.name)
            .putBoolean("tips", s.showTips)
            .apply()
        _state.value = s
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
}
