package com.r34v.r34v

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.core.graphics.scale
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import com.r34v.r34v.data.adapter.SiteAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.system.measureNanoTime

@RunWith(AndroidJUnit4::class)
class GalleryOfflineReadingUiTest : ComposeUiCase() {
    @Test fun selectedPagesSkipGapsInStripAndThumbnails() = ComposeFixture().use { fixture ->
        fixture.savedSelection { video, bytes, requests ->
            val library = fixture.app.library
            library.personal.setReaderPreferences(video, ReaderPreferences(mode = ReaderMode.STRIP.name, start = ReaderStart.LAST))
            library.saveReading(ReadingProgress(video, 9, PAGE_COUNT, "", offset = -120))
            fixture.launchOffline(video, read = false)
            clickText("开始阅读 · 第 3 页")
            waitForPage(3)
            waitText("3 / $PAGE_COUNT")
            ui.onNodeWithTag("reader-page-9").assertDoesNotExist()

            ui.onNodeWithTag("comic-strip").performScrollToIndex(1)
            waitForPage(7)
            waitText("7 / $PAGE_COUNT")
            readerTool("页码书签")
            clickText("添加第 7 页书签")
            assertEquals(7, library.personal.bookmarks(video).single().page)

            clickText("7 / $PAGE_COUNT")
            ui.onNode(hasSetTextAction()).performTextReplacement("9")
            clickText("确定")
            waitText("第 9 页未保存在本机")
            clickText("取消")
            readerTool("页面缩略图总览")
            waitText("页面总览 · 7 / $PAGE_COUNT")
            ui.onNodeWithContentDescription("跳到第 9 页").assertDoesNotExist()
            ui.onNodeWithContentDescription("跳到第 11 页").performClick()
            waitForPage(11)
            waitText("11 / $PAGE_COUNT")

            readerTool("页码书签")
            clickText("跳到第 7 页")
            waitText("7 / $PAGE_COUNT")
            val site = SiteRegistry.byIdOrDefault(video.site)
            val root = fixture.app.filesDir.absolutePath
            runBlocking {
                val url = checkNotNull(fixture.app.galleries.downloadedPageUrl(video, 7, root))
                val thumb = fixture.app.galleries.thumbnail(site, url, 240, entry = video, offlineOnly = true, sourceRoot = root)
                try { assertEquals(240, maxOf(thumb.bitmap.width, thumb.bitmap.height)) } finally { thumb.bitmap.recycle() }
                val exportedSource = fixture.app.galleries.originalPage(site, url, entry = video, offlineOnly = true, sourceRoot = root)
                assertArrayEquals(bytes, exportedSource.bytes)
            }
            fixture.assertNoGalleryRequests(requests)
        }
    }

    @Test fun selectedPagesRestoreHistoryAndPageInBothDirections() = ComposeFixture().use { fixture ->
        fixture.savedSelection { video, _, requests ->
            val library = fixture.app.library
            library.personal.setReaderMode(video.site, ReaderMode.LEFT_TO_RIGHT)
            library.saveReading(ReadingProgress(video, 7, PAGE_COUNT, ""))
            val activity = fixture.launchOffline(video)
            waitForPage(7)
            waitText("7 / $PAGE_COUNT")
            ui.onNodeWithTag("paged-reader").performTouchInput { swipeLeft() }
            waitForPage(11)
            waitText("11 / $PAGE_COUNT")
            readerTool("阅读模式")
            clickText("向右翻页")
            waitForPage(11)
            ui.onNodeWithTag("paged-reader").performTouchInput { swipeLeft() }
            waitForPage(7)
            waitText("7 / $PAGE_COUNT")
            ui.runOnIdle { activity.recreate() }
            ui.waitUntil(15000) {
                ui.runOnIdle {
                    ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .any { it is GalleryActivity && it !== activity }
                }
            }
            waitForPage(7)
            waitText("7 / $PAGE_COUNT")
            assertEquals(7, library.reading(video.id, video.site)?.page)
            fixture.assertNoGalleryRequests(requests)
        }
    }

