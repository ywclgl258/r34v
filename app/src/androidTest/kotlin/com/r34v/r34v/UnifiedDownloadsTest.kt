package com.r34v.r34v

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import com.r34v.r34v.downloads.GalleryDownloadStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
        ui.waitUntil(20000) { f.app.playback.downloads().any { f.app.playback.metadata(it)?.video?.id == f.video().id } }
        f.app.library.switchSite("exhentai"); f.launchDownloads(); waitText("下载中心")
        ui.onNodeWithText("全部内容").assertExists(); ui.onNodeWithText("图页缓存").assertDoesNotExist()
        ui.onNodeWithTag("download-center").performScrollToNode(hasTestTag("download-gallery:${task(f, "exhentai")!!.id}"))
        ui.onNodeWithTag("download-gallery:${task(f, "exhentai")!!.id}").assertExists()
        ui.onNodeWithTag("download-center").performScrollToIndex(0)
        f.shot("22-unified-downloads")
        ui.onNode(hasText("视频", substring = false) and hasAnyAncestor(hasTestTag("download-center"))).performClick()
        ui.waitUntil(15000) { ui.onAllNodes(hasTestTag("download-video:${f.app.playback.key(clip, "720p")}")).fetchSemanticsNodes().isNotEmpty() }
        f.shot("23-video-downloads")
        runBlocking { f.app.playback.downloads().filter { f.app.playback.metadata(it)?.video?.id == f.video("r34v").id }.forEach { f.app.playback.remove(it.request.id) } }
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
        val untouched = f.imageRequests.filter { it.first == "ehentai" && it.second != 2 }.size
        runBlocking { f.app.galleryDownloads.repair(task(f)!!.id) }; done(f)
        assertTrue(f.app.galleries.isDownloaded(entry))
        assertEquals(1, f.imageRequests.count { it.first == "ehentai" && it.second == 2 })
        assertEquals(untouched, f.imageRequests.filter { it.first == "ehentai" && it.second != 2 }.size)
        assertFalse(task(f)!!.partial)
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
            ui.onNodeWithTag("download-center").performScrollToNode(hasTestTag("download-legacy:r34v:shared-legacy-id"))
            ui.waitUntil(15000) { ui.onAllNodesWithTag("download-legacy:r34v:shared-legacy-id").fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag("download-center").performScrollToNode(hasTestTag("download-legacy:hanime1:shared-legacy-id"))
            ui.onNodeWithTag("download-legacy:hanime1:shared-legacy-id").assertExists()
            assertEquals(files.getValue("r34v").absolutePath, f.app.library.localVideos("r34v").single().path)
            f.app.library.switchSite("iwara")
            f.app.library.removeLocal("shared-legacy-id", "r34v")
            assertFalse(files.getValue("r34v").exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), files.getValue("hanime1").readBytes())
        } finally { files.values.forEach { it.delete() } }
    }
}
