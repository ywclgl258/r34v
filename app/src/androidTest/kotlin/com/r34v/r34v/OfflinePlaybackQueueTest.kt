@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Intent
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.test.*
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadProgress
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import com.r34v.r34v.playback.DownloadCaches
import com.r34v.r34v.playback.PlaybackStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Disk-only queue routing, including records restored without a DownloadManager entry. */
@RunWith(AndroidJUnit4::class)
class OfflinePlaybackQueueTest : ComposeUiCase() {
    @Test fun missingCurrentFileStopsTheOfflineQueueWithoutAnyNetwork(): Unit = ComposeFixture().use { f ->
        val first = f.video(index = 70)
        val second = f.video(index = 71)
        val local = addLocalVideo(f, 70)
        val currentKey = writeRestoredCache(f, first, label = "原画")
        writeRestoredCache(f, second, label = "HLS")
        f.app.library.personal.add(first)
        f.app.library.personal.add(local)
        f.app.library.personal.add(second)
        try {
            f.launch(); waitHome(); clickText("片库"); openWatchLater()
            ui.waitForIdle()
            f.offline = true
            f.requests.clear(); f.galleryCalls.clear(); f.coverRequests.clear()
            clickText("仅离线顺序打开")
            waitForDecodedVideo(f)
            val queue = requireNotNull(f.app.queue.snapshot())
            assertTrue(queue.offlineOnly)
            assertEquals(listOf(first, local, second).map { it.entryKey }, queue.entries.map { it.entryKey })
            assertEquals(first.entryKey, queue.current?.entryKey)
            val cache = cache(f).cache(f.app.filesDir.absolutePath)
            cache.getCachedSpans(currentKey).toList().forEach { assertTrue(checkNotNull(it.file).delete()) }
            clickText("下一项 · ${local.title}")
            clickText("打开播放器")
            waitText("无法打开下一项")
            ui.onNodeWithText("「${first.title}」的离线文件已不可用，顺序打开已停止", substring = true).assertExists()
            clickText("关闭")
            waitText("顺序观看已结束")
            ui.onNodeWithText("下一项 · ${local.title}").assertDoesNotExist()
            assertNull(f.app.queue.snapshot())
            assertEquals(0, f.galleryCalls.size)
            assertEquals(0, f.requests.size)
            assertTrue(f.coverRequests.none { it.url.host == first.url.substringAfter("https://").substringBefore('/') })
        } finally {
            cleanup(f, local = local, videos = listOf(first, second))
        }
    }

    @Test fun downloadedGalleryHandsOffToRestoredVideoAndSkipsMissingWork(): Unit = ComposeFixture().use { f ->
        val gallery = f.video("cosplaytele", index = 80)
        val missing = f.video(index = 81)
        val video = f.video(index = 82)
        writeGallery(f, gallery)
        writeRestoredCache(f, video, label = "原始文件")
        f.app.library.personal.add(gallery)
        f.app.library.personal.add(missing)
        f.app.library.personal.add(video)
        try {
            f.offline = true
            f.launch(); clickText("片库"); openWatchLater()
            clickText("仅离线顺序打开")
            ui.waitUntil(15_000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf(gallery.entryKey, video.entryKey), f.app.queue.snapshot()?.entries?.map { it.entryKey })
            ui.onNodeWithContentDescription("片单接续").performClick()
            clickText("下一项 · ${video.title}")
            clickText("打开播放器")
            waitForDecodedVideo(f)
            val current = f.app.queue.snapshot()
            assertEquals(video.entryKey, current?.current?.entryKey)
            assertEquals(1, current?.index)
            assertEquals(0, f.galleryCalls.count { it == "cosplaytele:detail" })
            assertTrue(f.coverRequests.none { it.url.encodedPath.contains("compose-cover-${f.nonce}-82") })
            ui.onNodeWithText("片单已到最后 · 标记完成").performScrollTo().assertIsDisplayed()
        } finally {
            cleanup(f, gallery = gallery, videos = listOf(video))
        }
    }

