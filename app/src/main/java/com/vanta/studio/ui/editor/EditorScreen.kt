package com.vanta.studio.ui.editor

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.FilterVintage
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FormatPaint
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RotateRight
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vanta.studio.AppContainer
import com.vanta.studio.R
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.timeline.Timeline
import com.vanta.studio.ui.components.ConfirmDialog
import com.vanta.studio.ui.components.TextInputDialog
import com.vanta.studio.ui.components.ToolButton
import com.vanta.studio.ui.components.formatTime
import com.vanta.studio.ui.theme.TimecodeStyle
import com.vanta.studio.ui.theme.Vanta

val LocalEditorVm = staticCompositionLocalOf<EditorViewModel> { error("EditorViewModel not provided") }

private val TIMELINE_HEIGHT = 196.dp

@Composable
fun EditorRoute(vm: EditorViewModel, container: AppContainer, onBack: () -> Unit, onExport: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalEditorVm provides vm) {
        when {
            !state.loaded -> Box(Modifier.fillMaxSize().background(Vanta.Bg), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Vanta.Accent)
            }
            state.loadFailed -> LoadFailed(onBack)
            else -> EditorScreen(vm, state, container, onBack, onExport)
        }
    }
}

@Composable
private fun LoadFailed(onBack: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Vanta.Bg).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.editor_load_failed), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.editor_load_failed_text), style = MaterialTheme.typography.bodyMedium, color = Vanta.TextDim)
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) { Text(stringResource(R.string.action_back), color = Vanta.AccentBright) }
    }
}

