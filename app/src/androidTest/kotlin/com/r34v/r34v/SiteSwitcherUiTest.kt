package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.r34v.r34v.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SiteSwitcherUiTest : ComposeUiCase() {
    @Test fun drawerTouchNavigationAndSiteSpecificLibrary() = ComposeFixture().use { f ->
        val video = f.video(); f.app.library.toggleFavorite(Favorite("video", video.id, video.title, video.url, video.thumb))
        f.launch(); waitHome(); switchSite(SiteRegistry.HANIME1.name); waitHome(); assertEquals("hanime1", f.app.library.siteId)
        clickText("片库"); clickText("收藏"); waitText("还没有收藏内容")
        switchSite(SiteRegistry.IWARA.name); waitHome(); assertEquals("iwara", f.app.library.siteId)
        clickText("发现"); waitText("精选榜单"); f.shot("07-iwara-discover")
        switchSite(SiteRegistry.R34V.name); waitHome(); clickText("片库"); clickText("收藏"); waitText(video.title)
        f.launch(); waitHome(); assertEquals("r34v", f.app.library.siteId)
    }
}