    @Test fun landscapeSpreadPairsSavedPagesAcrossGaps() = ComposeFixture().use { fixture ->
        fixture.savedSelection { video, _, requests ->
            fixture.app.library.personal.setReaderPreferences(video,
                ReaderPreferences(mode = ReaderMode.SPREAD.name, doubleCover = false))
            fixture.app.library.saveReading(ReadingProgress(video, 7, PAGE_COUNT, ""))
            val activity = fixture.launchOffline(video)
            try {
                ui.runOnIdle { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                ui.waitUntil(10000) { activity.resources.configuration.screenWidthDp > activity.resources.configuration.screenHeightDp }
                waitForPage(3)
                waitForPage(7)
                waitText("7 / $PAGE_COUNT")
                ui.onNodeWithContentDescription("下一页").performClick()
                waitForPage(11)
                waitText("11 / $PAGE_COUNT")
                ui.onNodeWithContentDescription("上一页").performClick()
                waitForPage(3)
                waitForPage(7)
                waitText("3 / $PAGE_COUNT")
                fixture.assertNoGalleryRequests(requests)
            } finally {
                ui.runOnIdle { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

    @Test fun completeShortPagesKeepOldOffsetAndCanReachTheEnd() = ComposeFixture().use { fixture ->
        fixture.shortBook { video, _, requests ->
            val library = fixture.app.library
            library.saveReading(ReadingProgress(video, 11, PAGE_COUNT, "", offset = 100))
            fixture.launchOffline(video)
            waitForPage(11)
            ui.waitUntil(10000) { library.reading(video.id, video.site)?.offset == 100 }
            assertEquals(11, library.reading(video.id, video.site)?.page)
            ui.onNodeWithContentDescription("下一页").performClick()
            waitForPage(12)
            ui.waitUntil(10000) { library.reading(video.id, video.site)?.offset == 0 }
            assertEquals(12, library.reading(video.id, video.site)?.page)
            fixture.assertNoGalleryRequests(requests)
        }
    }

    @Test fun perBookModeAndRestoreDefaultShareTheCurrentPageAndSavedStripOffset() = ComposeFixture().use { fixture ->
        fixture.savedSelection { video, _, requests ->
            val library = fixture.app.library
            library.personal.setReaderPreferences(video, ReaderPreferences(mode = ReaderMode.STRIP.name))
            library.personal.setReaderMode(video.site, ReaderMode.LEFT_TO_RIGHT)
            library.saveReading(ReadingProgress(video, 7, PAGE_COUNT, "", offset = -80))
            fixture.launchOffline(video)
            waitForPage(7)
            readerTool("本书显示设置")
            clickText("阅读模式 · 上下连续")
            clickText("向左翻页")
            waitForPage(7)
            waitText("7 / $PAGE_COUNT")
            readerTool("本书显示设置")
            clickText("恢复来源默认")
            waitForPage(7)
            waitText("7 / $PAGE_COUNT")
            assertEquals(-80, library.reading(video.id, video.site)?.offset)
            assertEquals(7, library.reading(video.id, video.site)?.page)
            readerTool("阅读模式")
            clickText("上下连续")
            waitForPage(7)
            assertEquals(-80, library.reading(video.id, video.site)?.offset)
            fixture.assertNoGalleryRequests(requests)
        }
    }

    @Test fun landscapeVolumeKeysUseTheSameSparseSpreadsAsScreenButtons() = ComposeFixture().use { fixture ->
        fixture.savedSelection { video, _, requests ->
            fixture.app.library.personal.setReaderPreferences(video, ReaderPreferences(mode = ReaderMode.SPREAD.name))
            fixture.app.library.personal.setReadingBehavior(video.site, ReadingBehavior(volumeKeys = true))
            fixture.app.library.saveReading(ReadingProgress(video, 7, PAGE_COUNT, ""))
            val activity = fixture.launchOffline(video)
            try {
                ui.runOnIdle { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                ui.waitUntil(10000) { activity.resources.configuration.screenWidthDp > activity.resources.configuration.screenHeightDp }
                waitForPage(3); waitForPage(7); waitText("7 / $PAGE_COUNT")
                fixture.pressVolume(activity, forward = true)
                waitForPage(11); waitText("11 / $PAGE_COUNT")
                fixture.pressVolume(activity, forward = false)
                waitForPage(3); waitForPage(7); waitText("3 / $PAGE_COUNT")
                ui.onNodeWithContentDescription("上一页").assertIsNotEnabled()
                fixture.assertNoGalleryRequests(requests)
            } finally {
                ui.runOnIdle { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

    @Test fun automaticPagingWaitsForEveryLeafOfTheSpread() = ComposeFixture().use { fixture ->
        fixture.savedSelection { video, _, requests ->
            val library = fixture.app.library
            library.personal.setReaderPreferences(video, ReaderPreferences(mode = ReaderMode.SPREAD.name))
            library.personal.setReadingBehavior(video.site, ReadingBehavior(autoPageSeconds = 1, volumeKeys = true))
            val activity = fixture.launchOffline(video)
            try {
                ui.runOnIdle { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                ui.waitUntil(10000) { activity.resources.configuration.screenWidthDp > activity.resources.configuration.screenHeightDp }
                waitForPage(3); waitText("3 / $PAGE_COUNT")
                readerTool("阅读行为")
                waitText("自动翻页：每 1 秒"); ui.onNodeWithText("自动翻页：每 1 秒").performScrollTo().performClick()
                waitText("每 3 秒，成功显示后推进"); ui.onNodeWithText("每 3 秒，成功显示后推进").performScrollTo().performClick()
                ui.waitUntil(10000) { library.reading(video.id, video.site)?.page == 11 }
                readerTool("阅读行为")
                waitText("暂停自动翻页"); ui.onNodeWithText("暂停自动翻页").performScrollTo().performClick()
                waitForPage(11)
                fixture.assertNoGalleryRequests(requests)
            } finally {
                ui.runOnIdle { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

    @Test fun bookmarksBeforeDetailAndViewingDetailsDoNotCreateOrChangeHistory() = ComposeFixture().use { fixture ->
        val video = fixture.video(SiteRegistry.EHENTAI.id)
        fixture.app.library.saveReading(ReadingProgress(video, 7, PAGE_COUNT, "old-page"))
        fixture.offline = true
        val activity = fixture.launchGallery(read = true)
        waitText("漫画加载失败")
        fixture.ins.runOnMainSync { activity.recreate() }
        waitText("漫画加载失败")
        ui.onNodeWithContentDescription("页码书签").performClick()
        assertEquals("old-page", fixture.app.library.reading(video.id, video.site)?.pageUrl)
        fixture.ins.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<GalleryActivity>().single().finish()
        }
        fixture.offline = false
        fixture.launchGallery(read = false)
        waitText("8 页", substring = true)
        waitText("继续阅读 · 第 7 页")
        assertEquals("old-page", fixture.app.library.reading(video.id, video.site)?.pageUrl)
        fixture.ins.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<GalleryActivity>().single().finish()
        }
        assertEquals("old-page", fixture.app.library.reading(video.id, video.site)?.pageUrl)
    }

    @Test fun offlineQueueStopsWhenSavedPagesAreRemovedBeforeOpening() = ComposeFixture().use { fixture ->
        fixture.savedSelection { video, _, requests ->
            val queue = fixture.app.queue.start("Offline", "", listOf(video), offlineOnly = true)
            fixture.launchOffline(video, queue = queue.id)
            waitText("顺序打开已停止", substring = true)
            assertNull(fixture.app.queue.session(queue.id, video))
            fixture.assertNoGalleryRequests(requests)
        }
    }

    @Test fun selectedPageRejectionShowsStorageMessage() = ComposeFixture().use { fixture ->
        fixture.savedSelection { video, _, requests ->
            val gson = fixture.app.library.gson
            val id = fixture.app.galleryDownloads.key(video)
            val task = GalleryDownload(id, video, state = GalleryDownloadState.QUEUED,
                total = PAGE_COUNT, pages = 1, selectedPages = listOf(3, 7, 9, 11))
            fixture.app.getSharedPreferences("gallery_downloads_v1", android.content.Context.MODE_PRIVATE)
                .edit().putString(id, gson.toJson(task)).commit().also(::assertTrue)
            fixture.launchOffline(video, read = false)
            ui.onNodeWithContentDescription("更多图集操作").performClick()
            clickText("选择页码下载")
            ui.onNode(hasSetTextAction()).performTextReplacement("7")
            clickText("确定")
            waitText("选择页码下载失败")
            waitText("当前图集已有不同的下载范围，请先暂停任务再更改")
            fixture.assertNoGalleryRequests(requests)
        }
    }

    private fun ComposeFixture.pressVolume(activity: GalleryActivity, forward: Boolean) {
        ins.runOnMainSync {
            val key = if (forward) KeyEvent.KEYCODE_VOLUME_UP else KeyEvent.KEYCODE_VOLUME_DOWN
            activity.onKeyDown(key, KeyEvent(KeyEvent.ACTION_DOWN, key))
            activity.onKeyDown(key, KeyEvent(KeyEvent.ACTION_UP, key))
        }
    }

    private fun ComposeFixture.shortBook(test: (Video, List<Int>, Collection<String>) -> Unit) {
        val entry = video(SiteRegistry.EHENTAI.id)
        val root = app.filesDir.absolutePath
        val folder = app.galleries.downloadDirectory(entry, root).apply { check(mkdirs()) }
        try {
            val pages = (1..PAGE_COUNT).map { GalleryPageLink(it, "${entry.url}short-page-$it") }
            val bytes = ByteArrayOutputStream().apply { writeNeutralImage(600, 200, this) }.toByteArray()
            File(folder, "book.json").writeText(app.library.gson.toJson(GalleryDetail(entry, PAGE_COUNT, pages, PAGE_COUNT)))
            File(folder, "complete").writeText(PAGE_COUNT.toString())
            File(folder, "links.json").writeText(app.library.gson.toJson(mapOf("links" to pages.associate { it.index to it.url })))
            for (link in pages) {
                val id = hash(link.url)
                File(folder, "$id.page.json").writeText(app.library.gson.toJson(GalleryImage(link.index, link.url, "${entry.url}compose-image-${link.index}.png", null, null)))
                File(folder, "$id.image").writeBytes(bytes)
            }
            offline = true
            val requests = ConcurrentLinkedQueue<String>()
            val client = app.network.client
            val clientField = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
            val recording = client.newBuilder().addInterceptor(Interceptor { chain ->
                if (chain.request().url.host == "e-hentai.org") {
                    requests += chain.request().url.encodedPath
                    throw java.io.IOException("Unexpected HTTP call in offline gallery test")
                }
                chain.proceed(chain.request())
            }).build()
            clientField.set(app.network, recording)
            try { test(entry, pages.map { it.index }, requests) } finally { clientField.set(app.network, client) }
        } finally { folder.deleteRecursively() }
    }

    @Test fun onlineSplitThumbnailReusesEncodedBytesForFullPage() = ComposeFixture().use { fixture ->
        val site = SiteRegistry.EHENTAI
        val video = fixture.video(site.id)
        val network = fixture.app.network
        val adapter = AdapterRegistry.adapter(site, network)
        val cache = AdapterRegistry::class.java.getDeclaredField("cache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST") val adapters = cache.get(AdapterRegistry) as MutableMap<String, SiteAdapter>
        adapters[site.id] = object : SiteAdapter by adapter {
            override suspend fun galleryImage(url: String) = adapter.galleryImage(url).copy(imageStripCount = 3)
        }
        runBlocking {
            val galleries = fixture.app.galleries
            val url = galleries.detail(video).pageLinks.first().url
            val thumbnail = galleries.thumbnail(site, url, 240, entry = video, offlineOnly = false, sourceRoot = "")
            val color = try {
                assertEquals(240, thumbnail.bitmap.height)
                thumbnail.bitmap.getPixel(80, 25)
            } finally { thumbnail.bitmap.recycle() }
            assertEquals(1, fixture.imageRequests.size)
            val original = galleries.originalPage(site, url, galleryUrl = video.url, entry = video)
            val rendered = BitmapFactory.decodeByteArray(original.bytes, 0, original.bytes.size)
            try {
                assertEquals(900, rendered.height)
                assertEquals(rendered.getPixel(300, 94), color)
            } finally { rendered.recycle() }
            assertTrue(original.cached)
            assertEquals(1, fixture.imageRequests.size)
        }
    }

    @Test fun compareThumbnailAllocationsAndDecodeTime() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as R34Application
        val id = hash("thumbnail-comparison-${System.nanoTime()}")
        val folder = File(app.localLibrary.rootDirectory(), id).apply { check(mkdir()) }
        try {
            val source = File(folder, "0.image")
            source.outputStream().use { writeNeutralImage(4096, 3072, it) }
            File(folder, "book.json").writeText(app.library.gson.toJson(LocalBook(id, "Neutral HD", listOf(source.name), source.length())))
            for (size in listOf(148, 240)) {
                var oldDecodedBytes = 0
                var oldTotalBytes = 0
                var newDecodedBytes = 0
                var newTotalBytes = 0
                var newDecodedEdge = 0
                val oldTimes = mutableListOf<Long>()
                val newTimes = mutableListOf<Long>()
                repeat(6) { iteration ->
                    suspend fun oldPath() = withContext(Dispatchers.IO) {
                        measureNanoTime {
                            val full = app.localLibrary.page(id, 1).bitmap
                            try {
                                val scale = minOf(1f, size.toFloat() / maxOf(full.width, full.height))
                                val resized = full.scale((full.width * scale).toInt().coerceAtLeast(1),
                                    (full.height * scale).toInt().coerceAtLeast(1), true)
                                oldDecodedBytes = full.allocationByteCount
                                oldTotalBytes = oldDecodedBytes + if (resized !== full) resized.allocationByteCount else 0
                                if (resized !== full) resized.recycle()
                            } finally { full.recycle() }
                        }
                    }
                    suspend fun newPath() = withContext(Dispatchers.IO) {
                        measureNanoTime {
                            var sample: Bitmap? = null
                            val resized = app.localLibrary.thumbnail(id, 1, size) {
                                sample = it
                                newDecodedBytes = it.allocationByteCount
                                newDecodedEdge = maxOf(it.width, it.height)
                            }.bitmap
                            try {
                                newTotalBytes = newDecodedBytes + if (resized !== sample) resized.allocationByteCount else 0
                            } finally { resized.recycle() }
                        }
                    }
                    val oldNanos: Long
                    val newNanos: Long
                    if (iteration % 2 == 0) {
                        oldNanos = oldPath(); newNanos = newPath()
                    } else {
                        newNanos = newPath(); oldNanos = oldPath()
                    }
                    if (iteration > 0) { oldTimes += oldNanos; newTimes += newNanos }
                }
                Log.i("GalleryThumbnailCompare", "target=${size}px oldDecodeBytes=$oldDecodedBytes newDecodeBytes=$newDecodedBytes " +
                    "oldBitmapBytes=$oldTotalBytes newBitmapBytes=$newTotalBytes " +
                    "oldMedianMs=${oldTimes.sorted()[2] / 1_000_000.0} newMedianMs=${newTimes.sorted()[2] / 1_000_000.0}")
                assertTrue("Decode output should be close to the requested pixel size", newDecodedEdge in size until size * 2)
                assertTrue("Total allocated bitmap bytes should drop by at least 8×", newTotalBytes * 8 < oldTotalBytes)
                val production = app.localLibrary.thumbnail(id, 1, size)
                try { assertEquals(size, maxOf(production.bitmap.width, production.bitmap.height)) } finally { production.bitmap.recycle() }
            }
        } finally { folder.deleteRecursively() }
        Unit
    }

    private fun ComposeFixture.savedSelection(test: (Video, ByteArray, Collection<String>) -> Unit) {
        val entry = video(SiteRegistry.EHENTAI.id)
        val root = app.filesDir.absolutePath
        val folder = app.galleries.downloadDirectory(entry, root).apply { check(mkdirs()) }
        try {
            val pages = (1..PAGE_COUNT).map { GalleryPageLink(it, "${entry.url}page-$it") }
            val selected = listOf(3, 7, 9, 11)
            val bytes = ByteArrayOutputStream().apply { writeNeutralImage(600, 900, this) }.toByteArray()
            File(folder, "book.json").writeText(app.library.gson.toJson(GalleryDetail(entry, PAGE_COUNT, pages, PAGE_COUNT)))
            File(folder, "selection.json").writeText(app.library.gson.toJson(GalleryRepository.DownloadSelection(selected, selected)))
            val links = selected.associateWith { pages[it - 1].url }
            File(folder, "links.json").writeText(app.library.gson.toJson(mapOf("links" to links)))
            for ((page, url) in links) {
                val id = hash(url)
                val image = GalleryImage(page, url, "${entry.url}compose-image-$page.png", null, null)
                File(folder, "$id.page.json").writeText(app.library.gson.toJson(image))
                File(folder, "$id.image").writeBytes(if (page == 9) byteArrayOf(1, 2, 3) else bytes)
            }
            offline = true
            val requests = ConcurrentLinkedQueue<String>()
            val client = app.network.client
            val clientField = Network::class.java.getDeclaredField("current").apply { isAccessible = true }
            val recording = client.newBuilder().apply {
                interceptors().add(0, Interceptor { chain ->
                    val request = chain.request()
                    if (request.url.host == "e-hentai.org" || request.url.encodedPath.contains("compose-")) {
                        requests.add(request.url.encodedPath)
                        throw java.io.IOException("Unexpected HTTP call in offline gallery test")
                    }
                    chain.proceed(request)
                })
            }.build()
            clientField.set(app.network, recording)
            try { test(entry, bytes, requests) } finally { clientField.set(app.network, client) }
        } finally { folder.deleteRecursively() }
    }

    private fun ComposeFixture.launchOffline(video: Video, read: Boolean = true, queue: String? = null): GalleryActivity =
        ins.startActivitySync(Intent(ins.targetContext, GalleryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("gallery", app.library.gson.toJson(video)).putExtra("offline", true)
            .putExtra("read", read).putExtra("root", app.filesDir.absolutePath)
            .putExtra("queue_session", queue)) as GalleryActivity

    private fun ComposeFixture.assertNoGalleryRequests(requests: Collection<String>) {
        assertTrue("HTTP calls attempted during offline reading: $requests", requests.isEmpty())
        assertTrue("Gallery adapter attempted a request", galleryCalls.isEmpty())
        assertTrue("Image loader attempted a page request", imageRequests.isEmpty())
        assertTrue("Cover loader attempted a request", coverRequests.isEmpty())
    }

    private fun waitForPage(page: Int) {
        ui.waitUntil(15000) {
            ui.onAllNodesWithContentDescription("第 $page 页，双击或双指缩放").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun writeNeutralImage(width: Int, height: Int, output: java.io.OutputStream) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(25, 56, 81))
            repeat(12) { stripe ->
                paint.color = Color.rgb(40 + stripe * 15, 85 + stripe * 10, 160 - stripe * 8)
                val y = stripe * height / 12f
                canvas.drawOval(RectF(width * .08f, y, width * .92f, y + height / 10f), paint)
            }
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output))
        } finally { bitmap.recycle() }
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private companion object { const val PAGE_COUNT = 12 }
}
