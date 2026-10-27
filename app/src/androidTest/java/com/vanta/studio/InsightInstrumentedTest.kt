package com.vanta.studio

import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.vanta.studio.domain.enhance.Enhance
import com.vanta.studio.domain.enhance.Highlights
import com.vanta.studio.domain.enhance.RecKind
import com.vanta.studio.domain.export.ExportQuality
import com.vanta.studio.domain.export.ExportResolution
import com.vanta.studio.domain.export.ExportSettings
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.timeline.Ripple
import com.vanta.studio.engine.ExportState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** "Improve this video" and "Best moments" on real decoded pictures and audio, then checked in the exported file. */
class InsightInstrumentedTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val container get() = (ctx.applicationContext as VantaApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private fun asset(name: String): Uri? = try {
        val f = File(ctx.cacheDir, name)
        inst.context.assets.open(name).use { i -> FileOutputStream(f).use { i.copyTo(it) } }
        Uri.fromFile(f)
    } catch (e: Exception) {
        null
    }

    private fun clipFor(uri: Uri, id: String): Clip {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1000
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt()
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toInt()
            return Clip(id = id, uri = uri.toString(), kind = MediaKind.VIDEO, sourceDurationUs = dur, width = w, height = h, hasAudio = true, trimEndUs = dur)
        } finally {
            r.release()
        }
    }

    private fun project(vararg c: Clip) = Project(id = "t", name = "T", createdAt = 0, modifiedAt = 0, clips = c.toList())

    private fun export(p: Project, name: String): Uri {
        val s = ExportSettings(ExportResolution.P480, 30, ExportQuality.STANDARD, true, name)
        inst.runOnMainSync { container.exporter.reset(); container.exporter.start(scope, p, s) }
        val st = runBlocking { withTimeout(300_000) { container.exporter.state.first { it is ExportState.Done || it is ExportState.Failed } } }
        assertTrue("export failed: $st", st is ExportState.Done)
        return (st as ExportState.Done).uri
    }

    private fun luma(uri: Uri, us: Long): Double {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val f = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)!!
            var s = 0.0
            var n = 0
            for (y in 0 until f.height step 6) for (x in 0 until f.width step 6) {
                val c = f.getPixel(x, y)
                s += 0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)
                n++
            }
            return s / n
        } finally {
            r.release()
        }
    }

    @Test
    fun aDarkQuietVideoGetsRealSuggestionsAndTheExportIsBrighter() {
        val uri = asset("dark.mp4")
        assumeTrue("dark.mp4 not provided", uri != null)
        val clip = clipFor(uri!!, "dark")
        val p = project(clip)
        val report = runBlocking { container.analysis.enhance(p, "dark") { } }
        println("INSIGHT frame=${report.frame} audio=${report.audio} frames=${report.framesUsed}")
        val recs = Enhance.recommend(report.frame, report.audio, clip, p.canvas.aspect)
        println("INSIGHT recs=${recs.map { it.kind }}")
        assertTrue("dark picture not detected: ${report.frame}", recs.any { it.kind == RecKind.BRIGHTEN })
        assertTrue("quiet audio not detected: ${report.audio}", recs.any { it.kind == RecKind.VOLUME_UP })
        val improved = Enhance.apply(p, "dark", recs.filter { it.actionable })
        assertTrue(improved.clips[0].adjust.exposure > 0f)
        val before = luma(export(p, "ci-dark-before"), 1_000_000)
        val after = luma(export(improved, "ci-dark-after"), 1_000_000)
        println("INSIGHT luma before=$before after=$after")
        assertTrue("export is not brighter: $before -> $after", after > before + 8)
    }

    @Test
    fun theLoudMovingStretchIsFoundInALongQuietClip() {
        val uri = asset("moments.mp4")
        assumeTrue("moments.mp4 not provided", uri != null)
        val clip = clipFor(uri!!, "m")
        val p = project(clip)
        val data = runBlocking { container.analysis.highlights(p, "m") { } }
        println("INSIGHT steps=${data.features.steps} stepUs=${data.features.stepUs} speechSeparable=${data.speechSeparable}")
        val picks = Highlights.pick(data.features, 15_000_000)
        println("INSIGHT picks=" + picks.joinToString { "${it.startUs / 1_000_000}-${it.endUs / 1_000_000}s ${it.why}" })
        // the burst is 20-26 s of a ~60 s clip
        assertTrue("no pick on the 20-26 s burst: $picks", picks.any { it.startUs < 26_000_000 && it.endUs > 20_000_000 })
        assertTrue("picked far too much of a quiet clip", picks.sumOf { it.durationUs } <= 24_000_000)
        // applying keeps the burst and shortens the clip, in one undo step worth of change
        val keep = picks
        val spans = Highlights.complement(keep, data.totalUs)
        val cut = Ripple.removeSpans(p, spans) { "id${System.nanoTime()}" }
        val before = p.clips.sumOf { it.durationUs }
        val after = cut.clips.sumOf { it.durationUs }
        println("INSIGHT duration before=$before after=$after")
        assertTrue("clip not shortened", after < before * 0.6)
        assertTrue("montage shorter than the picks allow", after >= keep.sumOf { it.durationUs } - 200_000)
        assertEquals(true, cut.clips.all { it.uri == clip.uri })
    }
}
