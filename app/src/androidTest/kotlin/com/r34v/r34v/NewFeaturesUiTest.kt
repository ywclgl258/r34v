@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import com.r34v.r34v.ui.CrossSearchState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class NewFeaturesUiTest : ComposeUiCase() {
    private fun engine(activity: PlayerActivity) = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as? ExoPlayer
    private fun ready(f: ComposeFixture, activity: PlayerActivity) { ui.waitUntil(20000) { var result = false; f.ins.runOnMainSync { result = engine(activity)?.playbackState == Player.STATE_READY }; result } }

    @Test fun crossSiteSearchShowsSuccessfulResultsWhenAnotherSiteFails() = ComposeFixture().use { f ->
        f.app.library.set("cross_search_sites", "r34v,hanime1")
        f.feedOverride = { site, request -> if (request.kind != FeedKind.SEARCH) null else {
            if (site == "hanime1") throw IOException("这个来源暂时不可用")
            FeedPage(listOf(f.video(site).copy(title = "跨站搜索成功结果")), request.page, 2, "search")
        } }
        f.launch(); waitHome(); clickText("搜索"); clickText("多个站点")
        ui.onNode(hasSetTextAction()).performTextInput("test query"); ui.onNodeWithContentDescription("提交搜索").performClick()
        waitText("跨站搜索成功结果")
        ui.onNodeWithTag("cross-search-results").performScrollToNode(hasText("这个来源暂时不可用")); waitText("这个来源暂时不可用")
        assertEquals("r34v", f.app.library.siteId)
        assertTrue(f.requests.any { it.first == "hanime1" && it.second.kind == FeedKind.SEARCH })
        ui.onNodeWithTag("cross-search-results").performScrollToNode(hasText("加载 Rule34Video 下一页")); clickText("加载 Rule34Video 下一页")
        ui.waitUntil(10000) { f.requests.any { it.first == "r34v" && it.second.kind == FeedKind.SEARCH && it.second.page == 2 } }
        f.shot("features-cross-search")
    }
    @Test fun replacingAQueryCancelsOldSourcesWithoutChangingTheActiveSite() = ComposeFixture().use { f ->
        val cancelled = AtomicBoolean()
        f.feedOverride = { site, request -> if (request.target == "old") {
            try { awaitCancellation() } finally { cancelled.set(true) }
        } else FeedPage(listOf(f.video(site).copy(title = request.target)), 1, 1, "new") }
        lateinit var state: CrossSearchState
        f.ins.runOnMainSync { state = CrossSearchState(); state.search(f.app, "old", listOf("r34v", "hanime1")) }
        ui.waitUntil(10000) { f.requests.count { it.second.target == "old" } == 2 }
        f.ins.runOnMainSync { state.search(f.app, "new", listOf("r34v", "hanime1")) }
        ui.waitUntil(10000) { state.buckets.values.all { !it.busy && it.videos.isNotEmpty() } }
        assertTrue(cancelled.get()); assertEquals("r34v", f.app.library.siteId)
        assertEquals(setOf("r34v", "hanime1"), state.buckets.values.flatMap { it.videos }.map { it.site }.toSet())
        assertTrue(state.buckets.values.flatMap { it.videos }.all { it.title == "new" })
        f.app.library.prefs.edit().putString("hanime1:blocked_tags", "[\"new\"]").commit()
        f.ins.runOnMainSync { state.search(f.app, "new", listOf("r34v", "hanime1")) }
        ui.waitUntil(10000) { state.buckets.values.all { !it.busy } }
        assertTrue(state.buckets.getValue("hanime1").videos.isEmpty()); assertTrue(state.buckets.getValue("r34v").videos.isNotEmpty())
    }
    @Test fun shelvesShowMultipleSourcesAndCompletionSurvivesRecreation() = ComposeFixture().use { f ->
        f.app.library.personal.add(f.video()); f.app.library.personal.add(f.video("ehentai"))
        val activity = f.launch(); waitHome(); clickText("片库"); openWatchLater(); waitText("按顺序观看 / 阅读")
        ui.onNodeWithContentDescription("管理 ${f.video().title}").performClick(); clickText("标记完成")
        clickText("筛选"); clickText("全部"); clickText("已完成"); clickText("显示作品"); waitText(f.video().title)
        assertTrue(f.app.library.personal.shelves().first().entries.first { it.video.site == "r34v" }.completed)
        ui.runOnIdle { activity.recreate() }; waitText("稍后看"); waitText(f.video().title)
        f.shot("features-shelves")
    }
    @Test fun readerSupportsBothPagingDirectionsAndPersistentBookmarks() = ComposeFixture().use { f ->
        val activity = f.launchGallery(read = true)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
        readerTool("阅读模式"); clickText("向左翻页")
        ui.onNodeWithTag("paged-reader").performTouchInput { swipeLeft() }
        ui.waitUntil(15000) { f.app.library.reading(f.video("ehentai").id, "ehentai")?.page == 2 }
        readerTool("页码书签"); clickText("添加第 2 页书签")
        assertEquals(2, f.app.library.personal.bookmarks(f.video("ehentai")).single().page)
        readerTool("阅读模式"); clickText("向右翻页")
        ui.onNodeWithTag("paged-reader").performTouchInput { swipeRight() }
        ui.waitUntil(15000) { f.app.library.reading(f.video("ehentai").id, "ehentai")?.page == 3 }
        ui.runOnIdle { activity.recreate() }
        ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-3").fetchSemanticsNodes().isNotEmpty() }
        f.shot("features-reader-paged")
    }
    @Test fun landscapeSpreadShowsTwoRealPages() = ComposeFixture().use { f ->
        val activity = f.launchGallery(read = true)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
        ui.runOnIdle { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10000) { activity.resources.configuration.screenWidthDp > activity.resources.configuration.screenHeightDp }
        readerTool("阅读模式"); clickText("横屏双页")
        ui.waitUntil(15000) { ui.onAllNodesWithContentDescription("第 2 页，双击或双指缩放").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("reader-page-1").assertIsDisplayed(); ui.onNodeWithTag("reader-page-2").assertIsDisplayed()
        f.shot("features-reader-spread")
    }
    @Test fun playerLockBlocksGesturesAndTheTimerActuallyPausesPlayback() = ComposeFixture().use { f ->
        val activity = f.launchPlayer(); ready(f, activity)
        ui.runOnIdle { engine(activity)!!.apply { pause(); seekTo(2000) } }
        ui.onNodeWithContentDescription("更多播放操作").performScrollTo().performClick(); clickText("锁定画面，防止误触")
        ui.onNodeWithText("解锁画面").assertExists()
        ui.onNodeWithTag("player-surface").performTouchInput { doubleClick() }
        ui.runOnIdle { assertFalse(engine(activity)!!.playWhenReady); assertTrue(engine(activity)!!.currentPosition < 4000) }
        clickText("解锁画面")
        ui.onNodeWithContentDescription("更多播放操作").performScrollTo().performClick(); clickText("定时暂停"); clickText("5 分钟后暂停")
        ui.runOnIdle {
            PlayerActivity::class.java.getDeclaredField("pauseAt").apply { isAccessible = true }.setLong(activity, SystemClock.elapsedRealtime() - 1)
            engine(activity)!!.play()
        }
        ui.waitUntil(5000) { var paused = false; f.ins.runOnMainSync { paused = engine(activity)?.playWhenReady == false }; paused }
        f.shot("features-player-extras")
    }
    @Test fun pictureInPictureKeepsPlayingAndReturnsToTheSameEngine() = ComposeFixture().use { f ->
        assumeTrue(android.os.Build.VERSION.SDK_INT >= 26 && f.ins.targetContext.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))
        val activity = f.launchPlayer(); ready(f, activity)
        lateinit var original: ExoPlayer
        ui.runOnIdle { original = engine(activity)!!; original.repeatMode = Player.REPEAT_MODE_ONE }
        ui.onNodeWithContentDescription("更多播放操作").performScrollTo().performClick(); clickText("进入画中画")
        val deadline = SystemClock.uptimeMillis() + 10000
        while (!activity.isInPictureInPictureMode && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertTrue(activity.isInPictureInPictureMode)
        f.ins.waitForIdleSync()
        ui.onAllNodesWithContentDescription("更多播放操作").assertCountEquals(0)
        ui.onAllNodesWithText(f.video().title).assertCountEquals(0)
        f.ins.runOnMainSync { assertTrue(original.playWhenReady); assertSame(original, engine(activity)) }
        f.shot("features-player-pip")
        f.ins.runOnMainSync { activity.startActivity(Intent(activity, PlayerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
        ui.waitUntil(10000) { !activity.isInPictureInPictureMode }
        ui.runOnIdle { assertSame(original, engine(activity)); assertTrue(original.playWhenReady) }
    }
    @Test fun backupScreenShowsValidationAndNoPrivacyControls() = ComposeFixture().use { f ->
        f.launch(); waitHome(); ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("备份与恢复").performScrollTo().performClick(); waitText("选择位置并导出")
        ui.onNodeWithText("选择位置并导出").assertIsNotEnabled()
        clickText("恢复备份"); waitText("选择备份文件"); ui.onNodeWithText("选择备份文件").assertIsEnabled()
        f.shot("features-backup")
    }
    @Test fun multiSourceSearchDefaultsToAllSupportedSourcesAndShowsAuthFailures() = ComposeFixture().use { f ->
        val expected = com.r34v.r34v.ui.searchableSiteIds(f.app.network)
        f.feedOverride = { site, request -> if (request.kind != FeedKind.SEARCH) null else
            FeedPage(listOf(f.video(site).copy(title = "全站搜索-$site")), 1, 1, "search") }
        f.launch(); waitHome(); clickText("搜索"); clickText("多个站点")
        waitText("选择来源 · ${expected.size} 个站点")
        clickText("选择来源 · ${expected.size} 个站点"); f.shot("multi-source-all"); clickText("全选"); clickText("确定")
        ui.onNode(hasSetTextAction()).performTextInput("all")
        ui.onNodeWithContentDescription("提交搜索").performClick()
        ui.waitUntil(25000) {
            f.requests.filter { it.second.kind == FeedKind.SEARCH && it.second.target == "all" }.map { it.first }.toSet() == expected.filter { it != "exhentai" }.toSet()
        }
        ui.onNodeWithTag("cross-search-results").performScrollToNode(hasText("本站需要登录", substring = true))
        assertEquals(expected.toSet(), f.app.library.string("cross_search_sites").split(',').filter(String::isNotBlank).toSet())
        assertEquals("r34v", f.app.library.siteId)
    }

    @Test fun libraryAndInboxExposeSubscriptionManagement() = ComposeFixture().use { f ->
        f.app.library.clearSubscriptions("r34v")
        f.app.library.toggleSubscription(Subscription("sample", "示例标签", site = "r34v"), "r34v")
        f.launch(); waitHome(); clickText("片库"); waitText("我的片库"); f.shot("library-tag-subscriptions"); clickText("标签订阅")
        waitText("管理标签"); clickText("管理标签"); waitText("示例标签")
        ui.onNodeWithContentDescription("取消订阅 示例标签").performClick(); clickText("完成")
        assertFalse(f.app.library.isSubscribed("sample", "r34v"))
        f.launch(); waitHome(); clickText("订阅更新 · 0"); clickText("标签订阅")
        waitText("还没有订阅任何标签")
    }

    @Test fun subscriptionInboxTracksNewWorksAndPersistsReadState() = ComposeFixture().use { f ->
        val updated = AtomicBoolean()
        f.app.library.clearSubscriptions("r34v")
        f.app.library.toggleSubscription(Subscription("sample", site = "r34v"), "r34v")
        f.feedOverride = { owner, request -> if (request.kind != FeedKind.TAG) null else FeedPage(
            listOf(f.video(owner).copy(id = "old", title = "原有订阅作品")) + if (updated.get()) listOf(f.video(owner).copy(id = "new", title = "新到订阅作品")) else emptyList(), 1, 1, "subscriptions") }
        f.launch(); waitHome(); clickText("片库"); ui.onNodeWithContentDescription("订阅").performClick()
        waitText("原有订阅作品"); clickText("更新收件箱"); waitText("未读 0")
        updated.set(true); clickText("刷新"); waitText("新到订阅作品"); waitText("未读 1")
        ui.onNodeWithContentDescription("新到订阅作品 的更多操作").performClick(); clickText("标记已读")
        waitText("未读 0"); assertTrue(f.app.library.personal.inbox("r34v").entries.first { it.video.id == "new" }.read)
        f.shot("features-inbox")
    }
    @Test fun downloadsCanPauseAndResumeSelectedBooksAndRememberNetworkConditions() = ComposeFixture().use { f ->
        if (android.os.Build.VERSION.SDK_INT >= 33) f.ins.uiAutomation.executeShellCommand("pm grant ${f.ins.targetContext.packageName} android.permission.POST_NOTIFICATIONS").close()
        f.launchDownloads(); waitText("下载中心"); f.imageDelayMillis = 2500
        runBlocking { f.app.galleryDownloads.add(f.video("ehentai")); f.app.galleryDownloads.add(f.video("nhentai")) }
        waitText("批量管理"); ui.onNodeWithTag("download-center").performScrollToNode(hasText("批量管理")); clickText("批量管理"); clickText("暂停所选")
        try { ui.waitUntil(15000) { f.app.galleryDownloads.tasks().filter { it.entry.id == f.video("ehentai").id }.let { it.size == 2 && it.all { book -> book.state == GalleryDownloadState.PAUSED } } } }
        catch (e: Throwable) { println("Batch pause state: ${f.app.galleryDownloads.tasks().map { it.id to it.state }}"); f.shot("batch-pause-timeout"); throw e }
        ui.onNodeWithTag("download-center").performScrollToNode(hasText("批量管理")); clickText("批量管理"); clickText("继续所选")
        ui.waitUntil(10000) { f.app.galleryDownloads.tasks().filter { it.entry.id == f.video("ehentai").id }.all { it.state in listOf(GalleryDownloadState.QUEUED, GalleryDownloadState.DOWNLOADING) } }
        ui.onNodeWithTag("download-center").performScrollToNode(hasText("任意网络")); clickText("任意网络"); clickText("仅非计费网络")
        ui.waitUntil(5000) { f.app.library.bool("download_unmetered") }
        f.shot("features-batch-downloads")
    }
    @Test fun backupUiWritesEncryptedFilePreviewsAndMergesOnlyAfterConfirmation() = ComposeFixture().use { f ->
        val entry = f.video(); f.app.library.personal.add(entry)
        val file = java.io.File(f.app.cacheDir, "updates/fixture-backup-${f.nonce}").apply { parentFile!!.mkdirs() }
        val uri = androidx.core.content.FileProvider.getUriForFile(f.app, "${f.app.packageName}.fileprovider", file)
        val created = f.ins.addMonitor(android.content.IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("application/octet-stream") },
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, Intent().setData(uri)), true)
        val opened = f.ins.addMonitor(android.content.IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*") },
            android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK, Intent().setData(uri)), true)
        try {
            f.launch(); waitHome(); ui.onNodeWithContentDescription("设置").performClick()
            ui.onNodeWithText("备份与恢复").performScrollTo().performClick()
            ui.onAllNodes(hasSetTextAction()).onFirst().performTextInput("fixture-password")
            ui.onAllNodes(hasSetTextAction()).onLast().performTextInput("fixture-password")
            ui.onNodeWithText("选择位置并导出").performScrollTo().performClick(); waitText("备份已保存")
            assertTrue(file.length() > 52); assertFalse(file.readText().contains(entry.title))
            f.app.library.personal.remove(entry)
            clickText("恢复备份"); clickText("选择备份文件"); waitText("已选择备份文件，请输入密码")
            val before = f.app.library.prefs.all.toMap()
            ui.onNode(hasSetTextAction()).performTextInput("wrong-password")
            ui.onNodeWithText("解密并预览").performScrollTo().performClick()
            try { ui.waitUntil(15000) { ui.onAllNodes(hasText("密码不正确或备份文件已损坏", substring = true)).fetchSemanticsNodes().isNotEmpty() } }
            catch (e: Throwable) { f.shot("backup-wrong-timeout"); throw e }
            f.shot("backup-wrong-password")
            assertEquals(before, f.app.library.prefs.all)
            ui.onNode(hasSetTextAction()).performTextReplacement("fixture-password")
            ui.onNodeWithText("解密并预览").performScrollTo().performClick()
            try { waitText("恢复预览") } catch (e: Throwable) { f.shot("backup-correct-timeout"); throw e }
            assertFalse(f.app.library.personal.contains(entry)); f.shot("features-backup-preview")
            ui.onNodeWithText("合并恢复").performScrollTo().performClick(); waitText("已合并恢复")
            assertTrue(f.app.library.personal.contains(entry))
        } finally { f.ins.removeMonitor(created); f.ins.removeMonitor(opened); file.delete() }
    }
}
