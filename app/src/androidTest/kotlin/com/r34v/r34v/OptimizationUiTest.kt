@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OptimizationUiTest : ComposeUiCase() {
    @Test fun refreshingCancelsTheOldPageBeforeReplacingTheList() = ComposeFixture().use { f ->
        val first = AtomicInteger(); val canceled = AtomicBoolean()
        f.feedOverride = { owner, request ->
            when {
                owner != "ehentai" -> null
                request.page == 2 -> try { awaitCancellation() } finally { canceled.set(true) }
                first.incrementAndGet() == 1 -> null
                else -> FeedPage(listOf(f.video(owner).copy(title = "刷新后的第一页")), 1, 1, "fresh")
            }
        }
        f.launchFeed(); waitText("刷新")
        ui.onNodeWithContentDescription("内容列表").performScrollToIndex(14)
        ui.waitUntil(10000) { f.requests.any { it.first == "ehentai" && it.second.page == 2 } }
        ui.onNodeWithContentDescription("内容列表").performScrollToIndex(0)
        clickText("刷新"); waitText("刷新后的第一页")
        assertTrue(canceled.get()); assertEquals(2, first.get())
    }
    @Test fun filteredEmptyPagesContinueWithABoundedAutomaticBudget() = ComposeFixture().use { f ->
        f.feedOverride = { owner, request ->
            if (owner != "ehentai") null else FeedPage(
                if (request.page < 4) emptyList() else listOf(f.video(owner).copy(title = "后续页的语言匹配")),
                request.page, 4, "filtered", nextCursor = if (request.page < 4) "cursor-${request.page + 1}" else null)
        }
        f.launchFeed(); waitText("继续加载")
        assertEquals(listOf(1, 2, 3), f.requests.filter { it.first == "ehentai" }.map { it.second.page })
        clickText("继续加载"); waitText("后续页的语言匹配")
        assertEquals(4, f.requests.count { it.first == "ehentai" })
    }
    @Test fun aRepeatedCursorStopsInsteadOfSpinning() = ComposeFixture().use { f ->
        f.feedOverride = { _, request -> FeedPage(emptyList(), request.page, 50, "loop", nextCursor = "same") }
        f.launchFeed(); waitText("原站返回了重复的翻页地址，请刷新列表")
        assertEquals(2, f.requests.size)
        f.feedOverride = null
        clickText("重试"); waitText("刷新")
        ui.waitUntil(10000) { f.requests.count { it.second.page == 1 } == 2 }
    }
    @Test fun loadedPagesAndTheirScrollAnchorSurviveNavigationAndRecreation() = ComposeFixture().use { f ->
        val activity = f.launchFeed(); waitText("刷新")
        ui.onNodeWithContentDescription("内容列表").performScrollToIndex(14)
        ui.waitUntil(10000) { f.requests.any { it.second.page == 2 } }
        ui.onNodeWithContentDescription("内容列表").performScrollToIndex(31)
        ui.waitUntil(10000) { f.requests.any { it.second.page == 3 } }
        ui.onNodeWithContentDescription("内容列表").performScrollToIndex(37)
        val firstPages = f.requests.count { it.second.page == 1 }
        ui.onNodeWithContentDescription("设置").performClick(); back(f)
        ui.waitUntil(10000) { ui.onAllNodesWithContentDescription("内容列表").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(firstPages, f.requests.count { it.second.page == 1 })
        ui.onNodeWithTag("media-ehentai-sample-${f.video("ehentai", 3, 4).id}").assertIsDisplayed()
        ui.runOnIdle { activity.recreate() }
        ui.waitUntil(10000) { ui.onAllNodesWithTag("media-ehentai-sample-${f.video("ehentai", 3, 4).id}").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(firstPages, f.requests.count { it.second.page == 1 })
    }
    @Test fun aNewBlockRuleInvalidatesTheCachedPage() = ComposeFixture().use { f ->
        f.launchFeed(); waitText("刷新")
        ui.onNodeWithContentDescription("设置").performClick()
        f.app.library.toggleBlocked("sample")
        back(f)
        waitText("当前条件下暂无漫画，可调整筛选或修改搜索条件")
        ui.onAllNodesWithTag("media-ehentai-sample-${f.video("ehentai").id}").assertCountEquals(0)
        Unit
    }
    private fun player(f: ComposeFixture): Pair<PlayerActivity?, ExoPlayer?> {
        var result: Pair<PlayerActivity?, ExoPlayer?> = null to null
        val read = Runnable {
            val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>().firstOrNull()
            result = activity to activity?.let { PlayerActivity::class.java.getDeclaredField("player").apply { isAccessible = true }.get(it) as? ExoPlayer }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) read.run() else f.ins.runOnMainSync(read)
        return result
    }
    private fun ready(f: ComposeFixture) { ui.waitUntil(20000) { var ready = false; f.ins.runOnMainSync { ready = player(f).second?.playbackState == Player.STATE_READY }; ready } }
    @Test fun delayedDetailRestoresThePausedPlaybackIntent() = ComposeFixture().use { f ->
        val original = f.launchPlayer(); ready(f)
        ui.runOnIdle { player(f).second!!.apply { pause(); seekTo(2000) } }
        f.detailDelayMillis = 300
        ui.runOnIdle { original.recreate() }
        ui.waitUntil(10000) { player(f).first?.let { it !== original } == true }
        ready(f)
        ui.runOnIdle { assertFalse(player(f).second!!.playWhenReady); assertTrue(player(f).second!!.currentPosition >= 1000) }
    }
    @Test fun refusingAutomaticDownloadDoesNotFailPlayback() = ComposeFixture().use { f ->
        f.app.library.set("auto_download", true)
        f.app.storage.requestOnRestart(StorageRoot.Mode.PRIVATE)
        f.launchPlayer(); ready(f)
        val activity = player(f).first!!
        ui.runOnIdle {
            val error = PlayerActivity::class.java.getDeclaredField("pendingError").apply { isAccessible = true }.get(activity)
            assertNull(error); assertTrue(player(f).second!!.playWhenReady)
        }
    }
}
