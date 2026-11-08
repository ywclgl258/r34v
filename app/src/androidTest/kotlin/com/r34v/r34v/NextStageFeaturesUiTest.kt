package com.r34v.r34v

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NextStageFeaturesUiTest : ComposeUiCase() {
    @Test fun iwaraGalleryFavoriteWallAndSlideshowReturnToOriginalPage() = ComposeFixture().use { f ->
        f.app.library.switchSite("iwara")
        val video = f.video("iwara").copy(mediaKind = MediaKind.GALLERY)
        f.app.library.personal.setReadingBehavior("iwara", ReadingBehavior(slideshowSeconds = 1))
        f.ins.startActivitySync(Intent(f.ins.targetContext, GalleryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("gallery", f.app.library.gson.toJson(video)))
        waitText("开始浏览"); clickText("开始浏览")
        ui.waitUntil(15000) { f.imageCalls.contains(1) }
        readerTool("收藏当前图片")
        assertTrue(f.app.library.personal.isImageFavorite(video, 1))
        readerTool("图集幻灯片"); clickText("开始幻灯片")
        try { ui.waitUntil(10000) { (f.app.library.reading(video.id, "iwara")?.page ?: 1) > 1 } }
        catch (e: Throwable) { println("Iwara slideshow progress: ${f.app.library.reading(video.id, "iwara")}"); f.shot("iwara-slideshow-timeout"); throw e }
        waitText("幻灯片播放中 · 点击暂停"); f.shot("next-stage-iwara-gallery")

        f.launch(); clickText("片库"); ui.onNodeWithTag("library-hub").performScrollToNode(hasText("单图收藏 · 1 张")); clickText("单图收藏 · 1 张")
        waitText("取消收藏"); ui.onNodeWithText("取消收藏").assertIsDisplayed()
        f.ins.uiAutomation.waitForIdle(1000, 5000); android.os.SystemClock.sleep(2000); f.shot("next-stage-image-wall")
        clickText(video.title)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-1").fetchSemanticsNodes().isNotEmpty() }
        ui.waitUntil(10000) { f.app.library.reading(video.id, "iwara")?.page == 1 }
        f.shot("next-stage-image-favorite-return")
    }

    @Test fun advancedSearchCanBeSavedPinnedAndReopenedWithItsConditions() = ComposeFixture(awaitUi = { ui.waitForIdle() }).use { f ->
        f.app.library.switchSite("nhentai"); f.launch(); waitHome(); clickText("搜索"); clickText("标签与高级筛选")
        ui.onAllNodes(hasSetTextAction()).filterToOne(hasText("关键词")).performTextReplacement("city")
        ui.onAllNodes(hasSetTextAction()).filterToOne(hasText("原始标签或 namespace:tag")).performTextReplacement("tag:sample")
        clickText("加入包含条件")
        ui.onAllNodes(hasSetTextAction()).filterToOne(hasText("最少页数")).performTextReplacement("5")
        closeKeyboard(f)
        clickText("应用条件")
        ui.onNodeWithText("保存搜索方案").performScrollTo().assertIsDisplayed().performClick()
        ui.onAllNodes(hasSetTextAction()).onLast().performTextReplacement("周末图集"); clickText("确定")
        android.os.SystemClock.sleep(5000)
        ui.onNodeWithText("最近搜索").performScrollTo()
        ui.onNodeWithContentDescription("管理搜索 周末图集").performClick(); f.shot("api24-search-manage")
        ui.onNodeWithText("固定到我的频道").performScrollTo().performClick()
        openPersonalHome()
        ui.onNodeWithTag("personal-home").performScrollToNode(hasText("我的频道"))
        ui.waitUntil(7000) { ui.onAllNodesWithText("已固定到“我的”中的频道").fetchSemanticsNodes().isEmpty() }
        f.shot("next-stage-discovery-channels")
        clickText("周末图集")
        ui.waitUntil(15000) { f.requests.any { it.first == "nhentai" && it.second.kind == FeedKind.SEARCH && it.second.target == "city" && it.second.includeTags == listOf("tag:sample") && it.second.filter.minPages == 5 } }
        val channels = f.app.library.personal.snapshot().channels
        assertEquals("周末图集", channels.single().name)
        assertEquals(5, channels.single().savedSearch?.filter?.minPages)
    }

    @Test fun playerTimeBookmarksAndSourcePreferencesPersist() = ComposeFixture().use { f ->
        f.launchPlayer(); ui.onNodeWithContentDescription("播放器菜单").performClick(); clickText("时间书签与片段")
        ui.onNode(hasText("添加当前位置", substring = true)).performClick()
        ui.onAllNodes(hasSetTextAction()).filterToOne(hasText("备注（可选）")).performTextReplacement("片段 A")
        clickText("保存")
        val video = f.video("r34v")
        assertEquals("片段 A", f.app.library.personal.timeBookmarks(video).single().note)
        ui.onNodeWithContentDescription("播放器菜单").performClick(); clickText("播放偏好")
        ui.onNode(hasText("默认倍速", substring = true)).performClick(); clickText("1.5×")
        assertEquals(1.5f, f.app.library.personal.playback("r34v").defaultRate)
        waitText("完成")
        ui.waitUntil(7000) { ui.onAllNodesWithText("已保存时间书签").fetchSemanticsNodes().isEmpty() }
        f.shot("next-stage-player-preferences")
    }

    @Test fun readerAutoPageDisplayOverrideAndVolumeKeysWorkTogether() = ComposeFixture().use { f ->
        f.app.library.switchSite("ehentai")
        f.app.library.personal.setReadingBehavior("ehentai", ReadingBehavior(autoPageSeconds = 1))
        f.launchGallery(read = true); ui.waitUntil(15000) { f.imageCalls.contains(1) }
        readerTool("阅读行为"); clickText("自动翻页：每 1 秒"); clickText("每 3 秒，成功显示后推进")
        ui.waitUntil(12000) { f.app.library.reading(f.video("ehentai").id)?.page == 2 }
        readerTool("阅读行为"); clickText("暂停自动翻页")
        readerTool("本书显示设置"); waitText("恢复来源默认"); ui.onNodeWithText("恢复来源默认").assertIsDisplayed()
        f.ins.uiAutomation.waitForIdle(1000, 5000); android.os.SystemClock.sleep(2000); f.shot("next-stage-reader-settings")
        clickText("手动裁边 · 0%"); clickText("5%"); clickText("完成")
        assertEquals(5, f.app.library.personal.readerPreferences(f.video("ehentai")).cropPercent)
        f.ins.runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<GalleryActivity>().single()
            activity.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_VOLUME_DOWN))
            activity.onKeyDown(android.view.KeyEvent.KEYCODE_VOLUME_DOWN, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_VOLUME_DOWN))
        }
        ui.waitUntil(10000) { f.app.library.reading(f.video("ehentai").id)?.page == 1 }
        f.shot("next-stage-reader-display")
    }
}