@Composable
private fun EditorScreen(vm: EditorViewModel, state: EditorState, container: AppContainer, onBack: () -> Unit, onExport: () -> Unit) {
    val project = state.project ?: return
    val aiState by vm.ai.state.collectAsStateWithLifecycle()
    val position by vm.engine.positionUs.collectAsStateWithLifecycle()
    val playing by vm.engine.isPlaying.collectAsStateWithLifecycle()
    val building by vm.engine.building.collectAsStateWithLifecycle()
    val engineError by vm.engine.error.collectAsStateWithLifecycle()
    val firstFrame by vm.engine.firstFrame.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showRename by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.engine.pause()
        vm.saveNow()
    }

    // ---- pickers
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(30)) { vm.addVisual(it) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addVisual(it) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::addAudio) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::addImageLayer) }

    val message = state.message
    if (message != null) {
        val text = stringResource(message.res, *message.args.toTypedArray())
        LaunchedEffect(message.id) {
            snackbar.showSnackbar(text)
            vm.messageShown(message.id)
        }
    }

    BackHandler {
        when {
            state.panel != null -> vm.openPanel(null)
            state.selection != Selection.None -> vm.select(Selection.None)
            else -> {
                vm.engine.pause()
                vm.saveNow()
                onBack()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Vanta.Bg)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar(
                name = project.name,
                state = state,
                onBack = {
                    vm.engine.pause()
                    vm.saveNow()
                    onBack()
                },
                onRename = { showRename = true },
                onUndo = vm::undo,
                onRedo = vm::redo,
                onExport = {
                    vm.engine.pause()
                    vm.saveNow()
                    onExport()
                },
                exportEnabled = project.clips.isNotEmpty(),
            )

            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFF050507)), contentAlignment = Alignment.Center) {
                if (project.clips.isEmpty()) {
                    EmptyEditor(onAdd = { showAdd = true })
                } else {
                    PreviewMonitor(
                        engine = vm.engine,
                        project = project,
                        selection = state.selection,
                        positionUs = position,
                        building = building,
                        pose = vm.currentPose(),
                        onSelect = vm::select,
                        onPoseChange = { f -> vm.applyPose(f) },
                        onGestureEnd = vm::endGesture,
                    )
                    if (engineError != null) {
                        PreviewErrorCard(engineError.orEmpty(), onRetry = vm.engine::retry)
                    } else if (!firstFrame) {
                        PreviewLoading()
                    }
                }
                if (state.busy) {
                    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Vanta.Accent)
                    }
                }
            }

            Transport(
                project = project,
                position = position,
                playing = playing,
                onPlay = vm::togglePlay,
                onStart = vm::jumpToStart,
                onEnd = { vm.jumpToEnd() },
                onFrame = vm::stepFrame,
                onZoomIn = { vm.zoomBy(1.4f) },
                onZoomOut = { vm.zoomBy(1f / 1.4f) },
                onFit = { vm.fitZoom(context.resources.displayMetrics.widthPixels.toFloat()) },
            )

            Box(Modifier.fillMaxWidth().height(TIMELINE_HEIGHT)) {
                TimelineView(
                    project = project,
                    selection = state.selection,
                    pxPerSec = state.pxPerSec,
                    positionUs = position,
                    missing = state.missingMedia,
                    snapEnabled = settings.snapping,
                    thumbs = container.thumbnails,
                    waveforms = container.waveforms,
                    hitThreshold = aiState.hitThreshold,
                    callbacks = remember(vm) {
                        TimelineCallbacks(
                            onUserScroll = { us ->
                                vm.engine.pause()
                                vm.seekTo(us)
                            },
                            onSelect = vm::select,
                            onZoom = vm::zoomBy,
                            onReorder = vm::moveClip,
                            onTrimClip = vm::trimLive,
                            onStillDuration = vm::setStillDuration,
                            onMoveLayer = vm::moveLayerLive,
                            onTrimLayer = vm::trimLayerLive,
                            onMoveAudio = vm::moveAudioLive,
                            onTrimAudio = vm::trimAudioLive,
                            onMoveFx = vm::moveFxLive,
                            onTrimFx = vm::trimFxLive,
                            onMoveCue = vm::moveCueLive,
                            onTrimCue = vm::trimCueLive,
                            onGestureEnd = vm::endGesture,
                            onTransitionTap = { id ->
                                vm.select(Selection.OfClip(id))
                                vm.openPanel(Panel.TRANSITION)
                            },
                            onAddMedia = { showAdd = true },
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (state.showTip && project.clips.isNotEmpty()) {
                    TipBanner(onDismiss = vm::dismissTip, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }

            BottomArea(
                vm = vm,
                state = state,
                position = position,
                container = container,
                onAddMedia = { showAdd = true },
                onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                onPickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onDelete = { vm.deleteSelected() },
                onConfirmDelete = { confirmDelete = true },
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
    }

    if (showRename) {
        TextInputDialog(
            title = stringResource(R.string.project_rename),
            initial = project.name,
            onConfirm = { vm.rename(it); showRename = false },
            onDismiss = { showRename = false },
        )
    }
    state.editingText?.let { initial ->
        TextInputDialog(
            title = stringResource(R.string.text_edit),
            initial = initial,
            singleLine = false,
            onConfirm = vm::setText,
            onDismiss = vm::stopEditingText,
        )
    }
    if (showAdd) {
        AddMediaDialog(
            onGallery = {
                showAdd = false
                galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            onFiles = {
                showAdd = false
                filePicker.launch(arrayOf("video/*", "image/*"))
            },
            onDismiss = { showAdd = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_item_title),
            text = stringResource(R.string.delete_item_text),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = { confirmDelete = false; vm.deleteSelected() },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun AddMediaDialog(onGallery: () -> Unit, onFiles: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Vanta.Surface2,
        title = { Text(stringResource(R.string.add_media_title), style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SourceRow(Icons.Rounded.PhotoLibrary, stringResource(R.string.add_from_gallery), stringResource(R.string.add_from_gallery_hint), onGallery)
                SourceRow(Icons.Rounded.FolderOpen, stringResource(R.string.add_from_files), stringResource(R.string.add_from_files_hint), onFiles)
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Vanta.TextDim) } },
    )
}

@Composable
private fun SourceRow(icon: ImageVector, title: String, hint: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Vanta.Surface3)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Vanta.AccentBright)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim)
        }
    }
}

@Composable
private fun TipBanner(onDismiss: () -> Unit, modifier: Modifier) {
    // A one-off hint: it goes away by itself and never comes back once seen or closed.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(8_000)
        onDismiss()
    }
    Row(
        modifier
            .padding(8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xF21E1A3A))
            .border(1.dp, Vanta.AccentDim, RoundedCornerShape(12.dp))
            .padding(start = 12.dp, top = 2.dp, bottom = 2.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = Vanta.AccentBright, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.tip_timeline), style = MaterialTheme.typography.labelSmall, color = Vanta.Text, modifier = Modifier.weight(1f, fill = false))
        IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, stringResource(R.string.action_close), tint = Vanta.TextDim, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun PreviewLoading() {
    Column(
        Modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xCC000000)).padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = Vanta.AccentBright, strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.preview_loading), style = MaterialTheme.typography.labelMedium, color = Vanta.Text)
    }
}

