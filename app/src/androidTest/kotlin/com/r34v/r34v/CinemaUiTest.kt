package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.r34v.r34v.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CinemaUiTest : ComposeUiCase() {
    @Test fun immersiveHeroPlaylistAndHorizontalShelves() = ComposeFixture(cinematic = true).use { f ->
        val video = f.video()
        f.app.library.clearHistory()
        f.launch(); openSourceHome(); waitCovers()
        ui.onNodeWithContentDescription("暂停轮播").performClick()
        ui.onNodeWithTag("home-carousel").assertIsDisplayed()
        val density = f.ins.targetContext.resources.displayMetrics.density
        val landscape = f.ins.targetContext.resources.configuration.let { it.screenWidthDp > it.screenHeightDp }
        assertTrue(ui.onNodeWithTag("home-carousel").fetchSemanticsNode().boundsInRoot.height > (if (landscape) 240 else 400) * density)
        f.shot("16-cinema-home")
        ui.onNodeWithContentDescription("加入我的片单 ${video.title}").performClick()
        ui.onNodeWithContentDescription("从我的片单移除 ${video.title}").assertExists()
        ui.runOnIdle { assertTrue(f.app.library.personal.contains(video)); assertFalse(f.app.library.isFavorite("video", video.id, video.site)) }
        f.shot("16-cinema-saved")
        ui.waitUntil(10000) { ui.onAllNodesWithText("已收藏").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithContentDescription("从我的片单移除 ${video.title}").performClick()
        ui.runOnIdle { assertFalse(f.app.library.personal.contains(video)) }
        ui.onNodeWithContentDescription("内容列表").performScrollToIndex(2)
        ui.onAllNodesWithTag("poster-${video.site}-${video.id}").onFirst().assertExists()
        f.shot("17-cinema-shelves")
        ui.onNodeWithContentDescription("搜索内容").performClick(); waitText("最近搜索"); f.shot("18-cinema-search")
        clickText("片库"); waitText("我的片库"); f.shot("19-cinema-library")
        clickText("发现"); waitText("精选榜单"); f.shot("20-cinema-explore")
    }

    @Test fun coilRetainsSiteHeadersOnSharedCdn() = ComposeFixture().use { f -> runBlocking {
        val sharedUrl = "https://cdn.example.invalid/compose-cover-${f.nonce}-shared.png"
        for (site in listOf(SiteRegistry.R34V, SiteRegistry.IWARA)) {
            val key = "${site.id}|$sharedUrl"
            val result = f.app.coverLoader(site).execute(ImageRequest.Builder(f.app).data(sharedUrl).memoryCacheKey(key).diskCacheKey(key).build())
            assertTrue(result is SuccessResult)
        }
        val calls = f.coverRequests.filter { it.url.toString() == sharedUrl }
        assertEquals(2, calls.size)
        assertEquals(listOf("r34v", "iwara"), calls.map { it.tag(SiteContext::class.java)?.id })
        assertEquals(listOf(SiteRegistry.R34V.referer, SiteRegistry.IWARA.referer), calls.map { it.header("Referer") })
        assertTrue(calls.all { it.header("Cookie") == null })
    } }
}
