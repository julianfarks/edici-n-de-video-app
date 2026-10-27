package com.vanta.studio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.vanta.studio.domain.export.ExportQuality
import com.vanta.studio.domain.export.ExportResolution
import com.vanta.studio.domain.export.ExportSettings
import com.vanta.studio.domain.fx.AutoSyncOptions
import com.vanta.studio.domain.fx.FxAutoSync
import com.vanta.studio.domain.fx.FxOps
import com.vanta.studio.domain.fx.SyncTarget
import com.vanta.studio.domain.model.AudioClip
import com.vanta.studio.domain.model.BeatSource
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.FxType
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.domain.model.Project
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
import kotlin.math.abs

/**
 * End-to-end checks of the light effects and the rhythm analysis on a real device image: the
 * exported file is decoded and its pixels are inspected, so these tests fail if a flash is not
 * actually in the video, or is at the wrong moment.
 */
class AiInstrumentedTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val container get() = (ctx.applicationContext as VantaApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Before
    fun reset() {
        inst.runOnMainSync { container.exporter.reset() }
    }

    private fun solid(name: String, color: Int): Uri {
        val bmp = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(color)
        val f = File(ctx.cacheDir, name)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.fromFile(f)
    }

    private fun asset(name: String): Uri? = try {
        val f = File(ctx.cacheDir, name)
        inst.context.assets.open(name).use { i -> FileOutputStream(f).use { i.copyTo(it) } }
        Uri.fromFile(f)
    } catch (e: Exception) {
        null
    }

    private fun still(uri: Uri, seconds: Long) = Clip(
        id = "i", uri = uri.toString(), kind = MediaKind.IMAGE, sourceDurationUs = Clip.UNBOUNDED,
        width = 640, height = 360, trimEndUs = seconds * 1_000_000,
    )

    private fun project(vararg clips: Clip) = Project(id = "t", name = "Test", createdAt = 0, modifiedAt = 0, clips = clips.toList())

    private fun export(p: Project, name: String): ExportState.Done {
        val settings = ExportSettings(ExportResolution.P480, 30, ExportQuality.STANDARD, true, name)
        inst.runOnMainSync { container.exporter.start(scope, p, settings) }
        val s = runBlocking {
            withTimeout(300_000) { container.exporter.state.first { it is ExportState.Done || it is ExportState.Failed } }
        }
        assertTrue("export failed: $s", s is ExportState.Done)
        return s as ExportState.Done
    }

    /** Mean brightness 0..255 of the frame nearest to [us]. */
    private fun luma(uri: Uri, us: Long): Double {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val f = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST) ?: error("no frame at $us")
            var sum = 0.0
            var n = 0
            for (y in 0 until f.height step 6) for (x in 0 until f.width step 6) {
                val c = f.getPixel(x, y)
                sum += 0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)
                n++
            }
            return sum / n
        } finally {
            r.release()
        }
    }

    @Test
    fun flashesAreInTheExportAtTheRightMoments() {
        var p = project(still(solid("dark.png", Color.rgb(25, 25, 35)), 4))
        p = FxOps.addAt(p, "f1", FxType.FLASH_WHITE, 1_000_000)
        p = FxOps.addAt(p, "f2", FxType.FLASH_WHITE, 2_000_000)
        p = FxOps.moveSyncTo(p, "f2", 3_000_000) // moved by hand after the fact
        val out = export(p, "ci-flash").uri
        val base = luma(out, 500_000)
        val atF1 = luma(out, 1_000_000)
        val oldF2 = luma(out, 2_000_000)
        val atF2 = luma(out, 3_000_000)
        val after = luma(out, 3_700_000)
        println("LUMA base=$base f1=$atF1 oldPlaceOfF2=$oldF2 f2=$atF2 after=$after")
        assertTrue("base should be dark: $base", base < 60)
        assertTrue("flash 1 missing: $atF1 vs $base", atF1 > base + 60)
        assertTrue("flash 2 should have left its old place: $oldF2", oldF2 < base + 25)
        assertTrue("flash 2 missing at its new place: $atF2 vs $base", atF2 > base + 60)
        assertTrue("effect should be gone afterwards: $after", after < base + 25)
    }

    @Test
    fun analysesARealSongFileAndSyncsFlashesToIt() {
        val song = asset("song.m4a")
        assumeTrue("song.m4a not provided", song != null)
        val map = runBlocking { container.beats.analyze(song!!, 0, 8_000_000) { } }
        println("BEATMAP bpm=${map.bpm} conf=${map.tempoConfidence} onsets=${map.onsets.size} beats=${map.beats.size}")
        assertTrue("tempo ${map.bpm}", abs(map.bpm - 120f) < 4f || abs(map.bpm - 60f) < 3f)
        assertTrue("beats ${map.beats.size}", map.beats.size >= 10)
        val kicks = (0 until 16).map { it * 500_000L }
        val hit = kicks.count { k -> map.onsets.any { abs(it.timeUs - k) < 70_000 } }
        assertTrue("onsets matched $hit of ${kicks.size} kicks", hit >= 12)

        val audio = AudioClip("a1", song.toString(), "song", 8_000_000, 0, 0, 8_000_000)
        var p = project(still(solid("dark2.png", Color.rgb(25, 25, 35)), 8)).copy(
            audio = listOf(audio), beat = BeatSource("a1", false, map),
        )
        val events = FxAutoSync.place(p, listOf(FxType.FLASH_WHITE), AutoSyncOptions(target = SyncTarget.STRONG_HITS, hitThreshold = 0.4f, safeMode = true)) { java.util.UUID.randomUUID().toString() }
        assertTrue("flashes placed: ${events.size}", events.size >= 8)
        val onKicks = events.count { e -> kicks.any { abs(it - e.syncUs) < 80_000 } }
        assertTrue("flashes on kicks: $onKicks of ${events.size}", onKicks >= events.size * 0.8)
        p = p.copy(fx = events)

        // move one flash by hand to a point between two beats
        val victim = events.sortedBy { it.syncUs }[5]
        val oldSync = victim.syncUs
        val newSync = oldSync + 250_000
        p = FxOps.moveSyncTo(p, victim.id, newSync)

        val out = export(p, "ci-song").uri
        val dark = luma(out, (oldSync + 125_000).coerceAtLeast(100_000))
        val onMoved = luma(out, newSync)
        val untouched = events.sortedBy { it.syncUs }[3].syncUs
        val onUntouched = luma(out, untouched)
        println("LUMA song dark=$dark moved=$onMoved untouched=$onUntouched")
        assertTrue("moved flash missing: $onMoved vs $dark", onMoved > dark + 35)
        assertTrue("untouched flash missing: $onUntouched vs $dark", onUntouched > dark + 35)
        val oldPlace = luma(out, oldSync)
        assertTrue("moved flash still at old place: $oldPlace vs $dark", oldPlace < dark + 30)
    }
}
