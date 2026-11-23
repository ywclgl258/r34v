package com.r34v.r34v

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryUiTest : ComposeUiCase() {
    @Test fun languageMasonryScrollAndSearchRemainSiteSpecific() = ComposeFixture().use { f ->
        f.launch(); waitHome(); switchSite(SiteRegistry.EHENTAI.name); waitText("最新漫画")
        val firstTag = "media-ehentai-${f.video("ehentai").id}"
        ui.onNodeWithContentDescription("内容列表").performScrollToNode(hasTestTag(firstTag))
        // The cover stage can put this row below a short viewport. Compare the complete
        // layout bounds so clipping at the screen edge cannot flatten both card heights.
        val first = ui.onNodeWithTag(firstTag).getUnclippedBoundsInRoot()
        val second = ui.onNodeWithTag("media-ehentai-${f.video("ehentai", index = 1).id}").getUnclippedBoundsInRoot()
        assertTrue("Gallery covers must keep their differing aspect ratios", first.bottom - first.top > second.bottom - second.top)
        ui.onNodeWithContentDescription("内容列表").performScrollToIndex(0)
        clickText("中＋日"); ui.waitUntil(10000) { f.requests.any { it.first == "ehentai" && it.second.language == GalleryLanguage.CHINESE_JAPANESE } }
        waitCovers(); f.shot("08-gallery-language")
        ui.onNodeWithContentDescription("内容列表").performTouchInput { swipeUp() }
        ui.waitUntil(5000) { ui.onAllNodesWithText("中＋日").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithContentDescription("内容列表").performTouchInput { swipeDown() }; waitText("中＋日")
        clickText("搜索"); ui.onNode(hasSetTextAction()).performTextInput("sample"); ui.onNodeWithContentDescription("提交搜索").performClick()
        ui.waitUntil(10000) { f.requests.any { it.first == "ehentai" && it.second.kind == FeedKind.SEARCH && it.second.language == GalleryLanguage.CHINESE_JAPANESE } }
        switchSite(SiteRegistry.NHENTAI.name); waitText("最新漫画"); assertEquals(GalleryLanguage.ALL, f.app.library.galleryLanguage())
    }
    @Test fun continuousReaderPreloadsZoomsFullscreensAndRestoresOffline() = ComposeFixture().use { f ->
        f.app.library.switchSite("ehentai"); val activity = f.launchGallery(); waitText("开始阅读"); clickText("收藏漫画"); clickText("开始阅读")
        ui.waitUntil(15000) { f.imageCalls.containsAll(listOf(1, 2, 3, 4)) && f.app.library.reading(f.video("ehentai").id)?.page == 1 }
        assertFalse("Pre-reading must not advance the saved page", f.app.library.reading(f.video("ehentai").id)!!.page > 1)
        ui.onNodeWithTag("reader-page-1").performTouchInput { doubleClick() }
        ui.onNodeWithTag("reader-page-1").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "缩放 2.0"))
        ui.onNodeWithTag("reader-page-1").performTouchInput { doubleClick() }
        ui.onNodeWithTag("reader-page-1").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "缩放 1.0"))
        ui.onNodeWithTag("reader-page-1").performTouchInput {
            down(0, androidx.compose.ui.geometry.Offset(center.x - 60f, center.y)); down(1, androidx.compose.ui.geometry.Offset(center.x + 60f, center.y))
            moveTo(0, androidx.compose.ui.geometry.Offset(center.x - 140f, center.y)); moveTo(1, androidx.compose.ui.geometry.Offset(center.x + 140f, center.y)); up(0); up(1)
        }
        ui.onNodeWithTag("reader-page-1").assert(SemanticsMatcher("pinch zoomed") { node ->
            node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.StateDescription) { "" }.removePrefix("缩放 ").toFloatOrNull()?.let { it > 1.3f } == true
        })
        ui.onNodeWithTag("reader-page-1").performTouchInput { doubleClick() }
        ui.onNodeWithTag("reader-page-1").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "缩放 1.0"))
        readerTool("全屏阅读"); ui.onNodeWithContentDescription("阅读工具").assertDoesNotExist(); f.shot("09-reader-fullscreen")
        back(f); ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("阅读工具").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("comic-strip").performTouchInput { swipeUp() }; ui.onNodeWithTag("comic-strip").performTouchInput { swipeUp() }
        ui.waitUntil(10000) { (f.app.library.reading(f.video("ehentai").id)?.page ?: 0) >= 2 }
        ui.onNode(hasText(" / 8", substring = true)).performClick(); ui.onNode(hasSetTextAction()).performTextReplacement("6"); clickText("确定")
        ui.waitUntil(15000) { f.app.library.reading(f.video("ehentai").id)?.page == 6 }; f.shot("10-reader-jump")
        val saved = f.app.library.reading(f.video("ehentai").id)!!
        f.offline = true; ui.runOnIdle { activity.recreate() }
        ui.waitUntil(15000) { ui.onAllNodesWithTag("reader-page-6").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(saved.page, f.app.library.reading(f.video("ehentai").id)!!.page)
        f.shot("11-reader-offline-restored")
    }
    @Test fun continuousComicRestoresOffsetAfterSinglePageRetryAndOfflineRecreation() = ComposeFixture().use { f ->
        val video = f.video("ehentai")
        val saved = ReadingProgress(video, 3, 8, "", offset = -240)
        f.app.library.personal.setReaderMode(video.site, ReaderMode.STRIP)
        f.app.library.saveReading(saved)
        f.brokenPage = saved.page
        val activity = f.launchGallery(read = true)
        waitText("重试此页")
        ui.onNodeWithText("3 / 8").assertIsDisplayed()
        assertEquals(0f, stripPageOffset(saved.page), 1f)

        val target = video.site to saved.page
        val attempts = f.imageRequests.count { it == target }
        val expectedOffset = saved.offset / activity.resources.displayMetrics.density
        f.brokenPage = 0
        clickText("重试此页")
        waitForReaderPageAt(saved.page, expectedOffset)
        assertEquals(attempts + 1, f.imageRequests.count { it == target })
        ui.onNodeWithText("重试此页").assertDoesNotExist()

        f.offline = true
        ui.runOnIdle { activity.recreate() }
        ui.waitUntil(15000) {
            ui.runOnIdle {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .any { it is GalleryActivity && it !== activity }
            }
        }
        waitForReaderPageAt(saved.page, expectedOffset)
        assertEquals(attempts + 1, f.imageRequests.count { it == target })
        back(f)
        waitText("继续阅读 · 第 3 页")
        val restored = checkNotNull(f.app.library.reading(video.id, video.site))
        assertEquals(saved.page, restored.page)
        assertEquals(saved.offset, restored.offset)
    }

    @Test fun explicitPhotoPageOpensAtTopAndKeepsBookModeOverride() = ComposeFixture().use { f ->
        val video = f.video(SiteRegistry.FOURKHD.id)
        f.app.library.saveReading(ReadingProgress(video, 2, 8, "", offset = -240))
        f.app.library.personal.setReaderMode(video.site, ReaderMode.RIGHT_TO_LEFT)
        val preferences = ReaderPreferences(mode = ReaderMode.STRIP.name, start = ReaderStart.LAST)
        f.app.library.personal.setReaderPreferences(video, preferences)
        f.ins.startActivitySync(Intent(f.ins.targetContext, GalleryActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra("gallery", f.app.library.gson.toJson(video)).putExtra("read", true).putExtra("page", 4))

        waitForReaderPageAt(4, 0f)
        ui.onNodeWithText("4 / 8").assertIsDisplayed()
        back(f)
        waitText("开始浏览 · 第 8 页")
        val saved = checkNotNull(f.app.library.reading(video.id, video.site))
        assertEquals(4, saved.page)
        assertEquals(0, saved.offset)
        assertEquals(preferences, f.app.library.personal.readerPreferences(video))
        assertEquals(ReaderMode.RIGHT_TO_LEFT, f.app.library.personal.readerMode(video.site))
    }

    @Test fun exLoginIsGated() = ComposeFixture().use { f ->
        f.app.library.switchSite("exhentai"); assertFalse(f.app.network.session.hasGallerySession(SiteRegistry.EXHENTAI))
        f.launch(); waitText("ExHentai 必须登录"); ui.onNodeWithText("首页").assertIsSelected(); f.shot("12-ex-login-gate")
        clickText("官方网页登录"); waitText("ExHentai 官方登录"); clickText("验证登录"); waitText("尚未取得登录会话，请先在官方页面完成登录。")
        ui.runOnIdle {
            val auth = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<SiteAuthActivity>().single()
            val web = SiteAuthActivity::class.java.getDeclaredField("browser").apply { isAccessible = true }.get(auth) as? android.webkit.WebView
            if (android.os.Build.VERSION.SDK_INT >= 26 && web != null) web.webViewClient.onPageFinished(web, com.r34v.r34v.data.adapter.EhLogin.officialUrl)
        }
        ui.onNodeWithText("尚未取得登录会话，请先在官方页面完成登录。").assertExists()
        assertFalse(f.app.network.session.hasGallerySession(SiteRegistry.EXHENTAI))
    }

    private fun stripPageOffset(page: Int): Float {
        val pageTop = ui.onNodeWithTag("reader-page-$page").getUnclippedBoundsInRoot().top.value
        val stripTop = ui.onNodeWithTag("comic-strip").getUnclippedBoundsInRoot().top.value
        return pageTop - stripTop
    }

    private fun waitForReaderPageAt(page: Int, offsetDp: Float) {
        ui.waitUntil(15000) {
            runCatching {
                ui.onAllNodesWithContentDescription("第 $page 页，双击或双指缩放").fetchSemanticsNodes().isNotEmpty() &&
                    kotlin.math.abs(stripPageOffset(page) - offsetDp) <= 1f
            }.getOrDefault(false)
        }
    }
}