/** A readable failure state: what happened, the technical detail for diagnosis, and a way to retry. */
@Composable
private fun PreviewErrorCard(detail: String, onRetry: () -> Unit) {
    Column(
        Modifier
            .padding(24.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Vanta.Surface2)
            .border(1.dp, Vanta.Danger.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.preview_failed_title), style = MaterialTheme.typography.titleSmall, color = Vanta.Text)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.preview_failed_text), style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.preview_error, detail),
            style = MaterialTheme.typography.labelSmall, color = Vanta.Danger, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.clip(RoundedCornerShape(12.dp)).background(Vanta.Accent).clickable(onClick = onRetry).padding(horizontal = 20.dp, vertical = 10.dp),
        ) { Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.labelLarge, color = Color.White) }
    }
}

@Composable
private fun EmptyEditor(onAdd: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(Vanta.Accent)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Add, stringResource(R.string.tool_add), tint = Color.White, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.editor_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.editor_empty_text), style = MaterialTheme.typography.bodySmall, color = Vanta.TextDim)
    }
}

@Composable
private fun TopBar(
    name: String,
    state: EditorState,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    exportEnabled: Boolean,
) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), tint = Vanta.Text) }
        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onRename).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text(name, style = MaterialTheme.typography.titleSmall, color = Vanta.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (icon, text, color) = when (state.saveStatus) {
                    SaveStatus.SAVED -> Triple(Icons.Rounded.CloudDone, R.string.status_saved, Vanta.Success)
                    SaveStatus.SAVING -> Triple(Icons.Rounded.Sync, R.string.status_saving, Vanta.TextDim)
                    SaveStatus.DIRTY -> Triple(Icons.Rounded.Sync, R.string.status_unsaved, Vanta.Warning)
                }
                Icon(icon, null, tint = color, modifier = Modifier.size(11.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(text), style = MaterialTheme.typography.labelSmall, color = color)
            }
        }
        IconButton(onClick = onUndo, enabled = state.canUndo) {
            Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.action_undo), tint = if (state.canUndo) Vanta.Text else Vanta.TextFaint)
        }
        IconButton(onClick = onRedo, enabled = state.canRedo) {
            Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.action_redo), tint = if (state.canRedo) Vanta.Text else Vanta.TextFaint)
        }
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .height(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (exportEnabled) Vanta.Accent else Vanta.Surface3)
                .clickable(enabled = exportEnabled, onClick = onExport)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(R.string.action_export),
                style = MaterialTheme.typography.labelLarge,
                color = if (exportEnabled) Color.White else Vanta.TextFaint,
            )
        }
        Spacer(Modifier.width(8.dp))
    }
}

@Composable
private fun Transport(
    project: Project,
    position: Long,
    playing: Boolean,
    onPlay: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
    onFrame: (Int) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
) {
    val total = Timeline.totalDurationUs(project)
    Row(
        Modifier.fillMaxWidth().height(48.dp).background(Vanta.Surface).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(92.dp)) {
            Text(formatTime(position), style = TimecodeStyle.copy(color = Vanta.Text))
            Text("/ " + formatTime(total), style = TimecodeStyle.copy(color = Vanta.TextFaint))
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onStart) { Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.action_to_start), tint = Vanta.TextDim) }
        IconButton(onClick = { onFrame(-1) }) { Icon(Icons.Rounded.ChevronLeft, stringResource(R.string.action_prev_frame), tint = Vanta.TextDim) }
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(Vanta.Text).clickable(onClick = onPlay),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                stringResource(if (playing) R.string.action_pause else R.string.action_play),
                tint = Vanta.Bg, modifier = Modifier.size(26.dp),
            )
        }
        IconButton(onClick = { onFrame(1) }) { Icon(Icons.Rounded.ChevronRight, stringResource(R.string.action_next_frame), tint = Vanta.TextDim) }
        IconButton(onClick = onEnd) { Icon(Icons.Rounded.SkipNext, stringResource(R.string.action_to_end), tint = Vanta.TextDim) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.width(92.dp), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onZoomOut, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.ZoomOut, stringResource(R.string.action_zoom_out), tint = Vanta.TextDim, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = onZoomIn, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.ZoomIn, stringResource(R.string.action_zoom_in), tint = Vanta.TextDim, modifier = Modifier.size(20.dp)) }
            IconButton(onClick = onFit, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.FitScreen, stringResource(R.string.action_zoom_fit), tint = Vanta.TextDim, modifier = Modifier.size(20.dp)) }
        }
    }
}

