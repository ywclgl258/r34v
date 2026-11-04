package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResponsiveComposeUiTest : ComposeUiCase() {
    @Test fun wideGalleryAndReaderKeepUsableSpace(): Unit = ComposeFixture().use { f ->
        f.app.library.switchSite("ehentai"); val activity = f.launch(); waitText("最新画廊"); waitCovers()
        val columns = if (f.ins.targetContext.resources.configuration.screenWidthDp >= 600) 3 else 2
        val cards = (0 until columns).map { ui.onNodeWithTag("media-ehentai-${f.video("ehentai", index = it).id}").fetchSemanticsNode().boundsInRoot }
        assertTrue(cards.zipWithNext().all { (left, right) -> left.left < right.left })
        f.shot("14-responsive-gallery")
        ui.runOnIdle { activity.recreate() }; waitText("最新画廊"); assertEquals("ehentai", f.app.library.siteId)
        f.launchGallery(read = true); ui.waitUntil(15000) { f.app.library.reading(f.video("ehentai").id)?.page == 1 }
        val inline = ui.onNodeWithTag("comic-strip").fetchSemanticsNode().boundsInRoot.height
        ui.onNodeWithContentDescription("全屏阅读").performClick()
        val full = ui.onNodeWithTag("comic-strip").fetchSemanticsNode().boundsInRoot.height
        assertTrue("Fullscreen must increase reading space", full > inline)
        f.shot("15-responsive-reader"); back(f); ui.onNodeWithContentDescription("全屏阅读").assertExists()
    }
}
