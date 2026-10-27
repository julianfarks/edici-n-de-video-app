package com.vanta.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.vanta.studio.domain.export.ExportQuality
import com.vanta.studio.domain.export.ExportResolution
import com.vanta.studio.domain.export.ExportSettings
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.FilterRef
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.domain.model.Project
import com.vanta.studio.domain.model.TextLayer
import com.vanta.studio.domain.timeline.Timeline
import com.vanta.studio.engine.ExportError
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
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/** Exports real projects through the real Transformer pipeline and inspects the resulting file. */
class ExportInstrumentedTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val container get() = (ctx.applicationContext as VantaApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before
    fun resetExporter() {
        inst.runOnMainSync { container.exporter.reset() }
    }

    private fun makeImage(name: String): Uri {
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(40, 90, 200))
        c.drawCircle(320f, 180f, 90f, Paint().apply { color = Color.rgb(250, 200, 40) })
        val f = File(ctx.cacheDir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    private fun sampleVideo(): Uri? = try {
        val f = File(ctx.cacheDir, "sample.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("sample.mp4").use { i ->
            FileOutputStream(f).use { i.copyTo(it) }
        }
        Uri.fromFile(f)
    } catch (e: Exception) {
        null
    }

    private fun project(vararg clips: Clip, texts: List<TextLayer> = emptyList()) =
        Project(id = "t", name = "Test", createdAt = 0, modifiedAt = 0, clips = clips.toList(), texts = texts)

    private fun export(p: Project, settings: ExportSettings): ExportState {
        inst.runOnMainSync { container.exporter.start(scope, p, settings) }
        return runBlocking {
            withTimeout(240_000) { container.exporter.state.first { it is ExportState.Done || it is ExportState.Failed } }
        }
    }

    private fun image(uri: Uri, seconds: Long) = Clip(
        id = "i", uri = uri.toString(), kind = MediaKind.IMAGE, sourceDurationUs = Clip.UNBOUNDED,
        width = 640, height = 360, trimEndUs = seconds * 1_000_000,
    )

    @Test
    fun exportsStillWithFilterAndText() {
        val clip = image(makeImage("still.png"), 2).copy(filter = FilterRef("vivid", 1f))
        val text = TextLayer(id = "t1", text = "Vanta", startUs = 0, durationUs = 2_000_000)
        val s = export(project(clip, texts = listOf(text)), ExportSettings(ExportResolution.P480, 30, ExportQuality.LOW, false, "ci-still"))
        assertTrue("expected Done but was $s", s is ExportState.Done)
        s as ExportState.Done
        assertTrue("duration ${s.durationMs}", s.durationMs in 1500..2800)
        assertEquals(480, minOf(s.width, s.height))
        assertTrue(s.sizeBytes > 1000)
        verifyPlayable(s.uri)
    }

    @Test
    fun exportsVideoSplitWithSpeedChange() {
        val uri = sampleVideo()
        assumeTrue("sample.mp4 not provided", uri != null)
        val whole = Clip(
            id = "v", uri = uri.toString(), kind = MediaKind.VIDEO, sourceDurationUs = 3_000_000,
            width = 640, height = 360, hasAudio = true, trimEndUs = 3_000_000,
        )
        var p = project(whole)
        p = Timeline.split(p, 1_500_000, "v2")!!.first
        p = Timeline.setSpeed(p, "v2", 2f) // second half: 1.5 s source -> 0.75 s
        val expectedMs = Timeline.totalDurationUs(p) / 1000 // 2250
        val s = export(p, ExportSettings(ExportResolution.P480, 30, ExportQuality.STANDARD, true, "ci-video"))
        assertTrue("expected Done but was $s", s is ExportState.Done)
        s as ExportState.Done
        assertTrue("duration ${s.durationMs} vs $expectedMs", kotlin.math.abs(s.durationMs - expectedMs) < 700)
        assertTrue("audio missing", s.hasAudio)
        verifyPlayable(s.uri)
    }

    @Test
    fun missingSourceFailsCleanly() {
        val p = project(image(Uri.fromFile(File(ctx.cacheDir, "does-not-exist.png")), 2))
        val s = export(p, ExportSettings(ExportResolution.P480, 30, ExportQuality.LOW, false, "ci-missing"))
        assertTrue("expected Failed but was $s", s is ExportState.Failed)
        assertEquals(ExportError.MISSING_MEDIA, (s as ExportState.Failed).error)
    }

    @Test
    fun emptyProjectFailsCleanly() {
        val s = export(project(), ExportSettings())
        assertEquals(ExportError.NO_CLIPS, (s as ExportState.Failed).error)
    }

    private fun verifyPlayable(uri: Uri) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            assertEquals("yes", r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
            val frame = r.getFrameAtTime(500_000)
            assertTrue("no decodable frame", frame != null && frame.width > 0)
        } finally {
            r.release()
        }
    }
}
