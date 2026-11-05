package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryUiTest : ComposeUiCase() {
    @Test fun languageMasonryScrollAndSearchRemainSiteSpecific() = ComposeFixture().use { f ->
        f.launch(); waitHome(); switchSite(SiteRegistry.EHENTAI.name); waitText("最新漫画")
        val first = ui.onNodeWithTag("media-ehentai-${f.video("ehentai").id}").fetchSemanticsNode().boundsInRoot
        val second = ui.onNodeWithTag("media-ehentai-${f.video("ehentai", index = 1).id}").fetchSemanticsNode().boundsInRoot
        assertTrue("Gallery covers must keep their differing aspect ratios", first.height > second.height)
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
        ui.onNodeWithContentDescription("全屏阅读").performClick(); ui.onNodeWithContentDescription("全屏阅读").assertDoesNotExist(); f.shot("09-reader-fullscreen")
        back(f); ui.onNodeWithContentDescription("全屏阅读").assertExists()
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
    @Test fun pageFailuresRecoverInPlaceAndExLoginIsGated() = ComposeFixture().use { f ->
        f.app.library.switchSite("ehentai"); f.brokenPage = 1; f.launchGallery(read = true); waitText("重试此页")
        f.brokenPage = 0; clickText("重试此页"); ui.waitUntil(15000) { f.app.library.reading(f.video("ehentai").id)?.page == 1 }
        back(f); f.app.library.switchSite("exhentai"); assertFalse(f.app.network.session.hasGallerySession(SiteRegistry.EXHENTAI))
        f.launch(); f.shot("12-ex-login-gate"); waitText("ExHentai 必须登录")
        clickText("官方网页登录"); waitText("ExHentai 官方登录"); clickText("验证登录"); waitText("尚未取得登录会话，请先在官方页面完成登录。")
        assertFalse(f.app.network.session.hasGallerySession(SiteRegistry.EXHENTAI))
    }
}
