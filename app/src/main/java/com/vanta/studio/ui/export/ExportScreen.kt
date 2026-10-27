package com.vanta.studio.ui.export

import android.content.Intent
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.studio.AppContainer
import com.vanta.studio.R
import com.vanta.studio.domain.export.ExportMath
import com.vanta.studio.domain.export.ExportQuality
import com.vanta.studio.domain.export.ExportResolution
import com.vanta.studio.domain.export.ExportSettings
import com.vanta.studio.domain.timeline.Timeline
import com.vanta.studio.engine.Capabilities
import com.vanta.studio.engine.ExportError
import com.vanta.studio.engine.ExportState
import com.vanta.studio.ui.components.Chip
import com.vanta.studio.ui.components.SectionLabel
import com.vanta.studio.ui.components.formatTime
import com.vanta.studio.ui.editor.EditorViewModel
import com.vanta.studio.ui.theme.Vanta

@Composable
fun ExportRoute(container: AppContainer, editor: EditorViewModel, onBack: () -> Unit) {
    val exporter = container.exporter
    val state by exporter.state.collectAsStateWithLifecycle()
    val appSettings by container.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val project = editor.project

    var resolution by rememberSaveable { mutableStateOf(appSettings.exportResolution) }
    var quality by rememberSaveable { mutableStateOf(appSettings.exportQuality) }
    var fps by rememberSaveable { mutableStateOf(project?.canvas?.fps?.takeIf { it in listOf(24, 30, 60) } ?: 30) }
    var audio by rememberSaveable { mutableStateOf(true) }
    var fileName by rememberSaveable { mutableStateOf(project?.name.orEmpty()) }

    val running = state is ExportState.Preparing || state is ExportState.Running || state is ExportState.Saving
    BackHandler(enabled = running) { /* the export keeps running; leaving needs an explicit action */ }

    Column(Modifier.fillMaxSize().background(Vanta.Bg).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, enabled = !running) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = if (running) Vanta.TextFaint else Vanta.Text)
            }
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleMedium)
        }

        AnimatedContent(
            targetState = state::class,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "exportState",
            modifier = Modifier.weight(1f),
        ) { _ ->
            when (val s = state) {
                ExportState.Idle, ExportState.Cancelled -> {
                    if (project == null) return@AnimatedContent
                    ExportForm(
                        durationUs = Timeline.totalDurationUs(project),
                        canvas = project.canvas,
                        resolution = resolution, onResolution = { resolution = it },
                        fps = fps, onFps = { fps = it },
                        quality = quality, onQuality = { quality = it },
                        audio = audio, onAudio = { audio = it },
                        fileName = fileName, onFileName = { fileName = it },
                        cancelled = s == ExportState.Cancelled,
                        hasAudioSources = project.clips.any { it.hasAudio && !it.muted } || project.audio.isNotEmpty(),
                        onStart = { exporter.start(scope, project, ExportSettings(resolution, fps, quality, audio, fileName)) },
                    )
                }
                ExportState.Preparing -> ProgressView(progress = null, label = stringResource(R.string.export_preparing), onCancel = null)
                is ExportState.Running -> ProgressView(
                    progress = s.progress / 100f,
                    label = stringResource(R.string.export_rendering, s.progress),
                    onCancel = exporter::cancel,
                )
                ExportState.Saving -> ProgressView(progress = 1f, label = stringResource(R.string.export_saving), onCancel = null)
                is ExportState.Done -> DoneView(
                    s = s,
                    onOpen = {
                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(i, null)) }
                    },
                    onShare = {
                        val i = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, s.uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        runCatching { context.startActivity(Intent.createChooser(i, null)) }
                    },
                    onAgain = exporter::reset,
                    onBack = {
                        exporter.reset()
                        onBack()
                    },
                    formatSize = { Formatter.formatShortFileSize(context, it) },
                )
                is ExportState.Failed -> FailedView(s, onRetry = exporter::reset)
            }
        }
    }
}

