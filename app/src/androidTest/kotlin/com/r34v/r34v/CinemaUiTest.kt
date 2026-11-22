package com.r34v.r34v

import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.r34v.r34v.data.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CinemaUiTest : ComposeUiCase() {
    @Test fun slowPopularRankingResumesAfterLeavingHome(): Unit = ComposeFixture().use { f ->
        val rankingReady = CompletableDeferred<Unit>()
        val winner = f.video().copy(id = "popular-only", title = "独立榜单作品")
        f.feedOverride = { _, request ->
            if (request.kind == FeedKind.POPULAR) {
                rankingReady.await()
                FeedPage(listOf(winner), 1, 1, "热门榜")
            } else null
        }
        try {
            f.launch(); openSourceHome()
            waitText("最新更新")
            ui.onAllNodesWithText("人气榜 Top 10").assertCountEquals(0)
            ui.waitUntil(10000) { f.requests.any { it.second.kind == FeedKind.POPULAR } }
            clickText("发现")
            ui.waitUntil(10000) { ui.onAllNodesWithTag("discover-feed").fetchSemanticsNodes().isNotEmpty() }
            openSourceHome()
            ui.waitUntil(10000) { f.requests.count { it.second.kind == FeedKind.POPULAR } >= 2 }
            rankingReady.complete(Unit)
            waitText("人气榜 Top 10")
            ui.onNodeWithTag("poster-${winner.site}-${winner.id}").assertIsDisplayed()
        } finally {
            rankingReady.complete(Unit)
        }
    }

    @Test fun immersiveHeroPlaylistAndHorizontalShelves() = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        val video = f.video()
        val featured = f.video(index = 1)
        f.app.library.clearHistory()
        f.launch(); openSourceHome(); waitCovers()
        val motion = Settings.Global.getFloat(f.ins.targetContext.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
        if (motion) {
            val secondPageSelected = hasContentDescription("精选第 2 页") and isSelected()
            ui.waitUntil(12000) { ui.onAllNodes(secondPageSelected).fetchSemanticsNodes().isNotEmpty() }
        }
        ui.onNodeWithContentDescription("首页精选轮播").performTouchInput { swipeLeft() }
        if (motion) ui.onNodeWithContentDescription("继续轮播").assertIsDisplayed()
        else ui.onNodeWithContentDescription("继续轮播").assertDoesNotExist()
        ui.onNodeWithContentDescription("精选第 1 页").performClick()
        ui.onNodeWithTag("home-carousel").assertIsDisplayed()
        val firstPageSelected = hasContentDescription("精选第 1 页") and isSelected()
        val playActions = ui.onAllNodes(hasText("播放") and hasAnyAncestor(hasTestTag("home-carousel")))
        ui.waitUntil(5000) {
            ui.onAllNodes(firstPageSelected).fetchSemanticsNodes().isNotEmpty() &&
                playActions.fetchSemanticsNodes().indices.any { playActions[it].isDisplayed() }
        }
        val screen = f.ins.targetContext.resources.configuration
        if (screen.screenHeightDp >= 700 && screen.screenHeightDp > screen.screenWidthDp && screen.fontScale <= 1.3f) {
            ui.onNodeWithText("人气榜 Top 10").assertIsDisplayed()
            ui.onAllNodesWithTag("poster-${video.site}-${video.id}").onFirst().assertIsDisplayed()
        }
        f.shot("16-cinema-home")
        ui.onNodeWithContentDescription("加入我的片单 ${featured.title}").performClick()
        ui.onNodeWithContentDescription("从我的片单移除 ${featured.title}").assertExists()
        ui.runOnIdle { assertTrue(f.app.library.personal.contains(featured)); assertFalse(f.app.library.isFavorite("video", featured.id, featured.site)) }
        f.shot("16-cinema-saved")
        ui.waitUntil(10000) { ui.onAllNodesWithText("已收藏").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithContentDescription("从我的片单移除 ${featured.title}").performClick()
        ui.runOnIdle { assertFalse(f.app.library.personal.contains(featured)) }
        ui.onNodeWithContentDescription("内容列表").performScrollToNode(hasText("最新更新"))
        ui.onAllNodesWithTag("poster-${video.site}-${video.id}").onFirst().assertExists()
        f.shot("17-cinema-shelves")
        ui.onNodeWithContentDescription("搜索内容").performClick(); waitText("最近搜索"); f.shot("18-cinema-search")
        clickText("片库"); waitText("我的片库"); f.shot("19-cinema-library")
        clickText("发现"); waitText(video.title); waitCovers()
        val firstWork = ui.onNodeWithTag("media-${video.site}-${video.id}")
        firstWork.assertIsDisplayed()
        f.shot("20-cinema-explore")
        val detailOpened = AtomicBoolean(false)
        f.detailHook = { detailOpened.set(true) }
        firstWork.performClick()
        ui.waitUntil(15000) { detailOpened.get() }
        ui.onNodeWithTag("video-poster-detail").assertIsDisplayed()
        ui.onNodeWithTag("player-surface").assertDoesNotExist()
    }

    @Test fun featuredFallbackKeepsThePlayActionVisibleWithoutRankingOrArtwork() = ComposeFixture().use { f ->
        val entry = f.video().copy(title = "这是一部需要跨行显示完整信息的中文测试作品，封面暂不可用", thumb = "")
        f.feedOverride = { _, request ->
            val videos = if (request.kind == FeedKind.LATEST) listOf(entry) else emptyList()
            FeedPage(videos, 1, 1, "精选占位")
        }
        f.launch(); openSourceHome()
        ui.onNodeWithTag("featured-artwork").assertIsDisplayed()
        ui.onNode(hasTestTag("cover-unavailable") and hasAnyAncestor(hasTestTag("home-carousel")), useUnmergedTree = true)
            .assertIsDisplayed()
        ui.onNode(hasText(entry.title) and hasAnyAncestor(hasTestTag("home-carousel"))).assertIsDisplayed()
        ui.onNodeWithText("播放").assertIsDisplayed()
        ui.onNodeWithText("人气榜 Top 10").assertDoesNotExist()
        ui.onNodeWithContentDescription("暂停轮播").assertDoesNotExist()
    }

    @Test fun featuredDetailsOpenOnThePosterBeforeStartingPlayback() = ComposeFixture(cinematic = true).use { f ->
        f.launch(); openSourceHome()
        ui.onNodeWithTag("home-carousel").assertIsDisplayed()
        ui.onNodeWithText("详情").performClick()
        ui.waitUntil(15000) { ui.onAllNodesWithTag("video-poster-detail").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("player-surface").assertDoesNotExist()
        ui.onNodeWithText("立即播放").assertIsDisplayed().performClick()
        ui.waitUntil(15000) { ui.onAllNodesWithTag("player-surface").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("video-poster-detail").assertDoesNotExist()
        ui.onNodeWithTag("artwork-player-controls").assertIsDisplayed()
        waitForDecodedVideo(f)
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
