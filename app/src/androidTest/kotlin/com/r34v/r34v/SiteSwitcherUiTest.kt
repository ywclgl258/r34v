package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class SiteSwitcherUiTest : ComposeUiCase() {
    @Test fun allSitesFitOnOpeningAndTheLastOneSwitchesWithOneTap() = ComposeFixture().use { f ->
        val config = f.launch().resources.configuration
        org.junit.Assume.assumeTrue(config.screenWidthDp >= 360 && config.screenHeightDp >= 600 &&
            config.screenWidthDp < config.screenHeightDp && config.fontScale == 1f)
        waitHome()
        val list = hasContentDescription("站点列表")
        fun entry(site: SiteContext) = ui.onNode(hasText(site.name) and hasAnyAncestor(list))
        ui.onNode(hasContentDescription("切换站点，当前", substring = true)).performClick()
        val viewport = ui.onNode(list).getUnclippedBoundsInRoot()
        val minTarget = (48 * f.ins.targetContext.resources.displayMetrics.density).roundToInt()
        SiteRegistry.all.forEach { site ->
            val tile = entry(site).assertIsDisplayed()
            val bounds = tile.getUnclippedBoundsInRoot()
            assertTrue("${site.name} must be fully visible on opening", bounds.top >= viewport.top && bounds.bottom <= viewport.bottom)
            val touch = tile.fetchSemanticsNode().boundsInRoot
            assertTrue("${site.name} must retain a 48dp touch target", touch.height >= minTarget && touch.width >= minTarget)
        }
        entry(SiteRegistry.R34V).assertIsSelected()
        f.shot("site-switcher-all-sites")
        val last = SiteRegistry.all.last()
        entry(last).performClick()
        ui.onNodeWithContentDescription("切换站点，当前 ${last.name}").assertIsDisplayed().performClick()
        entry(last).assertIsSelected()
        ui.onNodeWithContentDescription("关闭站点列表").performClick()
        assertEquals(last.id, f.app.library.siteId)
    }

    @Test fun drawerTouchNavigationAndSiteSpecificLibrary() = ComposeFixture().use { f ->
        val video = f.video(); f.app.library.toggleFavorite(Favorite("video", video.id, video.title, video.url, video.thumb))
        f.launch(); waitHome(); switchSite(SiteRegistry.HANIME1.name); waitHome(); assertEquals("hanime1", f.app.library.siteId)
        clickText("片库"); clickText("收藏"); waitText("还没有收藏内容")
        switchSite(SiteRegistry.IWARA.name); waitHome(); assertEquals("iwara", f.app.library.siteId)
        clickText("发现")
        val iwara = f.video(SiteRegistry.IWARA.id)
        waitText(iwara.title)
        ui.onNodeWithTag("media-${iwara.site}-${iwara.id}").assertIsDisplayed()
        f.shot("07-iwara-discover")
        switchSite(SiteRegistry.R34V.name); waitHome(); clickText("片库"); clickText("收藏"); waitText(video.title)
        f.launch(); waitHome(); assertEquals("r34v", f.app.library.siteId)
    }
}
