package com.vanta.studio.data

import android.graphics.Bitmap
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.ProjectCodec
import com.vanta.studio.domain.model.ProjectCorruptException
import com.vanta.studio.domain.timeline.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ProjectSummary(
    val id: String,
    val name: String,
    val modifiedAt: Long,
    val durationUs: Long,
    val clipCount: Int,
    val thumbnail: File?,
    val corrupt: Boolean,
)

/**
 * Stores each project in its own folder: project.json (+ .bak of the previous good save),
 * a cover image and a media/ folder for files the app generated or had to copy.
 * Writes are atomic (temp file + rename) so a crash never leaves a half-written project.
 */
class ProjectRepository(private val root: File) {
    private val lock = Mutex()

    init {
        root.mkdirs()
    }

    fun dir(id: String) = File(root, id)
    fun mediaDir(id: String) = File(dir(id), "media").apply { mkdirs() }
    private fun file(id: String) = File(dir(id), "project.json")
    private fun backup(id: String) = File(dir(id), "project.json.bak")
    private fun thumb(id: String) = File(dir(id), "cover.jpg")

    suspend fun list(): List<ProjectSummary> = withContext(Dispatchers.IO) {
        (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
            val id = d.name
            val f = file(id)
            val b = backup(id)
            if (!f.exists() && !b.exists()) return@mapNotNull null
            val thumbFile = thumb(id).takeIf { it.exists() }
            try {
                val p = readWithFallback(id)
                ProjectSummary(id, p.name, p.modifiedAt, Timeline.totalDurationUs(p), p.clips.size, thumbFile, false)
            } catch (e: Exception) {
                val name = runCatching { ProjectCodec.peekName(f.readText()) }.getOrNull() ?: id.take(8)
                ProjectSummary(id, name, d.lastModified(), 0, 0, thumbFile, true)
            }
        }.sortedByDescending { it.modifiedAt }
    }

    suspend fun load(id: String): Project = withContext(Dispatchers.IO) { readWithFallback(id) }

    /** Reads project.json, falling back to the backup of the previous save if it is damaged. */
    private fun readWithFallback(id: String): Project {
        val primary = file(id)
        val firstError = try {
            if (primary.exists()) return ProjectCodec.decode(primary.readText())
            ProjectCorruptException("Missing project file")
        } catch (e: ProjectCorruptException) {
            e
        } catch (e: java.io.IOException) {
            ProjectCorruptException("Cannot read project", e)
        }
        val bak = backup(id)
        if (bak.exists()) {
            try {
                return ProjectCodec.decode(bak.readText())
            } catch (_: Exception) {
            }
        }
        throw firstError
    }

    suspend fun save(project: Project) = withContext(Dispatchers.IO) {
        lock.withLock {
            val d = dir(project.id)
            d.mkdirs()
            val tmp = File(d, "project.json.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(ProjectCodec.encode(project).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            val current = file(project.id)
            val bak = backup(project.id)
            if (current.exists()) {
                bak.delete()
                current.renameTo(bak)
            }
            if (!tmp.renameTo(current)) {
                tmp.copyTo(current, overwrite = true)
                tmp.delete()
            }
        }
    }

    suspend fun saveThumbnail(id: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        val f = thumb(id)
        val tmp = File(f.parentFile, "cover.tmp")
        FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        tmp.renameTo(f)
    }

    fun hasThumbnail(id: String) = thumb(id).exists()

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        lock.withLock { dir(id).deleteRecursively() }
    }

    suspend fun rename(id: String, name: String) {
        val p = load(id)
        save(p.copy(name = name, modifiedAt = System.currentTimeMillis()))
    }

    /** Copies the whole folder; references to the project's own media are rewritten. */
    suspend fun duplicate(id: String, newId: String, newName: String): Project = withContext(Dispatchers.IO) {
        val src = dir(id)
        val dst = dir(newId)
        src.copyRecursively(dst, overwrite = true)
        File(dst, "project.json.bak").delete()
        val text = file(newId).readText()
            .replace(src.absolutePath, dst.absolutePath)
        val now = System.currentTimeMillis()
        val p = ProjectCodec.decode(text).copy(id = newId, name = newName, createdAt = now, modifiedAt = now)
        save(p)
        p
    }
}
