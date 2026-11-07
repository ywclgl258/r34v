package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidMediaUiTest : ComposeUiCase() {
    @Test fun sourceStagesKeepAllThreeMediaAndNavigationReachable() = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        f.app.library.prefs.edit().putString(PersonalStore.KEY, f.app.library.gson.toJson(PersonalLibrary())).commit()
        f.app.library.prefs.edit().apply { SiteRegistry.all.forEach { remove("${it.id}:watch_history_v1"); remove("${it.id}:reading_history_v1") } }.commit()
        val activity = f.launch(); waitHome()
        val suffix = "${activity.resources.configuration.screenWidthDp}dp-${(activity.resources.configuration.fontScale * 100).toInt()}font"
        ui.onNodeWithTag("personal-home").performScrollToNode(hasText("浏览当前来源的最新内容"))
        clickText("浏览当前来源的最新内容")
        ui.waitUntil(15000) { ui.onAllNodesWithTag("home-carousel").fetchSemanticsNodes().isNotEmpty() || ui.onAllNodesWithTag("video-stage").fetchSemanticsNodes().isNotEmpty() }; waitCovers()
        f.shot("liquid-video-$suffix")
        switchSite(SiteRegistry.EHENTAI.name)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("comic-stage").fetchSemanticsNodes().isNotEmpty() }; waitCovers()
        val overflow = ui.onNodeWithContentDescription("${f.video("ehentai").title} 的更多操作").fetchSemanticsNode().touchBoundsInRoot
        val touchTarget = 48 * activity.resources.displayMetrics.density
        assertTrue("Compact overflow icons must retain a 48dp touch target: $overflow", overflow.width >= touchTarget && overflow.height >= touchTarget)
        f.shot("liquid-comic-$suffix")
        ui.onNodeWithContentDescription("内容列表").performScrollToNode(hasText("开始阅读"))
        ui.onNodeWithText("开始阅读").assertIsDisplayed()
        switchSite(SiteRegistry.FOURKHD.name)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("photo-stage").fetchSemanticsNodes().isNotEmpty() }; waitCovers()
        f.shot("liquid-photo-$suffix")
        if (activity.resources.configuration.screenWidthDp >= 840 && activity.resources.configuration.fontScale <= 1.3f)
            ui.onNodeWithText("开始浏览").assertIsDisplayed()
        ui.onNodeWithContentDescription("内容列表").performScrollToNode(hasText("开始浏览"))
        clickText("开始浏览"); waitText("收藏写真集")
        f.shot("liquid-photo-detail-$suffix")
        ui.onNodeWithText("开始浏览").performScrollTo().performClick()
        ui.waitUntil(15000) { f.app.library.reading(f.video("4khd").id, "4khd")?.page == 1 }
        ui.onNodeWithContentDescription("下一页").assertIsDisplayed().performClick()
        ui.waitUntil(15000) { f.app.library.reading(f.video("4khd").id, "4khd")?.page == 2 }
        f.shot("liquid-photo-reader-$suffix")
        readerTool("页面缩略图总览"); waitText("页面总览 · 2 / 8")
        ui.onNodeWithContentDescription("跳到第 6 页").performScrollTo().performClick()
        ui.waitUntil(15000) { f.app.library.reading(f.video("4khd").id, "4khd")?.page == 6 }
        val resume = f.video("r34v")
        f.app.library.saveWatch(Watch(resume.id, resume.title, resume.thumb, resume.url, 180, 600, site = resume.site))
        f.launch(); openSourceHome(); switchSite(SiteRegistry.R34V.name)
        waitText("3 分钟处继续")
        val continueAction = hasText("继续观看") and hasClickAction()
        ui.onNodeWithContentDescription("内容列表").performScrollToNode(continueAction)
        ui.onNode(continueAction).assertIsDisplayed()
        f.shot("liquid-video-resume-$suffix")
    }

    @Test fun reducedTransparencyIsPersistedWithoutChangingNavigation() = ComposeFixture(cinematic = true).use { f ->
        val activity = f.launch(); waitHome()
        ui.onNodeWithContentDescription("设置").performClick(); waitText("减少透明效果")
        clickText("减少透明效果")
        assertTrue(f.app.library.bool("reduce_transparency"))
        ui.runOnIdle { activity.recreate() }; waitText("减少透明效果")
        assertTrue(f.app.library.bool("reduce_transparency"))
        f.shot("liquid-reduced-transparency")
        clickText("首页"); waitHome()
        ui.onNodeWithText("片库").assertIsDisplayed().performClick(); waitText("我的片库")
    }
}
