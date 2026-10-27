package com.vanta.studio.engine.assistant

import android.content.Context
import com.vanta.studio.domain.assistant.AssistantExec
import com.vanta.studio.domain.assistant.AssistantOp
import com.vanta.studio.domain.assistant.Interpretation
import com.vanta.studio.domain.assistant.Ops
import com.vanta.studio.domain.fx.FxLibrary
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.timeline.Timeline
import com.vanta.studio.engine.asr.SpeechException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar

/** Counts calls per month against a limit the user sets, so the cloud assistant can never run up a bill by surprise. */
class UsageMeter(context: Context) {
    private val prefs = context.getSharedPreferences("vanta_ai", Context.MODE_PRIVATE)

    private fun key(): String {
        val c = Calendar.getInstance()
        return "llm_calls_%04d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    var limit: Int
        get() = prefs.getInt("llm_limit", 30)
        set(v) = prefs.edit().putInt("llm_limit", v.coerceIn(0, 10_000)).apply()

    val used: Int get() = prefs.getInt(key(), 0)
    val canCall: Boolean get() = used < limit
    fun record() = prefs.edit().putInt(key(), used + 1).apply()
}

/**
 * Optional interpreter backed by any OpenAI-compatible chat service chosen and paid for by the
 * user. Only a short text summary of the project (durations, counts, state flags) and the
 * instruction are sent: no media, no file names. The reply must be JSON naming operations from a
 * fixed list; anything else is dropped, and each operation is validated before it can run.
 */
class LlmInterpreter(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
) {
    /** The exact text that would be sent as project context, shown to the user in the consent dialog. */
    companion object {
        fun summary(project: Project, playheadUs: Long): String {
            val clips = project.clips.mapIndexed { i, c ->
                "clip ${i + 1}: ${"%.1f".format(c.durationUs / 1e6)} s, ${if (c.isStill) "image" else "video"}${if (c.muted) ", muted" else ""}"
            }
            return buildString {
                appendLine("total duration: ${"%.1f".format(Timeline.totalDurationUs(project) / 1e6)} s")
                appendLine("playhead: ${"%.1f".format(playheadUs / 1e6)} s")
                appendLine("aspect: ${project.canvas.aspect.name}")
                clips.take(40).forEach { appendLine(it) }
                appendLine("rhythm analysed: ${project.beat != null}")
                appendLine("subtitles present: ${project.subtitles?.cues?.isNotEmpty() == true}")
            }
        }

        private fun system(): String {
            val fx = FxLibrary.all.joinToString(",") { it.type.name }
            return """
You convert a video-editing request (usually Spanish) into JSON operations for a mobile video editor. Reply with ONLY a JSON object: {"ops":[...],"unclear":["parts you cannot do"]}.
Each op is an object with "op" and only the fields it needs. Times are in seconds on the timeline. "clip" is a 1-based clip number (omit for all clips).
Allowed ops:
- split {timeSec}
- delete_range {timeSec,endSec}
- speed {value 0.1-8, clip?}
- volume {value 0-2, clip?}
- mute {flag, clip?}
- add_text {text, timeSec?, value? (duration seconds)}
- add_fx {name (one of: $fx), timeSec?}
- fx_on_beats {name: comma separated effect names from the list above, value? intensity 0-1}
- aspect {name: "9:16"|"16:9"|"1:1"|"4:5"}
- filter {name: warm|cool|vivid|pastel|punch|teal_orange|blockbuster|night|golden|sepia|film70|instant|vhs|faded|retro80|mono|noir|silver|dreamy|moody|sunset, value? intensity 0-1, clip?}
- subtitle_style {name: CINEMATIC|MINIMAL|HIGHLIGHT|CREATOR|KARAOKE|BOUNCE|PROGRESSIVE|SCALE_SYNC}
- open_tool {name: subtitles|silence|beat|auto_edit} for things that need analysis of the media (transcribing speech, removing silences, analysing music rhythm, cutting to the music)
Never invent other ops. If a request cannot be expressed with these ops (for example identifying objects in the picture or changing what people say), put it in "unclear".
""".trim()
        }
    }

    suspend fun interpret(instruction: String, project: Project, playheadUs: Long): Interpretation = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("max_tokens", 700)
            put("response_format", JSONObject().put("type", "json_object"))
            put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system()))
                    .put(JSONObject().put("role", "user").put("content", "Project:\n${summary(project, playheadUs)}\nRequest: $instruction")),
            )
        }
        val conn = (URL(baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code !in 200..299) {
                throw when (code) {
                    401, 403 -> SpeechException(SpeechException.Kind.AUTH, "The service rejected the key")
                    429 -> SpeechException(SpeechException.Kind.QUOTA, "Rate or quota limit reached")
                    else -> SpeechException(SpeechException.Kind.NETWORK, "Service error $code ${conn.errorStream?.bufferedReader()?.readText().orEmpty().take(160)}")
                }
            }
            val reply = JSONObject(conn.inputStream.bufferedReader().readText())
            val content = reply.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
            parse(content)
        } catch (e: java.io.IOException) {
            throw SpeechException(SpeechException.Kind.NETWORK, e.message ?: "Network error", e)
        } catch (e: org.json.JSONException) {
            throw SpeechException(SpeechException.Kind.ENGINE, "Unexpected reply from the service", e)
        } finally {
            conn.disconnect()
        }
    }

    /** Turns the model's JSON into operations, dropping anything outside the allowed list. */
    internal fun parse(content: String): Interpretation {
        val text = content.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = JSONObject(text)
        val ops = ArrayList<AssistantOp>()
        val unclear = ArrayList<String>()
        root.optJSONArray("unclear")?.let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let { unclear += it } }
        root.optJSONArray("ops")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                fun d(k: String) = if (o.has(k) && !o.isNull(k)) o.optDouble(k).takeIf { !it.isNaN() } else null
                val op = AssistantOp(
                    op = o.optString("op"),
                    timeSec = d("timeSec"), endSec = d("endSec"), value = d("value"),
                    text = o.optString("text").takeIf { o.has("text") && it.isNotEmpty() },
                    name = o.optString("name").takeIf { o.has("name") && it.isNotEmpty() },
                    clip = if (o.has("clip") && !o.isNull("clip")) o.optInt("clip") else null,
                    flag = if (o.has("flag") && !o.isNull("flag")) o.optBoolean("flag") else null,
                )
                if (AssistantExec.validate(op)) ops += op else unclear += "op: ${o.optString("op")}"
            }
        }
        return Interpretation(ops, unclear)
    }
}
