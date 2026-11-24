package com.r34v.r34v

import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.r34v.r34v.data.*
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModernUiTest : ComposeUiCase() {
    @Test fun launchAndHomeShowTheCurrentSiteWhilePersonalContentStaysInMine() = ComposeFixture(cinematic = true).use { f ->
        clear(f)
        val video = f.video()
        f.app.library.saveWatch(Watch(video.id, video.title, video.thumb, video.url, 180, 600, site = video.site))
        f.app.library.personal.add(video)
        val activity = f.launch(); waitHome()
        ui.onNodeWithTag("home-carousel").assertExists()
        ui.onNodeWithTag("personal-home").assertDoesNotExist()
        ui.onNodeWithText("首页").assertIsSelected()
        ui.onNodeWithContentDescription("返回").assertDoesNotExist()
        org.junit.Assert.assertTrue(f.requests.any { it.first == "r34v" && it.second.kind == FeedKind.LATEST })
        f.shot("navigation-site-home")
        openPersonalHome()
        ui.onNodeWithTag("personal-home").performScrollToNode(hasText("继续观看"))
        ui.onNodeWithText("继续观看").assertIsDisplayed()
        f.shot("navigation-personal")
        ui.runOnIdle { activity.recreate() }; waitHome()
        ui.onNodeWithTag("personal-home").assertExists()
        back(f); waitHome(); ui.onNodeWithText("首页").assertIsSelected()
        ui.onNodeWithTag("personal-home").assertDoesNotExist()
        switchSite(SiteRegistry.EHENTAI.name); waitHome()
        ui.onNodeWithTag("comic-stage").assertExists()
        ui.onNodeWithText("首页").assertIsSelected()
        f.launch(); waitHome()
        ui.onNodeWithTag("comic-stage").assertExists()
        org.junit.Assert.assertEquals("ehentai", f.app.library.siteId)
    }

    @Test fun personalCatalogRefreshesWithoutReopeningThePage() = ComposeFixture(cinematic = true).use { f ->
        clear(f)
        val video = f.video().copy(title = "正在更新的观看记录")
        f.launch()
        openPersonalHome()
        waitText("发现下一部")

        val watch = Watch(video.id, video.title, video.thumb, video.url, 120, 600, site = video.site)
        ui.runOnIdle { f.app.library.saveWatch(watch) }
        waitText(video.title)
        ui.runOnIdle { f.app.library.saveWatch(watch.copy(position = 600, ended = true)) }
        waitText("发现下一部")
    }

    private fun clear(f: ComposeFixture) {
        f.app.library.prefs.edit().putString(PersonalStore.KEY, f.app.library.gson.toJson(PersonalLibrary())).commit()
        f.app.library.prefs.edit().apply {
            SiteRegistry.librarySources.forEach { remove("${it.id}:watch_history_v1"); remove("${it.id}:reading_history_v1") }
        }.commit()
    }

    @Test fun emptyHomeAndLibraryKeepDestinationsReadable(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        clear(f); f.launch(); openPersonalHome(); waitText("发现下一部"); f.shot("modern-empty-home")
        clickText("片库"); waitText("我的片库")
        val config = f.ins.targetContext.resources.configuration
        if (config.screenWidthDp < 840 || config.fontScale > 1.3f) {
            ui.onNode(hasText("稍后看") and hasClickAction()).assertWidthIsAtLeast((config.screenWidthDp * .8f).dp)
        }
        f.shot("modern-empty-library")
        ui.onNodeWithTag("library-hub").performScrollToNode(hasText("导入 CBZ / ZIP / 图片目录"))
        ui.onNodeWithText("导入 CBZ / ZIP / 图片目录").assertIsDisplayed()
        ui.onNodeWithTag("library-hub").performScrollToNode(hasText("从链接打开作品"))
        ui.onNodeWithText("从链接打开作品").assertIsDisplayed().performClick()
        waitText("从链接打开作品"); f.shot("modern-open-link")
    }

    @Test fun favoriteKeepsTheChosenWorkAfterReopeningLibrary(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        clear(f)
        val video = f.video(index = 1)
        f.app.library.personal.add(video)
        f.launch(); openPersonalHome(); clickText("开始观看"); waitText("简介")
        ui.onNodeWithText("收藏").performScrollTo().performClick()
        waitText("已收藏")
        org.junit.Assert.assertTrue(f.app.library.isFavorite("video", video.id, video.site))
        f.launch(); waitHome(); clickText("片库"); clickText("收藏"); waitText(video.title)
        ui.onNodeWithText(video.title).assertIsDisplayed()
        f.shot("modern-favorite-identity")
    }

    @Test fun favoritesFindMixedWorksAcrossSourcesAndRemoveTheExactEntry() = ComposeFixture().use { f ->
        f.app.library.prefs.edit().apply {
            (SiteRegistry.librarySources.map { it.id } + SiteRegistry.retiredVideoHosts.keys).forEach { remove("$it:favorites_v1") }
        }.commit()
        val iwara = f.video(SiteRegistry.IWARA.id)
        val book = Favorite("gallery", iwara.id, "测试图库收藏", iwara.url, iwara.thumb, site = iwara.site)
        val clip = Favorite("video", iwara.id, "测试视频收藏", iwara.url, iwara.thumb, site = iwara.site)
        val comic = f.video(SiteRegistry.EHENTAI.id)
        val other = Favorite("gallery", comic.id, "测试漫画收藏", comic.url, comic.thumb, site = comic.site)
        listOf(book, clip, other).forEach { f.app.library.toggleFavorite(it, it.site) }
        f.app.library.personal.add(iwara.copy(title = clip.title))

        f.launch(); waitHome(); clickText("片库"); clickText("收藏")
        waitText("3 项收藏")
        ui.onNodeWithText(book.title).assertIsDisplayed()
        ui.onNodeWithText(clip.title).assertIsDisplayed()
        ui.onNodeWithText(other.title).assertIsDisplayed()
        f.shot("library-management-favorites")
        clickText("全部来源"); clickText("Iwara"); waitText("2 项收藏")
        ui.onNodeWithText(other.title).assertDoesNotExist()
        val search = ui.onNodeWithTag("favorites-search")
        search.performTextReplacement("图库"); search.performImeAction()
        waitText("1 项收藏")
        ui.onNodeWithText(clip.title).assertDoesNotExist()
        ui.onNodeWithTag("favorite-${book.key}").performClick()
        ui.waitUntil(15000) {
            var opened = false
            f.ins.runOnMainSync {
                opened = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { it is GalleryActivity }
            }
            opened
        }
        ui.onNodeWithContentDescription("返回").performClick()
        waitText(book.title)
        ui.onNodeWithContentDescription("${book.title} 的更多操作").performClick()
        clickText("取消收藏"); waitText("没有符合条件的收藏")
        org.junit.Assert.assertFalse(f.app.library.isFavorite("gallery", book.target, book.site))
        org.junit.Assert.assertTrue(f.app.library.isFavorite("video", clip.target, clip.site))
        org.junit.Assert.assertTrue(f.app.library.personal.contains(iwara))
        clickText("清空筛选"); waitText("2 项收藏")
    }

    @Test fun finishedWatchIsNotPromotedAsContinueWatching(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        clear(f)
        val video = f.video()
        f.app.library.personal.add(video)
        f.app.library.saveWatch(Watch(video.id, video.title, video.thumb, video.url, 600, 600, site = video.site))
        f.launch(); openPersonalHome(); waitText("发现下一部")
        ui.onAllNodes(hasText("继续观看") and hasClickAction()).assertCountEquals(0)
        ui.onNodeWithText("我的稍后看").assertExists()
        f.shot("modern-finished-watch")
    }

    @Test fun homeMediaTabsFilterTheFeatureAndSurviveRecreation(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        clear(f)
        val photo = SiteRegistry.all.first { it.category == SiteCategory.PHOTO }.id
        listOf("r34v", "ehentai", photo).forEach { f.app.library.personal.add(f.video(it)) }
        val activity = f.launch(); openPersonalHome()
        fun select(label: String, tag: String) {
            ui.onNodeWithTag("personal-home").performScrollToIndex(0)
            ui.onNode(hasText(label) and hasAnyAncestor(hasTestTag("personal-home"))).performScrollTo().performClick()
            ui.waitUntil(15000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            ui.onNodeWithTag(tag).assertExists()
        }
        select("视频", "video-stage"); waitCovers(); f.shot("restructure-home-video")
        select("漫画", "comic-stage"); f.shot("restructure-home-comic")
        select("写真", "photo-stage"); f.shot("restructure-home-photo")
        ui.runOnIdle { activity.recreate() }; waitHome()
        ui.waitUntil(15000) { ui.onAllNodesWithTag("photo-stage").fetchSemanticsNodes().isNotEmpty() }
        ui.onAllNodesWithTag("video-stage").assertCountEquals(0)
        ui.onAllNodesWithTag("comic-stage").assertCountEquals(0)
    }

    @Test fun librarySectionsRestoreAndKeepImportAndSavedSearchReachable(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        clear(f); f.app.library.personal.add(f.video())
        f.app.library.personal.saveSmart(SmartShelf(name = "未完成清单"))
        f.app.library.personal.saveSearch(SavedSearch(name = "周末片单", query = "space", sources = listOf("r34v")))
        val activity = f.launch(); waitHome(); clickText("片库"); waitText("我的片库")
        f.shot("restructure-library")
        ui.onNodeWithText("智能片单").performScrollTo().performClick(); waitText("未完成清单")
        f.shot("restructure-library-smart")
        ui.runOnIdle { activity.recreate() }; waitText("未完成清单")
        ui.onNodeWithText("本地内容").performScrollTo().performClick(); waitText("还没有本地作品")
        ui.onNodeWithTag("library-hub").performScrollToNode(hasText("导入 CBZ / ZIP / 图片目录"))
        ui.onNodeWithText("导入 CBZ / ZIP / 图片目录").assertIsDisplayed()
        clickText("搜索"); waitText("周末片单"); f.shot("restructure-search")
        ui.onNodeWithText("周末片单").performScrollTo().performClick(); waitText("搜索：space")
        ui.waitUntil(15000) { f.requests.any { it.second.kind == FeedKind.SEARCH && it.second.target == "space" } }
    }

    @Test fun movingAWatchLaterItemKeepsItsNewPositionVisible(): Unit = ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
        clear(f)
        (0..3).forEach { f.app.library.personal.add(f.video(index = it)) }
        f.launch(); openPersonalHome()
        val watchLater = hasText("稍后看 · 4") and hasClickAction()
        ui.onNodeWithTag("personal-home").performScrollToNode(watchLater)
        ui.onNode(watchLater).assertIsDisplayed().performClick()
        waitText("按顺序观看 / 阅读")
        val first = f.video()
        val moved = f.video(index = 1)
        ui.onNodeWithTag("catalog-list").performScrollToNode(hasText(moved.title))
        ui.onNodeWithContentDescription("管理 ${moved.title}").performClick()
        clickText("上移")
        ui.waitUntil(15000) { f.app.library.personal.shelves().first().entries.first().video.entryKey == moved.entryKey }
        ui.waitForIdle()
        ui.onNodeWithText(moved.title).assertIsDisplayed()
        org.junit.Assert.assertTrue(
            ui.onNodeWithText(moved.title).fetchSemanticsNode().boundsInRoot.top <
                ui.onNodeWithText(first.title).fetchSemanticsNode().boundsInRoot.top)
        f.shot("restructure-watchlist-reordered")
        ui.onNodeWithContentDescription("返回我的片库").performClick()
        waitText("我的片单")
    }

    /** Opt-in live fixture for independent manual UX review; always restores data when closed. */
    @Test fun liveReviewFixture(): Unit {
        assumeTrue(InstrumentationRegistry.getArguments().getString("modernUiReview") == "1")
        ComposeFixture(cinematic = true, awaitUi = { ui.waitForIdle() }).use { f ->
            clear(f)
            val video = f.video()
            f.app.library.saveWatch(Watch(video.id, video.title, video.thumb, video.url, 42, 600, site = video.site))
            (0..3).forEach { f.app.library.personal.add(f.video(index = it)) }
            f.launch(); waitHome(); waitCovers()
            val done = File(f.ins.targetContext.cacheDir, "modern-review-done")
            done.delete()
            val end = android.os.SystemClock.elapsedRealtime() + 8 * 60_000
            while (!done.exists() && android.os.SystemClock.elapsedRealtime() < end) {
                ui.waitForIdle() // Drive Compose's test frame clock while an external reviewer taps.
                Thread.sleep(100)
            }
            done.delete()
        }
    }
}
