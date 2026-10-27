package com.vanta.studio.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vanta.studio.AppContainer
import com.vanta.studio.ui.editor.EditorRoute
import com.vanta.studio.ui.editor.EditorSession
import com.vanta.studio.ui.export.ExportRoute
import com.vanta.studio.ui.home.HomeRoute
import com.vanta.studio.ui.settings.SettingsRoute
import com.vanta.studio.ui.theme.Vanta

sealed interface Route {
    data object Home : Route
    data class Editor(val projectId: String) : Route
    data class Export(val projectId: String) : Route
    data object Settings : Route
}

private val RouteSaver = Saver<Route, String>(
    save = {
        when (it) {
            Route.Home -> "home"
            Route.Settings -> "settings"
            is Route.Editor -> "editor:${it.projectId}"
            is Route.Export -> "export:${it.projectId}"
        }
    },
    restore = {
        when {
            it == "settings" -> Route.Settings
            it.startsWith("editor:") -> Route.Editor(it.removePrefix("editor:"))
            it.startsWith("export:") -> Route.Export(it.removePrefix("export:"))
            else -> Route.Home
        }
    },
)

private fun depth(r: Route) = when (r) {
    Route.Home -> 0
    Route.Settings, is Route.Editor -> 1
    is Route.Export -> 2
}

@Composable
fun VantaRoot(container: AppContainer) {
    var route by rememberSaveable(stateSaver = RouteSaver) { mutableStateOf<Route>(Route.Home) }
    val session = remember { EditorSession(container) }
    val inEditor = route is Route.Editor || route is Route.Export
    LaunchedEffect(inEditor) {
        if (!inEditor) session.release()
    }
    DisposableEffect(Unit) { onDispose { session.release() } }

    BackHandler(enabled = route is Route.Settings || route is Route.Export) {
        route = when (val r = route) {
            is Route.Export -> Route.Editor(r.projectId)
            else -> Route.Home
        }
    }

    Box(Modifier.fillMaxSize().background(Vanta.Bg)) {
        AnimatedContent(
            targetState = route,
            transitionSpec = {
                val forward = depth(targetState) >= depth(initialState)
                val dir = if (forward) 1 else -1
                (slideInHorizontally(tween(260)) { it / 6 * dir } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(260)) { -it / 6 * dir } + fadeOut(tween(180)))
            },
            contentKey = { r ->
                when (r) {
                    // Keep the editor alive when coming back from export.
                    is Route.Editor -> "editor"
                    else -> r
                }
            },
            label = "route",
        ) { r ->
            when (r) {
                Route.Home -> HomeRoute(
                    container = container,
                    openProject = { route = Route.Editor(it) },
                    openSettings = { route = Route.Settings },
                )
                is Route.Editor -> EditorRoute(
                    vm = remember(r.projectId) { session.get(r.projectId) },
                    container = container,
                    onBack = { route = Route.Home },
                    onExport = { route = Route.Export(r.projectId) },
                )
                is Route.Export -> ExportRoute(
                    container = container,
                    editor = remember(r.projectId) { session.get(r.projectId) },
                    onBack = { route = Route.Editor(r.projectId) },
                )
                Route.Settings -> SettingsRoute(container = container, onBack = { route = Route.Home })
            }
        }
    }
}
