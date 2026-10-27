package com.vanta.studio.domain.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class ProjectCorruptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Encodes and decodes the on-disk project format (JSON). Older formats are migrated step
 * by step before decoding, and unknown keys are ignored so newer builds can add fields
 * without breaking older project files.
 */
object ProjectCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun encode(project: Project): String =
        json.encodeToString(Project.serializer(), project.copy(formatVersion = Project.CURRENT_FORMAT_VERSION))

    fun decode(text: String): Project {
        val root: JsonElement = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw ProjectCorruptException("Invalid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw ProjectCorruptException("Invalid JSON", e)
        }
        if (root !is JsonObject) throw ProjectCorruptException("Project root is not an object")
        val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull ?: 1
        if (version > Project.CURRENT_FORMAT_VERSION) {
            throw ProjectCorruptException("Project was saved by a newer version ($version)")
        }
        val migrated = migrate(root, version)
        val project = try {
            json.decodeFromJsonElement(Project.serializer(), migrated)
        } catch (e: SerializationException) {
            throw ProjectCorruptException("Project structure is invalid", e)
        } catch (e: IllegalArgumentException) {
            throw ProjectCorruptException("Project structure is invalid", e)
        }
        return sanitize(project)
    }

    /** Applies migrations from [fromVersion] up to the current format. */
    fun migrate(root: JsonObject, fromVersion: Int): JsonObject {
        var obj = root
        var v = fromVersion
        while (v < Project.CURRENT_FORMAT_VERSION) {
            obj = when (v) {
                1 -> migrate1to2(obj)
                else -> obj
            }
            v++
        }
        return JsonObject(obj + ("formatVersion" to JsonPrimitive(Project.CURRENT_FORMAT_VERSION)))
    }

    /** Format 1 (development builds) named the clip transition "transition". */
    private fun migrate1to2(obj: JsonObject): JsonObject {
        val clips = obj["clips"] as? JsonArray ?: return obj
        val newClips = JsonArray(clips.map { el ->
            val c = el as? JsonObject ?: return@map el
            val t = c["transition"] ?: return@map c
            JsonObject(c - "transition" + ("transitionIn" to t))
        })
        return JsonObject(obj + ("clips" to newClips))
    }

    /** Repairs values that would break the editor (e.g. edited by hand or truncated). */
    fun sanitize(p: Project): Project = p.copy(
        name = p.name.ifBlank { "Project" },
        canvas = p.canvas.copy(fps = p.canvas.fps.coerceIn(1, 120)),
        clips = p.clips.mapNotNull { c ->
            val speed = if (c.speed.isFinite()) c.speed.coerceIn(0.1f, 8f) else 1f
            val start = c.trimStartUs.coerceAtLeast(0)
            val end = c.trimEndUs.coerceAtMost(c.sourceDurationUs)
            if (end - start < 1_000) null else c.copy(speed = speed, trimStartUs = start, trimEndUs = end)
        },
        audio = p.audio.filter { it.trimEndUs > it.trimStartUs }
            .map { it.copy(lane = it.lane.coerceIn(0, AudioClip.MAX_LANES - 1)) },
        texts = p.texts.filter { it.durationUs > 0 },
        overlays = p.overlays.filter { it.durationUs > 0 },
    )

    /** Reads only what the project list needs, tolerating partially broken files. */
    fun peekName(text: String): String? = try {
        (json.parseToJsonElement(text) as? JsonObject)?.get("name")?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }
}