// ============================================================================== bottom area

@Composable
private fun BottomArea(
    vm: EditorViewModel,
    state: EditorState,
    position: Long,
    container: AppContainer,
    onAddMedia: () -> Unit,
    onPickAudio: () -> Unit,
    onPickImage: () -> Unit,
    onDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    AnimatedContent(
        targetState = state.panel,
        transitionSpec = {
            (slideInVertically(tween(220)) { it / 3 } + fadeIn(tween(200))) togetherWith
                (slideOutVertically(tween(180)) { it / 3 } + fadeOut(tween(140)))
        },
        label = "bottomArea",
    ) { panel ->
        Box(Modifier.fillMaxWidth().background(Vanta.Surface)) {
            if (panel == null) {
                ToolBar(vm, state, onAddMedia, onPickAudio, onConfirmDelete)
            } else {
                val close = { vm.openPanel(null) }
                when (panel) {
                    Panel.TEXT_PRESETS -> TextPresetsPanel(vm, close)
                    Panel.TEXT_STYLE -> TextStylePanel(vm, state, close)
                    Panel.ANIMATION -> AnimationPanel(vm, state, close)
                    Panel.LAYERS -> LayersPanel(vm, onPickImage, close)
                    Panel.FILTERS -> FiltersPanel(vm, state, container.thumbnails, close)
                    Panel.ADJUST -> AdjustPanel(vm, close)
                    Panel.EFFECTS -> EffectsPanel(vm, close)
                    Panel.TRANSITION -> TransitionPanel(vm, state, close)
                    Panel.SPEED -> SpeedPanel(vm, close)
                    Panel.VOLUME -> VolumePanel(vm, close)
                    Panel.TRANSFORM -> TransformPanel(vm, state, position, close)
                    Panel.CROP -> CropPanel(vm, close)
                    Panel.TRIM -> TrimPanel(vm, close)
                    Panel.FORMAT -> FormatPanel(vm, state, close)
                    Panel.OVERLAY_COLOR -> OverlayColorPanel(vm, state, close)
                    Panel.AUDIO_FADE -> AudioPanel(vm, state, close)
                    Panel.AI_STUDIO -> AiStudioPanel(vm, container, state, close)
                    Panel.BEAT_FX -> BeatFxPanel(vm, state, close)
                    Panel.FX_EDIT -> FxEditPanel(vm, state, close)
                    Panel.SUBTITLES -> SubtitlesPanel(vm, container, state, close)
                    Panel.SILENCE -> SilencePanel(vm, state, close)
                    Panel.AUTO_EDIT -> AutoEditPanel(vm, state, close)
                    Panel.ASSISTANT -> AssistantPanel(vm, state, close)
                    Panel.ENHANCE -> EnhancePanel(vm, state, close)
                    Panel.HIGHLIGHTS -> HighlightsPanel(vm, state, close)
                }
            }
        }
    }
}

private class ToolDef(val icon: ImageVector, @androidx.annotation.StringRes val label: Int, val enabled: Boolean = true, val selected: Boolean = false, val onClick: () -> Unit)

