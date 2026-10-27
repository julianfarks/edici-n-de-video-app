package com.vanta.studio.engine.asr

import com.vanta.studio.domain.model.SubWord
import com.vanta.studio.engine.audio.DecodedAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Cloud transcription through any server that implements the OpenAI-style
 * `POST {baseUrl}/audio/transcriptions` (Whisper). The user supplies the URL, model and key;
 * nothing is built in. Audio is sent as 16 kHz mono WAV in chunks, and only after the caller has
 * obtained the user's consent. Word timestamps are requested; if the server only returns
 * segments, those are kept as segments (no word highlighting is claimed).
 */
class CloudEngine(
    private val baseUrl: String,
    private val model: String,
    private val apiKey: String,
) : SpeechEngine {
    override val id = "cloud-whisper"
    override val isCloud = true

    override suspend fun transcribe(audio: DecodedAudio, language: String, onProgress: (Float) -> Unit): Transcript =
        withContext(Dispatchers.IO) {
            val words = ArrayList<SubWord>()
            val segs = ArrayList<Segment>()
            val chunk = CHUNK_SECONDS * SpeechEngine.RATE
            var pos = 0
            while (pos < audio.pcm.size) {
                currentCoroutineContext().ensureActive()
                val n = minOf(chunk, audio.pcm.size - pos)
                val wav = wav(audio.pcm, pos, n)
                val offset = audio.startUs + pos * 1_000_000L / SpeechEngine.RATE
                parse(post(wav, language), offset, words, segs)
                pos += n
                onProgress(pos.toFloat() / audio.pcm.size)
            }
            Transcript(words, if (words.isEmpty()) segs else emptyList())
        }

    private fun post(wav: ByteArray, language: String): String {
        val boundary = "----vanta" + UUID.randomUUID().toString().replace("-", "")
        val conn = (URL(baseUrl.trimEnd('/') + "/audio/transcriptions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 180_000
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            val body = ByteArrayOutputStream()
            fun field(name: String, value: String) {
                body.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("model", model)
            field("response_format", "verbose_json")
            field("timestamp_granularities[]", "word")
            field("timestamp_granularities[]", "segment")
            if (language.isNotBlank()) field("language", language)
            body.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            body.write(wav)
            body.write("\r\n--$boundary--\r\n".toByteArray())
            conn.setFixedLengthStreamingMode(body.size())
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) return conn.inputStream.bufferedReader().readText()
            val err = conn.errorStream?.bufferedReader()?.readText().orEmpty().take(200)
            throw when (code) {
                401, 403 -> SpeechException(SpeechException.Kind.AUTH, "The service rejected the API key")
                429 -> SpeechException(SpeechException.Kind.QUOTA, "Rate or quota limit reached")
                else -> SpeechException(SpeechException.Kind.NETWORK, "Service error $code $err")
            }
        } catch (e: java.io.IOException) {
            throw SpeechException(SpeechException.Kind.NETWORK, e.message ?: "Network error", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String, offsetUs: Long, words: MutableList<SubWord>, segs: MutableList<Segment>) {
        val o = try { JSONObject(json) } catch (e: Exception) {
            throw SpeechException(SpeechException.Kind.ENGINE, "Unexpected response from the service", e)
        }
        o.optJSONArray("words")?.let { a ->
            for (i in 0 until a.length()) {
                val w = a.getJSONObject(i)
                val t = w.optString("word").trim()
                if (t.isNotEmpty()) words += SubWord(t, offsetUs + (w.getDouble("start") * 1e6).toLong(), offsetUs + (w.getDouble("end") * 1e6).toLong())
            }
        }
        o.optJSONArray("segments")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.getJSONObject(i)
                val t = s.optString("text").trim()
                if (t.isNotEmpty()) segs += Segment(t, offsetUs + (s.getDouble("start") * 1e6).toLong(), offsetUs + (s.getDouble("end") * 1e6).toLong())
            }
        }
    }

    private fun wav(pcm: FloatArray, from: Int, n: Int): ByteArray {
        val data = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (pcm[from + i] * 32767f).toInt().coerceIn(-32768, 32767)
            data[2 * i] = (v and 0xFF).toByte(); data[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
        }
        val out = ByteArrayOutputStream(44 + data.size)
        fun i32(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF); out.write(v shr 16 and 0xFF); out.write(v shr 24 and 0xFF) }
        fun i16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
        out.write("RIFF".toByteArray()); i32(36 + data.size); out.write("WAVEfmt ".toByteArray())
        i32(16); i16(1); i16(1); i32(SpeechEngine.RATE); i32(SpeechEngine.RATE * 2); i16(2); i16(16)
        out.write("data".toByteArray()); i32(data.size); out.write(data)
        return out.toByteArray()
    }

    companion object {
        /** ~5 min of 16 kHz mono 16-bit ≈ 9.6 MB, well under common 25 MB upload limits. */
        const val CHUNK_SECONDS = 300
    }
}
