@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.media3.exoplayer.offline.Download
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import com.r34v.r34v.downloads.GalleryDownloadStore
import com.r34v.r34v.playback.DownloadCaches
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class UnifiedDownloadsTest : ComposeUiCase() {
    private fun notifications(f: ComposeFixture) {
        if (Build.VERSION.SDK_INT >= 33 && f.ins.targetContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            f.ins.uiAutomation.grantRuntimePermission(f.ins.targetContext.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun task(f: ComposeFixture, site: String = "ehentai") = f.app.galleryDownloads.task(f.video(site))
    private fun done(f: ComposeFixture, site: String = "ehentai") { ui.waitUntil(20000) { task(f, site)?.state == GalleryDownloadState.COMPLETED } }

    @Test fun wholeBookDownloadsSurviveCacheClearAndOpenWithoutNetwork() = ComposeFixture().use { f ->
        notifications(f); f.launchGallery(); waitText("下载整本")
        ui.onNodeWithText("下载整本").performScrollTo().performClick(); done(f)
        assertEquals(8, task(f)!!.pages); assertTrue(task(f)!!.bytes > 0)
        runBlocking { f.app.galleries.clear(SiteRegistry.EHENTAI) }
        assertEquals(0, f.app.galleries.cacheInfo(SiteRegistry.EHENTAI).first)
        assertTrue(f.app.galleries.isDownloaded(f.video("ehentai")))
        val api = f.galleryCalls.size; val images = f.imageRequests.size
        f.offline = true; f.launchGallery(read = true, offline = true)
        ui.waitUntil(15000) { f.app.library.reading(f.video("ehentai").id, "ehentai")?.page == 1 }
        ui.onNode(hasText(" / 8", substring = true)).performClick(); ui.onNode(hasSetTextAction()).performTextReplacement("8"); clickText("确定")
        ui.waitUntil(15000) { f.app.library.reading(f.video("ehentai").id, "ehentai")?.page == 8 }
        assertEquals("Offline reading must not resolve remote gallery pages", api, f.galleryCalls.size)
        assertEquals("Offline reading must not request image bytes", images, f.imageRequests.size)
        f.shot("21-comic-downloaded-offline")
    }

    @Test fun pausePersistsAndResumeReusesCompletedPages() = ComposeFixture().use { f ->
        notifications(f); f.launch(); waitHome(); f.imageDelayMillis = 350
        runBlocking { f.app.galleryDownloads.add(f.video("ehentai")) }
        ui.waitUntil(15000) { (task(f)?.pages ?: 0) >= 2 }
        val id = task(f)!!.id; runBlocking { f.app.galleryDownloads.pause(id) }
        val saved = task(f)!!; assertEquals(GalleryDownloadState.PAUSED, saved.state)
        val restored = GalleryDownloadStore(f.ins.targetContext, f.app.galleries, f.app.network).task(id)!!
        assertEquals(saved.pages, restored.pages); assertEquals(GalleryDownloadState.PAUSED, restored.state)
        val firstPages = f.imageRequests.filter { it.first == "ehentai" && it.second <= saved.pages }.size
        runBlocking { f.app.galleryDownloads.add(saved.entry) }; done(f)
        assertEquals(firstPages, f.imageRequests.filter { it.first == "ehentai" && it.second <= saved.pages }.size)
        assertEquals(8, task(f)!!.pages)
    }

    @Test fun failedPagesRetryAndRemovingTaskCannotRecreateFiles() = ComposeFixture().use { f ->
        notifications(f); f.launch(); waitHome(); f.brokenPage = 3
        runBlocking { f.app.galleryDownloads.add(f.video("nhentai")) }
        ui.waitUntil(20000) { task(f, "nhentai")?.state == GalleryDownloadState.FAILED }
        assertEquals(2, task(f, "nhentai")!!.pages)
        f.brokenPage = 0; runBlocking { f.app.galleryDownloads.add(f.video("nhentai")) }; done(f, "nhentai")
        assertTrue(f.app.galleries.isDownloaded(f.video("nhentai")))
        runBlocking { f.app.galleryDownloads.remove(task(f, "nhentai")!!.id) }
        assertNull(task(f, "nhentai")); assertFalse(f.app.galleries.isDownloaded(f.video("nhentai")))
        val root = File(f.app.filesDir, "gallery-downloads/hitomi")
        val original = root.walkTopDown().filter { it.isFile }.map { it.absolutePath }.toSet()
        f.imageDelayMillis = 500; runBlocking { f.app.galleryDownloads.add(f.video("hitomi")) }
        ui.waitUntil(15000) { (task(f, "hitomi")?.pages ?: 0) >= 1 }
        runBlocking { f.app.galleryDownloads.remove(task(f, "hitomi")!!.id) }
        assertNull(task(f, "hitomi"))
        assertTrue(root.walkTopDown().filter { it.isFile }.all { it.absolutePath in original })
    }

    @Test fun batchRetryKeepsQualityAndSelectedPagesAndSkipsPausedAndRemovalFailures() = ComposeFixture().use { f ->
        notifications(f)
        val clip = f.video("r34v")
        val clipId = f.app.playback.key(clip, "480p")
        val automaticRefreshAttempted = AtomicBoolean()
        try {
            f.launchPlayer()
            waitText("下载")
            f.videoStatus = 503
            f.detailHook = { automaticRefreshAttempted.set(true); throw IOException("Fixture source unavailable") }
            ui.onNodeWithText("下载").performScrollTo().performClick()
            waitText("选择下载清晰度"); clickText("480p")
            ui.waitUntil(25000) { automaticRefreshAttempted.get() &&
                f.app.playback.downloads().any { it.request.id == clipId && it.state == Download.STATE_FAILED } }

            f.launchDownloads(); waitText("下载中心")
            f.brokenPage = 3
            runBlocking { f.app.galleryDownloads.add(f.video("ehentai"), listOf(1, 3, 5)) }
            ui.waitUntil(25000) { task(f)?.state == GalleryDownloadState.FAILED }
            val savedRequests = f.imageRequests.count { it == ("ehentai" to 1) }
            assertTrue(savedRequests > 0)
            runBlocking {
                val paused = f.app.galleryDownloads.add(f.video("hitomi"))
                f.app.galleryDownloads.pause(paused.id)
            }
            val deleting = f.video("nhentai")
            val deletingId = f.app.galleryDownloads.key(deleting)
            val failedRemoval = GalleryDownload(deletingId, deleting, GalleryDownloadState.FAILED,
                failure = GalleryFailureKind.STORAGE, error = "删除失败，请重试删除：Fixture directory unavailable")
            assertTrue(f.ins.targetContext.getSharedPreferences("gallery_downloads_v1", Context.MODE_PRIVATE).edit()
                .putString(deletingId, f.app.library.gson.toJson(failedRemoval)).commit())
            assertFalse(runBlocking { f.app.galleryDownloads.retryFailed(deletingId) })

            ui.onNode(hasSetTextAction()).performTextReplacement("ehentai")
            waitText("重试失败项 · 1")
            ui.onNodeWithContentDescription("清空下载搜索").performClick()
            waitText("重试失败项 · 2")
            f.brokenPage = 0
            clickText("重试失败项 · 2"); clickText("重试失败项")
            ui.waitUntil(15000) { ui.onAllNodesWithText("已提交 1 项重试", substring = true).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithText("Fixture source unavailable", substring = true).assertIsDisplayed()
            clickText("关闭")
            done(f)
            assertTrue(f.app.playback.downloads().any { it.request.id == clipId && it.state == Download.STATE_FAILED })

            f.videoStatus = 0; f.detailHook = null
            clickText("重试失败项 · 1"); clickText("重试失败项")
            waitText("已提交 1 项重试"); clickText("关闭")
            ui.waitUntil(20000) { f.app.playback.downloads().any { it.request.id == clipId && it.state == Download.STATE_COMPLETED } }
            assertEquals(listOf(1, 3, 5), task(f)!!.completedPages)
            assertTrue(task(f)!!.partial)
            assertEquals(savedRequests, f.imageRequests.count { it == ("ehentai" to 1) })
            assertEquals(GalleryDownloadState.PAUSED, task(f, "hitomi")?.state)
            assertEquals(failedRemoval, task(f, "nhentai"))
            ui.waitUntil(10000) { ui.onAllNodesWithText("重试失败项 · 1").fetchSemanticsNodes().isEmpty() }
        } finally {
            f.ins.runOnMainSync { f.app.playback.remove(clipId) }
        }
    }

    @Test fun unifiedCenterShowsVideoAndIsolatedBooksAcrossSites() = ComposeFixture().use { f ->
        notifications(f); f.launch(); waitHome()
        val owners = listOf("ehentai", "exhentai", "nhentai", "hitomi")
        runBlocking { owners.forEach { f.app.galleryDownloads.add(f.video(it)) } }
        owners.forEach { done(f, it) }
        assertEquals(4, owners.map { task(f, it)!!.id }.toSet().size)
        assertTrue(owners.all { owner -> f.imageRequests.any { it.first == owner } })
        val book = task(f, "ehentai")!!
        runBlocking { f.app.galleryDownloads.remove(book.id) }
        assertTrue(owners.drop(1).all { f.app.galleries.isDownloaded(f.video(it)) })
        // Existing Media3 downloads keep their identifiers and storage; the center reads them directly.
        val clip = f.video("r34v")
        f.launchPlayer(); ui.waitUntil(15000) { ui.onAllNodesWithText("下载").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("下载").performScrollTo().performClick(); waitText("选择下载清晰度"); clickText("720p")
        val clipId = f.app.playback.key(clip, "720p")
        ui.waitUntil(20000) { f.app.playback.downloads().any { it.request.id == clipId && it.state == Download.STATE_COMPLETED } }
        f.app.library.switchSite("exhentai")
        val downloads = f.launchDownloads()
        waitText("下载中心")
        ui.onNodeWithText("全部内容").assertDoesNotExist()
        ui.onNodeWithText("图页缓存").assertDoesNotExist()
        val center = ui.onNodeWithTag("download-center")
        val bookTag = hasTestTag("download-gallery:${task(f, "exhentai")!!.id}")
        center.performScrollToNode(bookTag)
        ui.onNode(bookTag).assertExists()
        center.performScrollToIndex(0)
        // Let the system toast from adding the MP4 leave the comparison frame.
        android.os.SystemClock.sleep(2200)
        f.shot("22-unified-downloads")
        clickText("筛选")
        clickText("全部内容")
        waitText("下载类型")
        clickText("视频")
        clickText("筛选 · 1")
        val clipTag = hasTestTag("download-video:$clipId")
        center.performScrollToNode(clipTag)
        ui.onNode(clipTag).assertIsDisplayed()
        ui.onNode(bookTag).assertDoesNotExist()
        f.shot("23-video-downloads")

        val imageRequests = f.imageRequests.size
        val galleryCalls = f.galleryCalls.size
        f.videoStatus = 503
        f.offline = true
        try {
            repeat(3) {
                ui.onNode(hasAnyAncestor(clipTag) and hasText("离线打开")).assertDoesNotExist()
                ui.onNode(clipTag).performClick()
                waitForDecodedVideo(f)
                back(f)
                // The system Back action can outlive Compose and accessibility becoming idle.
                ui.waitUntil(10000) {
                    var returned = false
                    f.ins.runOnMainSync { returned = downloads.hasWindowFocus() }
                    returned && center.isDisplayed()
                }
                ui.onNodeWithText("清空筛选").assertExists()
                center.performScrollToNode(clipTag)
                ui.onNode(clipTag).assertIsDisplayed()
            }
            center.performScrollToIndex(0)
            clickText("清空筛选")
            ui.onNodeWithText("筛选 · 1").assertDoesNotExist()
            clickText("筛选"); clickText("全部站点"); waitText("下载来源")
            val sheetOption = hasText(SiteRegistry.EXHENTAI.name) and hasClickAction() and !hasTestTag("site-entry-exhentai")
            ui.onNode(sheetOption).performScrollTo().assertIsDisplayed().performClick()
            clickText("筛选 · 1")
            ui.onNode(clipTag).assertDoesNotExist()
            center.performScrollToNode(bookTag)
            ui.onNode(hasAnyAncestor(bookTag) and hasText("离线打开")).assertDoesNotExist()
            ui.onNode(bookTag).performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(galleryCalls, f.galleryCalls.size)
            assertEquals(imageRequests, f.imageRequests.size)
        } finally {
            f.ins.runOnMainSync { f.app.playback.remove(clipId) }
        }
    }

    @Test fun backgroundDownloadAndTimeoutKeepSavedPages() = ComposeFixture().use { f ->
        notifications(f); f.launch(); waitHome(); f.imageDelayMillis = 300
        runBlocking { f.app.galleryDownloads.add(f.video("ehentai")) }
        ui.waitUntil(15000) { (task(f)?.pages ?: 0) >= 1 }
        f.ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
        done(f)
        f.launchDownloads(); waitText("下载中心")
        runBlocking { f.app.galleryDownloads.add(f.video("hitomi")) }
        ui.waitUntil(15000) { (task(f, "hitomi")?.pages ?: 0) >= 1 }
        f.app.galleryDownloads.pauseForTimeout()
        assertEquals(GalleryDownloadState.PAUSED, task(f, "hitomi")!!.state)
        assertTrue(task(f, "hitomi")!!.pages > 0)
        assertTrue(task(f, "hitomi")!!.error.isNotBlank())
        runBlocking { f.app.galleryDownloads.pause(task(f, "hitomi")!!.id); f.app.galleryDownloads.add(f.video("hitomi")) }
        done(f, "hitomi")
    }

    @Test fun selectedPagesStayPartialAndOpenOfflineWithoutJoiningMissingPages() = ComposeFixture().use { f ->
        notifications(f); f.launch(); waitHome()
        runBlocking { f.app.galleryDownloads.add(f.video("ehentai"), listOf(1, 3, 5)) }; done(f)
        val task = task(f)!!
        assertTrue(task.partial); assertEquals(listOf(1, 3, 5), task.completedPages)
        assertFalse(f.app.galleries.isDownloaded(f.video("ehentai")))
        val detail = runBlocking { f.app.galleries.detail(f.video("ehentai"), offlineOnly = true) }
        assertEquals(listOf(1, 3, 5), detail.downloadedPages); assertEquals(listOf(1, 3, 5), detail.requestedPages)
        val calls = f.galleryCalls.size; val images = f.imageRequests.size; f.offline = true
        f.launchGallery(read = false, offline = true)
        waitText("部分保存 · 3 / 8 页 · 仅已存页可离线阅读"); clickText("开始阅读")
        ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(calls, f.galleryCalls.size); assertEquals(images, f.imageRequests.size)
    }

    @Test fun integrityRepairOnlyRedownloadsTheCorruptPage() = ComposeFixture().use { f ->
        notifications(f); f.launch(); waitHome()
        runBlocking { f.app.galleryDownloads.add(f.video("ehentai")) }; done(f)
        val entry = f.video("ehentai")
        fun hash(raw: String) = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        val folder = File(f.app.filesDir, "gallery-downloads/ehentai/${hash(entry.id)}")
        val links = com.google.gson.JsonParser.parseString(File(folder, "links.json").readText()).asJsonObject.getAsJsonObject("links")
        val url = links.get("2").asString
        File(folder, "${hash(url.substringBefore('?'))}.image").writeBytes(byteArrayOf(1, 2, 3))
        val audit = runBlocking { f.app.galleryDownloads.inspect(task(f)!!.id) }
        assertEquals(listOf(2), audit.corruptPages)
        val repairedPage = "ehentai" to 2
        val beforeRepair = f.imageRequests.count { it == repairedPage }
        val untouched = f.imageRequests.count { it.first == "ehentai" && it != repairedPage }
        runBlocking { f.app.galleryDownloads.repair(task(f)!!.id) }; done(f)
        assertTrue(f.app.galleries.isDownloaded(entry))
        assertEquals(beforeRepair + 1, f.imageRequests.count { it == repairedPage })
        assertEquals(untouched, f.imageRequests.filter { it.first == "ehentai" && it.second != 2 }.size)
        assertFalse(task(f)!!.partial)
    }

    @Test fun damagedVideoAndGalleriesMoveToNeedsAttentionAndReturnAfterRedownload() = ComposeFixture().use { f ->
        notifications(f)
        val titlePrefix = "离线修复测试 ${f.nonce}"
        val clip = f.video("r34v").copy(title = "$titlePrefix MP4")
        val clipId = f.app.playback.key(clip, "720p")
        val selected = listOf(1, 3, 5)
        val wholeBook = f.video("ehentai").copy(title = "$titlePrefix 整本")
        val partialBook = f.video("nhentai").copy(title = "$titlePrefix 选页")
        f.launchDownloads(); waitText("下载中心")
        val stream = runBlocking { f.app.repository.detail(clip.url).streams.first { it.label == "720p" } }
        f.ins.runOnMainSync { f.app.playback.add(clip, stream) }
        try {
            ui.waitUntil(20000) { f.app.playback.downloads().any { it.request.id == clipId && it.state == Download.STATE_COMPLETED } }
            runBlocking {
                f.app.galleryDownloads.add(wholeBook)
                f.app.galleryDownloads.add(partialBook, selected)
            }
            done(f); done(f, partialBook.site)

            val field = f.app.playback.javaClass.getDeclaredField("downloadCaches").apply { isAccessible = true }
            val cache = (field.get(f.app.playback) as DownloadCaches).cache()
            RandomAccessFile(checkNotNull(cache.getCachedSpans(clipId).first().file), "rw").use { it.setLength(1) }
            assertFalse(f.app.playback.cached(clipId))
            fun page(entry: Video, index: Int): File {
                val url = checkNotNull(f.app.galleries.cachedPageUrl(entry, index)).substringBefore('?')
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
                return File(f.app.galleries.downloadDirectory(entry), "$hash.image")
            }
            val damagedPage = page(wholeBook, 2)
            val originalLength = damagedPage.length()
            assertTrue(originalLength > 3)
            damagedPage.writeBytes(byteArrayOf(1, 2, 3))
            assertTrue(page(partialBook, 3).delete())

            f.launchDownloads(); waitText("下载中心")
            val center = ui.onNodeWithTag("download-center")
            ui.onNode(hasSetTextAction()).performTextReplacement(titlePrefix)
            closeKeyboard(f)
            val empty = hasText("没有符合条件的下载")
            clickText("已完成"); center.performScrollToNode(empty)
            center.performScrollToIndex(0)
            clickText("需处理")
            val tags = listOf("download-video:$clipId", "download-gallery:${task(f)!!.id}",
                "download-gallery:${task(f, partialBook.site)!!.id}")
            for (tag in tags) {
                center.performScrollToNode(hasTestTag(tag))
                ui.onNode(hasAnyAncestor(hasTestTag(tag)) and hasText("离线文件不完整", substring = true)).assertExists()
                ui.onNode(hasAnyAncestor(hasTestTag(tag)) and hasText("重新下载")).performClick()
            }
            ui.waitUntil(20000) { f.app.playback.cached(clipId) }
            ui.waitUntil(20000) { damagedPage.length() == originalLength && page(partialBook, 3).isFile }
            done(f); done(f, partialBook.site)
            assertEquals(selected, task(f, partialBook.site)?.selectedPages)
            center.performScrollToIndex(0)
            ui.waitUntil(15000) { runCatching { center.performScrollToNode(empty) }.isSuccess }
            center.performScrollToIndex(0)
            clickText("已完成")
            for (tag in tags) center.performScrollToNode(hasTestTag(tag))
        } finally {
            f.ins.runOnMainSync { f.app.playback.remove(clipId) }
        }
    }

    @Test fun downloadsReuseLegacyReaderPagesAndRefreshDamagedBookMetadata() = ComposeFixture().use { f ->
        for (site in listOf(SiteRegistry.COSPLAYTELE, SiteRegistry.EHENTAI)) {
            val entry = f.video(site.id)
            val gallery = runBlocking { f.app.galleries.detail(entry) }
            val url = gallery.pageLinks.first().url
            runBlocking { f.app.galleries.originalPage(site, url, galleryUrl = entry.url) }
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
            val cached = File(f.app.filesDir, "gallery-cache/${site.id}/$hash.page.json")
            val pageJson = com.google.gson.JsonParser.parseString(cached.readText()).asJsonObject
            pageJson.remove("originalUrl")
            pageJson.remove("originalSize")
            cached.writeText(pageJson.toString())
            val downloadFolder = f.app.galleries.downloadDirectory(entry).apply { mkdirs() }
            File(downloadFolder, "book.json").writeText("""{"pageCount":8,"entry":null,"pageLinks":null}""")

            val requests = f.imageRequests.size
            val prepared = runBlocking { f.app.galleries.prepareDownload(entry) }
            assertEquals(entry.id, prepared.entry.id)
            val (image, bytes) = runBlocking { f.app.galleries.downloadPage(entry, 1, url) }
            assertEquals(url, image.pageUrl)
            assertTrue(bytes > 0)
            assertEquals(requests, f.imageRequests.size)
            f.app.galleries.forgetCachedPageLink(entry, 1, url)
            val audit = runBlocking { f.app.galleries.auditDownload(entry) }
            assertEquals(listOf(1), audit.validPages)
            assertEquals(url, f.app.galleries.cachedPageUrl(entry, 1))
        }
    }

    @Test fun queuePriorityAndGroupsSurviveStoreRecreation() = ComposeFixture().use { f ->
        notifications(f); f.launch(); waitHome()
        val first = runBlocking { f.app.galleryDownloads.add(f.video("ehentai")); f.app.galleryDownloads.add(f.video("nhentai")) }
        runBlocking { f.app.galleryDownloads.pause(first.id) }
        val second = task(f, "nhentai")!!
        runBlocking { f.app.galleryDownloads.pause(second.id) }
        f.app.galleryDownloads.prioritize(second.id); f.app.galleryDownloads.setGroup(second.id, "周末")
        val restored = GalleryDownloadStore(f.ins.targetContext, f.app.galleries, f.app.network).task(second.id)!!
        assertTrue(restored.priority > task(f)!!.priority); assertEquals("周末", restored.group)
        f.app.galleryDownloads.move(second.id, 1)
        assertTrue(task(f, "nhentai")!!.priority >= 0)
    }

    @Test fun temporary503RetriesAndPersistent503BecomesClassifiedFailure() = ComposeFixture().use { f ->
        notifications(f); f.launch(); waitHome()
        f.imageHttpStatus = 503; f.imageHttpFailures = 1
        runBlocking { f.app.galleryDownloads.add(f.video("ehentai")) }; done(f)
        assertTrue(f.app.galleries.isDownloaded(f.video("ehentai")))
        f.imageHttpFailures = 99
        runBlocking { f.app.galleryDownloads.add(f.video("nhentai")) }
        ui.waitUntil(25000) { task(f, "nhentai")?.state == GalleryDownloadState.FAILED }
        val failed = task(f, "nhentai")!!
        assertEquals(GalleryFailureKind.NETWORK, failed.failure); assertEquals(GalleryDownloadStage.DOWNLOADING_PAGE, failed.stage)
        assertTrue(failed.error.contains("503"))
    }

    @Test fun longRateLimitWaitPersistsAndManualPauseCancelsIt() = ComposeFixture().use { f ->
        notifications(f); f.launch(); waitHome()
        f.imageHttpStatus = 429; f.imageHttpFailures = 1; f.imageRetryAfter = "120"
        runBlocking { f.app.galleryDownloads.add(f.video("nhentai")) }
        ui.waitUntil(15000) { task(f, "nhentai")?.state == GalleryDownloadState.RETRY_WAIT }
        val waiting = task(f, "nhentai")!!
        assertEquals(GalleryFailureKind.RATE_LIMIT, waiting.failure)
        assertTrue(waiting.retryAt > System.currentTimeMillis() + 60_000)
        runBlocking { f.app.galleryDownloads.pause(waiting.id) }
        assertEquals(GalleryDownloadState.PAUSED, task(f, "nhentai")!!.state); assertEquals(0, task(f, "nhentai")!!.retryAt)
    }

    @Test fun legacyFilesStayInPlaceAndDeletionKeepsOtherSites() = ComposeFixture().use { f ->
        val files = listOf("r34v", "hanime1").associateWith { site -> File(f.app.filesDir, "legacy-download-${f.nonce}-$site.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) } }
        try {
            files.forEach { (site, file) ->
                f.app.library.prefs.edit().putString("$site:video_cache_v1", f.app.library.gson.toJson(listOf(LocalVideo("shared-legacy-id", "$site 旧版下载", file.absolutePath, site = site)))).commit()
            }
            f.launchDownloads(); waitText("下载中心")
            val center = ui.onNodeWithTag("download-center")
            val legacyKeys = files.keys.map { "legacy:$it:shared-legacy-id" }
            ui.waitUntil(15000) {
                val indexForKey = center.fetchSemanticsNode().config.getOrNull(SemanticsProperties.IndexForKey)
                indexForKey != null && legacyKeys.all { indexForKey(it) >= 0 }
            }
            legacyKeys.forEach { key ->
                center.performScrollToKey(key)
                ui.onNodeWithTag("download-$key").assertIsDisplayed()
            }
            assertEquals(files.getValue("r34v").absolutePath, f.app.library.localVideos("r34v").single().path)
            f.app.library.switchSite("iwara")
            f.app.library.removeLocal("shared-legacy-id", "r34v")
            assertFalse(files.getValue("r34v").exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), files.getValue("hanime1").readBytes())
        } finally { files.values.forEach { it.delete() } }
    }
}
