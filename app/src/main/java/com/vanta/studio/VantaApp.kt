package com.vanta.studio

import android.app.Application
import android.content.Context
import com.vanta.studio.data.MediaImporter
import com.vanta.studio.data.ProjectRepository
import com.vanta.studio.data.SettingsRepository
import com.vanta.studio.data.Thumbnails
import com.vanta.studio.data.Waveforms
import com.vanta.studio.engine.CompositionFactory
import com.vanta.studio.engine.ExportEngine
import com.vanta.studio.engine.asr.ModelManager
import com.vanta.studio.engine.asr.SecretStore
import com.vanta.studio.engine.asr.SubtitleService
import com.vanta.studio.engine.audio.AudioDecoder
import com.vanta.studio.engine.audio.BeatService
import com.vanta.studio.engine.audio.SilenceService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import java.io.File

/** Manual dependency container: the app is small enough not to need a DI framework. */
class AppContainer(app: Application) {
    val context: Context = app
    val appScope: CoroutineScope = MainScope()
    val projects = ProjectRepository(File(app.filesDir, "projects"))
    val thumbnails = Thumbnails(app)
    val waveforms = Waveforms(app)
    val importer = MediaImporter(app, projects, thumbnails)
    val settings = SettingsRepository(app)
    val compositionFactory = CompositionFactory(app)
    val exporter = ExportEngine(app, compositionFactory)
    val audioDecoder = AudioDecoder(app)
    val beats = BeatService(audioDecoder)
    val models = ModelManager(app)
    val secrets = SecretStore(app)
    val subtitles = SubtitleService(audioDecoder)
    val silence = SilenceService(audioDecoder)
}

class VantaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
