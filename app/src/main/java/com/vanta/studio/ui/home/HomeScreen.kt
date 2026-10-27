package com.vanta.studio.ui.home

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vanta.studio.AppContainer
import com.vanta.studio.R
import com.vanta.studio.data.ProjectSummary
import com.vanta.studio.ui.components.ConfirmDialog
import com.vanta.studio.ui.components.TextInputDialog
import com.vanta.studio.ui.components.VantaMark
import com.vanta.studio.ui.components.Wordmark
import com.vanta.studio.ui.components.formatTime
import com.vanta.studio.ui.theme.Vanta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun HomeRoute(container: AppContainer, openProject: (String) -> Unit, openSettings: () -> Unit) {
    val vm: HomeViewModel = viewModel(factory = viewModelFactory { initializer { HomeViewModel(container) } })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refresh() }

    var showNew by rememberSaveable { mutableStateOf(false) }
    val defaultName = stringResource(R.string.project_default_name)

    val quickPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.quickEdit(uri, defaultName, openProject)
    }

    HomeScreen(
        state = state,
        onNewProject = { showNew = true },
        onQuickEdit = { quickPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
        onOpen = openProject,
        onSettings = openSettings,
        onRename = vm::rename,
        onDuplicate = vm::duplicate,
        onDelete = vm::delete,
        onDismissFailures = vm::consumeFailures,
    )

    if (showNew) {
        NewProjectSheet(
            onDismiss = { showNew = false },
            onCreate = { name, canvas, uris ->
                showNew = false
                vm.create(name, canvas, uris, openProject)
            },
        )
    }
}

@Composable
private fun HomeScreen(
    state: HomeState,
    onNewProject: () -> Unit,
    onQuickEdit: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onRename: (String, String) -> Unit,
    onDuplicate: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismissFailures: () -> Unit,
) {
    var renaming by remember { mutableStateOf<ProjectSummary?>(null) }
    var deleting by remember { mutableStateOf<ProjectSummary?>(null) }
    val copySuffix = stringResource(R.string.project_copy_suffix)

    Box(Modifier.fillMaxSize().background(Vanta.Bg)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 160.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Wordmark(Modifier.weight(1f))
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Rounded.Settings, stringResource(R.string.settings_title), tint = Vanta.TextDim)
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Hero(onNewProject, onQuickEdit) }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.home_recent), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (state.projects.isNotEmpty()) {
                        Text(
                            pluralStringResource(R.plurals.home_project_count, state.projects.size, state.projects.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = Vanta.TextFaint,
                        )
                    }
                }
            }
            if (!state.loading && state.projects.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { EmptyProjects() }
            }
            items(state.projects, key = { it.id }) { p ->
                ProjectCard(
                    summary = p,
                    generating = p.id in state.generatingCovers,
                    onOpen = { onOpen(p.id) },
                    onRename = { renaming = p },
                    onDuplicate = { onDuplicate(p.id, copySuffix) },
                    onDelete = { deleting = p },
                    modifier = Modifier.animateItem(),
                )
            }
        }

        AnimatedVisibility(state.creating, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Vanta.Accent)
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.home_importing), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (state.importFailures > 0) {
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                containerColor = Vanta.Surface3,
                contentColor = Vanta.Text,
                action = {
                    Text(
                        stringResource(R.string.action_ok),
                        color = Vanta.AccentBright,
                        modifier = Modifier.clickable(onClick = onDismissFailures).padding(8.dp),
                    )
                },
            ) {
                Text(pluralStringResource(R.plurals.import_failed, state.importFailures, state.importFailures))
            }
        }
    }

    renaming?.let { p ->
        TextInputDialog(
            title = stringResource(R.string.project_rename),
            initial = p.name,
            onConfirm = {
                onRename(p.id, it)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { p ->
        ConfirmDialog(
            title = stringResource(R.string.project_delete_title),
            text = stringResource(R.string.project_delete_text, p.name),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                onDelete(p.id)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun Hero(onNewProject: () -> Unit, onQuickEdit: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(listOf(Color(0xFF1D1838), Vanta.Surface2, Vanta.Surface)),
            )
            .border(1.dp, Vanta.Outline, RoundedCornerShape(24.dp))
            .padding(20.dp),
    ) {
        Text(stringResource(R.string.home_hero_title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.home_hero_subtitle), style = MaterialTheme.typography.bodyMedium, color = Vanta.TextDim)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier
                    .weight(1.2f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(Vanta.Accent, Color(0xFF6E4BF5))))
                    .clickable(onClick = onNewProject)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Add, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_new_project), style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1)
            }
            Row(
                Modifier
                    .weight(1f)
                    .height(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Vanta.Surface3)
                    .border(1.dp, Vanta.OutlineStrong, RoundedCornerShape(16.dp))
                    .clickable(onClick = onQuickEdit)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.VideoLibrary, null, tint = Vanta.AccentBright, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.home_edit_video), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyProjects() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Vanta.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            VantaMark(40.dp, Modifier.alpha(0.8f))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.home_empty_text),
            style = MaterialTheme.typography.bodyMedium,
            color = Vanta.TextDim,
            modifier = Modifier.padding(horizontal = 24.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ProjectCard(
    summary: ProjectSummary,
    generating: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val cover by produceState<ImageBitmap?>(null, summary.thumbnail, summary.modifiedAt) {
        value = summary.thumbnail?.let { f ->
            withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull()
            }
        }
    }
    Column(modifier.clip(RoundedCornerShape(16.dp)).clickable(enabled = !summary.corrupt, onClick = onOpen)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(RoundedCornerShape(16.dp))
                .background(Vanta.Surface2),
            contentAlignment = Alignment.Center,
        ) {
            val c = cover
            when {
                c != null -> Image(c, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                generating -> Shimmer()
                summary.corrupt -> Icon(Icons.Rounded.WarningAmber, null, tint = Vanta.Warning)
                else -> Icon(Icons.Rounded.Movie, null, tint = Vanta.TextFaint)
            }
            if (summary.durationUs > 0) {
                Text(
                    formatTime(summary.durationUs, fraction = false),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(summary.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (summary.corrupt) {
                        stringResource(R.string.project_corrupt)
                    } else {
                        DateUtils.getRelativeTimeSpanString(summary.modifiedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (summary.corrupt) Vanta.Warning else Vanta.TextFaint,
                    maxLines = 1,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.action_more), tint = Vanta.TextDim)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = Vanta.Surface3) {
                    if (!summary.corrupt) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.project_rename)) },
                            leadingIcon = { Icon(Icons.Rounded.DriveFileRenameOutline, null) },
                            onClick = { menu = false; onRename() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.project_duplicate)) },
                            leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                            onClick = { menu = false; onDuplicate() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = Vanta.Danger) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = Vanta.Danger) },
                        onClick = { menu = false; onDelete() },
                    )
                }
            }
        }
    }
}

@Composable
fun Shimmer(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "shimmerX")
    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    listOf(Vanta.Surface2, Vanta.Surface3, Vanta.Surface2),
                    start = androidx.compose.ui.geometry.Offset(x * 400f, 0f),
                    end = androidx.compose.ui.geometry.Offset(x * 400f + 300f, 300f),
                ),
            ),
    )
}
