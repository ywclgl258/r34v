@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.media3.common.Player
import androidx.media3.datasource.*
import androidx.media3.datasource.cache.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import com.r34v.r34v.playback.DownloadCaches
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextRegressionUiTest : ComposeUiCase() {
    private fun home() { ui.waitUntil(15000) { ui.onAllNodesWithTag("personal-home").fetchSemanticsNodes().isNotEmpty() } }
    @Test fun savedSearchSourceSurvivesRecreationEvenWhenGeneralSearchSettingsDiffer() = ComposeFixture().use { f ->
        f.app.library.set("cross_search_sites", "r34v")
        val saved = SavedSearch(name = "仅漫画来源", query = "space", sources = listOf("nhentai"), category = "COMIC", languages = mapOf("nhentai" to "CHINESE"))
        f.app.library.personal.saveSearch(saved)
        val activity = f.launch(); home(); clickText(saved.name); waitText("nhentai · 16 项")
        ui.runOnIdle { activity.recreate() }; waitText("nhentai · 16 项")
        assertFalse(f.requests.any { it.first == "r34v" && it.second.kind == FeedKind.SEARCH })
        assertTrue(f.requests.filter { it.second.kind == FeedKind.SEARCH }.all { it.second.language == GalleryLanguage.CHINESE })
    }
    @Test fun scannedVideoWithoutDownloadDatabaseRecordOpensOfflineFromTheHomeRouter() = ComposeFixture().use { f ->
        val video = f.video(); val bytes = f.ins.context.assets.open("test-pattern.mp4").use { it.readBytes() }
        val caches = f.app.playback.javaClass.getDeclaredField("downloadCaches").apply { isAccessible = true }.get(f.app.playback) as DownloadCaches
        val key = f.app.playback.key(video, "720p")
        try {
            runBlocking(Dispatchers.IO) {
                val cache = caches.cache()
                val source = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(DataSource.Factory { ByteArrayDataSource(bytes) })
                CacheWriter(source.createDataSource(), DataSpec.Builder().setUri("https://example.invalid/sample.mp4").setKey(key).setLength(bytes.size.toLong()).build(), null, null).cache()
                cache.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), bytes.size.toLong()))
                f.app.index.putVideo(video, "720p", bytes.size.toLong())
            }
            assertFalse(f.app.playback.downloads().any { it.request.id == key })
            val activity = f.launch(); home(); f.offline = true
            f.ins.runOnMainSync { MediaRouter.open(activity, video) }
            ui.waitUntil(20000) {
                var ready = false
                f.ins.runOnMainSync { val current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull()
                    if (current != null) { val engine = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(current) as? ExoPlayer; ready = current.intent.getStringExtra("cached") == key && engine?.playbackState == Player.STATE_READY }
                }; ready
            }
        } finally {
            f.ins.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().forEach { it.finish() } }
            f.ins.waitForIdleSync(); caches.remove(key); f.app.index.removeVideo(video.site, video.id, "720p")
        }
    }
    @Test fun scannedExHentaiBookOpensOfflineWithoutADownloadTaskOrLogin() = ComposeFixture().use { f ->
        val video = f.video("exhentai")
        try {
            runBlocking {
                val detail = f.app.galleries.prepareDownload(video)
                var bytes = 0L
                detail.pageLinks.forEach { bytes += f.app.galleries.downloadPage(video, it.index, it.url).second }
                f.app.galleries.finishDownload(video, detail.pageCount); f.app.index.putComic(video, bytes)
            }
            assertNull(f.app.galleryDownloads.task(video)); assertFalse(f.app.network.session.hasGallerySession(SiteRegistry.EXHENTAI))
            val activity = f.launch(); home(); val before = f.galleryCalls.size; f.offline = true
            f.ins.runOnMainSync { MediaRouter.open(activity, video) }
            ui.waitUntil(20000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(before, f.galleryCalls.size)
        } finally {
            f.ins.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<GalleryActivity>().forEach { it.finish() } }
            f.ins.waitForIdleSync(); runBlocking { f.app.galleries.removeDownload(video) }; f.app.index.forgetComic(video.site, video.id)
        }
    }
}
