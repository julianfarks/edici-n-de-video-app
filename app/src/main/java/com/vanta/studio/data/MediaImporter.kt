package com.vanta.studio.data

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import com.vanta.studio.domain.model.AudioClip
import com.vanta.studio.domain.model.CanvasSpec
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Brings user media into a project. Content URIs are kept with a persistable read grant so
 * they still work after a reboot; when a provider does not allow that, the file is copied
 * into the project's media folder instead. Unreadable/damaged files are skipped.
 */
class MediaImporter(private val context: Context, private val repo: ProjectRepository, private val thumbnails: Thumbnails) {

    data class VisualResult(val clips: List<Clip>, val failed: Int)

    suspend fun importVisual(projectId: String, uris: List<Uri>, stillDurationUs: Long = DEFAULT_STILL_US): VisualResult =
        withContext(Dispatchers.IO) {
            var failed = 0
            val clips = uris.mapNotNull { original ->
                val uri = keepAccess(projectId, original)
                val info = uri?.let { MediaProbe.probe(context, it) }
                when {
                    uri == null || info == null -> {
                        failed++
                        null
                    }
                    info.isImage -> Clip(
                        id = newId(), uri = uri.toString(), kind = MediaKind.IMAGE,
                        sourceDurationUs = Clip.UNBOUNDED, width = info.width, height = info.height,
                        hasAudio = false, trimStartUs = 0, trimEndUs = stillDurationUs, name = info.displayName,
                    )
                    info.isVideo -> Clip(
                        id = newId(), uri = uri.toString(), kind = MediaKind.VIDEO,
                        sourceDurationUs = info.durationUs, width = info.width, height = info.height,
                        hasAudio = info.hasAudio, trimStartUs = 0, trimEndUs = info.durationUs, name = info.displayName,
                    )
                    else -> {
                        failed++
                        null
                    }
                }
            }
            VisualResult(clips, failed)
        }

    suspend fun importAudio(projectId: String, original: Uri, startUs: Long): AudioClip? = withContext(Dispatchers.IO) {
        val uri = keepAccess(projectId, original) ?: return@withContext null
        val info = MediaProbe.probe(context, uri) ?: return@withContext null
        if (!info.hasAudio) return@withContext null
        AudioClip(
            id = newId(), uri = uri.toString(), name = info.displayName.substringBeforeLast('.'),
            sourceDurationUs = info.durationUs, startUs = startUs, trimStartUs = 0, trimEndUs = info.durationUs,
        )
    }

    /** Image used as a layer: kept the same way as clips. */
    suspend fun importImageLayer(projectId: String, original: Uri): Uri? = withContext(Dispatchers.IO) {
        keepAccess(projectId, original)?.takeIf { MediaProbe.probe(context, it)?.isImage == true }
    }

    /** Solid colour background clip, generated as a small image with the canvas aspect. */
    suspend fun createColorClip(projectId: String, color: Long, canvas: CanvasSpec, durationUs: Long = DEFAULT_STILL_US): Clip =
        withContext(Dispatchers.IO) {
            val (w, h) = canvas.sizeForShortSide(180)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(bmp).drawColor(color.toInt())
            val f = File(repo.mediaDir(projectId), "color_${java.lang.Long.toHexString(color)}_${w}x$h.png")
            if (!f.exists()) FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            Clip(
                id = newId(), uri = Uri.fromFile(f).toString(), kind = MediaKind.COLOR,
                sourceDurationUs = Clip.UNBOUNDED, width = w, height = h, trimEndUs = durationUs, color = color,
            )
        }

    /** Grabs the exact frame at [sourceTimeUs] and returns it as a still clip. */
    suspend fun createFreezeFrame(projectId: String, clip: Clip, sourceTimeUs: Long, durationUs: Long = 2_000_000): Clip? {
        val frame = thumbnails.fullFrame(clip.uri, sourceTimeUs, 1920) ?: return null
        return withContext(Dispatchers.IO) {
            val f = File(repo.mediaDir(projectId), "freeze_${newId()}.png")
            FileOutputStream(f).use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
            frame.recycle()
            clip.copy(
                id = newId(), uri = Uri.fromFile(f).toString(), kind = MediaKind.IMAGE,
                sourceDurationUs = Clip.UNBOUNDED, hasAudio = false, trimStartUs = 0, trimEndUs = durationUs,
                speed = 1f, keyframes = emptyList(), transitionIn = null, rotation = clip.rotation,
            )
        }
    }

    private fun keepAccess(projectId: String, uri: Uri): Uri? {
        if (uri.scheme == "file") return uri
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return uri
        } catch (_: SecurityException) {
            // Provider does not offer persistable grants: fall through and copy.
        } catch (_: IllegalArgumentException) {
        }
        return copyIntoProject(projectId, uri)
    }

    private fun copyIntoProject(projectId: String, uri: Uri): Uri? {
        return try {
        val name = MediaProbe.displayName(context, uri).replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "media" }
        val f = File(repo.mediaDir(projectId), "${newId().take(8)}_$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(f).use { input.copyTo(it, 256 * 1024) }
        } ?: return null
        Uri.fromFile(f)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val DEFAULT_STILL_US = 3_000_000L
        fun newId(): String = UUID.randomUUID().toString()
    }
}
