package com.vanta.studio

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.vanta.studio.domain.model.CanvasSpec
import com.vanta.studio.domain.model.AspectRatio
import com.vanta.studio.domain.model.Clip
import com.vanta.studio.domain.model.MediaKind
import com.vanta.studio.domain.model.Project
import com.vanta.studio.engine.PreviewEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Plays real video through the real preview engine into a real SurfaceView and reads the pixels
 * back with PixelCopy, so these tests fail if the monitor stays black or the player errors out
 * (the original bug: "Error de vista previa: -9223372036854775807" with a black monitor).
 * The long clip is 146 s, like the video in the bug report.
 */
class PreviewInstrumentedTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val container get() = (ctx.applicationContext as VantaApp).container
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var surface: SurfaceView
    private var engine: PreviewEngine? = null
    private val engines = ArrayList<PreviewEngine>()

    private fun asset(name: String): Uri? = try {
        val f = File(ctx.cacheDir, name)
        inst.context.assets.open(name).use { i -> FileOutputStream(f).use { i.copyTo(it) } }
        Uri.fromFile(f)
    } catch (e: Exception) {
        null
    }

    @Before
    fun openWindow() {
        val ready = CountDownLatch(1)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { a ->
            surface = SurfaceView(a)
            surface.setZOrderOnTop(true)
            surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
                override fun surfaceCreated(holder: android.view.SurfaceHolder) = ready.countDown()
                override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                override fun surfaceDestroyed(holder: android.view.SurfaceHolder) = Unit
            })
            a.addContentView(surface, ViewGroup.LayoutParams(640, 360))
        }
        assertTrue("surface never created", ready.await(20, TimeUnit.SECONDS))
    }

    @After
    fun closeWindow() {
        inst.runOnMainSync { engines.forEach { runCatching { it.release() } } }
        scenario?.close()
    }

    private fun newEngine(): PreviewEngine {
        var e: PreviewEngine? = null
        inst.runOnMainSync {
            e = PreviewEngine(ctx, scope, container.compositionFactory).also { it.attachSurface(surface); engines += it }
        }
        engine = e
        return e!!
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

    private fun project(vararg clips: Clip, canvas: CanvasSpec = CanvasSpec()) =
        Project(id = "prev-${System.nanoTime()}", name = "Preview test", createdAt = 0, modifiedAt = 0, clips = clips.toList(), canvas = canvas)

    private fun feed(e: PreviewEngine, p: Project) = inst.runOnMainSync { e.update(p, 360) }

    private fun <T> waitFor(what: String, timeoutMs: Long = 40_000, cond: () -> T?): T {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            cond()?.let { return it }
            Thread.sleep(50)
        }
        error("timed out waiting for $what (error=${engine?.error?.value}, firstFrame=${engine?.firstFrame?.value}, pos=${engine?.positionUs?.value})")
    }

    private fun waitForPicture(e: PreviewEngine) {
        waitFor("first frame") {
            assertNull("preview error: ${e.error.value}", e.error.value)
            if (e.firstFrame.value) true else null
        }
    }

    private fun grab(): Bitmap {
        val bmp = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        val latch = CountDownLatch(1)
        var result = -1
        PixelCopy.request(surface, bmp, { r -> result = r; latch.countDown() }, Handler(Looper.getMainLooper()))
        assertTrue("PixelCopy timed out", latch.await(10, TimeUnit.SECONDS))
        assertEquals("PixelCopy failed", PixelCopy.SUCCESS, result)
        return bmp
    }

    private data class Stats(val mean: Double, val std: Double)

    private fun stats(b: Bitmap): Stats {
        val v = ArrayList<Double>()
        for (y in 0 until b.height step 6) for (x in 0 until b.width step 6) {
            val c = b.getPixel(x, y)
            v += 0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)
        }
        val m = v.average()
        return Stats(m, Math.sqrt(v.sumOf { (it - m) * (it - m) } / v.size))
    }

    private fun diff(a: Bitmap, b: Bitmap): Double {
        var s = 0.0
        var n = 0
        for (y in 0 until a.height step 6) for (x in 0 until a.width step 6) {
            val ca = a.getPixel(x, y)
            val cb = b.getPixel(x, y)
            s += (abs(android.graphics.Color.red(ca) - android.graphics.Color.red(cb)) +
                abs(android.graphics.Color.green(ca) - android.graphics.Color.green(cb)) +
                abs(android.graphics.Color.blue(ca) - android.graphics.Color.blue(cb))) / 3.0
            n++
        }
        return s / n
    }

    /** Waits until the surface shows a picture (not black) and returns it. */
    private fun waitForVisibleFrame(): Bitmap = waitFor("a visible, non-black frame") {
        val b = grab()
        val s = stats(b)
        if (s.mean > 20 && s.std > 15) b else null
    }

    private fun assertNoError(e: PreviewEngine, where: String) = assertNull("preview error at $where: ${e.error.value}", e.error.value)

    @Test
    fun longVideoShowsFirstFramePlaysPausesSeeksAndSteps() {
        val uri = asset("long.mp4")
        assumeTrue("long.mp4 not provided", uri != null)
        val clip = clipFor(uri!!, "long")
        println("PREVIEW source duration us=${clip.sourceDurationUs}")
        assertTrue("test clip should be about 2:26", clip.sourceDurationUs > 140_000_000)
        val e = newEngine()
        feed(e, project(clip))

        // 1. first frame visible, no error
        waitForPicture(e)
        val first = waitForVisibleFrame()
        println("PREVIEW first frame stats=${stats(first)}")
        assertNoError(e, "first frame")

        // 2. play: position advances, picture keeps changing
        inst.runOnMainSync { e.togglePlay() }
        waitFor("playback to start") { if (e.isPlaying.value) true else null }
        val p0 = e.positionUs.value
        waitFor("position to advance") { if (e.positionUs.value > p0 + 800_000) true else null }
        val playing = waitForVisibleFrame()
        assertTrue("picture did not change while playing: ${diff(first, playing)}", diff(first, playing) > 1.0)
        assertNoError(e, "playing")

        // 3. pause: position holds and the picture stays visible
        inst.runOnMainSync { e.pause() }
        waitFor("pause") { if (!e.isPlaying.value) true else null }
        Thread.sleep(300)
        val pausedAt = e.positionUs.value
        Thread.sleep(700)
        assertTrue("position kept moving after pause", abs(e.positionUs.value - pausedAt) < 100_000)
        val paused = grab()
        assertTrue("picture vanished on pause: ${stats(paused)}", stats(paused).mean > 20)

        // 4. jump to different places, including the last second
        val frames = ArrayList<Bitmap>()
        for (sec in listOf(30L, 100L, 140L)) {
            inst.runOnMainSync { e.seekTo(sec * 1_000_000, immediate = true) }
            waitFor("position $sec s") { if (abs(e.positionUs.value - sec * 1_000_000) < 300_000) true else null }
            Thread.sleep(400)
            frames += waitForVisibleFrame()
            assertNoError(e, "seek $sec")
        }
        assertTrue("frames at 30 s and 100 s identical", diff(frames[0], frames[1]) > 1.0)

        // 5. step forward and back one frame
        val base = e.positionUs.value
        val frame = 1_000_000L / 24
        inst.runOnMainSync { e.seekTo(base + frame, immediate = true) }
        assertEquals(base + frame, e.positionUs.value)
        inst.runOnMainSync { e.seekTo(base, immediate = true) }
        assertEquals(base, e.positionUs.value)

        // 6. invalid positions are normalised, never sent to the player
        inst.runOnMainSync { e.seekTo(-5_000_000, immediate = true) }
        assertEquals(0L, e.positionUs.value)
        inst.runOnMainSync { e.seekTo(10_000_000_000_000L, immediate = true) }
        assertEquals(clip.trimEndUs, e.positionUs.value)
        Thread.sleep(800)
        assertNoError(e, "out-of-range seeks")

        // 7. scrubbing quickly: the last request wins
        val target = 77_000_000L
        inst.runOnMainSync {
            for (i in 1..40) e.seekTo(i * 3_000_000L)
            e.seekTo(target)
        }
        Thread.sleep(1500)
        assertTrue("stale seek overwrote the latest: ${e.positionUs.value}", abs(e.positionUs.value - target) < 400_000)
        waitForVisibleFrame()
        assertNoError(e, "scrubbing")
    }

    @Test
    fun switchingToAVerticalClipKeepsAspectAndTheRightPicture() {
        val a = asset("long.mp4")
        val b = asset("vertical.mp4")
        assumeTrue("assets not provided", a != null && b != null)
        val c1 = clipFor(a!!, "h").copy(trimEndUs = 4_000_000)
        val c2 = clipFor(b!!, "v")
        val e = newEngine()
        feed(e, project(c1, c2))
        waitForPicture(e)
        val horizontal = waitForVisibleFrame()
        // inside the second (vertical) clip
        inst.runOnMainSync { e.seekTo(5_500_000, immediate = true) }
        Thread.sleep(800)
        val vertical = waitForVisibleFrame()
        assertNoError(e, "clip switch")
        assertTrue("same picture for both clips", diff(horizontal, vertical) > 2.0)
        // a vertical video inside a 16:9 canvas is fitted, not stretched: bars at the sides, picture in the middle
        val side = vertical.getPixel(8, vertical.height / 2)
        val centre = vertical.getPixel(vertical.width / 2, vertical.height / 2)
        fun lum(c: Int) = 0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)
        println("PREVIEW vertical side=${lum(side)} centre=${lum(centre)}")
        assertTrue("side bar should be black: ${lum(side)}", lum(side) < 25)
        // and playing across the cut works
        inst.runOnMainSync { e.seekTo(3_000_000, immediate = true) }
        inst.runOnMainSync { e.togglePlay() }
        waitFor("to play across the cut") { if (e.positionUs.value > 4_600_000) true else null }
        assertNoError(e, "playing across clips")
        inst.runOnMainSync { e.pause() }
    }

    @Test
    fun reopeningTheSavedProjectStillPlaysAndTheMediaIsStillThere() {
        val uri = asset("long.mp4")
        assumeTrue("long.mp4 not provided", uri != null)
        // like the importer: the media lives in the project's own folder
        val p0 = project(clipFor(uri!!, "long"))
        val media = File(container.projects.mediaDir(p0.id), "long.mp4")
        File(uri.path!!).copyTo(media, overwrite = true)
        val saved = p0.copy(clips = listOf(p0.clips[0].copy(uri = Uri.fromFile(media).toString())))
        runBlocking { container.projects.save(saved) }

        val e1 = newEngine()
        feed(e1, saved)
        waitForPicture(e1)
        waitForVisibleFrame()
        inst.runOnMainSync { e1.release() }

        // "close and reopen"
        val loaded = runBlocking { container.projects.load(saved.id) }
        assertEquals(saved.clips[0].uri, loaded.clips[0].uri)
        assertTrue("media missing after reopening", File(Uri.parse(loaded.clips[0].uri).path!!).length() > 0)
        val e2 = newEngine()
        feed(e2, loaded)
        waitForPicture(e2)
        waitForVisibleFrame()
        assertNoError(e2, "reopened")
        runBlocking { container.projects.delete(saved.id) }
    }

    @Test
    fun aBrokenSourceShowsAnErrorInsteadOfAnEndlessBlackScreenAndRecovers() {
        val good = asset("long.mp4")
        assumeTrue("long.mp4 not provided", good != null)
        val goodClip = clipFor(good!!, "long")
        val broken = goodClip.copy(id = "gone", uri = Uri.fromFile(File(ctx.cacheDir, "does-not-exist.mp4")).toString())
        val e = newEngine()
        feed(e, project(broken))
        val msg = waitFor("an error to be reported") { e.error.value }
        println("PREVIEW broken source reports: $msg")
        assertTrue("error should not be the unset-time value: $msg", !msg.contains("9223372036854775807"))
        assertTrue(!e.firstFrame.value)
        // fixing the project clears the error and shows the picture
        feed(e, project(goodClip))
        waitForPicture(e)
        waitForVisibleFrame()
        assertNull(e.error.value)
        assertNotNull(e.positionUs.value)
    }
}
