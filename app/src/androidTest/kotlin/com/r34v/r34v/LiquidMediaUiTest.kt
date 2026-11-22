package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiquidMediaUiTest : ComposeUiCase() {
    @Test fun sourceStagesKeepAllThreeMediaAndNavigationReachable(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        f.app.library.prefs.edit().putString(PersonalStore.KEY, f.app.library.gson.toJson(PersonalLibrary())).commit()
        f.app.library.prefs.edit().apply { SiteRegistry.all.forEach { remove("${it.id}:watch_history_v1"); remove("${it.id}:reading_history_v1") } }.commit()
        val activity = f.launch(); waitHome()
        val suffix = "${activity.resources.configuration.screenWidthDp}dp-${(activity.resources.configuration.fontScale * 100).toInt()}font"
        ui.onNodeWithTag("personal-home").assertDoesNotExist()
        ui.waitUntil(15000) { ui.onAllNodesWithTag("home-carousel").fetchSemanticsNodes().isNotEmpty() || ui.onAllNodesWithTag("video-stage").fetchSemanticsNodes().isNotEmpty() }; waitCovers()
        f.shot("liquid-video-$suffix")
        switchSite(SiteRegistry.EHENTAI.name)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("comic-stage").fetchSemanticsNodes().isNotEmpty() }; waitCovers()
        val overflow = ui.onNodeWithContentDescription("${f.video("ehentai").title} 的更多操作").fetchSemanticsNode().touchBoundsInRoot
        val touchTarget = 48 * activity.resources.displayMetrics.density
        assertTrue("Compact overflow icons must retain a 48dp touch target: $overflow", overflow.width >= touchTarget && overflow.height >= touchTarget)
        f.shot("liquid-comic-$suffix")
        ui.onNodeWithContentDescription("内容列表").performScrollToNode(hasText("开始阅读"))
        ui.onNodeWithText("开始阅读").assertIsDisplayed().performClick()
        waitText("收藏漫画"); waitCovers()
        ui.onNodeWithText("开始阅读").assertIsDisplayed()
        f.shot("rental-comic-detail-$suffix")
        back(f); waitHome()
        switchSite(SiteRegistry.FOURKHD.name)
        ui.waitUntil(15000) { ui.onAllNodesWithTag("photo-stage").fetchSemanticsNodes().isNotEmpty() }; waitCovers()
        val landscape = f.video("4khd", index = 1)
        val landscapeCover = hasTestTag("cover-ready") and hasAnyAncestor(hasTestTag("media-${landscape.site}-${landscape.id}"))
        ui.waitUntil(15000) {
            ui.onAllNodes(landscapeCover, useUnmergedTree = true).fetchSemanticsNodes().any { cover ->
                val bounds = cover.boundsInRoot
                bounds.height > 0 && bounds.width / bounds.height > 1.8f
            }
        }
        f.shot("liquid-photo-$suffix")
        if (activity.resources.configuration.screenWidthDp >= 840 && activity.resources.configuration.fontScale <= 1.3f)
            ui.onNodeWithText("开始浏览").assertIsDisplayed()
        ui.onNodeWithContentDescription("内容列表").performScrollToNode(hasText("开始浏览"))
        clickText("开始浏览"); waitText("收藏写真集"); waitCovers()
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
        ui.onNodeWithTag("home-carousel").assertExists()
        openPersonalHome()
        // Closing the photo reader also saves progress. Select the video shelf before
        // checking its resume action, and wait for the catalog's asynchronous scan.
        ui.onNode(hasText("视频") and hasAnyAncestor(hasTestTag("personal-home"))).performClick()
        val continueAction = hasText("继续观看") and hasClickAction()
        ui.waitUntil(15000) { ui.onAllNodes(continueAction).fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("personal-home").performScrollToNode(continueAction)
        ui.onNode(continueAction).assertIsDisplayed()
        ui.onNodeWithText("进度 · 3:00 / 10:00").assertIsDisplayed()
        f.shot("liquid-video-resume-$suffix")

        f.app.library.saveWatch(Watch(resume.id, resume.title, resume.thumb, resume.url, 3, 8, site = resume.site))
        ui.onNode(continueAction).performClick()
        waitForDecodedVideo(f, 3000L..4500L)
        back(f)
        val personal = hasTestTag("personal-home")
        ui.waitUntil(15000) { ui.onAllNodes(personal).fetchSemanticsNodes().isNotEmpty() }
        ui.onNode(hasText("写真") and hasAnyAncestor(personal)).performClick()
        waitText("进度 · 6 / 8\u00a0张")
        clickText("继续浏览")
        ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-6").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("reader-page-6").assertIsDisplayed()
    }

    @Test fun reducedTransparencyIsPersistedWithoutChangingNavigation(): Unit = ComposeFixture(cinematic = true).use { f ->
        val activity = f.launch(); waitHome()
        ui.onNodeWithContentDescription("设置").performClick(); waitText("减少透明效果")
        clickText("减少透明效果")
        assertTrue(f.app.library.bool("reduce_transparency"))
        ui.runOnIdle { activity.recreate() }; waitText("减少透明效果")
        assertTrue(f.app.library.bool("reduce_transparency"))
        f.shot("liquid-reduced-transparency")
        clickText("首页"); waitHome()
        val dock = hasAnyAncestor(hasTestTag("primary-navigation"))
        ui.onNode(hasText("首页") and dock).assertIsSelected()
        val library = ui.onNode(hasText("片库") and dock)
        val bounds = library.assertIsDisplayed().fetchSemanticsNode().touchBoundsInRoot
        val minimum = 48 * activity.resources.displayMetrics.density
        assertTrue(bounds.width >= minimum && bounds.height >= minimum)
        library.performClick(); waitText("我的片库")
        library.assertIsSelected()
        ui.onAllNodes(dock and isSelected()).assertCountEquals(1)
    }

    @Test fun settingsKeepTheDarkPhotoThemeThroughSelectionAndBack() = ComposeFixture().use { f ->
        val activity = f.launch()
        waitHome()
        switchSite(SiteRegistry.FOURKHD.name)
        waitHome()
        ui.onNodeWithContentDescription("设置").performClick()
        waitText("减少透明效果")
        ui.runOnIdle {
            val bars = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertFalse(bars.isAppearanceLightStatusBars)
            assertFalse(bars.isAppearanceLightNavigationBars)
        }
        ui.onNodeWithText("默认清晰度").performScrollTo().performClick()
        val choice = hasText("1080p") and hasClickAction()
        ui.onNode(choice).performScrollTo().performClick()
        ui.onNodeWithTag("primary-navigation").assertIsDisplayed()
        assertEquals("1080p", f.app.library.string("quality"))
        ui.onNodeWithContentDescription("返回").performClick()
        waitText("最新写真集")
    }
}
