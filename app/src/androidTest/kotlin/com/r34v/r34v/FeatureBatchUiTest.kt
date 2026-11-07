@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

@RunWith(AndroidJUnit4::class)
class FeatureBatchUiTest : ComposeUiCase() {
    private fun engine(activity: PlayerActivity) = PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(activity) as? ExoPlayer
    private inline fun <reified T : Activity> resumed(): T = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().single()
    private fun ready(f: ComposeFixture, activity: PlayerActivity) { ui.waitUntil(20000) { var value = false; f.ins.runOnMainSync { value = engine(activity)?.playbackState == Player.STATE_READY }; value } }
    @Test fun videoLoopRepeatsSurvivesRecreationAndClearsWhenSeekingOutside(): Unit = ComposeFixture().use { f ->
        var activity = f.launchPlayer(); ready(f, activity)
        fun menu() { ui.onNodeWithContentDescription("更多播放操作").performScrollTo().performClick(); clickText("A–B 区间循环") }
        ui.runOnIdle { engine(activity)!!.apply { pause(); seekTo(1000) } }
        menu(); clickText("将当前位置设为 A 点")
        ui.runOnIdle { engine(activity)!!.seekTo(3000) }
        menu(); clickText("将当前位置设为 B 点并循环")
        ui.onNodeWithTag("video-loop-range", useUnmergedTree = true).assertTextContains("循环 0:01 – 0:03")
        val repeats = AtomicInteger()
        ui.runOnIdle {
            engine(activity)!!.addListener(object : Player.Listener {
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (oldPosition.positionMs >= 2900 && newPosition.positionMs == 1000L) repeats.incrementAndGet()
                }
            }); engine(activity)!!.play()
        }
        ui.waitUntil(15000) { repeats.get() >= 2 }
        ui.runOnIdle { engine(activity)!!.pause(); activity.recreate() }
        ui.waitUntil(10000) { var found = false; f.ins.runOnMainSync { runCatching { resumed<PlayerActivity>() }.getOrNull()?.let { if (it !== activity) { activity = it; found = true } } }; found }
        ready(f, activity)
        ui.onNodeWithTag("video-loop-range", useUnmergedTree = true).assertTextContains("循环 0:01 – 0:03")
        f.shot("batch-video-loop")
        ui.runOnIdle { engine(activity)!!.seekTo(0) }
        ui.waitUntil(5000) { ui.onAllNodesWithTag("video-loop-range", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() }
    }
    @Test fun readerStatusScreenSettingsPersistAndOnlyAffectReading(): Unit = ComposeFixture().use { f ->
        var activity = f.launchGallery(read = true)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
        readerTool("阅读状态栏与屏幕")
        listOf(0, 1, 3, 4).forEach { ui.onNodeWithTag("reader-status-$it").performScrollTo().performClick() }
        ui.onNodeWithTag("reader-brightness").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(40f) }
        clickText("完成"); readerTool("全屏阅读")
        ui.onNodeWithTag("reader-status").assertTextContains("1 / 8", substring = true)
        ui.onNodeWithTag("reader-status").assertTextContains("12%", substring = true)
        ui.runOnIdle { assertEquals(.4f, activity.window.attributes.screenBrightness); assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0); activity.recreate() }
        ui.waitUntil(10000) { var changed = false; f.ins.runOnMainSync { runCatching { resumed<GalleryActivity>() }.getOrNull()?.let { if (it !== activity) { activity = it; changed = true } } }; changed }
        ui.onNodeWithTag("reader-status").assertExists(); f.shot("batch-reader-status")
        f.ins.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        ui.onNodeWithContentDescription("返回").performClick()
        ui.waitForIdle()
        ui.runOnIdle { assertEquals(-1f, activity.window.attributes.screenBrightness); assertEquals(0, activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        assertTrue(PersonalStore(f.app.library).readingBehavior("ehentai").showClock)
    }
    @Test fun exportMenuSavesOriginalAndCbzAndSharesOnlyContentUris(): Unit = ComposeFixture().use { f ->
        val image = File(f.app.cacheDir, "updates/export-${f.nonce}.png").apply { parentFile!!.mkdirs() }
        val cbz = File(f.app.cacheDir, "updates/export-${f.nonce}.cbz")
        fun monitor(mime: String, target: File) = f.ins.addMonitor(IntentFilter(Intent.ACTION_CREATE_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); addDataType(mime) },
            Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(FileProvider.getUriForFile(f.app, "${f.app.packageName}.fileprovider", target))), true)
        val first = monitor("image/png", image); val second = monitor("application/vnd.comicbook+zip", cbz)
        var shared: Intent? = null
        val shareMonitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                @Suppress("DEPRECATION")
                shared = intent.getParcelableExtra(Intent.EXTRA_INTENT)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        f.ins.addMonitor(shareMonitor)
        try {
            val activity = f.launchGallery(read = true)
            ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
            fun export() { readerTool("图片保存、分享与 CBZ") }
            fun complete() { ui.waitUntil(20000) { var done = false; f.ins.runOnMainSync { done = !ViewModelProvider(activity)[GalleryExportModel::class.java].busy }; done }; ui.waitForIdle() }
            export(); clickText("保存当前原图 · 第 1 页")
            ui.waitUntil(20000) { image.length() > 0 }; complete()
            val original = runBlocking { f.app.galleries.originalPage(SiteRegistry.EHENTAI, f.app.galleries.cachedPageUrl(f.video("ehentai"), 1)!!, entry = f.video("ehentai")) }
            assertArrayEquals(original.bytes, image.readBytes())
            export(); clickText("导出完整 CBZ · 8 页")
            ui.waitUntil(20000) { cbz.length() > 0 }; complete()
            ZipFile(cbz).use { zip -> assertEquals(8, zip.size()); assertArrayEquals(original.bytes, zip.getInputStream(zip.getEntry("000001.png")).readBytes()) }
            export(); clickText("分享当前原图")
            if (android.os.Build.VERSION.SDK_INT < 26) {
                ui.waitUntil(15000) { f.ins.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("分享图片")?.any { it.isVisibleToUser } == true }
                f.shot("batch-api24-system-share")
                f.ins.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            } else ui.waitUntil(15000) { shared != null }
            shared?.let { intent ->
                assertEquals(Intent.ACTION_SEND, intent.action)
                assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertEquals("content", intent.clipData!!.getItemAt(0).uri.scheme)
            }
            f.shot("batch-export-reader")
        } finally { f.ins.removeMonitor(first); f.ins.removeMonitor(second); f.ins.removeMonitor(shareMonitor); image.delete(); cbz.delete() }
    }
    @Test fun savedSearchUpdateUiShowsIndependentFailureNewResultsAndReadState(): Unit = ComposeFixture().use { f ->
        val search = SavedSearch(name = "更新测试", query = "sample", sources = listOf("r34v", "hanime1"))
        f.app.library.personal.saveSearch(search)
        val generation = AtomicInteger()
        val fresh = f.video("r34v", 1, 1).copy(title = "搜索新增作品")
        f.feedOverride = { site, request -> if (request.kind != FeedKind.SEARCH) null else {
            if (site == "hanime1") throw IOException("来源暂不可用")
            FeedPage(if (generation.get() == 0) listOf(f.video("r34v")) else listOf(fresh, f.video("r34v")), 1, 1, "search")
        } }
        f.launch(); waitHome(); clickText("搜索")
        ui.onNodeWithText("保存搜索更新与未读").performScrollTo().performClick()
        clickText("检查搜索更新")
        ui.waitUntil(15000) { f.app.library.personal.snapshot().searchUpdates[search.id]?.checks?.size == 2 }
        assertTrue(f.app.library.personal.snapshot().searchUpdates.getValue(search.id).entries.all { it.read })
        clickText("检查详情"); waitText("Hanime1：来源暂不可用"); clickText("关闭")
        generation.incrementAndGet(); clickText("检查搜索更新")
        ui.waitUntil(15000) {
            f.app.library.personal.snapshot().searchUpdates.getValue(search.id).entries.any { it.video.entryKey == fresh.entryKey && !it.read }
        }
        // The header can fill a small CI viewport; lazy rows appear only after scrolling.
        ui.onNodeWithTag("catalog-list").performScrollToNode(hasText("搜索新增作品"))
        ui.onNodeWithText("搜索新增作品").assertIsDisplayed(); f.shot("batch-search-updates")
        assertEquals(1, f.app.library.personal.snapshot().searchUpdates.getValue(search.id).entries.count { !it.read })
        clickText("搜索新增作品")
        ui.waitUntil(15000) { f.app.library.personal.snapshot().searchUpdates.getValue(search.id).entries.all { it.read } }
        assertTrue(f.requests.filter { it.second.kind == FeedKind.SEARCH }.all { it.second.sort == Sort.NEWEST })
    }
    @Test fun savedSearchResultsRemainReachableInShortLandscape(): Unit = ComposeFixture().use { f ->
        val search = SavedSearch(name = "横屏方案", query = "q", sources = listOf("r34v"))
        val entry = f.video("r34v").copy(title = "横屏未读作品")
        f.app.library.personal.saveSearch(search)
        f.app.library.personal.refreshSearch(search, "r34v", FeedPage(emptyList(), 1, 1, ""))
        f.app.library.personal.refreshSearch(search, "r34v", FeedPage(listOf(entry), 1, 1, ""))
        val activity = f.launch(); waitHome(); clickText("搜索")
        ui.onNodeWithText("保存搜索更新与未读").performScrollTo().performClick()
        ui.runOnIdle { activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        ui.waitUntil(10000) { activity.resources.configuration.screenWidthDp > activity.resources.configuration.screenHeightDp }
        ui.onNodeWithTag("catalog-list").performScrollToNode(hasText("横屏未读作品"))
        ui.onNodeWithText("横屏未读作品").assertIsDisplayed()
        f.shot("batch-search-landscape")
        ui.onNodeWithTag("catalog-list").performScrollToNode(hasText("检查搜索更新"))
        ui.onNodeWithText("检查搜索更新").assertIsDisplayed()
    }
}
