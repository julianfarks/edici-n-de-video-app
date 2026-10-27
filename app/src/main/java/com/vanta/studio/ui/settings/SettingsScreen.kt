package com.vanta.studio.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.studio.AppContainer
import com.vanta.studio.BuildConfig
import com.vanta.studio.R
import com.vanta.studio.data.PreviewQuality
import com.vanta.studio.domain.export.ExportQuality
import com.vanta.studio.domain.export.ExportResolution
import com.vanta.studio.ui.components.Chip
import com.vanta.studio.ui.components.SectionLabel
import com.vanta.studio.ui.theme.Vanta

@Composable
fun SettingsRoute(container: AppContainer, onBack: () -> Unit) {
    val s by container.settings.state.collectAsStateWithLifecycle()
    var language by remember {
        mutableStateOf(AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').ifBlank { "system" })
    }

    Column(Modifier.fillMaxSize().background(Vanta.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = Vanta.Text) }
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionLabel(stringResource(R.string.settings_language))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.language_system), language == "system", {
                    language = "system"
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                })
                Chip("Español", language == "es", {
                    language = "es"
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("es"))
                })
                Chip("English", language == "en", {
                    language = "en"
                    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                })
            }

            SectionLabel(stringResource(R.string.settings_preview_quality))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("480p", s.previewQuality == PreviewQuality.LOW, { container.settings.update { it.copy(previewQuality = PreviewQuality.LOW) } })
                Chip("720p", s.previewQuality == PreviewQuality.MEDIUM, { container.settings.update { it.copy(previewQuality = PreviewQuality.MEDIUM) } })
                Chip("1080p", s.previewQuality == PreviewQuality.HIGH, { container.settings.update { it.copy(previewQuality = PreviewQuality.HIGH) } })
            }
            Text(
                stringResource(R.string.settings_preview_hint),
                style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )

            SectionLabel(stringResource(R.string.settings_timeline))
            ToggleRow(stringResource(R.string.settings_snapping), stringResource(R.string.settings_snapping_hint), s.snapping) {
                container.settings.update { st -> st.copy(snapping = it) }
            }
            ToggleRow(stringResource(R.string.settings_frame_snap), stringResource(R.string.settings_frame_snap_hint), s.frameSnapping) {
                container.settings.update { st -> st.copy(frameSnapping = it) }
            }
            ToggleRow(stringResource(R.string.settings_tips), null, s.showTips) { container.settings.update { st -> st.copy(showTips = it) } }

            SectionLabel(stringResource(R.string.settings_export_defaults))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExportResolution.entries.forEach { r ->
                    Chip(
                        when (r) { ExportResolution.P480 -> "480p"; ExportResolution.P720 -> "720p"; ExportResolution.P1080 -> "1080p"; ExportResolution.P2160 -> "4K" },
                        s.exportResolution == r, { container.settings.update { it.copy(exportResolution = r) } },
                    )
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip(stringResource(R.string.quality_low), s.exportQuality == ExportQuality.LOW, { container.settings.update { it.copy(exportQuality = ExportQuality.LOW) } })
                Chip(stringResource(R.string.quality_standard), s.exportQuality == ExportQuality.STANDARD, { container.settings.update { it.copy(exportQuality = ExportQuality.STANDARD) } })
                Chip(stringResource(R.string.quality_high), s.exportQuality == ExportQuality.HIGH, { container.settings.update { it.copy(exportQuality = ExportQuality.HIGH) } })
            }

            SectionLabel(stringResource(R.string.settings_about))
            Text(
                "Vanta Studio ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp),
            )
            Text(
                stringResource(R.string.settings_about_text),
                style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Vanta.TextFaint)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Vanta.Accent))
    }
}
