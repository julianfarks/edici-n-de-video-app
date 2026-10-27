package com.vanta.studio.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Loads small frames for the timeline on demand (never whole videos) and keeps them in a
 * memory-bounded cache. Retrievers are opened per request and released immediately so no
 * decoder stays open.
 */
class Thumbnails(private val context: Context) {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(2)
    private val stillLock = Mutex()

    fun cached(key: String): Bitmap? = cache.get(key)

    fun videoKey(uri: String, timeUs: Long, height: Int) = "v|$uri|${timeUs / 100_000}|$height"
    fun stillKey(uri: String, height: Int) = "s|$uri|$height"

    suspend fun videoFrame(uri: String, timeUs: Long, height: Int): Bitmap? {
        val key = videoKey(uri, timeUs, height)
        cache.get(key)?.let { return it }
        return withContext(io) {
            cache.get(key) ?: run {
                val r = MediaMetadataRetriever()
                try {
                    r.setDataSource(context, Uri.parse(uri))
                    val frame = r.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        height * 2,
                        height,
                    )
                    frame?.also { cache.put(key, it) }
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

    suspend fun still(uri: String, height: Int): Bitmap? {
        val key = stillKey(uri, height)
        cache.get(key)?.let { return it }
        return withContext(io) {
            stillLock.withLock {
                cache.get(key) ?: decodeStill(uri, height)?.also { cache.put(key, it) }
            }
        }
    }

    private fun decodeStill(uri: String, height: Int): Bitmap? = try {
        val u = Uri.parse(uri)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= height) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = max(1, sample) }
        context.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, opts) }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    /** A single representative frame, used for project covers and the freeze-frame tool. */
    suspend fun fullFrame(uri: String, timeUs: Long, maxSide: Int): Bitmap? = withContext(io) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, Uri.parse(uri))
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: maxSide
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxSide
            val scale = minOf(1f, maxSide.toFloat() / max(w, h))
            r.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST,
                max(1, (w * scale).toInt()),
                max(1, (h * scale).toInt()),
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