@Composable
private fun ExportForm(
    durationUs: Long,
    canvas: com.vanta.studio.domain.model.CanvasSpec,
    resolution: ExportResolution, onResolution: (ExportResolution) -> Unit,
    fps: Int, onFps: (Int) -> Unit,
    quality: ExportQuality, onQuality: (ExportQuality) -> Unit,
    audio: Boolean, onAudio: (Boolean) -> Unit,
    fileName: String, onFileName: (String) -> Unit,
    cancelled: Boolean,
    hasAudioSources: Boolean,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    val (w, h) = ExportMath.outputSize(canvas, resolution)
    val bitrate = ExportMath.videoBitrate(w, h, fps, quality)
    val estimate = ExportMath.estimateBytes(durationUs, bitrate, audio && hasAudioSources)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        if (cancelled) {
            Text(
                stringResource(R.string.export_cancelled),
                style = MaterialTheme.typography.bodySmall, color = Vanta.Warning, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        OutlinedTextField(
            value = fileName, onValueChange = onFileName, singleLine = true,
            label = { Text(stringResource(R.string.export_file_name)) },
            suffix = { Text(".mp4", color = Vanta.TextDim) },
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Vanta.Accent, unfocusedBorderColor = Vanta.Outline),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
        )
        SectionLabel(stringResource(R.string.export_resolution), Modifier.padding(start = 4.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportResolution.entries.forEach { r ->
                val (rw, rh) = ExportMath.outputSize(canvas, r)
                val ok = Capabilities.supportsAvc(rw, rh, 30)
                Box(Modifier.clickable(enabled = ok) { onResolution(r) }) {
                    Chip(resLabel(r), resolution == r, { if (ok) onResolution(r) }, Modifier.then(if (ok) Modifier else Modifier.background(Color.Transparent)))
                }
            }
        }
        Text(
            "${w} × ${h}",
            style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        SectionLabel(stringResource(R.string.export_fps), Modifier.padding(start = 4.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(24, 30, 60).forEach { f ->
                val ok = Capabilities.supportsAvc(w, h, f)
                Chip("$f fps" + if (!ok) " ✕" else "", fps == f, { if (ok) onFps(f) })
            }
        }
        if (!Capabilities.supportsAvc(w, h, 60)) {
            Text(
                stringResource(R.string.export_no_60),
                style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
        SectionLabel(stringResource(R.string.export_quality), Modifier.padding(start = 4.dp))
        Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.quality_low), quality == ExportQuality.LOW, { onQuality(ExportQuality.LOW) })
            Chip(stringResource(R.string.quality_standard), quality == ExportQuality.STANDARD, { onQuality(ExportQuality.STANDARD) })
            Chip(stringResource(R.string.quality_high), quality == ExportQuality.HIGH, { onQuality(ExportQuality.HIGH) })
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.export_audio), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = audio, onCheckedChange = onAudio, colors = SwitchDefaults.colors(checkedTrackColor = Vanta.Accent))
        }
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Vanta.Surface2)
                .padding(16.dp),
        ) {
            SummaryRow(stringResource(R.string.export_duration), formatTime(durationUs, fraction = false))
            SummaryRow(stringResource(R.string.export_bitrate), String.format(java.util.Locale.US, "%.1f Mbps", bitrate / 1_000_000f))
            SummaryRow(stringResource(R.string.export_estimate), "≈ " + Formatter.formatShortFileSize(context, estimate))
        }
        Text(
            stringResource(R.string.export_preview_note),
            style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(Vanta.Accent, Color(0xFF6E4BF5))))
                .clickable(onClick = onStart),
            contentAlignment = Alignment.Center,
        ) { Text(stringResource(R.string.export_start), style = MaterialTheme.typography.labelLarge, color = Color.White) }
    }
}

@Composable
private fun resLabel(r: ExportResolution) = when (r) {
    ExportResolution.P480 -> "480p"
    ExportResolution.P720 -> "720p"
    ExportResolution.P1080 -> "1080p"
    ExportResolution.P2160 -> "4K"
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Vanta.TextDim, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProgressView(progress: Float?, label: String, onCancel: (() -> Unit)?) {
    val animated by animateFloatAsState(progress ?: 0f, tween(300), label = "exportProgress")
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 14.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(Vanta.Surface3, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                if (progress != null) {
                    drawArc(
                        Brush.sweepGradient(listOf(Vanta.AccentBright, Vanta.Accent, Vanta.AccentBright)),
                        -90f, 360f * animated, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            if (progress != null) {
                Text("${(animated * 100).toInt()}%", style = MaterialTheme.typography.displaySmall)
            } else {
                androidx.compose.material3.CircularProgressIndicator(color = Vanta.Accent, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.export_keep_open), style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, textAlign = TextAlign.Center)
        if (onCancel != null) {
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel), color = Vanta.Danger) }
        }
    }
}

@Composable
private fun DoneView(
    s: ExportState.Done,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onAgain: () -> Unit,
    onBack: () -> Unit,
    formatSize: (Long) -> String,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.CheckCircle, null, tint = Vanta.Success, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.export_done), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.export_done_where), style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Vanta.Surface2).padding(16.dp),
        ) {
            SummaryRow(stringResource(R.string.export_file_name), s.fileName)
            SummaryRow(stringResource(R.string.export_resolution), "${s.width} × ${s.height}")
            SummaryRow(stringResource(R.string.export_duration), formatTime(s.durationMs * 1000, fraction = false))
            SummaryRow(stringResource(R.string.export_size), formatSize(s.sizeBytes))
            SummaryRow(stringResource(R.string.export_audio), stringResource(if (s.hasAudio) R.string.yes else R.string.no))
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ActionButton(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.action_open), true, onOpen, Modifier.weight(1f))
            ActionButton(Icons.Rounded.Share, stringResource(R.string.action_share), false, onShare, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            TextButton(onClick = onAgain) { Text(stringResource(R.string.export_again), color = Vanta.AccentBright) }
            TextButton(onClick = onBack) { Text(stringResource(R.string.export_back_editor), color = Vanta.TextDim) }
        }
    }
}

@Composable
private fun ActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier) {
    Row(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) Vanta.Accent else Vanta.Surface3)
            .border(1.dp, if (primary) Color.Transparent else Vanta.OutlineStrong, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

@Composable
private fun FailedView(s: ExportState.Failed, onRetry: () -> Unit) {
    val message = when (s.error) {
        ExportError.NO_CLIPS -> stringResource(R.string.export_err_no_clips)
        ExportError.MISSING_MEDIA -> stringResource(R.string.export_err_missing, s.detail ?: "?")
        ExportError.NO_SPACE -> stringResource(R.string.export_err_space, s.detail ?: "?")
        ExportError.ENCODER -> stringResource(R.string.export_err_encoder)
        ExportError.INPUT -> stringResource(R.string.export_err_input)
        ExportError.IO -> stringResource(R.string.export_err_io)
        ExportError.VERIFY -> stringResource(R.string.export_err_verify)
        ExportError.UNKNOWN -> stringResource(R.string.export_err_unknown)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = Vanta.Danger, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.export_failed), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, color = Vanta.TextDim, textAlign = TextAlign.Center)
        if (!s.detail.isNullOrBlank() && s.error != ExportError.MISSING_MEDIA && s.error != ExportError.NO_SPACE) {
            Spacer(Modifier.height(10.dp))
            Text(s.detail, style = MaterialTheme.typography.labelSmall, color = Vanta.TextFaint, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_try_again), color = Vanta.AccentBright) }
    }
}
