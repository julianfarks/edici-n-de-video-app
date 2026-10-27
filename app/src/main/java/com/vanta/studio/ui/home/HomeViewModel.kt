package com.vanta.studio.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vanta.studio.AppContainer
import com.vanta.studio.data.MediaImporter
import com.vanta.studio.data.MediaProbe
import com.vanta.studio.data.ProjectSummary
import com.vanta.studio.domain.model.AspectRatio
import com.vanta.studio.domain.model.CanvasSpec
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.domain.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeState(
    val loading: Boolean = true,
    val projects: List<ProjectSummary> = emptyList(),
    val generatingCovers: Set<String> = emptySet(),
    val creating: Boolean = false,
    /** Number of picked files that could not be imported (damaged / unsupported). */
    val importFailures: Int = 0,
)

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            val list = c.projects.list()
            _state.update { it.copy(loading = false, projects = list) }
            generateMissingCovers(list)
        }
    }

    private suspend fun generateMissingCovers(list: List<ProjectSummary>) {
        val missing = list.filter { it.thumbnail == null && it.clipCount > 0 && !it.corrupt }
        if (missing.isEmpty()) return
        _state.update { it.copy(generatingCovers = missing.map { m -> m.id }.toSet()) }
        for (s in missing) {
            try {
                val p = c.projects.load(s.id)
                val first = p.clips.firstOrNull() ?: continue
                val bmp = if (first.kind == MediaKind.VIDEO) {
                    c.thumbnails.fullFrame(first.uri, first.trimStartUs, 480)
                } else {
                    c.thumbnails.still(first.uri, 320)
                }
                if (bmp != null) c.projects.saveThumbnail(s.id, bmp)
            } catch (_: Exception) {
            }
            _state.update { it.copy(generatingCovers = it.generatingCovers - s.id) }
        }
        val updated = c.projects.list()
        _state.update { it.copy(projects = updated) }
    }

    /** Creates a project, imports the picked media into it and returns its id. */
    fun create(name: String, canvas: CanvasSpec, uris: List<Uri>, onCreated: (String) -> Unit) {
        if (_state.value.creating) return
        _state.update { it.copy(creating = true, importFailures = 0) }
        viewModelScope.launch {
            val id = MediaImporter.newId()
            val now = System.currentTimeMillis()
            var project = Project(id = id, name = name, createdAt = now, modifiedAt = now, canvas = canvas)
            var failed = 0
            if (uris.isNotEmpty()) {
                val result = c.importer.importVisual(id, uris)
                failed = result.failed
                project = project.copy(clips = result.clips)
            }
            c.projects.save(project)
            _state.update { it.copy(creating = false, importFailures = failed) }
            onCreated(id)
        }
    }

    /** "Edit video": one video, canvas matched to its orientation. */
    fun quickEdit(uri: Uri, defaultName: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.IO) { MediaProbe.probe(c.context, uri) }
            val aspect = when {
                info == null || info.width == 0 || info.height == 0 -> AspectRatio.R16_9
                info.width == info.height -> AspectRatio.R1_1
                info.height > info.width -> AspectRatio.R9_16
                else -> AspectRatio.R16_9
            }
            val name = info?.displayName?.substringBeforeLast('.')?.ifBlank { null } ?: defaultName
            create(name, CanvasSpec(aspect = aspect), listOf(uri), onCreated)
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            c.projects.rename(id, name.trim())
            refresh()
        }
    }

    fun duplicate(id: String, copySuffix: String) {
        viewModelScope.launch {
            val source = _state.value.projects.firstOrNull { it.id == id } ?: return@launch
            c.projects.duplicate(id, MediaImporter.newId(), "${source.name} $copySuffix")
            refresh()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            c.projects.delete(id)
            refresh()
        }
    }

    fun consumeFailures() = _state.update { it.copy(importFailures = 0) }
}
