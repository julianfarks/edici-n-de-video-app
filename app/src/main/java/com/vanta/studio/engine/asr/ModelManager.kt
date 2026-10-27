package com.vanta.studio.engine.asr

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/** A downloadable offline recognition model. */
data class SpeechModel(
    val id: String,
    /** Language code understood by the UI; the same as the model's language. */
    val language: String,
    val label: String,
    val sizeMb: Int,
    val url: String,
)

/**
 * Downloads and stores Vosk models in the app's private storage. Models are never bundled in the
 * APK (keeps it small) and are only fetched after the user agrees, since they are tens of MB.
 */
class ModelManager(private val context: Context) {
    val catalog: List<SpeechModel> = listOf(
        SpeechModel("vosk-small-es", "es", "Español", 39, "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"),
        SpeechModel("vosk-small-en", "en", "English", 40, "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"),
        SpeechModel("vosk-small-pt", "pt", "Português", 31, "https://alphacephei.com/vosk/models/vosk-model-small-pt-0.3.zip"),
        SpeechModel("vosk-small-fr", "fr", "Français", 41, "https://alphacephei.com/vosk/models/vosk-model-small-fr-0.22.zip"),
        SpeechModel("vosk-small-de", "de", "Deutsch", 45, "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip"),
        SpeechModel("vosk-small-it", "it", "Italiano", 48, "https://alphacephei.com/vosk/models/vosk-model-small-it-0.22.zip"),
    )

    private val root: File get() = File(context.filesDir, "models").apply { mkdirs() }

    fun forLanguage(lang: String): SpeechModel? = catalog.firstOrNull { it.language == lang.lowercase().substringBefore('-') }

    /** Directory of an installed model, or null. */
    fun installed(model: SpeechModel): File? {
        val dir = File(root, model.id)
        return dir.takeIf { File(it, "am").isDirectory || File(it, "conf").isDirectory }
    }

    fun delete(model: SpeechModel) {
        File(root, model.id).deleteRecursively()
    }

    /**
     * Downloads and unpacks [model]. [onProgress] gets 0..1 (download counts for 85 %, unpacking
     * for the rest). On failure nothing half-installed is left behind.
     */
    suspend fun install(model: SpeechModel, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        installed(model)?.let { return@withContext it }
        val tmpZip = File(context.cacheDir, "${model.id}.zip.part")
        val tmpDir = File(root, "${model.id}.part")
        try {
            tmpDir.deleteRecursively()
            download(model.url, tmpZip) { onProgress(it * 0.85f) }
            unzip(tmpZip, tmpDir) { onProgress(0.85f + it * 0.15f) }
            // The archive contains one top-level folder; use it as the model root.
            val inner = tmpDir.listFiles()?.singleOrNull { it.isDirectory } ?: tmpDir
            val dest = File(root, model.id)
            dest.deleteRecursively()
            if (!inner.renameTo(dest)) throw SpeechException(SpeechException.Kind.ENGINE, "Could not install the model")
            tmpDir.deleteRecursively()
            if (installed(model) == null) throw SpeechException(SpeechException.Kind.ENGINE, "The downloaded model is incomplete")
            onProgress(1f)
            dest
        } catch (e: SpeechException) {
            throw e
        } catch (e: java.io.IOException) {
            throw SpeechException(SpeechException.Kind.NETWORK, e.message ?: "Network error", e)
        } finally {
            tmpZip.delete()
            tmpDir.deleteRecursively()
        }
    }

    private suspend fun download(url: String, dest: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw SpeechException(SpeechException.Kind.NETWORK, "Download failed (HTTP ${conn.responseCode})")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private suspend fun unzip(zip: File, destDir: File, onProgress: (Float) -> Unit) {
        destDir.mkdirs()
        val canonicalRoot = destDir.canonicalPath + File.separator
        val total = zip.length().coerceAtLeast(1)
        var read = 0L
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zis.nextEntry ?: break
                val out = File(destDir, entry.name)
                // Zip-slip protection: refuse entries that escape the target directory.
                if (!out.canonicalPath.startsWith(canonicalRoot)) {
                    throw SpeechException(SpeechException.Kind.ENGINE, "Unsafe path in model archive")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { zis.copyTo(it) }
                }
                read += entry.compressedSize.coerceAtLeast(0)
                onProgress((read.toFloat() / total).coerceIn(0f, 1f))
                zis.closeEntry()
            }
        }
    }
}