    @Test fun unavailableNextWorkShowsAnErrorAndStopsWithoutAdvancing(): Unit = ComposeFixture().use { f ->
        val first = f.video(index = 90)
        val next = f.video(index = 91)
        val key = writeRestoredCache(f, first)
        f.app.queue.start("离线测试", "shelf", listOf(first, next), offlineOnly = true)
        try {
            f.ins.startActivitySync(Intent(f.ins.targetContext, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("video", f.app.library.gson.toJson(first))
                .putExtra("cached", key)
                .putExtra("root", f.app.filesDir.absolutePath)
                .putExtra("queue_session", f.app.queue.snapshot()!!.id)) as PlayerActivity
            waitForDecodedVideo(f)
            f.offline = true
            clickText("下一项 · ${next.title}")
            clickText("打开播放器")
            waitText("无法打开下一项")
            ui.onNodeWithText("「${next.title}」的离线文件已不可用，顺序打开已停止", substring = true).assertExists()
            clickText("关闭")
            waitText("顺序观看已结束")
            assertNull(f.app.queue.snapshot())
            assertTrue(f.requests.isEmpty())
        } finally {
            cleanup(f, videos = listOf(first))
        }
    }

    @Test fun libraryRecordKeepsTaskTimeAndCompletionState(): Unit = ComposeFixture().use { f ->
        val video = f.video(index = 100)
        val stream = f.mediaStream("720p")
        val key = f.app.playback.key(video, stream.label)
        val data = f.app.library.gson.toJson(DownloadMeta(video, stream.label, video.site)).toByteArray()
        val request = DownloadRequest.Builder(key, Uri.parse(stream.url)).setData(data).build()
        val downloading = Download(request, Download.STATE_DOWNLOADING, 123_456L, 123_456L,
            25, Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE,
            DownloadProgress().apply { bytesDownloaded = 12 })
        val record = requireNotNull(f.app.playback.libraryRecord(downloading))
        assertEquals(123_456L, record.at)
        assertEquals(LibraryRecord.STATE_PARTIAL, record.state)
        assertEquals(12L, record.bytes)
        val completed = Download(request, Download.STATE_COMPLETED, 987_654L, 987_654L,
            video.url.length.toLong(), Download.STOP_REASON_NONE, Download.FAILURE_REASON_NONE,
            DownloadProgress().apply { bytesDownloaded = video.url.length.toLong() })
        val finished = requireNotNull(f.app.playback.libraryRecord(completed))
        assertEquals(987_654L, finished.at)
        assertEquals(LibraryRecord.STATE_COMPLETED, finished.state)
    }

    private fun cache(f: ComposeFixture): DownloadCaches {
        val field = f.app.playback.javaClass.getDeclaredField("downloadCaches").apply { isAccessible = true }
        return field.get(f.app.playback) as DownloadCaches
    }

    private fun writeRestoredCache(f: ComposeFixture, video: Video, label: String = "720p"): String {
        val key = PlaybackStore.key(video.site, video.id, label)
        val bytes = f.ins.context.assets.open("test-pattern.mp4").use { it.readBytes() }
        val root = f.app.filesDir.absolutePath
        val cache = cache(f).cache(root)
        val spec = DataSpec.Builder().setUri(video.url).setKey(key).setLength(bytes.size.toLong()).build()
        val source = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(DataSource.Factory { ByteArrayDataSource(bytes) })
        CacheWriter(source.createDataSource(), spec, null, null).cache()
        cache.applyContentMetadataMutations(key, ContentMetadataMutations.setContentLength(ContentMetadataMutations(), bytes.size.toLong()))
        f.app.index.putVideo(video, label, bytes.size.toLong(), at = System.currentTimeMillis())
        return key
    }

    private fun addLocalVideo(f: ComposeFixture, index: Int): Video {
        val id = MessageDigest.getInstance("SHA-256").digest("offline-${f.nonce}-$index".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val directory = File(f.app.localLibrary.rootDirectory(), id).apply { check(mkdir()) }
        val file = File(directory, "clip.mp4")
        f.ins.context.assets.open("test-pattern.mp4").use { input -> file.outputStream().use(input::copyTo) }
        File(directory, "book.json").writeText(f.app.library.gson.toJson(LocalBook(id, "本地离线视频", listOf(file.name), file.length(), kind = "video")))
        val video = f.app.localLibrary.videoEntry(requireNotNull(f.app.localLibrary.book(id)))
        f.app.library.addLocalVideo(LocalVideo(video.id, video.title, file.absolutePath, site = video.site), video.site)
        return video
    }

    private fun writeGallery(f: ComposeFixture, video: Video) {
        val entry = video.copy(mediaKind = MediaKind.GALLERY)
        val pages = 2
        val folder = f.app.galleries.downloadDirectory(entry, f.app.filesDir.absolutePath).apply { check(mkdirs()) }
        val links = (1..pages).map { GalleryPageLink(it, "${entry.url}page-$it") }
        File(folder, "book.json").writeText(f.app.library.gson.toJson(GalleryDetail(entry, pages, links, pages)))
        File(folder, "links.json").writeText(f.app.library.gson.toJson(mapOf("links" to links.associate { it.index to it.url })))
        val bytes = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(12, 18, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).drawColor(Color.rgb(70, 90, 120))
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        links.forEach { link ->
            val id = MessageDigest.getInstance("SHA-256").digest(link.url.toByteArray()).joinToString("") { "%02x".format(it) }
            File(folder, "$id.image").writeBytes(bytes)
            File(folder, "$id.page.json").writeText(f.app.library.gson.toJson(GalleryImage(link.index, link.url, "https://r34v.local/page", null, null)))
        }
        File(folder, "complete").writeText(pages.toString())
        f.app.index.putComic(entry, (bytes.size * pages).toLong())
    }

    private fun cleanup(f: ComposeFixture, local: Video? = null, gallery: Video? = null, videos: List<Video>) {
        if (local != null) runBlocking { f.app.localLibrary.remove(local.id) }
        if (gallery != null) runBlocking {
            val entry = gallery.copy(mediaKind = MediaKind.GALLERY)
            f.app.galleries.removeDownload(entry, sourceRoot = f.app.filesDir.absolutePath)
            f.app.index.forgetComic(entry.site, entry.id)
        }
        videos.forEach { video ->
            f.app.playback.remove("${video.site}:${video.id}:720p")
            f.app.playback.remove("${video.site}:${video.id}:原画")
            f.app.playback.remove("${video.site}:${video.id}:HLS")
            f.app.playback.remove("${video.site}:${video.id}:原始文件")
        }
    }

    private fun resumedPlayer(f: ComposeFixture): PlayerActivity =
        androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
            .filterIsInstance<PlayerActivity>().single()
}
