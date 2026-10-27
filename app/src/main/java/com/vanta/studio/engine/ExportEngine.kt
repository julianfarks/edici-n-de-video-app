package com.vanta.studio.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.vanta.studio.data.MediaProbe
import com.vanta.studio.domain.export.ExportMath
import com.vanta.studio.domain.export.ExportSettings
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.timeline.Timeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ExportError { NO_CLIPS, MISSING_MEDIA, NO_SPACE, ENCODER, INPUT, IO, VERIFY, UNKNOWN }

sealed interface ExportState {
    data object Idle : ExportState
    data object Preparing : ExportState
    data class Running(val progress: Int) : ExportState
    data object Saving : ExportState
    data class Done(
        val uri: Uri,
        val fileName: String,
        val sizeBytes: Long,
        val durationMs: Long,
        val width: Int,
        val height: Int,
        val hasAudio: Boolean,
    ) : ExportState
    data class Failed(val error: ExportError, val detail: String? = null) : ExportState
    data object Cancelled : ExportState
}

/** Facts read back from an exported file. */
data class VideoFileInfo(val durationMs: Long, val width: Int, val height: Int, val hasVideo: Boolean, val hasAudio: Boolean)

/**
 * Renders a project to an MP4 (H.264 + AAC) with Media3 Transformer, verifies the result,
 * and publishes it to the gallery (Movies/Vanta Studio). The project is never modified.
 * Must be started from the main thread; it keeps running if the screen is left.
 */
class ExportEngine(private val context: Context, private val factory: CompositionFactory) {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    private var transformer: Transformer? = null
    private var progressJob: Job? = null
    private var outputFile: File? = null

    val isRunning: Boolean
        get() = _state.value is ExportState.Preparing || _state.value is ExportState.Running || _state.value is ExportState.Saving

    fun reset() {
        if (!isRunning) _state.value = ExportState.Idle
    }

    fun start(scope: CoroutineScope, project: Project, settings: ExportSettings) {
        if (isRunning) return
        _state.value = ExportState.Preparing
        scope.launch {
            if (project.clips.isEmpty()) {
                _state.value = ExportState.Failed(ExportError.NO_CLIPS)
                return@launch
            }
            val missing = withContext(Dispatchers.IO) {
                (project.clips.map { it.uri } + project.audio.map { it.uri }).distinct()
                    .count { !MediaProbe.isReadable(context, Uri.parse(it)) }
            }
            if (missing > 0) {
                _state.value = ExportState.Failed(ExportError.MISSING_MEDIA, missing.toString())
                return@launch
            }
            val (w, h) = ExportMath.outputSize(project.canvas, settings.resolution)
            val bitrate = ExportMath.videoBitrate(w, h, settings.fps, settings.quality)
            val duration = Timeline.totalDurationUs(project)
            val needed = ExportMath.estimateBytes(duration, bitrate, settings.includeAudio) * 2
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            if (StatFs(dir.absolutePath).availableBytes < needed) {
                _state.value = ExportState.Failed(ExportError.NO_SPACE, (needed / (1024 * 1024)).toString())
                return@launch
            }
            dir.listFiles()?.forEach { it.delete() }
            val fileName = ExportMath.sanitizeFileName(settings.fileName, project.name.ifBlank { "Vanta" })
            val out = File(dir, fileName)
            outputFile = out

            val composition: Composition = try {
                withContext(Dispatchers.Default) {
                    factory.build(
                        project,
                        CompositionFactory.Mode.Export(w, h, settings.fps, settings.includeAudio),
                        LiveProject(project),
                        PlaybackSync(),
                    )
                }
            } catch (e: Exception) {
                _state.value = ExportState.Failed(ExportError.UNKNOWN, e.message)
                return@launch
            }

            val t = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(
                    DefaultEncoderFactory.Builder(context)
                        .setRequestedVideoEncoderSettings(
                            VideoEncoderSettings.Builder().setBitrate(bitrate).build(),
                        )
                        .setEnableFallback(true)
                        .build(),
                )
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        progressJob?.cancel()
                        transformer = null
                        scope.launch { finish(out, fileName) }
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        progressJob?.cancel()
                        transformer = null
                        out.delete()
                        _state.value = ExportState.Failed(mapError(exportException), exportException.message)
                    }
                })
                .build()
            transformer = t
            _state.value = ExportState.Running(0)
            try {
                t.start(composition, out.absolutePath)
            } catch (e: Exception) {
                transformer = null
                _state.value = ExportState.Failed(ExportError.UNKNOWN, e.message)
                return@launch
            }
            progressJob = scope.launch {
                val holder = ProgressHolder()
                while (isActive && transformer === t) {
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        _state.value = ExportState.Running(holder.progress.coerceIn(0, 100))
                    }
                    delay(200)
                }
            }
        }
    }

    fun cancel() {
        val t = transformer ?: return
        progressJob?.cancel()
        t.cancel()
        transformer = null
        outputFile?.delete()
        _state.value = ExportState.Cancelled
    }

    private suspend fun finish(file: File, fileName: String) {
        _state.value = ExportState.Saving
        val info = withContext(Dispatchers.IO) { readInfo(file) }
        if (info == null || !info.hasVideo || info.durationMs <= 0 || file.length() == 0L) {
            file.delete()
            _state.value = ExportState.Failed(ExportError.VERIFY)
            return
        }
        val size = file.length()
        val uri = try {
            withContext(Dispatchers.IO) { saveToGallery(file, fileName) }
        } catch (e: Exception) {
            _state.value = ExportState.Failed(ExportError.IO, e.message)
            return
        } finally {
            withContext(Dispatchers.IO) { file.delete() }
        }
        _state.value = ExportState.Done(uri, fileName, size, info.durationMs, info.width, info.height, info.hasAudio)
    }

    private fun saveToGallery(file: File, fileName: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Vanta Studio")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 256 * 1024) } }
                ?: throw IllegalStateException("Cannot open gallery file")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    private fun mapError(e: ExportException): ExportError = when (e.errorCode) {
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.ERROR_CODE_ENCODING_FAILED,
        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
        -> ExportError.ENCODER
        ExportException.ERROR_CODE_DECODER_INIT_FAILED,
        ExportException.ERROR_CODE_DECODING_FAILED,
        ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> ExportError.INPUT
        ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
        ExportException.ERROR_CODE_IO_NO_PERMISSION,
        ExportException.ERROR_CODE_IO_UNSPECIFIED,
        -> ExportError.IO
        else -> ExportError.UNKNOWN
    }

    companion object {
        /** Reads an exported file back; used to verify it is a playable video. */
        fun readInfo(file: File): VideoFileInfo? {
            if (!file.exists() || file.length() == 0L) return null
            val r = MediaMetadataRetriever()
            return try {
                r.setDataSource(file.absolutePath)
                VideoFileInfo(
                    durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0,
                    width = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                    height = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                    hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes",
                    hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes",
                )
            } catch (e: Exception) {
                null
            } finally {
                try {
                    r.release()
                } catch (_: Exception) {
                }
            }
        }
    }
}
