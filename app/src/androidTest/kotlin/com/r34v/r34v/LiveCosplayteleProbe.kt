@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class LiveCosplayteleProbe {
    @get:Rule val ui = createEmptyComposeRule()
    @org.junit.Before fun requireExplicitSelection() = requireLiveProbe(javaClass.name)

    private inline fun <reified T> resumed(): T? = ActivityLifecycleMonitorRegistry.getInstance()
        .getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().firstOrNull()

    private fun field(activity: Any?, name: String): Any? {
        val type = activity!!.javaClass
        val field = runCatching { type.getDeclaredField(name) }.getOrElse { type.getDeclaredField("${name}\$delegate") }
        val value = field.apply { isAccessible = true }.get(activity)
        return if (value is androidx.compose.runtime.State<*>) value.value else value
    }

    private fun waitNode(text: String, substring: Boolean = false) {
        ui.waitUntil(45_000) {
            runCatching { ui.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        }
    }

    @Test fun mixedArticlePlaysVideoAndReturnsToReadableGallery() = kotlinx.coroutines.runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext.applicationContext as R34Application
        val oldSite = app.library.siteId
        val oldQuality = app.library.string("quality", "720p")
        val oldDownload = app.library.bool("auto_download")
        val oldFullscreen = app.library.bool("auto_fullscreen")
        val originalClient = app.network.client
        val clientField = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
        val requests = CopyOnWriteArrayList<String>()
        val report = mutableListOf<String>()
        val output = File(app.cacheDir, "cosplaytele-live").apply { mkdirs() }
        var gallery: GalleryActivity? = null
        var player: PlayerActivity? = null
        try {
            app.library.switchSite("cosplaytele")
            app.library.set("auto_download", false)
            app.library.set("auto_fullscreen", false)
            app.library.set("quality", "1920p")
            clientField.set(app.network, originalClient.newBuilder().addInterceptor { chain ->
                val request = chain.request()
                if (request.url.host.endsWith("cossora.stream")) {
                    requests += "${request.url.host}${request.url.encodedPath} | Referer=${request.header("Referer")}"
                }
                chain.proceed(request)
            }.build())
            val entry = Video("lucky-bikini-nebris", "Cosplaytele mixed article",
                "https://cosplaytele.com/lucky-bikini-nebris/", site = "cosplaytele", mediaKind = MediaKind.GALLERY)
            gallery = ins.startActivitySync(Intent(ins.targetContext, GalleryActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("gallery", app.library.gson.toJson(entry))) as GalleryActivity
            waitNode("观看文章内视频")
            val initial = field(gallery, "detail") as GalleryDetail
            assertTrue(initial.pageCount > 0)
            assertTrue(initial.embeddedVideos.isNotEmpty())
            assertTrue(initial.embeddedVideos.all { it.id.startsWith("video-") && it.id != initial.entry.id })
            report += "galleryPages=${initial.pageCount}; embeddedVideos=${initial.embeddedVideos.size}"
            waitNode("浏览", substring = true)
            ui.onAllNodesWithText("浏览", substring = true).onFirst().performScrollTo().performClick()
            ui.waitUntil(45_000) {
                var loaded = false
                ins.runOnMainSync { loaded = (field(gallery, "images") as? Map<*, *>)?.get(1) != null }
                loaded
            }
            val originalPage = field(gallery, "page") as Int
            ui.onAllNodesWithContentDescription("返回").onFirst().performClick()
            waitNode("观看文章内视频")
            ui.onAllNodesWithText("观看文章内视频").onFirst().performScrollTo().performClick()
            ui.waitUntil(15_000) { var found: PlayerActivity? = null; ins.runOnMainSync { found = resumed<PlayerActivity>() }; found != null }
            ins.runOnMainSync { player = resumed<PlayerActivity>() }
            val playerField = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            var ready = false
            var frames = 0
            var width = 0
            var height = 0
            var settled = false
            val deadline = System.currentTimeMillis() + 90_000
            while (System.currentTimeMillis() < deadline && (frames < 24 || !settled)) {
                ins.runOnMainSync {
                    val engine = playerField.get(player) as? ExoPlayer
                    ready = engine?.playbackState == Player.STATE_READY
                    frames = engine?.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                    width = engine?.videoSize?.width ?: 0
                    height = engine?.videoSize?.height ?: 0
                    settled = field(player, "phase") == null && field(player, "status").toString().contains("正在播放") && field(player, "detail") != null
                }
                Thread.sleep(150)
            }
            report += "route=${app.network.routeDescription()}"
            report += "ready=$ready; renderedFrames=$frames; videoSize=${width}x$height; stablePlayback=$settled"
            report += requests.take(6)
            ui.waitForIdle()
            ins.uiAutomation.waitForIdle(1_000, 5_000)
            val screenshot = ins.uiAutomation.takeScreenshot()
            if (screenshot != null) {
                File(output, "video.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
                screenshot.recycle()
            }
            assertTrue("Real HLS did not render stable playback", ready && settled && frames >= 24 && width > 0 && height > 0)
            ins.runOnMainSync { player?.finish() }
            ui.waitUntil(15_000) { var restored = false; ins.runOnMainSync { restored = resumed<GalleryActivity>() === gallery }; restored }
            assertSame(initial, field(gallery, "detail"))
            assertEquals(originalPage, field(gallery, "page"))
            waitNode("观看文章内视频")
            ui.onAllNodesWithText("浏览", substring = true).onFirst().performScrollTo().performClick()
            ui.waitUntil(30_000) {
                var readable = false
                ins.runOnMainSync { readable = (field(gallery, "images") as? Map<*, *>)?.get(originalPage) != null }
                readable
            }
            report += "returnedToSameGallery=true; page=$originalPage; imagesReadableAfterReturn=true"
        } finally {
            ins.runOnMainSync { resumed<PlayerActivity>()?.finish(); resumed<GalleryActivity>()?.finish() }
            clientField.set(app.network, originalClient)
            app.library.set("quality", oldQuality)
            app.library.set("auto_download", oldDownload)
            app.library.set("auto_fullscreen", oldFullscreen)
            app.library.switchSite(oldSite)
            File(output, "report.txt").writeText(report.joinToString("\n"))
        }
    }
}
