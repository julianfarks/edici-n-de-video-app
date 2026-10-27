package com.vanta.studio.ui.editor

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vanta.studio.AppContainer

/**
 * Keeps one [EditorViewModel] alive while the user moves between the editor and the export
 * screen, and releases it (stopping the player and saving) as soon as the editor is left
 * for good. The app has no navigation library, so the lifetime is managed explicitly.
 */
class EditorSession(private val container: AppContainer) {
    private var store: ViewModelStore? = null
    private var projectId: String? = null
    private var viewModel: EditorViewModel? = null

    fun get(id: String): EditorViewModel {
        if (projectId != id) release()
        viewModel?.let { return it }
        val s = ViewModelStore()
        val vm = ViewModelProvider.create(
            s,
            viewModelFactory { initializer { EditorViewModel(container, id) } },
        )[EditorViewModel::class.java]
        store = s
        projectId = id
        viewModel = vm
        return vm
    }

    fun release() {
        store?.clear()
        store = null
        viewModel = null
        projectId = null
    }
}
