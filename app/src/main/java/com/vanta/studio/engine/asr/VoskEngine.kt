package com.vanta.studio.engine.asr

import com.vanta.studio.domain.model.SubWord
import com.vanta.studio.engine.audio.DecodedAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Offline speech recognition with Vosk (Kaldi). Runs on the CPU of any phone the library has a
 * native build for (arm64, armv7, x86_64), needs no connection once the model is installed, and
 * returns a start and end time for every recognised word.
 */
class VoskEngine(private val modelDir: File) : SpeechEngine {
    override val id = "vosk"
    override val isCloud = false

    override suspend fun transcribe(audio: DecodedAudio, language: String, onProgress: (Float) -> Unit): Transcript =
        withContext(Dispatchers.Default) {
            require(audio.sampleRate == SpeechEngine.RATE) { "Vosk needs 16 kHz audio" }
            val words = ArrayList<SubWord>()
            LibVosk.setLogLevel(LogLevel.WARNINGS)
            var model: Model? = null
            var rec: Recognizer? = null
            try {
                model = try {
                    Model(modelDir.absolutePath)
                } catch (e: Exception) {
                    throw SpeechException(SpeechException.Kind.NO_MODEL, "The speech model could not be loaded", e)
                }
                rec = Recognizer(model, SpeechEngine.RATE.toFloat())
                rec.setWords(true)
                val pcm = audio.pcm
                val chunk = 4000 // 250 ms
                val bytes = ByteArray(chunk * 2)
                var pos = 0
                while (pos < pcm.size) {
                    currentCoroutineContext().ensureActive()
                    val n = minOf(chunk, pcm.size - pos)
                    for (i in 0 until n) {
                        val v = (pcm[pos + i] * 32767f).toInt().coerceIn(-32768, 32767)
                        bytes[2 * i] = (v and 0xFF).toByte()
                        bytes[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
                    }
                    if (rec.acceptWaveForm(bytes, n * 2)) collect(rec.result, audio.startUs, words)
                    pos += n
                    onProgress(pos.toFloat() / pcm.size)
                }
                collect(rec.finalResult, audio.startUs, words)
                onProgress(1f)
                Transcript(words)
            } finally {
                try { rec?.close() } catch (_: Exception) {}
                try { model?.close() } catch (_: Exception) {}
            }
        }

    private fun collect(json: String, offsetUs: Long, into: MutableList<SubWord>) {
        val arr = try {
            JSONObject(json).optJSONArray("result")
        } catch (e: Exception) {
            null
        } ?: return
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val text = o.optString("word").trim()
            if (text.isEmpty()) continue
            into += SubWord(text, offsetUs + (o.getDouble("start") * 1_000_000).toLong(), offsetUs + (o.getDouble("end") * 1_000_000).toLong())
        }
    }
}