@Composable
private fun ToolBar(vm: EditorViewModel, state: EditorState, onAdd: () -> Unit, onAudio: () -> Unit, onDelete: () -> Unit) {
    val project = state.project ?: return
    val hasClips = project.clips.isNotEmpty()
    val sel = state.selection
    val clip = vm.selectedClip()
    val tools: List<ToolDef> = when (sel) {
        Selection.None -> listOf(
            ToolDef(Icons.Rounded.Add, R.string.tool_add) { onAdd() },
            ToolDef(Icons.Rounded.MusicNote, R.string.tool_audio, hasClips) { onAudio() },
            ToolDef(Icons.Rounded.TextFields, R.string.tool_text, hasClips) { vm.openPanel(Panel.TEXT_PRESETS) },
            ToolDef(Icons.Rounded.Layers, R.string.tool_layers, hasClips) { vm.openPanel(Panel.LAYERS) },
            ToolDef(Icons.Rounded.FilterVintage, R.string.tool_filters, hasClips) { vm.openPanel(Panel.FILTERS) },
            ToolDef(Icons.Rounded.AutoFixHigh, R.string.tool_effects, hasClips) { vm.openPanel(Panel.EFFECTS) },
            ToolDef(Icons.Rounded.Tune, R.string.tool_adjust, hasClips) { vm.openPanel(Panel.ADJUST) },
            ToolDef(Icons.Rounded.AutoAwesome, R.string.tool_transitions, hasClips) { vm.openPanel(Panel.TRANSITION) },
            ToolDef(Icons.Rounded.AspectRatio, R.string.tool_format) { vm.openPanel(Panel.FORMAT) },
            ToolDef(Icons.Rounded.AutoAwesome, R.string.tool_ai, hasClips) { vm.openPanel(Panel.AI_STUDIO) },
            ToolDef(Icons.Rounded.FlashOn, R.string.tool_beat, hasClips) { vm.openPanel(Panel.BEAT_FX) },
            ToolDef(Icons.Rounded.Subtitles, R.string.tool_subtitles, hasClips) { vm.openPanel(Panel.SUBTITLES) },
        )
        is Selection.OfClip -> buildList {
            add(ToolDef(Icons.Rounded.ContentCut, R.string.tool_split) { vm.splitAtPlayhead() })
            add(ToolDef(Icons.Rounded.Straighten, R.string.tool_trim) { vm.openPanel(Panel.TRIM) })
            add(ToolDef(Icons.Rounded.Speed, R.string.tool_speed, clip?.isStill == false) { vm.openPanel(Panel.SPEED) })
            add(ToolDef(Icons.Rounded.VolumeUp, R.string.tool_volume, clip?.kindHasAudio() == true) { vm.openPanel(Panel.VOLUME) })
            add(ToolDef(Icons.Rounded.OpenWith, R.string.tool_transform) { vm.openPanel(Panel.TRANSFORM) })
            add(ToolDef(Icons.Rounded.Crop, R.string.tool_crop) { vm.openPanel(Panel.CROP) })
            add(ToolDef(Icons.Rounded.FilterVintage, R.string.tool_filters) { vm.openPanel(Panel.FILTERS) })
            add(ToolDef(Icons.Rounded.Tune, R.string.tool_adjust) { vm.openPanel(Panel.ADJUST) })
            add(ToolDef(Icons.Rounded.AutoFixHigh, R.string.tool_effects) { vm.openPanel(Panel.EFFECTS) })
            add(ToolDef(Icons.Rounded.AutoAwesome, R.string.tool_transitions) { vm.openPanel(Panel.TRANSITION) })
            add(ToolDef(Icons.Rounded.RotateRight, R.string.tool_rotate) { vm.rotate90() })
            add(ToolDef(Icons.Rounded.Flip, R.string.tool_flip_h) { vm.flipH() })
            add(ToolDef(Icons.Rounded.Flip, R.string.tool_flip_v) { vm.flipV() })
            add(ToolDef(Icons.Rounded.AcUnit, R.string.tool_freeze, clip?.isStill == false) { vm.freezeFrame() })
            add(ToolDef(Icons.Rounded.VolumeOff, R.string.tool_mute, clip?.kindHasAudio() == true, selected = clip?.muted == true) { vm.toggleMute() })
            add(ToolDef(Icons.Rounded.ContentCopy, R.string.tool_duplicate) { vm.duplicateSelected() })
            add(ToolDef(Icons.Rounded.Delete, R.string.tool_delete) { onDelete() })
        }
        is Selection.OfText -> listOf(
            ToolDef(Icons.Rounded.Edit, R.string.tool_edit_text) { vm.startEditingText() },
            ToolDef(Icons.Rounded.FormatPaint, R.string.tool_style) { vm.openPanel(Panel.TEXT_STYLE) },
            ToolDef(Icons.Rounded.Animation, R.string.tool_animation) { vm.openPanel(Panel.ANIMATION) },
            ToolDef(Icons.Rounded.OpenWith, R.string.tool_transform) { vm.openPanel(Panel.TRANSFORM) },
            ToolDef(Icons.Rounded.ContentCut, R.string.tool_split) { vm.splitAtPlayhead() },
            ToolDef(Icons.Rounded.ArrowUpward, R.string.tool_forward) { vm.layerOrderUp(true) },
            ToolDef(Icons.Rounded.ArrowDownward, R.string.tool_backward) { vm.layerOrderUp(false) },
            ToolDef(Icons.Rounded.ContentCopy, R.string.tool_duplicate) { vm.duplicateSelected() },
            ToolDef(Icons.Rounded.Delete, R.string.tool_delete) { onDelete() },
        )
        is Selection.OfOverlay -> listOf(
            ToolDef(Icons.Rounded.FormatPaint, R.string.tool_style) { vm.openPanel(Panel.OVERLAY_COLOR) },
            ToolDef(Icons.Rounded.Animation, R.string.tool_animation) { vm.openPanel(Panel.ANIMATION) },
            ToolDef(Icons.Rounded.OpenWith, R.string.tool_transform) { vm.openPanel(Panel.TRANSFORM) },
            ToolDef(Icons.Rounded.ContentCut, R.string.tool_split) { vm.splitAtPlayhead() },
            ToolDef(Icons.Rounded.ArrowUpward, R.string.tool_forward) { vm.layerOrderUp(true) },
            ToolDef(Icons.Rounded.ArrowDownward, R.string.tool_backward) { vm.layerOrderUp(false) },
            ToolDef(Icons.Rounded.ContentCopy, R.string.tool_duplicate) { vm.duplicateSelected() },
            ToolDef(Icons.Rounded.Delete, R.string.tool_delete) { onDelete() },
        )
        is Selection.OfFx -> listOf(
            ToolDef(Icons.Rounded.Tune, R.string.tool_edit_fx) { vm.openPanel(Panel.FX_EDIT) },
            ToolDef(Icons.Rounded.ContentCopy, R.string.tool_duplicate) { vm.duplicateFx(sel.id) },
            ToolDef(Icons.Rounded.Delete, R.string.tool_delete) { vm.deleteFx(sel.id) },
        )
        is Selection.OfCue -> listOf(
            ToolDef(Icons.Rounded.Edit, R.string.tool_edit_cue) { vm.openPanel(Panel.SUBTITLES) },
            ToolDef(Icons.Rounded.Delete, R.string.tool_delete) { vm.ai.deleteCue(sel.id) },
        )
        is Selection.OfAudio -> {
            val audio = project.audio.firstOrNull { it.id == sel.id }
            listOf(
                ToolDef(Icons.Rounded.VolumeUp, R.string.tool_volume) { vm.openPanel(Panel.AUDIO_FADE) },
                ToolDef(Icons.Rounded.ContentCut, R.string.tool_split) { vm.splitAtPlayhead() },
                ToolDef(Icons.Rounded.VolumeOff, R.string.tool_mute, selected = audio?.muted == true) { vm.updateSelectedAudio { it.copy(muted = !it.muted) } },
                ToolDef(Icons.Rounded.ContentCopy, R.string.tool_duplicate) { vm.duplicateSelected() },
                ToolDef(Icons.Rounded.Delete, R.string.tool_delete) { onDelete() },
            )
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sel != Selection.None) {
            ToolButton(Icons.Rounded.Check, stringResource(R.string.action_done), { vm.select(Selection.None) }, tint = Vanta.AccentBright)
            Box(Modifier.width(1.dp).height(36.dp).background(Vanta.Outline))
        }
        tools.forEach { t ->
            ToolButton(t.icon, stringResource(t.label), t.onClick, enabled = t.enabled, selected = t.selected)
        }
    }
}

private fun com.vanta.studio.domain.model.Clip.kindHasAudio() =
    kind == com.vanta.studio.domain.model.MediaKind.VIDEO && hasAudio
