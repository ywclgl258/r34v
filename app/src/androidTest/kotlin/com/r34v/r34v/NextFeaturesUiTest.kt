@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.r34v.r34v

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextFeaturesUiTest : ComposeUiCase() {
    private fun home() = openPersonalHome()
    private fun seed(f: ComposeFixture) {
        f.app.library.prefs.edit().putString(PersonalStore.KEY, f.app.library.gson.toJson(PersonalLibrary())).commit()
        f.app.library.prefs.edit().apply { SiteRegistry.librarySources.forEach { remove("${it.id}:watch_history_v1"); remove("${it.id}:reading_history_v1") } }.commit()
        val video = f.video()
        f.app.library.saveWatch(Watch(video.id, video.title, video.thumb, video.url, 42, 600, site = video.site))
    }
    @Test fun personalHomeAggregatesResumeAndSavedSearchSurvivesRecreation() = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        seed(f); f.app.library.personal.add(f.video("ehentai"))
        val activity = f.launch(); home(); waitText("怪奇物语")
        ui.waitUntil(15000) { ui.onAllNodesWithTag("video-stage").fetchSemanticsNodes().isNotEmpty() }
        waitCovers(); f.shot("next-personal-home")
        clickText("搜索"); clickText("多个站点")
        ui.onNode(hasSetTextAction()).performTextInput("space")
        closeKeyboard(f)
        clickText("保存搜索方案"); ui.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextReplacement("我的科幻搜索")
        closeKeyboard(f); clickText("确定")
        assertEquals("space", f.app.library.personal.snapshot().savedSearches.single().query)
        home()
        ui.onNodeWithTag("personal-home").performScrollToNode(hasText("我的科幻搜索"))
        ui.onNodeWithTag("personal-home").performTouchInput { swipeUp() }
        ui.onNodeWithText("我的科幻搜索").assertIsDisplayed(); f.shot("api24-personal-saved-search")
        ui.runOnIdle { activity.recreate() }; home()
        ui.onNodeWithTag("personal-home").performScrollToNode(hasText("我的科幻搜索"))
        ui.onNodeWithTag("personal-home").performTouchInput { swipeUp() }
        ui.onNodeWithText("我的科幻搜索").assertIsDisplayed().performClick()
        waitText("搜索：space")
        assertEquals("r34v", f.app.library.siteId)
        f.shot("next-saved-search")
    }
    @Test fun orderedShelfRequiresConfirmationBeforeCrossMediaTransition() = ComposeFixture().use { f ->
        // This path tests explicit handoff while the first item is still loading. Decoder
        // readiness is covered separately by the offline routing regression.
        f.detailDelayMillis = 60_000
        seed(f); val first = f.video(); val next = f.video("ehentai")
        f.app.library.personal.add(first); f.app.library.personal.add(next)
        f.launch(); home(); clickText("片库"); openWatchLater(); clickText("按顺序观看 / 阅读")
        ui.waitUntil(20000) { ui.onAllNodesWithText("下一项 · ${next.title}").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("下一项 · ${next.title}").performScrollTo().performClick()
        waitText("打开下一项？")
        assertEquals(0, f.app.queue.snapshot()!!.index)
        clickText("打开阅读器")
        ui.waitUntil(20000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(1, f.app.queue.snapshot()!!.index)
        assertTrue(f.app.library.personal.shelves().first().entries.first { it.video.entryKey == first.entryKey }.completed)
        ui.onNodeWithContentDescription("下一页").assertIsDisplayed()
        ui.onNodeWithContentDescription("片单接续").assertIsDisplayed().performClick()
        waitText("片单已到最后 · 标记完成")
        ui.onNodeWithText("片单已到最后 · 标记完成").assertIsDisplayed()
        clickText("关闭")
        ui.onNodeWithContentDescription("下一页").assertIsDisplayed()
        f.shot("next-cross-media-reader")
    }
    @Test fun sharedWorkIsConfirmedAndDoesNotSwitchTheBrowsingSource() = ComposeFixture().use { f ->
        seed(f)
        f.ins.startActivitySync(Intent(f.ins.targetContext, MainActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "分享标题\n${f.video("ehentai").url}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitText("在 R34V 中打开？"); assertTrue(f.galleryCalls.isEmpty()); clickText("识别并打开")
        waitText(f.video("ehentai").title)
        assertTrue(f.galleryCalls.contains("ehentai:detail")); assertEquals("r34v", f.app.library.siteId)
        f.shot("next-shared-work")
    }
    @Test fun thumbnailsJumpToARealPageAndKeepReaderPosition() = ComposeFixture().use { f ->
        f.launchGallery(read = true)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
        readerTool("页面缩略图总览")
        waitText("页面总览 · 1 / 8")
        ui.onNodeWithContentDescription("跳到第 6 页").performScrollTo().performClick()
        ui.waitUntil(15000) { f.app.library.reading(f.video("ehentai").id, "ehentai")?.page == 6 }
        f.shot("next-thumbnails-jump")
    }
    @Test fun offlinePlanShowsPendingAndSmartShelfIsStored() = ComposeFixture().use { f ->
        seed(f); f.app.library.personal.add(f.video()); f.app.library.personal.add(f.video("ehentai"))
        f.launch(); home(); clickText("片库"); clickText("智能片单"); clickText("新建智能片单"); clickText("未看完"); clickText("全部类型"); clickText("全部来源"); clickText("确定")
        assertEquals(SmartRule.UNFINISHED, f.app.library.personal.snapshot().smartShelves.single().rule)
        f.shot("next-library-hub")
        clickText("我的片单"); openWatchLater(); clickText("准备离线片单"); waitText("0 / 2 部已就绪")
        ui.onNodeWithText("加入下载 · 2").assertIsDisplayed(); f.shot("next-offline-plan")
        clickText("取消")
        clickText("筛选"); waitText("片单筛选"); clickText("显示作品")
        f.shot("next-collection")
    }
}
