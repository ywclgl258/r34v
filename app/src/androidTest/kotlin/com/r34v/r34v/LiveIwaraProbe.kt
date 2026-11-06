@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.IwaraAdapter
import com.r34v.r34v.data.adapter.IwaraProtocol
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Explicitly selected live probe: general-rated public content, read-only API/media requests. */
@RunWith(AndroidJUnit4::class)
class LiveIwaraProbe {
    @org.junit.Before fun requireExplicitSelection() = requireLiveProbe(javaClass.name)
    @Test fun publicApiAndNativePlayback() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext.applicationContext as R34Application
        val originalSite = app.library.siteId
        val oldQuality = app.library.string("quality", "720p")
        val oldFullscreen = app.library.bool("auto_fullscreen")
        val oldDownload = app.library.bool("auto_download")
        val report = mutableListOf<String>()
        var activity: PlayerActivity? = null
        try {
            app.library.switchSite("iwara")
            app.library.set("quality", "540p"); app.library.set("auto_fullscreen", false); app.library.set("auto_download", false)
            val started = System.currentTimeMillis()
            val data = app.network.json("${SiteRegistry.IWARA.apiBase}/videos?sort=date&rating=general&limit=5&page=0")
            val feed = IwaraProtocol.feed(data, SiteRegistry.IWARA, FeedRequest())
            assertTrue(feed.videos.isNotEmpty())
            report.add("Public general-rated list: ${feed.videos.size} cards, ${System.currentTimeMillis() - started} ms")
            val adapter = IwaraAdapter(SiteRegistry.IWARA, app.network)
            val detail = adapter.detail(feed.videos.first { it.type.isBlank() }.url)
            assertTrue(detail.streams.isNotEmpty())
            report.add("Detail: ${detail.streams.map { it.label }}, ${detail.related.size} related cards")
            val image = detail.video.thumb
            app.network.client.newCall(Request.Builder().url(image).header("Range", "bytes=0-1023").build()).execute().use {
                assertTrue(it.isSuccessful); assertTrue(it.body!!.bytes().isNotEmpty())
                report.add("Thumbnail: HTTP ${it.code}")
            }
            activity = ins.startActivitySync(Intent(ins.targetContext, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("video", app.library.gson.toJson(detail.video))) as PlayerActivity
            val playerField = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }
            val deadline = System.currentTimeMillis() + 45000
            var frames = 0; var position = 0L; var ready = false
            while (System.currentTimeMillis() < deadline && frames < 5) {
                ins.runOnMainSync {
                    val player = playerField.get(activity) as? ExoPlayer
                    ready = player?.playbackState == Player.STATE_READY
                    frames = player?.videoDecoderCounters?.renderedOutputBufferCount ?: 0
                    position = player?.currentPosition ?: 0
                }
                Thread.sleep(150)
            }
            report.add("Media3: ready=$ready, renderedFrames=$frames, positionMs=$position")
            assertTrue("Live Iwara did not render video frames", frames >= 5)
        } finally {
            ins.runOnMainSync { activity?.finish() }; ins.waitForIdleSync()
            app.library.set("quality", oldQuality); app.library.set("auto_fullscreen", oldFullscreen); app.library.set("auto_download", oldDownload)
            app.library.switchSite(originalSite)
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            val dir = if (output == null) ins.targetContext.filesDir else File(output).apply { mkdirs() }
            File(dir, "iwara-live.txt").writeText(report.joinToString("\n"))
        }
    }

    @Test fun publicGalleryApiDownloadAndOfflineDecode() = runBlocking {
        val ins = InstrumentationRegistry.getInstrumentation()
        val app = ins.targetContext.applicationContext as R34Application
        val originalSite = app.library.siteId
        val adapter = IwaraAdapter(SiteRegistry.IWARA, app.network)
        val repository = GalleryRepository(app, app.network, app.storage)
        var entry: Video? = null
        val report = mutableListOf<String>()
        try {
            app.library.switchSite("iwara")
            val data = app.network.json("${SiteRegistry.IWARA.apiBase}/images?sort=date&rating=general&limit=3&page=0")
            val feed = IwaraProtocol.imageFeed(data, SiteRegistry.IWARA, FeedRequest(contentKind = MediaKind.GALLERY))
            check(feed.videos.isNotEmpty()) { "No public general-rated gallery returned" }
            entry = feed.videos.first()
            val detail = adapter.gallery(entry.url)
            check(detail.pageCount > 0)
            repository.prepareDownload(entry)
            repository.downloadPage(entry, 1, detail.pageLinks.first().url)
            val rendered = repository.page(SiteRegistry.IWARA, detail.pageLinks.first().url, entry = entry, offlineOnly = true)
            rendered.bitmap.recycle()
            val audit = repository.auditDownload(entry)
            check(audit.validPages.contains(1))
            report.add("Gallery list: ${feed.videos.size}; readable pages: ${detail.pageCount}; first page downloaded and decoded offline")
        } finally {
            entry?.let { runCatching { repository.removeDownload(it) } }
            app.library.switchSite(originalSite)
            val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            val dir = if (output == null) ins.targetContext.filesDir else File(output).apply { mkdirs() }
            File(dir, "iwara-gallery-live.txt").writeText(report.joinToString("\n"))
        }
        Unit
    }
}
