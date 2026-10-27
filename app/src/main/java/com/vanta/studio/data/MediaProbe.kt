package com.vanta.studio.data

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

data class ProbeResult(
    val isVideo: Boolean,
    val isImage: Boolean,
    val isAudio: Boolean,
    val durationUs: Long,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val displayName: String,
)

/** Reads the basic properties of a media file. Returns null for unreadable or unsupported files. */
object MediaProbe {
    fun probe(context: Context, uri: Uri): ProbeResult? {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val name = displayName(context, uri)
        return when {
            mime.startsWith("image/") -> probeImage(context, uri, name)
            else -> probeAv(context, uri, name, mime)
        }
    }

    fun displayName(context: Context, uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment.orEmpty()
    } catch (e: Exception) {
        uri.lastPathSegment.orEmpty()
    }

    private fun probeImage(context: Context, uri: Uri, name: String): ProbeResult? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            null
        } else {
            ProbeResult(false, true, false, 0, opts.outWidth, opts.outHeight, false, name)
        }
    } catch (e: Exception) {
        null
    }

    private fun probeAv(context: Context, uri: Uri, name: String, mime: String): ProbeResult? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val hasVideo = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (durMs <= 0 || (!hasVideo && !hasAudio)) return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w
                w = h
                h = t
            }
            ProbeResult(
                isVideo = hasVideo,
                isImage = false,
                isAudio = !hasVideo && (hasAudio || mime.startsWith("audio/")),
                durationUs = durMs * 1000,
                width = w,
                height = h,
                hasAudio = hasAudio,
                displayName = name,
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

    /** True when the uri can still be opened (the source file has not disappeared). */
    fun isReadable(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }
}
