package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveComposeUiTest : ComposeUiCase() {
    @Test fun wideGalleryAndReaderKeepUsableSpace(): Unit = ComposeFixture().use { f ->
        f.app.library.switchSite("ehentai"); val activity = f.launch(); openSourceHome(); ui.waitUntil(15000) { ui.onAllNodesWithContentDescription("内容列表").fetchSemanticsNodes().isNotEmpty() }; waitCovers()
        ui.onNodeWithContentDescription("内容列表").performScrollToNode(hasTestTag("media-ehentai-${f.video("ehentai").id}"))
        val columns = if (f.ins.targetContext.resources.configuration.screenWidthDp >= 600) 3 else 2
        val cards = (0 until columns).map { ui.onNodeWithTag("media-ehentai-${f.video("ehentai", index = it).id}").fetchSemanticsNode().boundsInRoot }
        assertTrue(cards.zipWithNext().all { (left, right) -> left.left < right.left })
        f.shot("14-responsive-gallery")
        ui.runOnIdle { activity.recreate() }; waitCovers(); assertEquals("ehentai", f.app.library.siteId)
        f.launchGallery(read = true); ui.waitUntil(15000) { f.app.library.reading(f.video("ehentai").id)?.page == 1 }
        val inline = ui.onNodeWithTag("comic-strip").fetchSemanticsNode().boundsInRoot.height
        readerTool("全屏阅读")
        val full = ui.onNodeWithTag("comic-strip").fetchSemanticsNode().boundsInRoot.height
        ui.onNodeWithContentDescription("阅读工具").assertDoesNotExist()
        assertEquals("Hiding floating controls must not shift the page", inline, full, 1f)
        f.shot("15-responsive-reader"); back(f)
        ui.waitUntil(5000) { ui.onAllNodesWithContentDescription("阅读工具").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithContentDescription("阅读工具").assertIsDisplayed()
    }
}
