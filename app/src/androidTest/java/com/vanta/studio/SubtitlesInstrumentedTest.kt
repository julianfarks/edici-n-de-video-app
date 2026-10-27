package com.vanta.studio

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.vanta.studio.domain.export.ExportQuality
import com.vanta.studio.domain.export.ExportResolution
import com.vanta.studio.domain.export.ExportSettings
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.subtitles.Srt
import com.vanta.studio.domain.subtitles.SubtitleOps
import com.vanta.studio.engine.ExportState
import com.vanta.studio.engine.asr.SpeechException
import com.vanta.studio.engine.asr.VoskEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * Spanish speech -> recognised text with timings -> correction -> burnt into the exported video.
 * The speech is synthetic (espeak-ng) so the test is repeatable; it exercises the real model,
 * decoder, timeline mapping and exporter.
 */
class SubtitlesInstrumentedTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val container get() = (ctx.applicationContext as VantaApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before
    fun reset() {
        inst.runOnMainSync { container.exporter.reset() }
    }

    private fun asset(name: String): Uri? = try {
        val f = File(ctx.cacheDir, name)
        inst.context.assets.open(name).use { i -> FileOutputStream(f).use { i.copyTo(it) } }
        Uri.fromFile(f)
    } catch (e: Exception) {
        null
    }

    private fun norm(s: String) = SubtitleOps.fold(s).replace(Regex("[^a-z ]"), "")

    private fun export(p: Project, name: String): ExportState.Done {
        val settings = ExportSettings(ExportResolution.P480, 30, ExportQuality.STANDARD, true, name)
        inst.runOnMainSync { container.exporter.start(scope, p, settings) }
        val s = runBlocking { withTimeout(400_000) { container.exporter.state.first { it is ExportState.Done || it is ExportState.Failed } } }
        assertTrue("export failed: $s", s is ExportState.Done)
        return s as ExportState.Done
    }

    /** Mean absolute difference of the bottom third of two frames taken at [us]. */
    private fun bottomDiff(a: Uri, b: Uri, us: Long): Double {
        fun frame(u: Uri): android.graphics.Bitmap {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, u)
                return r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)!!
            } finally {
                r.release()
            }
        }
        val fa = frame(a)
        val fb = frame(b)
        var sum = 0.0
        var n = 0
        for (y in fa.height * 2 / 3 until fa.height step 3) for (x in 0 until fa.width step 3) {
            val ca = fa.getPixel(x, y)
            val cb = fb.getPixel(x, y)
            sum += (abs(Color.red(ca) - Color.red(cb)) + abs(Color.green(ca) - Color.green(cb)) + abs(Color.blue(ca) - Color.blue(cb))) / 3.0
            n++
        }
        return sum / n
    }

    @Test
    fun spanishSpeechBecomesEditableBurntInSubtitles() {
        val video = asset("speech.mp4")
        assumeTrue("speech.mp4 not provided", video != null)
        val model = container.models.forLanguage("es")!!
        val dir = try {
            runBlocking { container.models.install(model) { } }
        } catch (e: SpeechException) {
            assumeTrue("model download not possible here: ${e.message}", false)
            return
        }
        val clip = Clip(
            id = "v", uri = video.toString(), kind = MediaKind.VIDEO, sourceDurationUs = 9_000_000,
            width = 640, height = 360, hasAudio = true, trimEndUs = 9_000_000,
        )
        var p = Project(id = "t", name = "Test", createdAt = 0, modifiedAt = 0, clips = listOf(clip))

        val track = runBlocking { container.subtitles.generate(p, "v", VoskEngine(dir), "es") { } }
        val text = norm(track.cues.joinToString(" ") { it.text })
        println("RECOGNISED: $text")
        println("SRT:\n" + Srt.format(track.cues))
        // Phrase 1 (0.3 s): "hola amigos bienvenidos a mi canal"; phrase 2 (after ~3 s of silence): "hoy vamos a editar un video muy rapido"
        val expected1 = listOf("hola", "amigos", "bienvenidos", "canal")
        val expected2 = listOf("vamos", "editar", "video", "rapido")
        val found1 = expected1.count { it in text.split(' ') }
        val found2 = expected2.count { it in text.split(' ') }
        assertTrue("phrase 1 recognised words $found1/4 in '$text'", found1 >= 2)
        assertTrue("phrase 2 recognised words $found2/4 in '$text'", found2 >= 2)

        // timing: words of phrase 1 early, words of phrase 2 after the pause
        val words = track.cues.flatMap { it.words }
        assertTrue("no measured word timings", words.isNotEmpty())
        words.zipWithNext().forEach { (a, b) -> assertTrue("overlap ${a.text}/${b.text}", b.startUs >= a.startUs) }
        val hola = words.firstOrNull { norm(it.text) == "hola" }
        if (hola != null) assertTrue("'hola' at ${hola.startUs}", hola.startUs < 2_000_000)
        val vamos = words.firstOrNull { norm(it.text) == "vamos" }
        if (vamos != null) assertTrue("'vamos' at ${vamos.startUs}", vamos.startUs > 4_000_000)

        // manual correction of the first cue
        val first = track.cues.minByOrNull { it.startUs }!!
        val fixedTrack = SubtitleOps.update(track, first.id) { SubtitleOps.setText(it, "HOLA AMIGOS") }
        assertEquals("HOLA AMIGOS", fixedTrack.cues.minByOrNull { it.startUs }!!.text)

        // export with and without the subtitles, compare the bottom of the frame
        val withSubs = export(p.copy(subtitles = fixedTrack), "ci-subs").uri
        val without = export(p, "ci-nosubs").uri
        val mid = (first.startUs + first.endUs) / 2
        val gap = 3_200_000L
        val dSub = bottomDiff(withSubs, without, mid)
        val dGap = bottomDiff(withSubs, without, gap)
        println("DIFF at cue=$dSub in the silence=$dGap")
        assertTrue("subtitles not burnt in at ${mid}us: diff $dSub", dSub > 4.0)
        assertTrue("something drawn where there is no cue: diff $dGap", dGap < dSub / 2)
    }
}
